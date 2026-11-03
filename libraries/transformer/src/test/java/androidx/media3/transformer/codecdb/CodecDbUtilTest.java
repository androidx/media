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
package androidx.media3.transformer.codecdb;

import static androidx.media3.transformer.codecdb.CodecDbUtil.calculatePixelsPerSecond;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CodecDbUtil}. */
@RunWith(AndroidJUnit4.class)
public class CodecDbUtilTest {

  @Test
  public void calculatePixelsPerSecond_withStandardResolutions_returnsCorrectPixelsPerSecond() {
    long pps1080p30 = calculatePixelsPerSecond(1920, 1080, 30.0);
    long pps720p60 = calculatePixelsPerSecond(1280, 720, 60.0);
    long pps4k60 = calculatePixelsPerSecond(3840, 2160, 60.0);

    assertThat(pps1080p30).isEqualTo(62_208_000L);
    assertThat(pps720p60).isEqualTo(55_296_000L);
    assertThat(pps4k60).isEqualTo(497_664_000L);
  }

  @Test
  public void calculatePixelsPerSecond_withHighThroughput8k120fps_doesNotOverflow32BitInteger() {
    long pps8k120 = calculatePixelsPerSecond(7680, 4320, 120.0);

    assertThat(pps8k120).isEqualTo(3_981_312_000L);
    assertThat(pps8k120).isGreaterThan((long) Integer.MAX_VALUE);
  }

  @Test
  public void calculatePixelsPerSecond_withFractionalFrameRate_roundsCorrectly() {
    long pps1080p2997 = calculatePixelsPerSecond(1920, 1080, 29.97);

    assertThat(pps1080p2997).isEqualTo(62_145_792L);
  }

  @Test
  public void
      calculatePixelsPerSecond_withInvalidDimensionsOrFrameRate_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(0, 1080, 30.0));
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(-1, 1080, 30.0));
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(1920, 0, 30.0));
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(1920, -1, 30.0));
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(1920, 1080, 0.0));
    assertThrows(IllegalArgumentException.class, () -> calculatePixelsPerSecond(1920, 1080, -1.0));
    assertThrows(
        IllegalArgumentException.class, () -> calculatePixelsPerSecond(1920, 1080, Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> calculatePixelsPerSecond(1920, 1080, Double.POSITIVE_INFINITY));
  }
}
