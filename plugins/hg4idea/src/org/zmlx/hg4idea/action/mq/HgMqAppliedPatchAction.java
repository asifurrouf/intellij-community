/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.zmlx.hg4idea.action.mq;

import com.intellij.openapi.util.Condition;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.vcs.log.VcsFullCommitDetails;
import org.jetbrains.annotations.NotNull;
import org.zmlx.hg4idea.HgNameWithHashInfo;
import org.zmlx.hg4idea.action.HgLogSingleCommitAction;
import org.zmlx.hg4idea.repo.HgRepository;

public abstract class HgMqAppliedPatchAction extends HgLogSingleCommitAction {

  @Override
  protected boolean isEnabled(@NotNull MultiMap<HgRepository, VcsFullCommitDetails> grouped) {
    return super.isEnabled(grouped) &&
           grouped.values().size() == 1 &&
           isAppliedPatch(grouped.keySet().iterator().next(), grouped.values().iterator().next());
  }

  public static boolean isAppliedPatch(@NotNull HgRepository repository, @NotNull final VcsFullCommitDetails commitDetails) {
    return ContainerUtil.exists(repository.getMQAppliedPatches(), new Condition<HgNameWithHashInfo>() {
      @Override
      public boolean value(HgNameWithHashInfo info) {
        return info.getHash().equals(commitDetails.getId());
      }
    });
  }
}
