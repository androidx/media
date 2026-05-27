/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp3;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link VbriSeeker}. */
@RunWith(AndroidJUnit4.class)
public final class VbriSeekerTest {

  private static final int VBRI_FRAME_HEADER_DATA = 0xFFFB3000;
  private static final int VBRI_FRAME_POSITION = 157;

  @Test
  public void getAverageBitrate_returnsAverageFromDataSizeAndDuration() {
    MpegAudioUtil.Header header = new MpegAudioUtil.Header();
    header.setForHeaderData(VBRI_FRAME_HEADER_DATA);
    int dataSize = 1_000;
    int frameCount = 40;
    VbriSeeker seeker =
        checkNotNull(
            VbriSeeker.create(
                /* inputLength= */ C.LENGTH_UNSET,
                VBRI_FRAME_POSITION,
                header,
                createVbriFrame(dataSize, frameCount, /* segmentSizes= */ 400, 600)));
    long durationUs =
        Util.sampleCountToDurationUs(
            ((long) frameCount * header.samplesPerFrame) - 1, header.sampleRate);
    int expectedAverageBitrate =
        (int)
            Util.scaleLargeValue(
                dataSize, C.BITS_PER_BYTE * C.MICROS_PER_SECOND, durationUs, RoundingMode.HALF_UP);

    assertThat(seeker.getAverageBitrate()).isEqualTo(expectedAverageBitrate);
    assertThat(seeker.getAverageBitrate()).isNotEqualTo(header.bitrate);
  }

  private static ParsableByteArray createVbriFrame(
      int dataSize, int frameCount, int... segmentSizes) {
    ByteBuffer payload = ByteBuffer.allocate(6 + 4 + 4 + 2 + 2 + 2 + 2 + 2 * segmentSizes.length);
    payload.position(6);
    payload.putInt(dataSize);
    payload.putInt(frameCount);
    payload.putShort((short) segmentSizes.length);
    payload.putShort((short) 1); // scale
    payload.putShort((short) 2); // entry size
    payload.putShort((short) 0);
    for (int segmentSize : segmentSizes) {
      payload.putShort((short) segmentSize);
    }
    return new ParsableByteArray(payload.array());
  }
}
