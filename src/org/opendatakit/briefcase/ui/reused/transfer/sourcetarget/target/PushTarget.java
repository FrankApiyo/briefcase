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

package org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.target;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.reused.http.Http;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.reused.transfer.AggregateServer;
import org.opendatakit.briefcase.reused.transfer.CentralServer;
import org.opendatakit.briefcase.transfer.TransferForms;
import org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.SourceOrTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface PushTarget<T> extends SourceOrTarget<T> {
  Logger log = LoggerFactory.getLogger(PushTarget.class);

  static void clearAllPreferences(BriefcasePreferences prefs) {
    Aggregate.clearPreferences(prefs);
    Central.clearPreferences(prefs);
  }

  static PushTarget<AggregateServer> aggregate(Http http, Consumer<PushTarget> consumer) {
    return new Aggregate(http, server -> server.testPush(http), "Form Manager", consumer);
  }

  static PushTarget<CentralServer> central(Http http, Consumer<PushTarget> consumer) {
    return new Central(http, server -> server.testCredentials(http), consumer);
  }

  void storePreferences(BriefcasePreferences prefs, boolean storePasswords);

  JobsRunner push(TransferForms forms, Path briefcaseDir);

  String getDescription();

}

