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
package androidx.media3.exoplayer.rtsp.reader;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link RtpReaderUtils}. */
@RunWith(AndroidJUnit4.class)
public class RtpReaderUtilsTest {
  @Test
  public void toSampleTimeUs_withWrappedTimestamp_returnsCorrectSampleTimeUs() {
    assertThat(
            RtpReaderUtils.toSampleTimeUs(
                /* startTimeOffsetUs= */ 0,
                /* rtpTimestamp= */ 9_000_040L,
                /* firstReceivedRtpTimestamp= */ 4_285_967_336L,
                /* mediaFrequency= */ 90_000))
        .isEqualTo(200_000_000L);
  }
}
