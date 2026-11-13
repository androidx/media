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

import static com.google.common.base.Preconditions.checkArgument;

import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput.CapabilityChangeListener;
import java.math.RoundingMode;
import java.nio.ByteBuffer;

/**
 * An {@link AudioOutput} that passes Dolby TrueHD through as IEC 61937 MAT bursts, using an {@link
 * AudioTrack} with {@link AudioFormat#ENCODING_IEC61937}.
 *
 * <p>TrueHD access units written to this output are packed into MAT frames by a {@link
 * TrueHdMatPacker}. Each MAT frame carries 24 access units (20 ms for 48 kHz based streams) and is
 * written to an 8 channel, 16-bit IEC 61937 track running at 192 kHz (or 176.4 kHz for 44.1 kHz
 * based streams).
 *
 * <p>The IEC 61937 track is driven through an {@link AudioTrackAudioOutput} configured as 8 channel
 * 16-bit PCM, so that position tracking works on the IEC 61937 frame count. Towards the caller,
 * this output reports the TrueHD sample rate, so that positions and frame counts match the encoded
 * frames written by the sink.
 *
 * <p>Tunneling is supported if the device accepts {@link
 * android.media.AudioAttributes#FLAG_HW_AV_SYNC} on an IEC 61937 track. MAT frames don't align
 * with input buffers, so each MAT frame is written with its own presentation time, derived by the
 * packer from the fixed MAT frame duration. Offload is not supported.
 *
 * <p>Requires API 31, as earlier versions only allow stereo IEC 61937 tracks.
 */
@UnstableApi
@RequiresApi(24)
public final class TrueHdMatAudioOutput extends ForwardingAudioOutput {

  private static final String TAG = "TrueHdMatAudioOutput";

  // Per the Android spec this has to be CHANNEL_OUT_STEREO, but the IEC61937 spec says
  // it has to be CHANNEL_OUT_7POINT1_SURROUND and the decoder hangs if it isn't.
  // https://developer.android.com/reference/android/media/AudioFormat#ENCODING_IEC61937
  private static final int IEC61937_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
  // 8 channels of 16-bit samples.
  private static final int IEC61937_FRAME_SIZE = 16;
  // Number of MAT frames the IEC 61937 track should be able to hold at least.
  private static final int MIN_BUFFERED_MAT_FRAMES = 4;

  private final AudioTrackAudioOutput iecOutput;
  private final TrueHdMatPacker packer;
  private final int trueHdSampleRate;
  private final int iecSampleRate;

  private byte[] accessUnitData;
  @Nullable private TrueHdMatPacker.MatFrame pendingMatFrame;
  private boolean inputBufferPacked;
  private boolean stopPending;

  /**
   * Creates an instance, including its IEC 61937 {@link AudioTrack}.
   *
   * @param trueHdConfig The output configuration for the TrueHD stream, with {@link
   *     OutputConfig#encoding} set to {@link C#ENCODING_DOLBY_TRUEHD} and {@link
   *     OutputConfig#sampleRate} set to the TrueHD sample rate.
   * @param capabilityChangeListener An optional {@link CapabilityChangeListener}.
   * @param clock The {@link Clock}.
   * @return The new instance.
   * @throws InitializationException If the {@link AudioTrack} could not be created, for example
   *     because the device doesn't support tunneling on IEC 61937 tracks.
   */
  public static TrueHdMatAudioOutput create(
      OutputConfig trueHdConfig,
      @Nullable CapabilityChangeListener capabilityChangeListener,
      Clock clock)
      throws InitializationException {
    checkArgument(trueHdConfig.encoding == C.ENCODING_DOLBY_TRUEHD);
    int iecSampleRate = getIecSampleRate(trueHdConfig.sampleRate);
    int minBufferSize = TrueHdMatPacker.MAT_FRAME_SIZE * MIN_BUFFERED_MAT_FRAMES;
    int bufferSize =
        Util.ceilDivide(Math.max(trueHdConfig.bufferSize, minBufferSize), IEC61937_FRAME_SIZE)
            * IEC61937_FRAME_SIZE;

    AudioTrack audioTrack;
    try {
      audioTrack =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  trueHdConfig.isTunneling
                      ? getTunnelingAudioAttributes()
                      : trueHdConfig.audioAttributes.getPlatformAudioAttributes())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_IEC61937)
                      .setSampleRate(iecSampleRate)
                      .setChannelMask(IEC61937_CHANNEL_MASK)
                      .build())
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setBufferSizeInBytes(bufferSize)
              .setSessionId(trueHdConfig.audioSessionId)
              .build();
    } catch (UnsupportedOperationException | IllegalArgumentException e) {
      throw new InitializationException(e);
    }
    if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
      audioTrack.release();
      throw new InitializationException();
    }

    // The IEC 61937 stream is handled like 8 channel 16-bit PCM for writing and position tracking.
    OutputConfig iecConfig =
        trueHdConfig
            .buildUpon()
            .setEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(iecSampleRate)
            .setChannelMask(IEC61937_CHANNEL_MASK)
            .setBufferSize(bufferSize)
            .setIsOffload(false)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .build();
    AudioTrackAudioOutput iecOutput =
        new AudioTrackAudioOutput(
            audioTrack,
            iecConfig,
            capabilityChangeListener,
            /* maxPlaybackSpeed= */ 1f,
            clock);
    return new TrueHdMatAudioOutput(iecOutput, trueHdConfig.sampleRate);
  }

  /**
   * Creates an instance.
   *
   * @param iecOutput An {@link AudioTrackAudioOutput} wrapping an 8 channel {@link
   *     AudioFormat#ENCODING_IEC61937} {@link AudioTrack}, configured as 8 channel 16-bit PCM.
   * @param trueHdSampleRate The sample rate of the TrueHD stream.
   */
  public TrueHdMatAudioOutput(AudioTrackAudioOutput iecOutput, int trueHdSampleRate) {
    super(iecOutput);
    this.iecOutput = iecOutput;
    this.trueHdSampleRate = trueHdSampleRate;
    iecSampleRate = getIecSampleRate(trueHdSampleRate);
    packer = new TrueHdMatPacker();
    accessUnitData = new byte[0];
  }

  @Override
  public void play() {
    maybeCompletePendingStop();
    super.play();
  }

  @Override
  public boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
      throws WriteException {
    if (!inputBufferPacked) {
      boolean isTimeKnown =
          presentationTimeUs != C.TIME_UNSET && presentationTimeUs != C.TIME_END_OF_SOURCE;
      packAccessUnits(buffer, isTimeKnown ? presentationTimeUs : C.TIME_UNSET);
      inputBufferPacked = true;
    }
    if (!writePendingMatFrames()) {
      // The input stays unconsumed until all MAT frames packed from it have been written.
      return false;
    }
    buffer.position(buffer.limit());
    inputBufferPacked = false;
    return true;
  }

  @Override
  public void flush() {
    packer.reset();
    pendingMatFrame = null;
    inputBufferPacked = false;
    stopPending = false;
    super.flush();
  }

  @Override
  public void stop() {
    // Complete the partial MAT frame, so that all written audio is played out. The sink only
    // reports the end of the stream once the position has caught up with all written frames.
    packer.finish();
    stopPending = true;
    maybeCompletePendingStop();
  }

  @Override
  public void release() {
    packer.reset();
    pendingMatFrame = null;
    super.release();
  }

  @Override
  public int getSampleRate() {
    return trueHdSampleRate;
  }

  @Override
  public long getBufferSizeInFrames() {
    return Util.scaleLargeValue(
        super.getBufferSizeInFrames(), trueHdSampleRate, iecSampleRate, RoundingMode.DOWN);
  }

  @Override
  public long getPositionUs() {
    // The sink polls the position while waiting for the end of the stream, so use it to write out
    // what remains after stop().
    maybeCompletePendingStop();
    return super.getPositionUs();
  }

  @Override
  public boolean isStalled() {
    maybeCompletePendingStop();
    return super.isStalled();
  }

  @Override
  public boolean isOffloadedPlayback() {
    return false;
  }

  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    // Not supported.
  }

  @Override
  public void setOffloadEndOfStream() {
    // Not supported.
  }

  private void packAccessUnits(ByteBuffer buffer, long bufferTimeUs) {
    int position = buffer.position();
    int limit = buffer.limit();
    int accessUnitIndex = 0;
    while (limit - position >= 4) {
      // The first 16 bits are a 4 bit check nibble and the 12 bit access unit length in 16-bit
      // words.
      int accessUnitSize =
          (((buffer.get(position) & 0x0F) << 8) | (buffer.get(position + 1) & 0xFF)) * 2;
      if (accessUnitSize < 4 || accessUnitSize > limit - position) {
        Log.w(TAG, "Dropping invalid TrueHD access unit of size " + accessUnitSize);
        return;
      }
      if (accessUnitData.length < accessUnitSize) {
        accessUnitData = new byte[accessUnitSize];
      }
      ByteBuffer accessUnit = buffer.duplicate();
      accessUnit.position(position);
      accessUnit.get(accessUnitData, 0, accessUnitSize);
      long accessUnitTimeUs =
          bufferTimeUs == C.TIME_UNSET
              ? C.TIME_UNSET
              : bufferTimeUs + getAccessUnitsDurationUs(accessUnitIndex);
      packer.packTrueHd(accessUnitData, 0, accessUnitSize, accessUnitTimeUs);
      position += accessUnitSize;
      accessUnitIndex++;
    }
  }

  private long getAccessUnitsDurationUs(int accessUnitCount) {
    // Every access unit is 1/1200 s for 48 kHz based streams and 1/1102.5 s for 44.1 kHz based
    // ones, i.e. 40 samples at the base rate.
    int baseSampleRate = trueHdSampleRate % 44100 == 0 ? 44100 : 48000;
    return Util.sampleCountToDurationUs(40L * accessUnitCount, baseSampleRate);
  }

  /** Writes queued MAT frames to the IEC 61937 output, returning whether all were written. */
  private boolean writePendingMatFrames() throws WriteException {
    while (true) {
      if (pendingMatFrame == null) {
        pendingMatFrame = packer.pollOutputFrame();
        if (pendingMatFrame == null) {
          return true;
        }
      }
      // An unknown frame time only happens without input timestamps. When tunneling, fall back to
      // reusing the last written time.
      long frameTimeUs =
          pendingMatFrame.timeUs == C.TIME_UNSET ? C.TIME_END_OF_SOURCE : pendingMatFrame.timeUs;
      if (!iecOutput.write(pendingMatFrame.data, /* encodedAccessUnitCount= */ 1, frameTimeUs)) {
        return false;
      }
      pendingMatFrame = null;
    }
  }

  private void maybeCompletePendingStop() {
    if (!stopPending) {
      return;
    }
    try {
      if (!writePendingMatFrames()) {
        return;
      }
    } catch (WriteException e) {
      Log.w(TAG, "Failed to write final MAT frames", e);
      packer.reset();
      pendingMatFrame = null;
    }
    stopPending = false;
    super.stop();
  }

  private static android.media.AudioAttributes getTunnelingAudioAttributes() {
    return new android.media.AudioAttributes.Builder()
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .setFlags(android.media.AudioAttributes.FLAG_HW_AV_SYNC)
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .build();
  }

  private static int getIecSampleRate(int trueHdSampleRate) {
    // MAT frames are always 61440 bytes and carry 24 access units, so the IEC 61937 rate depends
    // on the base rate of the stream.
    return trueHdSampleRate % 44100 == 0 ? 176_400 : 192_000;
  }
}
