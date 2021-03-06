/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package com.intellij.openapi.vcs.changes;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Konstantin Bulenkov
 */
public class PsiChangeTracker {
  private PsiChangeTracker() {
  }

  public static <T extends PsiElement> Map<T, FileStatus> getElementsChanged(PsiElement file,
                                                                             PsiElement oldFile,
                                                                             final PsiFilter<T> filter) {
    final HashMap<T, FileStatus> result = new HashMap<T, FileStatus>();
    final List<T> oldElements = new ArrayList<T>();
    final List<T> elements = new ArrayList<T>();

    if (file == null) {
      oldFile.accept(filter.createVisitor(oldElements));
      calculateStatuses(elements, oldElements, result, filter);
      return result;
    }

    final Project project = file.getProject();

    file.accept(filter.createVisitor(elements));
    final VirtualFile vf = file.getContainingFile().getVirtualFile();
    FileStatus status = vf == null ? null : FileStatusManager.getInstance(project).getStatus(vf);
    if (status == null && oldFile == null) {
      status = FileStatus.ADDED;
    }
    if (status == FileStatus.ADDED ||
        status == FileStatus.DELETED ||
        status == FileStatus.DELETED_FROM_FS ||
        status == FileStatus.UNKNOWN) {
      for (T element : elements) {
        result.put(element, status);
      }
      return result;
    }

    if (oldFile == null) return result;
    oldFile.accept(filter.createVisitor(oldElements));
    calculateStatuses(elements, oldElements, result, filter);

    return result;
  }

  private static <T extends PsiElement> Map<T, FileStatus> calculateStatuses(List<T> elements,
                                                                             List<T> oldElements,
                                                                             Map<T, FileStatus> result, PsiFilter<T> filter) {
    for (T element : elements) {
      T e = null;
      for (T oldElement : oldElements) {
        if (filter.areEquivalent(element, oldElement)) {
          e = oldElement;
          break;
        }
      }
      if (e != null) {
        oldElements.remove(e);
        if (!element.getText().equals(e.getText())) {
          result.put(element, FileStatus.MODIFIED);
        }
      }
      else {
        result.put(element, FileStatus.ADDED);
      }
    }

    for (T oldElement : oldElements) {
      result.put(oldElement, FileStatus.DELETED);
    }

    return result;
  }
}
