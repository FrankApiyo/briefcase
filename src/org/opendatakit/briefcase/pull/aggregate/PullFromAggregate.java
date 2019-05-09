/*
 * Copyright (C) 2019 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.briefcase.pull.aggregate;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.Collections.emptyList;
import static java.util.function.BinaryOperator.maxBy;
import static java.util.stream.Collectors.toList;
import static org.opendatakit.briefcase.reused.UncheckedFiles.createDirectories;
import static org.opendatakit.briefcase.reused.UncheckedFiles.write;
import static org.opendatakit.briefcase.reused.http.RequestBuilder.get;
import static org.opendatakit.briefcase.reused.job.Job.allOf;
import static org.opendatakit.briefcase.reused.job.Job.supply;
import static org.opendatakit.briefcase.util.DatabaseUtils.withDb;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.opendatakit.briefcase.export.XmlElement;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.OptionalProduct;
import org.opendatakit.briefcase.reused.http.Http;
import org.opendatakit.briefcase.reused.http.Request;
import org.opendatakit.briefcase.reused.http.RequestBuilder;
import org.opendatakit.briefcase.reused.http.response.Response;
import org.opendatakit.briefcase.reused.job.Job;
import org.opendatakit.briefcase.reused.job.RunnerStatus;
import org.opendatakit.briefcase.reused.transfer.AggregateServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PullFromAggregate {
  public static final Logger log = LoggerFactory.getLogger(PullFromAggregate.class);
  private final Http http;
  private final AggregateServer server;
  private final Path briefcaseDir;
  private final boolean includeIncomplete;
  private final Consumer<FormStatusEvent> onEventCallback;

  public PullFromAggregate(Http http, AggregateServer server, Path briefcaseDir, boolean includeIncomplete, Consumer<FormStatusEvent> onEventCallback) {
    this.http = http;
    this.server = server;
    this.briefcaseDir = briefcaseDir;
    this.includeIncomplete = includeIncomplete;
    this.onEventCallback = onEventCallback;
  }

  static Cursor getLastCursor(List<InstanceIdBatch> batches) {
    return batches.stream()
        .map(InstanceIdBatch::getCursor)
        .reduce(maxBy(Cursor::compareTo))
        .orElseThrow(BriefcaseException::new);
  }

  public Job<PullFromAggregateResult> pull(FormStatus form, Optional<Cursor> lastCursor) {
    PullFromAggregateTracker tracker = new PullFromAggregateTracker(form, onEventCallback);

    return allOf(
        supply(runnerStatus -> downloadForm(form, runnerStatus, tracker)),
        supply(runnerStatus -> getSubmissions(form, lastCursor.orElse(Cursor.empty()), runnerStatus, tracker)),
        supply(runnerStatus -> getFormAttachments(form, runnerStatus, tracker))
            .thenAccept((runnerStatus, attachments) ->
                attachments.forEach(attachment -> downloadFormAttachment(form, attachment, runnerStatus, tracker))
            )
    ).thenApply((runnerStatus, t) -> withDb(form.getFormDir(briefcaseDir), db -> {
      // Build the submission key generator with the blank form XML
      SubmissionKeyGenerator subKeyGen = SubmissionKeyGenerator.from(t.get1());

      // Extract all the instance IDs from all the batches and download each instance
      t.get2().stream()
          .flatMap(batch -> batch.getInstanceIds().stream())
          .filter(instanceId -> db.hasRecordedInstance(instanceId) == null)
          .map(instanceId -> downloadSubmission(form, instanceId, subKeyGen, runnerStatus, tracker))
          .filter(Objects::nonNull)
          .forEach(submission -> {
            submission.getAttachments().forEach(attachment ->
                downloadSubmissionAttachment(form, submission, attachment, runnerStatus, tracker)
            );
            db.putRecordedInstanceDirectory(submission.getInstanceId(), form.getSubmissionDir(briefcaseDir, submission.getInstanceId()).toFile());
          });

      // Return the pull result with the last cursor
      return PullFromAggregateResult.of(form, getLastCursor(t.get2()));
    }));
  }

  String downloadForm(FormStatus form, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Download form");
      return null;
    }

    Response<String> response = http.execute(server.getDownloadFormRequest(form.getFormId()));
    if (!response.isSuccess()) {
      tracker.trackError("Error downloading form", response);
      return null;
    }

    Path formFile = form.getFormFile(briefcaseDir);
    createDirectories(formFile.getParent());

    String formXml = response.get();
    write(formFile, formXml, CREATE, TRUNCATE_EXISTING);
    tracker.trackFormDownloaded();
    return formXml;
  }

  List<MediaFile> getFormAttachments(FormStatus form, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Get form attachments");
      return emptyList();
    }

    if (!form.getManifestUrl().filter(RequestBuilder::isUri).isPresent())
      return emptyList();

    URL manifestUrl = form.getManifestUrl().map(RequestBuilder::url).get();
    Request<List<MediaFile>> request = get(manifestUrl)
        .asXmlElement()
        .withResponseMapper(PullFromAggregate::parseMediaFiles)
        .build();
    Response<List<MediaFile>> response = http.execute(request);
    if (!response.isSuccess()) {
      tracker.trackError("Error getting form attachments", response);
      return Collections.<MediaFile>emptyList();
    }

    List<MediaFile> attachments = response.get();
    List<MediaFile> attachmentsToDownload = attachments.stream()
        .filter(mediaFile -> mediaFile.needsUpdate(form.getFormMediaDir(briefcaseDir)))
        .collect(toList());
    tracker.trackMediaFiles(attachments.size(), attachmentsToDownload.size());
    return attachmentsToDownload;
  }

  List<InstanceIdBatch> getSubmissions(FormStatus form, Cursor lastCursor, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Get submissions");
      return emptyList();
    }

    return getInstanceIdBatches(form, runnerStatus, tracker, lastCursor);
  }

  void downloadFormAttachment(FormStatus form, MediaFile mediaFile, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Download form attachment " + mediaFile.getFilename());
      return;
    }

    Path target = form.getFormMediaFile(briefcaseDir, mediaFile.getFilename());
    createDirectories(target.getParent());

    Response response = http.execute(get(mediaFile.getDownloadUrl()).downloadTo(target).build());
    if (response.isSuccess())
      tracker.formAttachmentDownloaded(mediaFile);
    else
      tracker.trackError("Error downloading form attachment " + mediaFile.getFilename(), response);
  }

  DownloadedSubmission downloadSubmission(FormStatus form, String instanceId, SubmissionKeyGenerator subKeyGen, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Download submission " + instanceId);
      return null;
    }

    String submissionKey = subKeyGen.buildKey(instanceId);
    Response<DownloadedSubmission> response = http.execute(server.getDownloadSubmissionRequest(submissionKey));
    if (!response.isSuccess()) {
      tracker.trackError("Error downloading submission " + instanceId, response);
      return null;
    }

    DownloadedSubmission submission = response.get();

    Path submissionFile = form.getSubmissionFile(briefcaseDir, submission.getInstanceId());
    createDirectories(submissionFile.getParent());
    write(
        submissionFile,
        submission.getXml(),
        CREATE, TRUNCATE_EXISTING
    );
    tracker.trackSubmission();
    return submission;
  }

  void downloadSubmissionAttachment(FormStatus form, DownloadedSubmission submission, MediaFile attachment, RunnerStatus runnerStatus, PullFromAggregateTracker tracker) {
    if (runnerStatus.isCancelled()) {
      tracker.trackCancellation("Download submission attachment " + attachment.getFilename() + " of " + submission.getInstanceId());
      return;
    }

    Path target = form.getSubmissionMediaFile(briefcaseDir, submission.getInstanceId(), attachment.getFilename());
    createDirectories(target.getParent());

    Response response = http.execute(get(attachment.getDownloadUrl()).downloadTo(target).build());
    if (response.isSuccess())
      tracker.submissionAttachmentDownloaded(submission.getInstanceId(), attachment);
    else
      tracker.trackError("Error downloading attachment " + attachment.getFilename() + " of submission " + submission.getInstanceId(), response);
  }

  private static List<MediaFile> parseMediaFiles(XmlElement root) {
    return asMediaFileList(root.findElements("mediaFile"));
  }

  static List<MediaFile> asMediaFileList(List<XmlElement> xmlElements) {
    return xmlElements.stream()
        .map(mediaFile -> OptionalProduct.all(
            mediaFile.findElement("filename").flatMap(XmlElement::maybeValue),
            mediaFile.findElement("hash").flatMap(XmlElement::maybeValue),
            mediaFile.findElement("downloadUrl").flatMap(XmlElement::maybeValue)
        ).map(MediaFile::of))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(toList());
  }

  private List<InstanceIdBatch> getInstanceIdBatches(FormStatus form, RunnerStatus runnerStatus, PullFromAggregateTracker tracker, Cursor lastCursor) {
    List<InstanceIdBatch> batches = new ArrayList<>();
    InstanceIdBatchGetter batchPager = new InstanceIdBatchGetter(server, http, form.getFormId(), includeIncomplete, lastCursor);
    while (runnerStatus.isStillRunning() && batchPager.hasNext())
      batches.add(batchPager.next());
    tracker.trackBatches(batches);
    return batches;
  }

}
