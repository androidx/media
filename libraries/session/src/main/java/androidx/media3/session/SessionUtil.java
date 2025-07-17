/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.annotation.Nullable;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/** Static utilities for the session module. */
/* package */ class SessionUtil {

  private SessionUtil() {}

  /**
   * Returns whether the given package is one of the ones that is owned by UID.
   *
   * @param context A {@link Context}.
   * @param packageName The package name to check.
   * @param uid The process UID to which the package name should belong.
   * @return Whether the package name belongs to the given UID.
   */
  @EnsuresNonNullIf(result = true, expression = "#1")
  public static boolean isValidPackage(Context context, @Nullable String packageName, int uid) {
    if (packageName == null) {
      return false;
    }
    PackageManager packageManager = context.getPackageManager();
    String[] packagesForUid = packageManager.getPackagesForUid(uid);
    if (packagesForUid == null) {
      return false;
    }
    for (String packageForUid : packagesForUid) {
      if (packageForUid.equals(packageName)) {
        return true;
      }
    }
    return false;
  }
}
