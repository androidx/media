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
package androidx.media3.test.proguard;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test executing methods in {@link EffectNdkModuleProguard}. */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4.class)
public final class EffectNdkModuleProguardTest {

  @Test
  public void bindEglImage_succeeds() {
    EffectNdkModuleProguard.bindEglImage();
  }
}
