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

import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.UnstableApi;

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

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    try {
      return LOADER.isAvailable();
    } catch (Exception e) {
      return false;
    }
  }
}
