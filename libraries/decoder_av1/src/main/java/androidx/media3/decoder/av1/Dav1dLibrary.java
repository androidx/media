/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.av1;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.UnstableApi;
import org.jspecify.annotations.Nullable;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class Dav1dLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.dav1d");
  }

  private static final LibraryLoader LOADER =
      new LibraryLoader("dav1dJNI") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  private Dav1dLibrary() {}

  // LINT.IfChange(is_available)
  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    try {
      return LOADER.isAvailable();
    } catch (Exception e) {
      return false;
    }
  }

  // LINT.ThenChange(
  //     ../../../../../../../../exoplayer/proguard-rules.txt,
  //
  // ../../../../../../../../exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java:dav1d_extension_discovery)

  /**
   * Overrides whether the underlying library is available for testing.
   *
   * <p>Passing {@code true} skips loading the native library, so it must only be used in Java-layer
   * unit tests (such as Robolectric tests) where code paths relying on native methods are not
   * invoked.
   *
   * @param isAvailable Whether the underlying library should be reported as available, or {@code
   *     null} to restore default loading behavior.
   */
  @RestrictTo(LIBRARY_GROUP)
  @VisibleForTesting
  public static void setAvailableForTesting(@Nullable Boolean isAvailable) {
    LOADER.setAvailableForTesting(isAvailable);
  }
}
