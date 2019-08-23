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
package org.opendatakit.briefcase.operations;

import static java.util.stream.Collectors.toList;
import static org.opendatakit.briefcase.operations.Common.FORM_ID;
import static org.opendatakit.briefcase.operations.Common.MAX_HTTP_CONNECTIONS;
import static org.opendatakit.briefcase.operations.Common.STORAGE_DIR;
import static org.opendatakit.briefcase.reused.http.Http.DEFAULT_HTTP_CONNECTIONS;

import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.opendatakit.briefcase.model.RemoteFormDefinition;
import org.opendatakit.briefcase.pull.aggregate.Cursor;
import org.opendatakit.briefcase.pull.aggregate.PullFromAggregate;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.Optionals;
import org.opendatakit.briefcase.reused.http.CommonsHttp;
import org.opendatakit.briefcase.reused.http.Credentials;
import org.opendatakit.briefcase.reused.http.Http;
import org.opendatakit.briefcase.reused.http.RequestBuilder;
import org.opendatakit.briefcase.reused.http.response.Response;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.reused.transfer.AggregateServer;
import org.opendatakit.briefcase.transfer.TransferForms;
import org.opendatakit.briefcase.ui.pull.PullPanel;
import org.opendatakit.briefcase.util.FormCache;
import org.opendatakit.common.cli.Args;
import org.opendatakit.common.cli.Operation;
import org.opendatakit.common.cli.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PullFormFromCentral {
  private static final Logger log = LoggerFactory.getLogger(PullFormFromCentral.class);
  public static final Param<Void> PULL_FROM_CENTRAL = Param.flag("pllc", "pull_central", "Pull form from a Central server");
  public static final Param<URL> SERVER_URL = Param.arg("url", "url", "Central url", RequestBuilder::url);
  public static final Param<Integer> PROJECT_ID = Param.arg("pid", "project_id", "Central Project ID number", Integer::parseInt);
  public static final Param<String> EMAIL = Param.arg("em", "email", "Central user account email");
  public static final Param<String> PASSWORD = Param.arg("p", "password", "Central user account password");


  public static Operation OPERATION = Operation.of(
      PULL_FROM_CENTRAL,
      PullFormFromCentral::pullFormFromAggregate,
      Arrays.asList(STORAGE_DIR, SERVER_URL, PROJECT_ID, EMAIL, PASSWORD),
      Arrays.asList(FORM_ID, MAX_HTTP_CONNECTIONS)
  );

  public static void pullFormFromAggregate(Args args) {
    CliEventsCompanion.attach(log);
    Path briefcaseDir = Common.getOrCreateBriefcaseDir(args.get(STORAGE_DIR));
    FormCache formCache = FormCache.from(briefcaseDir);
    formCache.update();
    BriefcasePreferences appPreferences = BriefcasePreferences.appScoped();
    appPreferences.setStorageDir(briefcaseDir);
    BriefcasePreferences tabPreferences = BriefcasePreferences.forClass(PullPanel.class);

    int maxHttpConnections = Optionals.race(
        args.getOptional(MAX_HTTP_CONNECTIONS),
        appPreferences.getMaxHttpConnections()
    ).orElse(DEFAULT_HTTP_CONNECTIONS);
    Http http = appPreferences.getHttpProxy()
        .map(host -> CommonsHttp.of(maxHttpConnections, host))
        .orElseGet(() -> CommonsHttp.of(maxHttpConnections));

    AggregateServer aggregateServer = AggregateServer.authenticated(args.get(SERVER_URL), new Credentials(args.get(EMAIL), args.get(PASSWORD)));

    Response<List<RemoteFormDefinition>> response = http.execute(aggregateServer.getFormListRequest());
    if (!response.isSuccess()) {
      System.err.println(response.isRedirection()
          ? "Error connecting to Aggregate: Redirection detected"
          : response.isUnauthorized()
          ? "Error connecting to Aggregate: Wrong credentials"
          : response.isNotFound()
          ? "Error connecting to Aggregate: Aggregate not found"
          : "Error connecting to Aggregate");
      return;
    }

    Optional<String> formId = args.getOptional(FORM_ID);

    List<FormStatus> filteredForms = response.orElseThrow(BriefcaseException::new)
        .stream()
        .filter(f -> formId.map(id -> f.getFormId().equals(id)).orElse(true))
        .map(FormStatus::new)
        .collect(toList());

    if (formId.isPresent() && filteredForms.isEmpty())
      throw new BriefcaseException("Form " + formId.get() + " not found");

    TransferForms forms = TransferForms.empty();
    forms.load(filteredForms);
    forms.selectAll();

    PullFromAggregate pullOp = new PullFromAggregate(http, aggregateServer, briefcaseDir, appPreferences, false, PullFormFromCentral::onEvent);
    JobsRunner.launchAsync(
        forms.map(form -> pullOp.pull(form, resolveCursor(
            false,
            Optional.empty(),
            appPreferences,
            tabPreferences,
            form
        ))),
        PullFormFromCentral::onError
    ).waitForCompletion();
    System.out.println();
    System.out.println("All operations completed");
    System.out.println();
  }

  private static Optional<Cursor> resolveCursor(boolean resumeLastPull, Optional<LocalDate> startFromDate, BriefcasePreferences appPreferences, BriefcasePreferences localPreferences, FormStatus form) {
    return Optionals.race(
        startFromDate.map(Cursor::of),
        resumeLastPull
            ? Optionals.race(Cursor.readPrefs(form, appPreferences), Cursor.readPrefs(form, localPreferences))
            : Optional.empty()
    );
  }

  private static void onEvent(FormStatusEvent event) {
    System.out.println(event.getStatus().getFormName() + " - " + event.getStatusString());
    // The PullFromAggregateTracker already logs normal events
  }

  private static void onError(Throwable e) {
    System.err.println("Error pulling a form: " + e.getMessage() + " (see the logs for more info)");
    log.error("Error pulling a form", e);
  }

}
