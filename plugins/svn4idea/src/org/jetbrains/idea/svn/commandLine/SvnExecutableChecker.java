/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.commandLine;

import com.intellij.execution.ExecutableValidator;
import com.intellij.notification.Notification;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Version;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.*;

/**
 * Created with IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 1/31/12
 * Time: 3:02 PM
 */
public class SvnExecutableChecker extends ExecutableValidator {

  private static final Logger LOG = Logger.getInstance(SvnExecutableChecker.class);

  public SvnExecutableChecker(Project project) {
    super(project, getNotificationTitle(), getWrongPathMessage());
  }

  @Override
  protected String getCurrentExecutable() {
    return SvnApplicationSettings.getInstance().getCommandLinePath();
  }

  @NotNull
  @Override
  protected String getConfigurableDisplayName() {
    return SvnConfigurable.DISPLAY_NAME;
  }

  @NotNull
  private SvnVcs getVcs() {
    return SvnVcs.getInstance(myProject);
  }

  @Override
  protected void showSettingsAndExpireIfFixed(@NotNull Notification notification) {
    showSettings();
    // always expire notification as different message could be detected
    notification.expire();

    getVcs().checkCommandLineVersion();
  }

  @Override
  @Nullable
  protected Notification validate(@NotNull String executable) {
    Notification result = createDefaultNotification();

    // Necessary executable path will be taken from settings while command execution
    final Version version = getConfiguredClientVersion();
    if (version != null) {
      try {
        result = validateVersion(version);
      }
      catch (Throwable e) {
        LOG.info(e);
      }
    }

    return result;
  }

  @Nullable
  private Notification validateVersion(@NotNull Version version) {
    return !getVcs().isSupportedByCommandLine(WorkingCopyFormat.from(version)) ? new ExecutableNotValidNotification(
      getOldExecutableMessage(version)) : null;
  }

  @Nullable
  private Version getConfiguredClientVersion() {
    Version result = null;

    try {
      result = getVcs().getCommandLineFactory().createVersionClient().getVersion();
    }
    catch (Throwable e) {
      LOG.info(e);
    }

    return result;
  }

  private static String getWrongPathMessage() {
    return SvnBundle.message("subversion.executable.notification.description");
  }

  private static String getNotificationTitle() {
    return SvnBundle.message("subversion.executable.notification.title");
  }

  private static String getOldExecutableMessage(@NotNull Version version) {
    return SvnBundle.message("subversion.executable.too.old", version);
  }
}
