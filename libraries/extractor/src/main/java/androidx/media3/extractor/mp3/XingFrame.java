/*
 * Copyright 2024 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.MpegAudioUtil;

/** Representation of a LAME Xing or Info frame. */
/* package */ final class XingFrame {

  /** The header of the Xing or Info frame. */
  public final MpegAudioUtil.Header header;

  /** The frame count, or {@link C#LENGTH_UNSET} if not present in the header. */
  public final long frameCount;

  /**
   * Data size, including the XING frame, or {@link C#LENGTH_UNSET} if not present in the header.
   */
  public final long dataSize;

  /** Whether this frame is the LAME variant of a Xing frame, and hence has ReplayGain data. */
  public final boolean hasReplayGain;

  /**
   * @see Mp3InfoReplayGain#peak
   */
  public final float replayGainPeak;

  /**
   * @see Mp3InfoReplayGain#field1Name
   */
  public final byte replayGainField1Name;

  /**
   * @see Mp3InfoReplayGain#field1Originator
   */
  public final byte replayGainField1Originator;

  /**
   * @see Mp3InfoReplayGain#field1Value
   */
  public final float replayGainField1Value;

  /**
   * @see Mp3InfoReplayGain#field2Name
   */
  public final byte replayGainField2Name;

  /**
   * @see Mp3InfoReplayGain#field2Originator
   */
  public final byte replayGainField2Originator;

  /**
   * @see Mp3InfoReplayGain#field2Value
   */
  public final float replayGainField2Value;

  /**
   * The number of samples to skip at the start of the stream, or {@link C#LENGTH_UNSET} if not
   * present in the header.
   */
  public final int encoderDelay;

  /**
   * The number of samples to skip at the end of the stream, or {@link C#LENGTH_UNSET} if not
   * present in the header.
   */
  public final int encoderPadding;

  /**
   * Entries are in the range [0, 255], but are stored as long integers for convenience. Null if the
   * table of contents was missing from the header, in which case seeking is not be supported.
   */
  @Nullable public final long[] tableOfContents;

  private XingFrame(
      MpegAudioUtil.Header header,
      long frameCount,
      long dataSize,
      @Nullable long[] tableOfContents,
      boolean hasReplayGain,
      float replayGainPeak,
      byte replayGainField1Name,
      byte replayGainField1Originator,
      float replayGainField1Value,
      byte replayGainField2Name,
      byte replayGainField2Originator,
      float replayGainField2Value,
      int encoderDelay,
      int encoderPadding) {
    this.header = new MpegAudioUtil.Header(header);
    this.frameCount = frameCount;
    this.dataSize = dataSize;
    this.tableOfContents = tableOfContents;
    this.hasReplayGain = hasReplayGain;
    this.replayGainPeak = replayGainPeak;
    this.replayGainField1Name = replayGainField1Name;
    this.replayGainField1Originator = replayGainField1Originator;
    this.replayGainField1Value = replayGainField1Value;
    this.replayGainField2Name = replayGainField2Name;
    this.replayGainField2Originator = replayGainField2Originator;
    this.replayGainField2Value = replayGainField2Value;
    this.encoderDelay = encoderDelay;
    this.encoderPadding = encoderPadding;
  }

  /**
   * Returns a {@link XingFrame} containing the info parsed from a LAME Xing (VBR) or Info (CBR)
   * frame.
   *
   * <p>The {@link ParsableByteArray#getPosition()} in {@code frame} when this method exits is
   * undefined.
   *
   * @param mpegAudioHeader The MPEG audio header associated with the frame.
   * @param frame The data in this audio frame, with its position set to immediately after the
   *     'Xing' or 'Info' tag.
   */
  public static XingFrame parse(MpegAudioUtil.Header mpegAudioHeader, ParsableByteArray frame) {
    int flags = frame.readInt();
    int frameCount = (flags & 0x01) != 0 ? frame.readUnsignedIntToInt() : C.LENGTH_UNSET;
    long dataSize = (flags & 0x02) != 0 ? frame.readUnsignedInt() : C.LENGTH_UNSET;

    long[] tableOfContents;
    if ((flags & 0x04) == 0x04) {
      tableOfContents = new long[100];
      for (int i = 0; i < 100; i++) {
        tableOfContents[i] = frame.readUnsignedByte();
      }
    } else {
      tableOfContents = null;
    }

    if ((flags & 0x8) != 0) {
      frame.skipBytes(4); // Quality indicator
    }

    boolean hasReplayGain = false;
    float replayGainPeak = 0f;
    byte replayGainField1Name = 0;
    byte replayGainField1Originator = 0;
    float replayGainField1Value = 0;
    byte replayGainField2Name = 0;
    byte replayGainField2Originator = 0;
    float replayGainField2Value = 0;
    int encoderDelay = C.LENGTH_UNSET;
    int encoderPadding = C.LENGTH_UNSET;
    // Skip: version string (9), revision & VBR method (1), lowpass filter (1).
    int bytesToSkipBeforeReplayGain = 9 + 1 + 1;
    if (frame.bytesLeft() >= bytesToSkipBeforeReplayGain + 8) {
      frame.skipBytes(bytesToSkipBeforeReplayGain);
      hasReplayGain = true;
      replayGainPeak = frame.readFloat();
      short field1 = frame.readShort();
      replayGainField1Name = (byte) ((field1 >> 13) & 7);
      replayGainField1Originator = (byte) ((field1 >> 10) & 7);
      replayGainField1Value = ((field1 & 0x1ff) * ((field1 & 0x200) != 0 ? -1 : 1)) / 10f;
      short field2 = frame.readShort();
      replayGainField2Name = (byte) ((field2 >> 13) & 7);
      replayGainField2Originator = (byte) ((field2 >> 10) & 7);
      replayGainField2Value = ((field2 & 0x1ff) * ((field2 & 0x200) != 0 ? -1 : 1)) / 10f;

      // Skip: encoding flags & ATH type (1), bitrate (1).
      int bytesToSkipBeforeEncoderDelayAndPadding = 1 + 1;
      if (frame.bytesLeft() >= bytesToSkipBeforeEncoderDelayAndPadding + 3) {
        frame.skipBytes(bytesToSkipBeforeEncoderDelayAndPadding);
        int encoderDelayAndPadding = frame.readUnsignedInt24();
        encoderDelay = (encoderDelayAndPadding & 0xFFF000) >> 12;
        encoderPadding = (encoderDelayAndPadding & 0xFFF);
      }
    }

    return new XingFrame(
        mpegAudioHeader,
        frameCount,
        dataSize,
        tableOfContents,
        hasReplayGain,
        replayGainPeak,
        replayGainField1Name,
        replayGainField1Originator,
        replayGainField1Value,
        replayGainField2Name,
        replayGainField2Originator,
        replayGainField2Value,
        encoderDelay,
        encoderPadding);
  }

  /**
   * Compute the stream duration, in microseconds, represented by this frame. Returns {@link
   * C#LENGTH_UNSET} if the frame doesn't contain enough information to compute a duration.
   */
  // TODO: b/319235116 - Handle encoder delay and padding when calculating duration.
  public long computeDurationUs() {
    if (frameCount == C.LENGTH_UNSET || frameCount == 0) {
      // If the frame count is missing/invalid, the header can't be used to determine the duration.
      return C.TIME_UNSET;
    }
    // Audio requires both a start and end PCM sample, so subtract one from the sample count before
    // calculating the duration.
    return Util.sampleCountToDurationUs(
        (frameCount * header.samplesPerFrame) - 1, header.sampleRate);
  }

  /** Provide the metadata derived from this Xing frame, such as ReplayGain data. */
  public @Nullable Metadata getMetadata() {
    if (hasReplayGain) {
      return new Metadata(new Mp3InfoReplayGain(this));
    }
    return null;
  }
}
