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

import java.util.Optional;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.reused.BriefcaseException;

public class PullResult {
  private final FormStatus form;
  private final Optional<String> lastCursor;

  private PullResult(FormStatus form, Optional<String> lastCursor) {
    this.form = form;
    this.lastCursor = lastCursor;
  }

  public static PullResult of(FormStatus form, String lastCursor) {
    return new PullResult(form, Optional.of(lastCursor));
  }

  public static PullResult of(FormStatus form) {
    return new PullResult(form, Optional.empty());
  }

  public FormStatus getForm() {
    return form;
  }

  public String getLastCursor() {
    return lastCursor.orElseThrow(BriefcaseException::new);
  }
}
