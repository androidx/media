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
package androidx.media3.transformer;

import static org.junit.Assert.assertThrows;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link CodecDbLite}. */
@RunWith(AndroidJUnit4.class)
public class CodecDbLiteTest {

  @BeforeClass
  public static void setUp() {
    ShadowBuild.setSystemOnChipManufacturer("Google");
    ShadowBuild.setSystemOnChipModel("Tensor G3");
  }

  @Test
  public void getRecommendedVideoEncoderSettings_noMimeType_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CodecDbLite.getRecommendedVideoEncoderSettings(new Format.Builder().build()));
  }

  @Test
  public void getRecommendedVideoEncoderSettings_nonVideoMimeType_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CodecDbLite.getRecommendedVideoEncoderSettings(
                new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()));
  }
}
