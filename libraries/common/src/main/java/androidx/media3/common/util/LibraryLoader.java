/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.common.util;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Configurable loader for native libraries. */
@UnstableApi
public abstract class LibraryLoader {

  private static final String TAG = "LibraryLoader";

  private String[] nativeLibraries;
  private boolean loadAttempted;
  private boolean isAvailable;
  private @Nullable Boolean isAvailableForTesting;

  /**
   * @param libraries The names of the libraries to load.
   */
  public LibraryLoader(String... libraries) {
    nativeLibraries = libraries;
  }

  /**
   * Overrides the names of the libraries to load. Must be called before any call to {@link
   * #isAvailable()}.
   */
  public synchronized void setLibraries(String... libraries) {
    checkState(!loadAttempted, "Cannot set libraries after loading");
    nativeLibraries = libraries;
  }

  /** Returns whether the underlying libraries are available, loading them if necessary. */
  public synchronized boolean isAvailable() {
    if (isAvailableForTesting != null) {
      return isAvailableForTesting;
    }
    if (loadAttempted) {
      return isAvailable;
    }
    loadAttempted = true;
    try {
      for (String lib : nativeLibraries) {
        loadLibrary(lib);
      }
      isAvailable = true;
    } catch (UnsatisfiedLinkError exception) {
      // Log a warning as an attempt to check for the library indicates that the app depends on an
      // extension and generally would expect its native libraries to be available.
      Log.w(TAG, "Failed to load " + Arrays.toString(nativeLibraries), exception);
    }
    return isAvailable;
  }

  /**
   * Overrides whether the underlying libraries are available for testing.
   *
   * <p>Passing {@code true} skips loading the native libraries, so it must only be used in
   * Java-layer unit tests (such as Robolectric tests) where code paths relying on native methods
   * are not invoked.
   *
   * @param isAvailable Whether the underlying libraries should be reported as available, or {@code
   *     null} to restore default loading behavior.
   */
  @RestrictTo(LIBRARY_GROUP)
  @VisibleForTesting
  public synchronized void setAvailableForTesting(@Nullable Boolean isAvailable) {
    this.isAvailableForTesting = isAvailable;
  }

  /**
   * Should be implemented to call {@code System.loadLibrary(name)}.
   *
   * <p>It's necessary for each subclass to implement this method because {@link
   * System#loadLibrary(String)} uses reflection to obtain the calling class, which is then used to
   * obtain the class loader to use when loading the native library. If this class were to implement
   * the method directly, and if a subclass were to have a different class loader, then loading of
   * the native library would fail.
   *
   * @param name The name of the library to load.
   */
  protected abstract void loadLibrary(String name);
}
