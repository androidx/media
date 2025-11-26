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
package androidx.media3.test.proguard;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test executing methods in {@link ExtractorModuleProguard}. */
@Ignore // Can't read asset list from gradle from test-proguard (internal bug-ref: b/463675073)
@RunWith(AndroidJUnit4.class)
public final class ExtractorModuleProguardTest {

  @Test
  public void defaultExtractorFactory_createExtensionFlacExtractor_succeeds() throws Exception {
    ExtractorModuleProguard.createLibFlacExtractorWithDefaultExtractorsFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultExtractorFactory_createMidiExtractor_succeeds() throws Exception {
    ExtractorModuleProguard.createMidiExtractorWithDefaultExtractorsFactory(
        ApplicationProvider.getApplicationContext());
  }
}
