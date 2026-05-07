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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link XingFrame}. */
@RunWith(AndroidJUnit4.class)
public final class XingFrameTest {

  private static final int INFO_FRAME_HEADER_DATA = 0xFFFB40C0;

  @Test
  public void parse_withLameEncoderIdentifier_adjustsDelayAndPaddingForDecodedPcm() {
    XingFrame frame =
        createXingFrame(
            INFO_FRAME_HEADER_DATA,
            /* frameCount= */ 40,
            /* dataSize= */ 8_541,
            /* encoderDelay= */ 576,
            /* encoderPadding= */ 1_404,
            /* encoderIdentifier= */ "LAME3.99r");

    assertThat(frame.encoderDelay).isEqualTo(1_105);
    assertThat(frame.encoderPadding).isEqualTo(875);
  }

  @Test
  public void parse_withLameEncoderIdentifierAndSmallPadding_clampsPaddingToZero() {
    XingFrame frame =
        createXingFrame(
            INFO_FRAME_HEADER_DATA,
            /* frameCount= */ 40,
            /* dataSize= */ 8_541,
            /* encoderDelay= */ 576,
            /* encoderPadding= */ 398,
            /* encoderIdentifier= */ "LAME3.99r");

    assertThat(frame.encoderDelay).isEqualTo(1_105);
    assertThat(frame.encoderPadding).isEqualTo(0);
  }

  private static XingFrame createXingFrame(
      int headerData, int frameCount, int dataSize, int encoderDelay, int encoderPadding) {
    return createXingFrame(
        headerData,
        frameCount,
        dataSize,
        encoderDelay,
        encoderPadding,
        /* encoderIdentifier= */ "");
  }

  private static XingFrame createXingFrame(
      int headerData,
      int frameCount,
      int dataSize,
      int encoderDelay,
      int encoderPadding,
      String encoderIdentifier) {
    int encoderDelayAndPadding = (encoderDelay << 12) | encoderPadding;
    ByteBuffer payload = ByteBuffer.allocate(4 + 4 + 4 + 11 + 8 + 2 + 3);
    payload.putInt(0x03);
    payload.putInt(frameCount);
    payload.putInt(dataSize);
    payload.put(createFixedLengthEncoderIdentifier(encoderIdentifier));
    payload.position(payload.position() + 2 + 8 + 2);
    payload.put((byte) (encoderDelayAndPadding >> 16));
    payload.put((byte) (encoderDelayAndPadding >> 8));
    payload.put((byte) encoderDelayAndPadding);
    return createXingFrame(headerData, payload.array());
  }

  private static byte[] createFixedLengthEncoderIdentifier(String encoderIdentifier) {
    byte[] fixedLengthIdentifier = new byte[9];
    byte[] identifierBytes = Util.getUtf8Bytes(encoderIdentifier);
    System.arraycopy(
        identifierBytes,
        /* srcPos= */ 0,
        fixedLengthIdentifier,
        /* destPos= */ 0,
        Math.min(identifierBytes.length, fixedLengthIdentifier.length));
    return fixedLengthIdentifier;
  }

  private static XingFrame createXingFrame(int headerData, byte[] payload) {
    MpegAudioUtil.Header xingFrameHeader = new MpegAudioUtil.Header();
    xingFrameHeader.setForHeaderData(headerData);
    return XingFrame.parse(xingFrameHeader, new ParsableByteArray(payload));
  }
}
