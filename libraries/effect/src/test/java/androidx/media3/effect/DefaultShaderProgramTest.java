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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultShaderProgram}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultShaderProgramTest {

  private static final ColorInfo HDR_HLG_COLOR_INFO =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT2020)
          .setColorRange(C.COLOR_RANGE_LIMITED)
          .setColorTransfer(C.COLOR_TRANSFER_HLG)
          .build();

  @Test
  public void createWithExternalSampler_hdrInputWithoutYuvTargetExtension_throwsException() {
    assumeFalse(GlUtil.isYuvTargetExtensionSupported());

    VideoFrameProcessingException exception =
        assertThrows(
            VideoFrameProcessingException.class,
            () ->
                DefaultShaderProgram.createWithExternalSampler(
                    ApplicationProvider.getApplicationContext(),
                    /* inputColorInfo= */ HDR_HLG_COLOR_INFO,
                    /* outputColorInfo= */ ColorInfo.SDR_BT709_LIMITED,
                    WORKING_COLOR_SPACE_DEFAULT,
                    /* sampleWithNearest= */ false));

    assertThat(exception)
        .hasMessageThat()
        .contains("The EXT_YUV_target extension is required for HDR editing input.");
  }
}
