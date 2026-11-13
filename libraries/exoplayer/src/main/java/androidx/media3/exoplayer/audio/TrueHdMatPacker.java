/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Packs Dolby TrueHD access units into IEC 61937 MAT bursts.
 *
 * <p>The design follows Kodi's {@code CPackerMAT}, which in turn derives its seamless-branch
 * handling from the TrueHD MAT packer in LAV Filters.
 *
 * <p>A MAT frame is a sequence of padded TrueHD access units. The padded size of each access unit
 * is derived from the input timing in its header, so that on average every access unit occupies
 * 2560 bytes and 24 access units fit in one MAT frame. Individual access units can be larger than
 * that during bitrate spikes, in which case they are preceded by smaller ones, so the padding has
 * to be computed dynamically rather than padding to a constant size.
 *
 * <p>Each output buffer is a complete {@link #MAT_FRAME_SIZE} byte IEC 61937 burst, including the
 * burst preamble, in native byte order 16-bit words as expected by an {@link
 * android.media.AudioFormat#ENCODING_IEC61937} {@link android.media.AudioTrack}.
 *
 * <p>Every MAT frame has the same duration (24 access units), so output frames are timestamped by
 * counting frames from the timestamp of the first access unit packed after a reset.
 */
/* package */ final class TrueHdMatPacker {

  /** A packed MAT frame. */
  public static final class MatFrame {

    /** The IEC 61937 burst, {@link #MAT_FRAME_SIZE} bytes long. */
    public final ByteBuffer data;

    /** The presentation time of the frame, in microseconds, or {@link C#TIME_UNSET} if unknown. */
    public final long timeUs;

    private MatFrame(ByteBuffer data, long timeUs) {
      this.data = data;
      this.timeUs = timeUs;
    }
  }

  /** The size of an IEC 61937 MAT burst, in bytes. */
  public static final int MAT_FRAME_SIZE = 61440;

  private static final String TAG = "TrueHdMatPacker";

  // 24 access units of 40 samples at the base rate (48 kHz or 44.1 kHz).
  private static final int MAT_FRAME_BASE_RATE_SAMPLES = 960;

  private static final int FORMAT_MAJOR_SYNC = 0xF8726FBA;

  private static final int BURST_HEADER_SIZE = 8;
  // Space reserved at the end of the frame for the MAT end code.
  private static final int MAT_BUFFER_LIMIT = MAT_FRAME_SIZE - 24;
  // The middle point, plus the IEC 61937 burst header in front.
  private static final int MAT_POS_MIDDLE = 30708 + BURST_HEADER_SIZE;

  private static final short IEC61937_SYNC_WORD_1 = (short) 0xF872;
  private static final short IEC61937_SYNC_WORD_2 = (short) 0x4E1F;
  private static final short IEC61937_DATA_TYPE_TRUEHD = 0x16;
  // Burst payload length in bytes.
  private static final short IEC61937_TRUEHD_LENGTH = (short) 61424;

  // Magic MAT format values, meaning is unknown at this point.
  private static final byte[] MAT_START_CODE =
      toBytes(
          0x07, 0x9E, 0x00, 0x03, 0x84, 0x01, 0x01, 0x01, 0x80, 0x00, 0x56, 0xA5, 0x3B, 0xF4, 0x81,
          0x83, 0x49, 0x80, 0x77, 0xE0);
  private static final byte[] MAT_MIDDLE_CODE =
      toBytes(0xC3, 0xC1, 0x42, 0x49, 0x3B, 0xFA, 0x82, 0x83, 0x49, 0x80, 0x77, 0xE0);
  private static final byte[] MAT_END_CODE =
      toBytes(
          0xC3, 0xC2, 0xC0, 0xC4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x97,
          0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);

  private final byte[] buffer;
  private final ArrayDeque<MatFrame> outputQueue;
  private int bufferCount;

  // Presentation time of the first MAT frame since the last reset, and the number of MAT frames
  // output since then.
  private long anchorTimeUs;
  private long outputFrameCount;

  // Differentiates the first header.
  private boolean init;
  // audio_sampling_frequency: 0 -> 48 kHz, 1 -> 96 kHz, 2 -> 192 kHz, 8 -> 44.1 kHz,
  // 9 -> 88.2 kHz, 10 -> 176.4 kHz.
  private int ratebits;
  // Output timing (uint16) parsed from the major sync restart header, or inferred by advancing a
  // counter. Jumps at a seamless branch point.
  private int outputTiming;
  private boolean outputTimingValid;
  // Input timing (uint16) of the previous access unit, used to calculate padding bytes.
  private int prevFrameTime;
  private boolean prevFrameTimeValid;
  // Size in bytes of the current and previous access unit within the MAT frame.
  private int matFrameSize;
  private int prevMatFrameSize;
  // Padding bytes pending to be written.
  private int padding;
  // Frame time to output time offset, used to size the padding carried forward on the next branch
  // point.
  private int outputTimeOffset;

  public TrueHdMatPacker() {
    buffer = new byte[MAT_FRAME_SIZE];
    outputQueue = new ArrayDeque<>();
    anchorTimeUs = C.TIME_UNSET;
  }

  /**
   * Packs a single TrueHD access unit.
   *
   * @param data The array holding the access unit.
   * @param offset The offset of the access unit in {@code data}.
   * @param size The size of the access unit, in bytes.
   * @param timeUs The presentation time of the access unit, in microseconds, or {@link
   *     C#TIME_UNSET} if unknown.
   * @return Whether there are complete MAT frames available from {@link #pollOutputFrame()}.
   */
  public boolean packTrueHd(byte[] data, int offset, int size, long timeUs) {
    // Discard packets that are too small to be valid.
    if (size < 10) {
      return false;
    }

    boolean isMajorSync = readInt(data, offset + 4) == FORMAT_MAJOR_SYNC;
    MajorSyncInfo info = MajorSyncInfo.INVALID;
    if (isMajorSync) {
      info = parseMajorSyncHeaders(data, offset, size);
      // Fall back to reading the ratebits directly if the full header parse fails, so that the
      // frame is still packed (without seamless branch detection) instead of being dropped.
      ratebits = info.valid ? info.ratebits : (data[offset + 8] >> 4) & 0x0F;
    } else if (!prevFrameTimeValid) {
      // Only start streaming on a major sync frame.
      return false;
    }

    int frameTime = readUnsignedShort(data, offset + 2);
    int frameSamples = 40 << (ratebits & 7);
    int bytesPerSample = 64 >> (ratebits & 7);
    int spaceSize = 0;

    // Advance the output timing counter. If the parsed output timing doesn't match, this is a
    // seamless branch point, so carry the padding forward instead of dropping frames.
    outputTiming = (outputTiming + frameSamples) & 0xFFFF;
    if (info.outputTimingPresent) {
      if (outputTimingValid && info.outputTiming != outputTiming) {
        Log.d(
            TAG,
            "Stream discontinuity (seamless branch), expected outputTiming="
                + outputTiming
                + ", actual="
                + info.outputTiming);
        // Reset frame timing and use the standard padding of one MAT slot (2560 bytes at every
        // rate). prevMatFrameSize is kept for the padding calculation.
        prevFrameTimeValid = false;
        spaceSize = frameSamples * bytesPerSample;

        // The output timing is always one frame ahead for buffering reasons, so deduct one frame.
        int currentFrameOutputOffset =
            getFrameOutputOffset(info.outputTiming, frameSamples, frameTime);
        // The previous offset should never be smaller than the incoming offset, or the reserved
        // space would be lacking.
        if (outputTimeOffset >= currentFrameOutputOffset) {
          padding += (outputTimeOffset - currentFrameOutputOffset) * bytesPerSample;
        }
        Log.d(
            TAG,
            "Carrying forward "
                + padding
                + " padding (offset "
                + outputTimeOffset
                + " - "
                + currentFrameOutputOffset
                + ")");
      }
      outputTiming = info.outputTiming;
      outputTimingValid = true;
    }

    // Compute the final padded size of the previous frame, if any.
    if (prevFrameTimeValid) {
      spaceSize = ((frameTime - prevFrameTime) & 0xFFFF) * bytesPerSample;
    }
    // If the space size is inconsistent, align the actual frame size instead.
    if (spaceSize < prevMatFrameSize) {
      spaceSize = (prevMatFrameSize + bytesPerSample - 1) & ~(bytesPerSample - 1);
    }
    padding += spaceSize - prevMatFrameSize;

    // An unbounded positive padding (e.g. a frameTime jump on a seek or a corrupt stream) would
    // emit a huge number of MAT frames, so re-initialize instead.
    if (padding > MAT_FRAME_SIZE * 5) {
      Log.i(TAG, "Discontinuity detected, re-initializing MAT packer state");
      resetState();
      init = true;
      return false;
    }

    // Record the offset of frame time to output time, used to size the padding carried forward on
    // the next discontinuity.
    if (outputTimingValid) {
      outputTimeOffset = getFrameOutputOffset(outputTiming, frameSamples, frameTime);
    }

    prevFrameTime = frameTime;
    prevFrameTimeValid = true;

    // The first access unit after a reset starts the first MAT frame, right after its header.
    if (bufferCount == 0 && outputFrameCount == 0) {
      anchorTimeUs = timeUs;
    }

    if (bufferCount == 0) {
      writeHeader();
      // The initial header doesn't count towards the frame size.
      if (!init) {
        init = true;
        matFrameSize = 0;
      }
    }

    // Write the padding of the previous frame, if any.
    while (padding > 0) {
      writePadding();
      if (bufferCount == MAT_FRAME_SIZE) {
        // The buffer is full, so submit it and set up a new one.
        flushPacket();
        writeHeader();
      }
    }

    // Write the actual audio data.
    int remaining = fillDataBuffer(data, offset, size, /* isPadding= */ false);
    if (remaining > 0 || bufferCount == MAT_FRAME_SIZE) {
      flushPacket();
      if (remaining > 0) {
        writeHeader();
        fillDataBuffer(data, offset + size - remaining, remaining, /* isPadding= */ false);
      }
    }

    // Store the size of the current frame, so that padding can be added later.
    prevMatFrameSize = matFrameSize;
    matFrameSize = 0;

    return !outputQueue.isEmpty();
  }

  /**
   * Completes the MAT frame being assembled, if any, by padding it to its full size, and queues it
   * for output. The packer is reset afterwards, so that the next access unit must be a major sync.
   *
   * <p>Used at the end of the stream, so that the final partial MAT frame is played out.
   */
  public void finish() {
    if (bufferCount > 0) {
      while (bufferCount < MAT_FRAME_SIZE) {
        fillDataBuffer(/* data= */ null, /* offset= */ 0, MAT_FRAME_SIZE, /* isPadding= */ true);
      }
      flushPacket();
    }
    resetState();
  }

  /** Returns the next complete MAT frame, or null if there is none. */
  @Nullable
  public MatFrame pollOutputFrame() {
    return outputQueue.poll();
  }

  /** Discards all pending output and resets the packer to its initial state. */
  public void reset() {
    outputQueue.clear();
    resetState();
  }

  private void resetState() {
    bufferCount = 0;
    anchorTimeUs = C.TIME_UNSET;
    outputFrameCount = 0;
    init = false;
    ratebits = 0;
    outputTiming = 0;
    outputTimingValid = false;
    prevFrameTime = 0;
    prevFrameTimeValid = false;
    matFrameSize = 0;
    prevMatFrameSize = 0;
    padding = 0;
    outputTimeOffset = 0;
  }

  private void writeHeader() {
    Arrays.fill(buffer, (byte) 0);

    // Write the MAT start code. The IEC 61937 header is written when flushing, so only skip it.
    System.arraycopy(MAT_START_CODE, 0, buffer, BURST_HEADER_SIZE, MAT_START_CODE.length);
    int size = BURST_HEADER_SIZE + MAT_START_CODE.length;
    bufferCount = size;

    // Unless the start code falls into the padding, it's part of the current MAT frame. Audio
    // frames are not always aligned with MAT frames, so there may already be a partial frame.
    matFrameSize += size;

    // The MAT metadata counts as padding, so it reduces any padding still to be written.
    if (padding > 0) {
      if (padding > size) {
        padding -= size;
        matFrameSize = 0;
      } else {
        matFrameSize = size - padding;
        padding = 0;
      }
    }
  }

  private void writePadding() {
    if (padding == 0) {
      return;
    }
    // The buffer is zeroed already, so padding only needs to be skipped.
    int remaining = fillDataBuffer(/* data= */ null, /* offset= */ 0, padding, /* isPadding= */ true);
    if (remaining >= 0) {
      // Not all padding could be written, write the rest later.
      padding = remaining;
      matFrameSize = 0;
    } else {
      // More padding than requested was written, because a MAT middle or end code was written.
      padding = 0;
      matFrameSize = -remaining;
    }
  }

  private void appendData(@Nullable byte[] data, int offset, int size, boolean isPadding) {
    if (!isPadding && data != null) {
      System.arraycopy(data, offset, buffer, bufferCount, size);
    }
    matFrameSize += size;
    bufferCount += size;
  }

  /**
   * Writes data or padding to the buffer, inserting the MAT middle and end codes as needed.
   *
   * @return The number of bytes that didn't fit in the buffer. When writing padding, this may be
   *     negative if a MAT code was written in place of more padding than was requested.
   */
  private int fillDataBuffer(@Nullable byte[] data, int offset, int size, boolean isPadding) {
    if (bufferCount >= MAT_BUFFER_LIMIT) {
      return size;
    }

    int remaining = size;

    // The MAT middle code always needs to be at the exact same position, so any audio data is
    // split around it. When writing padding, the code counts as padding.
    if (bufferCount <= MAT_POS_MIDDLE && bufferCount + size > MAT_POS_MIDDLE) {
      int bytesBefore = MAT_POS_MIDDLE - bufferCount;
      appendData(data, offset, bytesBefore, isPadding);
      remaining -= bytesBefore;

      appendData(MAT_MIDDLE_CODE, 0, MAT_MIDDLE_CODE.length, /* isPadding= */ false);
      if (isPadding) {
        remaining -= MAT_MIDDLE_CODE.length;
      }

      if (remaining > 0) {
        remaining = fillDataBuffer(data, offset + bytesBefore, remaining, isPadding);
      }
      return remaining;
    }

    // Not enough room to write all the data, so write as much as possible and add the MAT end
    // code.
    if (bufferCount + size >= MAT_BUFFER_LIMIT) {
      int bytesBefore = MAT_BUFFER_LIMIT - bufferCount;
      appendData(data, offset, bytesBefore, isPadding);
      remaining -= bytesBefore;

      appendData(MAT_END_CODE, 0, MAT_END_CODE.length, /* isPadding= */ false);
      if (isPadding) {
        remaining -= MAT_END_CODE.length;
      }
      // Any remaining data is written by later calls.
      return remaining;
    }

    appendData(data, offset, size, isPadding);
    return 0;
  }

  private void flushPacket() {
    if (bufferCount == 0) {
      return;
    }
    ByteBuffer packet = ByteBuffer.allocateDirect(MAT_FRAME_SIZE).order(ByteOrder.nativeOrder());
    packet.putShort(IEC61937_SYNC_WORD_1);
    packet.putShort(IEC61937_SYNC_WORD_2);
    packet.putShort(IEC61937_DATA_TYPE_TRUEHD);
    packet.putShort(IEC61937_TRUEHD_LENGTH);
    // The MAT payload is big endian, but IEC 61937 data is passed as native 16-bit words.
    for (int i = BURST_HEADER_SIZE; i < MAT_FRAME_SIZE; i += 2) {
      packet.putShort((short) (((buffer[i] & 0xFF) << 8) | (buffer[i + 1] & 0xFF)));
    }
    packet.flip();
    long timeUs = C.TIME_UNSET;
    if (anchorTimeUs != C.TIME_UNSET) {
      int baseSampleRate = (ratebits & 8) != 0 ? 44100 : 48000;
      timeUs =
          anchorTimeUs
              + Util.sampleCountToDurationUs(
                  outputFrameCount * MAT_FRAME_BASE_RATE_SAMPLES, baseSampleRate);
    }
    outputQueue.add(new MatFrame(packet, timeUs));
    outputFrameCount++;
    bufferCount = 0;
  }

  private static int getFrameOutputOffset(int outputTiming, int frameSamples, int frameTime) {
    int prevOutput = (outputTiming - frameSamples) & 0xFFFF;
    if (prevOutput < frameTime) {
      // Wrap around, the output time is always ahead of the frame time.
      prevOutput += 0x10000;
    }
    return prevOutput - frameTime;
  }

  /**
   * Parses a TrueHD major sync for the ratebits and, when a restart header is present, the output
   * timing used for seamless branch detection.
   */
  private static MajorSyncInfo parseMajorSyncHeaders(byte[] data, int offset, int size) {
    if (size < 32) {
      return MajorSyncInfo.INVALID;
    }

    int majorSyncSize = 28;
    if ((data[offset + 29] & 1) != 0) {
      // A restart header exists.
      int extensionSize = (data[offset + 30] >> 4) & 0x0F;
      majorSyncSize += 2 + extensionSize * 2;
    }
    if (majorSyncSize > size) {
      return MajorSyncInfo.INVALID;
    }

    ParsableBitArray bits = new ParsableBitArray(data, offset + size);
    bits.setPosition((offset + 4) * 8);
    skipBits(bits, 32); // format_sync
    int ratebits = readBits(bits, 4);

    // 6ch_multichannel_type (1), 8ch_multichannel_type (1), reserved (2),
    // 2ch_presentation_channel_modifier (2), 6ch_presentation_channel_modifier (2),
    // 6ch_presentation_channel_assignment (5), 8ch_presentation_channel_modifier (2),
    // 8ch_presentation_channel_assignment (13), signature (16), flags (16), reserved (16),
    // variable_rate (1), peak_data_rate (15).
    skipBits(bits, 1 + 1 + 2 + 2 + 2 + 5 + 2 + 13 + 16 + 16 + 16 + 1 + 15);
    int substreamCount = readBits(bits, 4);
    skipBits(bits, 4 + (majorSyncSize - 17) * 8);

    // Substream directory.
    for (int i = 0; i < substreamCount; i++) {
      boolean extraSubstreamWord = readBits(bits, 1) != 0;
      // restart_nonexistent (1), crc_present (1), reserved (1), substream_end_ptr (12).
      skipBits(bits, 15);
      if (extraSubstreamWord) {
        skipBits(bits, 16); // drc_gain_update, drc_time_update, reserved
      }
    }

    // Only the first substream segment is inspected.
    int outputTiming = 0;
    boolean outputTimingPresent = false;
    if (substreamCount > 0 && readBits(bits, 1) != 0) { // block_header_exists
      if (readBits(bits, 1) != 0) { // restart_header_exists
        skipBits(bits, 14); // restart_sync_word
        if (bits.bitsLeft() >= 16) {
          outputTiming = bits.readBits(16);
          outputTimingPresent = true;
        }
      }
    }
    return new MajorSyncInfo(ratebits, outputTiming, outputTimingPresent);
  }

  private static int readBits(ParsableBitArray bits, int numBits) {
    return bits.bitsLeft() >= numBits ? bits.readBits(numBits) : 0;
  }

  private static void skipBits(ParsableBitArray bits, int numBits) {
    bits.setPosition(bits.getPosition() + Math.min(numBits, bits.bitsLeft()));
  }

  private static int readInt(byte[] data, int offset) {
    return (data[offset] & 0xFF) << 24
        | (data[offset + 1] & 0xFF) << 16
        | (data[offset + 2] & 0xFF) << 8
        | (data[offset + 3] & 0xFF);
  }

  private static int readUnsignedShort(byte[] data, int offset) {
    return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
  }

  private static byte[] toBytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }

  private static final class MajorSyncInfo {

    public static final MajorSyncInfo INVALID = new MajorSyncInfo();

    public final boolean valid;
    public final int ratebits;
    public final int outputTiming;
    public final boolean outputTimingPresent;

    private MajorSyncInfo() {
      valid = false;
      ratebits = 0;
      outputTiming = 0;
      outputTimingPresent = false;
    }

    public MajorSyncInfo(int ratebits, int outputTiming, boolean outputTimingPresent) {
      valid = true;
      this.ratebits = ratebits;
      this.outputTiming = outputTiming;
      this.outputTimingPresent = outputTimingPresent;
    }
  }
}
