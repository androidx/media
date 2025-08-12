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

import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Static utilities for the session module. */
/* package */ class SessionUtil {

  private SessionUtil() {}

  /**
   * Result of {@link #checkPackageValidity(Context, String, int)}.
   *
   * <p>One of {@link #PACKAGE_VALID}, {@link #PACKAGE_INVALID}, or {@link #PACKAGE_CANT_CHECK}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({PACKAGE_VALID, PACKAGE_INVALID, PACKAGE_CANT_CHECK})
  public @interface PackageValidationResult {}

  /** The package is valid for the given UID. */
  public static final int PACKAGE_VALID = 0;

  /** The package is not valid for the given UID. */
  public static final int PACKAGE_INVALID = 1;

  /**
   * The package validity cannot be checked (e.g. because the package manager does not provide read
   * access to the requested UID).
   */
  public static final int PACKAGE_CANT_CHECK = 2;

  /**
   * Returns whether the given package is one of the ones that is owned by UID.
   *
   * @param context A {@link Context}.
   * @param packageName The package name to check.
   * @param uid The process UID to which the package name should belong.
   * @return The {@link PackageValidationResult} indicating whether the package name belongs to the
   *     given UID.
   */
  public static @PackageValidationResult int checkPackageValidity(
      Context context, @Nullable String packageName, int uid) {
    if (packageName == null) {
      return PACKAGE_INVALID;
    }
    PackageManager packageManager = context.getPackageManager();
    String[] packagesForUid = packageManager.getPackagesForUid(uid);
    if (packagesForUid == null || packagesForUid.length == 0) {
      return PACKAGE_CANT_CHECK;
    }
    for (String packageForUid : packagesForUid) {
      if (packageForUid.equals(packageName)) {
        return PACKAGE_VALID;
      }
    }
    return PACKAGE_INVALID;
  }

  /**
   * Disconnect a {@link IMediaController}, ignoring remote exceptions in case the controller is
   * broken or already died.
   */
  public static void disconnectIMediaController(@Nullable IMediaController controller) {
    try {
      if (controller != null) {
        controller.onDisconnected(/* seq= */ 0);
      }
    } catch (RemoteException e) {
      // Intentionally ignored. Controller may have died already or is malformed.
    }
  }
}
