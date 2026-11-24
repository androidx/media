/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link LibraryLoader}. */
@RunWith(AndroidJUnit4.class)
public final class LibraryLoaderTest {

  @Test
  public void isAvailable_whenSetAvailableForTestingTrue_returnsTrueWithoutLoadingLibraries() {
    List<String> loadedLibraries = new ArrayList<>();
    LibraryLoader loader =
        new LibraryLoader("testLib") {
          @Override
          protected void loadLibrary(String name) {
            loadedLibraries.add(name);
            throw new UnsatisfiedLinkError("missing " + name);
          }
        };

    loader.setAvailableForTesting(true);
    boolean available = loader.isAvailable();

    assertThat(available).isTrue();
    assertThat(loadedLibraries).isEmpty();
  }

  @Test
  public void isAvailable_whenSetAvailableForTestingFalse_returnsFalseWithoutLoadingLibraries() {
    List<String> loadedLibraries = new ArrayList<>();
    LibraryLoader loader =
        new LibraryLoader("testLib") {
          @Override
          protected void loadLibrary(String name) {
            loadedLibraries.add(name);
          }
        };

    loader.setAvailableForTesting(false);
    boolean available = loader.isAvailable();

    assertThat(available).isFalse();
    assertThat(loadedLibraries).isEmpty();
  }

  @Test
  public void setAvailableForTesting_withNull_restoresDefaultLoadingBehavior() {
    List<String> loadedLibraries = new ArrayList<>();
    LibraryLoader loader =
        new LibraryLoader("testLib") {
          @Override
          protected void loadLibrary(String name) {
            loadedLibraries.add(name);
          }
        };
    loader.setAvailableForTesting(false);
    boolean availableWhileOverridden = loader.isAvailable();

    loader.setAvailableForTesting(null);
    boolean availableAfterReset = loader.isAvailable();

    assertThat(availableWhileOverridden).isFalse();
    assertThat(availableAfterReset).isTrue();
    assertThat(loadedLibraries).containsExactly("testLib");
  }

  @Test
  public void isAvailable_whenSetAvailableForTestingAfterFailedLoad_overridesCachedResult() {
    List<String> loadedLibraries = new ArrayList<>();
    LibraryLoader loader =
        new LibraryLoader("testLib") {
          @Override
          protected void loadLibrary(String name) {
            loadedLibraries.add(name);
            throw new UnsatisfiedLinkError("missing " + name);
          }
        };
    boolean initialAvailable = loader.isAvailable();

    loader.setAvailableForTesting(true);
    boolean overriddenAvailable = loader.isAvailable();
    loader.setAvailableForTesting(null);
    boolean restoredAvailable = loader.isAvailable();

    assertThat(initialAvailable).isFalse();
    assertThat(overriddenAvailable).isTrue();
    assertThat(restoredAvailable).isFalse();
    assertThat(loadedLibraries).containsExactly("testLib");
  }
}
