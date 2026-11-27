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
package androidx.media3.muxer;

import static androidx.media3.common.util.Util.getByteDepth;
import static androidx.media3.common.util.Util.getPcmFrameSize;
import static androidx.media3.common.util.Util.toUnsignedInt;
import static androidx.media3.common.util.WavUtil.DATA_FOURCC;
import static androidx.media3.common.util.WavUtil.FACT_FOURCC;
import static androidx.media3.common.util.WavUtil.FMT_FOURCC;
import static androidx.media3.common.util.WavUtil.RIFF_FOURCC;
import static androidx.media3.common.util.WavUtil.TYPE_FLOAT;
import static androidx.media3.common.util.WavUtil.TYPE_PCM;
import static androidx.media3.common.util.WavUtil.WAVE_FOURCC;
import static androidx.media3.common.util.WavUtil.getTypeForPcmEncoding;
import static androidx.media3.muxer.MuxerUtil.UNSIGNED_INT_MAX_VALUE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Writes uncompressed PCM audio samples into a WAV file format.
 *
 * <p>Only integer PCM is supported.
 */
/* package */ final class WavWriter {

  private static final int FMT_CHUNK_SIZE_INT_PCM = 16;
  private static final int FMT_CHUNK_SIZE_FLOAT_PCM = 18;
  private static final int FACT_CHUNK_SIZE_FLOAT_PCM = 12;

  private final SeekableMuxerOutput muxerOutput;

  private @MonotonicNonNull Format format;
  private boolean headerAdded;
  private long dataSize;

  /**
   * Creates a new instance.
   *
   * @param muxerOutput The {@link SeekableMuxerOutput} to write the WAV data to. This output will
   *     be closed automatically when {@link #close()} is called.
   */
  public WavWriter(SeekableMuxerOutput muxerOutput) {
    this.muxerOutput = muxerOutput;
  }

  public void setFormat(Format format) {
    checkState(this.format == null);
    validateFormat(format);
    this.format = format;
  }

  public void writeSampleData(ByteBuffer byteBuffer) throws IOException {
    if (!headerAdded) {
      writeWavHeader();
    }
    dataSize += muxerOutput.write(byteBuffer);
  }

  public void close() throws IOException {
    // If no sample is written, skip writing header.
    if (headerAdded) {
      // If the data size is an odd number of bytes, a pad byte with value zero is written after
      // data.
      if (dataSize % 2 == 1) {
        muxerOutput.write(ByteBuffer.wrap(new byte[1]));
      }
      // Rewrite the header with the correct data size.
      muxerOutput.setPosition(0);
      writeWavHeader();
    }
    muxerOutput.close();
  }

  private static void validateFormat(Format format) {
    checkArgument(Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW));
    checkArgument(format.sampleRate > 0);
    checkArgument(format.channelCount > 0);
    checkArgument(format.pcmEncoding != Format.NO_VALUE);
    int audioFormatType = getTypeForPcmEncoding(format.pcmEncoding);
    // TODO: b/474575207 - Add support for float PCM.
    checkArgument(
        audioFormatType == TYPE_PCM || audioFormatType == TYPE_FLOAT, "Only integer PCM supported");
  }

  private void writeWavHeader() throws IOException {
    checkNotNull(format);
    int audioFormatType = getTypeForPcmEncoding(format.pcmEncoding);
    boolean isFloatPcm = audioFormatType == TYPE_FLOAT;
    int fmtChunkSize = isFloatPcm ? FMT_CHUNK_SIZE_FLOAT_PCM : FMT_CHUNK_SIZE_INT_PCM;
    int factChunkSize = isFloatPcm ? FACT_CHUNK_SIZE_FLOAT_PCM : 0;
    // RIFF/WAVE header + fmt chunk + fact chunk + data chunk
    int headerSize = 12 + (8 + fmtChunkSize) + factChunkSize + 8;
    ByteBuffer header = ByteBuffer.allocate(headerSize);
    header.order(ByteOrder.LITTLE_ENDIAN);

    // The RIFF/WAVE header
    header.putInt(Integer.reverseBytes(RIFF_FOURCC));
    // If the data size is an odd number of bytes, a pad byte with value zero is written after data.
    int extraPadding = (int) (dataSize % 2);
    // Subtract 8 for ChunkId and ChunkSize bytes
    long totalFileSize = headerSize - 8 + dataSize + extraPadding;
    checkState(totalFileSize <= UNSIGNED_INT_MAX_VALUE);
    header.putInt((int) totalFileSize);
    header.putInt(Integer.reverseBytes(WAVE_FOURCC));

    // The "fmt " chunk
    header.putInt(Integer.reverseBytes(FMT_FOURCC));
    header.putInt(fmtChunkSize);
    header.putShort((short) audioFormatType);
    header.putShort((short) format.channelCount);
    header.putInt(format.sampleRate);
    int bytesPerFrame = getPcmFrameSize(format.pcmEncoding, format.channelCount);
    int byteRate = format.sampleRate * bytesPerFrame;
    header.putInt(byteRate);
    header.putShort((short) bytesPerFrame); // Block Align
    header.putShort((short) (getByteDepth(format.pcmEncoding) * 8)); // Bits per sample
    if (isFloatPcm) {
      header.putShort((short) 0); // cbSize
    }
    if (isFloatPcm) {
      // The "fact" chunk
      header.putInt(Integer.reverseBytes(FACT_FOURCC));
      header.putInt(4); // fact chunk size
      int numFrames = (int) (dataSize / bytesPerFrame);
      header.putInt(numFrames);
    }

    // The "data" chunk
    header.putInt(Integer.reverseBytes(DATA_FOURCC));
    header.putInt(toUnsignedInt(dataSize)); // Chunk size
    header.flip();
    muxerOutput.write(header);
    headerAdded = true;
  }
}
