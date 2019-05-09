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

package org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.source;

import static java.awt.Cursor.HAND_CURSOR;
import static java.awt.Cursor.getPredefinedCursor;
import static java.awt.Desktop.getDesktop;
import static java.util.stream.Collectors.toList;
import static javax.swing.SwingUtilities.invokeLater;
import static org.opendatakit.briefcase.ui.reused.UI.removeAllMouseListeners;

import java.awt.Container;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JLabel;
import org.bushe.swing.event.EventBus;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.pull.aggregate.PullEvent;
import org.opendatakit.briefcase.pull.aggregate.PullForm;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.DeferredValue;
import org.opendatakit.briefcase.reused.transfer.RemoteServer;
import org.opendatakit.briefcase.reused.http.Http;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.transfer.TransferForms;
import org.opendatakit.briefcase.ui.reused.MouseAdapterBuilder;
import org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.RemoteServerDialog;

public class Aggregate implements PullSource<RemoteServer> {
  private final Http http;
  private final Consumer<PullSource> consumer;
  private RemoteServer.Test serverTester;
  private String requiredPermission;
  private RemoteServer server;

  Aggregate(Http http, RemoteServer.Test serverTester, String requiredPermission, Consumer<PullSource> consumer) {
    this.http = http;
    this.serverTester = serverTester;
    this.requiredPermission = requiredPermission;
    this.consumer = consumer;
  }

  static void clearPreferences(BriefcasePreferences prefs) {
    prefs.removeAll(RemoteServer.PREFERENCE_KEYS);
  }

  private static void uncheckedBrowse(URL url) {
    try {
      getDesktop().browse(url.toURI());
    } catch (URISyntaxException | IOException e) {
      throw new BriefcaseException(e);
    }
  }

  @Override
  public void onSelect(Container ignored) {
    RemoteServerDialog dialog = RemoteServerDialog.empty(serverTester, requiredPermission);
    dialog.onConnect(this::set);
    dialog.getForm().setVisible(true);
  }

  @Override
  public void set(RemoteServer server) {
    this.server = server;
    consumer.accept(this);
  }

  @Override
  public boolean accepts(Object o) {
    return o instanceof RemoteServer;
  }

  @Override
  public DeferredValue<List<FormStatus>> getFormList() {
    return DeferredValue.of(() -> server.getFormsList(http).stream()
        .map(FormStatus::new)
        .collect(toList()));
  }

  @Override
  public void storePreferences(BriefcasePreferences prefs, boolean storePasswords) {
    server.storePreferences(prefs, storePasswords);
  }

  @Override
  public JobsRunner pull(TransferForms forms, Path briefcaseDir, boolean pullInParallel, Boolean includeIncomplete, boolean resumeLastPull, Optional<LocalDate> startFromDate) {
    return JobsRunner.launchAsync(
        forms.map(form -> PullForm.pull(http, server, briefcaseDir, includeIncomplete, EventBus::publish, form, resumeLastPull ? forms.getLastCursor(form) : Optional.empty())),
        results -> {
          results.forEach(result -> forms.setLastPullCursor(result.getForm(), result.getLastCursor()));
          EventBus.publish(new PullEvent.Success(forms, server.asServerConnectionInfo()));
        },
        e -> {
          log.error("Error pulling forms", e);
          EventBus.publish(new PullEvent.Failure());
        }
    );
  }

  @Override
  public boolean canBeReloaded() {
    return true;
  }

  @Override
  public String getDescription() {
    return server.getBaseUrl().toString();
  }

  @Override
  public void decorate(JLabel label) {
    label.setText("<html><a href=\"" + server.getBaseUrl().toString() + "\">" + getDescription() + "</a></html>");
    label.setCursor(getPredefinedCursor(HAND_CURSOR));
    removeAllMouseListeners(label);
    label.addMouseListener(new MouseAdapterBuilder()
        .onClick(__ -> invokeLater(() -> uncheckedBrowse(server.getBaseUrl())))
        .build());
  }

  @Override
  public String toString() {
    return "Aggregate server";
  }
}
