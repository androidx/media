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
package androidx.media3.muxer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.Format;
import androidx.media3.common.util.CodecSpecificDataUtil;
import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A writer for AAC audio data. */
/* package */ final class AacWriter {

  private static final int ADTS_HEADER_LENGTH = 7;

  private static final ImmutableMap<Integer, Integer> SAMPLE_RATE_TABLE_INDEX =
      ImmutableMap.ofEntries(
          Map.entry(96000, 0),
          Map.entry(88200, 1),
          Map.entry(64000, 2),
          Map.entry(48000, 3),
          Map.entry(44100, 4),
          Map.entry(32000, 5),
          Map.entry(24000, 6),
          Map.entry(22050, 7),
          Map.entry(16000, 8),
          Map.entry(12000, 9),
          Map.entry(11025, 10),
          Map.entry(8000, 11),
          Map.entry(7350, 12));

  private final FileChannel outputFileChannel;

  private @MonotonicNonNull Format format;
  private int profileCode;
  private int sampleFreqIndex;

  public AacWriter(FileOutputStream outputStream) {
    outputFileChannel = outputStream.getChannel();
  }

  /**
   * Sets the {@link Format} for the AAC track.
   *
   * <p>This method must be called before any samples are written.
   *
   * @param format The {@link Format} of the AAC audio track.
   */
  public void setFormat(Format format) {
    checkArgument(
        format.channelCount >= 1 && format.channelCount <= 7,
        "Channel count must be between 1 and 7, got %s",
        format.channelCount);

    profileCode = checkNotNull(CodecSpecificDataUtil.getCodecProfileAndLevel(format)).first;
    sampleFreqIndex = checkNotNull(SAMPLE_RATE_TABLE_INDEX.get(format.sampleRate));
    this.format = format;
  }

  public void writeSampleData(ByteBuffer byteBuffer, BufferInfo bufferInfo) throws IOException {
    ByteBuffer adtsHeader = createAdtsHeader(bufferInfo.size + ADTS_HEADER_LENGTH);
    outputFileChannel.write(adtsHeader);
    outputFileChannel.write(byteBuffer);
  }

  private ByteBuffer createAdtsHeader(int frameLength) {
    ByteBuffer content = ByteBuffer.allocate(ADTS_HEADER_LENGTH);
    // // First 8 bits of syncword 0xFFF (12 bits), all bits must be 1
    content.put((byte) 0xFF);

    // last 4 bits of syncword (4 bits) + fieldId (1 bit) + mpegLayer (2 bits) + protectionAbsence
    // (1 bit).
    content.put((byte) (0xF << 4 | 0x0 << 3 | 0x0 << 1 | 0x1));

    int privateStream = 0;
    int channelConfigCode = checkNotNull(format).channelCount;

    // profile code (2 bits) + sampling frequency index code (4 bits) + private stream (1 bit) +
    // channel configuration code (3 bits).
    byte data =
        (byte)
            ((profileCode << 6)
                | (sampleFreqIndex << 2)
                | (privateStream << 1)
                | (channelConfigCode >> 2)); // Upper 1 bit of 3-bit channel_configuration
    content.put(data);
    // channel_configuration (2 bits) +  originality (1 bit), home (1 bit),
    // copyrighted_stream (1 bit), copyright_start (1 bit) + frame_length (2 bits).
    data =
        (byte)
            (((channelConfigCode & 3) << 6)
                | (0 << 2) // Combined 4 bits, all set to 0
                | ((frameLength & 0x1800) >> 11));
    content.put(data);
    // frame_length (8 bits)
    data = (byte) ((frameLength & 0x07F8) >> 3);
    content.put(data);
    // 0x7FF indicates a variable bitrate stream.
    int bufferFullness = 0x7FF;
    //  frame_length (3 bits) + adts_buffer_fullness (5 bits)
    data = (byte) (((frameLength & 0x07) << 5) | ((bufferFullness & 0x07C0) >> 6));
    content.put(data);
    // adts_buffer_fullness (6 bits) + frames count (2 bits)
    data = (byte) ((bufferFullness & 0x03F) << 2);
    content.put(data);
    content.flip();
    return content;
  }
}
