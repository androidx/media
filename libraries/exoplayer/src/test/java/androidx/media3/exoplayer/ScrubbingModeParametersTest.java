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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ScrubbingModeParameters}. */
@RunWith(AndroidJUnit4.class)
public final class ScrubbingModeParametersTest {

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated fields.
  public void defaultValues() {
    assertThat(ScrubbingModeParameters.DEFAULT.disabledTrackTypes)
        .containsExactly(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA);
    assertThat(ScrubbingModeParameters.DEFAULT.fractionalSeekToleranceBefore).isNull();
    assertThat(ScrubbingModeParameters.DEFAULT.fractionalSeekToleranceAfter).isNull();
    assertThat(ScrubbingModeParameters.DEFAULT.shouldIncreaseCodecOperatingRate).isTrue();
    assertThat(ScrubbingModeParameters.DEFAULT.isMediaCodecFlushEnabled).isFalse();
    assertThat(ScrubbingModeParameters.DEFAULT.allowSkippingMediaCodecFlush).isTrue();
    assertThat(ScrubbingModeParameters.DEFAULT.allowSkippingKeyFrameReset).isTrue();
    assertThat(ScrubbingModeParameters.DEFAULT.shouldEnableDynamicScheduling).isTrue();
    assertThat(ScrubbingModeParameters.DEFAULT.useDecodeOnlyFlag).isTrue();
  }
}
