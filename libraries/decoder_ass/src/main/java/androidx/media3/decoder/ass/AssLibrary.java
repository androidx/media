/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.decoder.ass;

import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.UnstableApi;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class AssLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.ass");
  }

  private static final LibraryLoader LOADER =
      new LibraryLoader("assJNI") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  private AssLibrary() {}

  /**
   * Override the names of the Ass native libraries. If an application wishes to call this method,
   * it must do so before calling any other method defined by this class, and before instantiating
   * any {@link AssRenderer} instances.
   *
   * @param libraries The names of the Ass native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }
}
