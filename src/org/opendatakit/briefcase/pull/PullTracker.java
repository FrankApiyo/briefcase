/*
 * Copyright (C) 2018 Nafundi
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

package org.opendatakit.briefcase.pull;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bushe.swing.event.EventBus;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PullTracker {
  private static final Logger log = LoggerFactory.getLogger(PullTracker.class);
  private final FormStatus form;
  private int totalSubmissions;
  private AtomicInteger submissionCounter = new AtomicInteger(0);

  PullTracker(FormStatus form) {
    this.form = form;
  }

  void trackBlankForm() {
    form.setStatusString("Downloaded blank form");
    log.info("Downloaded blank form {}", form.getFormName());
    fireUIEvent();
  }

  void trackBatches(List<InstanceIdBatch> batches) {
    totalSubmissions = batches.stream().map(InstanceIdBatch::count).reduce(0, Integer::sum);
    form.setStatusString("Downloading " + totalSubmissions + " submissions");
    log.info("Downloaded {} submissions", totalSubmissions);
    fireUIEvent();
  }

  void trackSubmission() {
    form.setStatusString("Downloaded submission " + submissionCounter.incrementAndGet() + " of " + totalSubmissions);
    log.info("Downloaded submission {} of {}", submissionCounter.get(), totalSubmissions);
    fireUIEvent();
  }

  void trackMediaFiles(List<MediaFile> manifestMediaFiles, List<MediaFile> downloadedMediaFiles) {
    if (!downloadedMediaFiles.isEmpty()) {
      form.setStatusString("Downloaded " + downloadedMediaFiles.size() + " attachments");
      log.info("Downloaded {} attachments", downloadedMediaFiles.size());
      fireUIEvent();
    }
    if (manifestMediaFiles.size() > downloadedMediaFiles.size()) {
      int number = manifestMediaFiles.size() - downloadedMediaFiles.size();
      form.setStatusString("Ignoring " + number + " attachments (already present)");
      log.info("Ignoring {} attachments (already present)", number);
      fireUIEvent();
    }
  }

  private void fireUIEvent() {
    EventBus.publish(new FormStatusEvent(form));
  }
}
