/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.test.core.app.ApplicationProvider;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An {@link AudioSink} forwarding to {@link DefaultAudioSink} that captures configuration,
 * discontinuity and buffer events.
 */
@UnstableApi
public class CapturingAudioSink extends ForwardingAudioSink implements Dumper.Dumpable {

  private final List<Dumper.Dumpable> interceptedData;
  private final AudioSink audioSink;

  private int bufferCount;
  private long lastPresentationTimeUs;
  @Nullable private ByteBuffer currentBuffer;
  private @MonotonicNonNull Format format;

  /** Creates the capturing audio sink. */
  public static CapturingAudioSink create() {
    InterceptingBufferSink interceptingBufferSink = new InterceptingBufferSink();
    CapturingAudioSink capturingAudioSink =
        new CapturingAudioSink(
            new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
                .setAudioProcessorChain(
                    new DefaultAudioSink.DefaultAudioProcessorChain(
                        new TeeAudioProcessor(interceptingBufferSink)))
                .build());
    interceptingBufferSink.setCapturingAudioSink(capturingAudioSink);
    return capturingAudioSink;
  }

  protected CapturingAudioSink(AudioSink sink) {
    super(sink);
    audioSink = sink;
    interceptedData = new ArrayList<>();
  }

  /** Returns the wrapped {@link AudioSink}. */
  protected final AudioSink getDelegateAudioSink() {
    return audioSink;
  }

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    this.format = inputFormat;
    interceptedData.add(new DumpableConfiguration(inputFormat, outputChannels));
    super.configure(inputFormat, specifiedBufferSize, outputChannels);
  }

  @Override
  public void handleDiscontinuity() {
    interceptedData.add(new DumpableDiscontinuity());
    super.handleDiscontinuity();
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public boolean handleBuffer(
      ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
      throws InitializationException, WriteException {
    lastPresentationTimeUs = presentationTimeUs;
    // The handleBuffer is called repeatedly with the same buffer until it's been fully consumed by
    // the sink. We only want to dump each buffer once.
    if (buffer != currentBuffer && !buffer.hasRemaining()) {
      // Empty buffers are not processed any further and need to be intercepted here.
      // TODO: b/174737370 - Output audio bytes in Robolectric to avoid this situation.
      interceptedData.add(
          new DumpableBuffer(bufferCount++, checkNotNull(format), buffer, lastPresentationTimeUs));
      currentBuffer = buffer;
    }
    boolean fullyBuffered = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    if (fullyBuffered) {
      currentBuffer = null;
    }
    return fullyBuffered;
  }

  @Override
  public void dump(Dumper dumper) {
    if (interceptedData.isEmpty()) {
      return;
    }
    dumper.startBlock("AudioSink").add("buffer count", bufferCount);
    for (int i = 0; i < interceptedData.size(); i++) {
      interceptedData.get(i).dump(dumper);
    }
    dumper.endBlock();
  }

  public static final class InterceptingBufferSink implements TeeAudioProcessor.AudioBufferSink {

    private @MonotonicNonNull CapturingAudioSink capturingAudioSink;
    private @MonotonicNonNull Format format;

    public void setCapturingAudioSink(CapturingAudioSink capturingAudioSink) {
      this.capturingAudioSink = capturingAudioSink;
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
      this.format = Util.getPcmFormat(encoding, channelCount, sampleRateHz);
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      checkNotNull(capturingAudioSink)
          .interceptedData
          .add(
              new DumpableBuffer(
                  capturingAudioSink.bufferCount++,
                  checkNotNull(format),
                  buffer,
                  capturingAudioSink.lastPresentationTimeUs));
    }
  }

  private static final class DumpableConfiguration implements Dumper.Dumpable {

    private final Format inputFormat;
    @Nullable private final int[] outputChannels;

    public DumpableConfiguration(Format inputFormat, @Nullable int[] outputChannels) {
      this.inputFormat = inputFormat;
      this.outputChannels = outputChannels;
    }

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("config");
      if (inputFormat.sampleMimeType != null
          && !inputFormat.sampleMimeType.equals(MimeTypes.AUDIO_RAW)) {
        dumper.add("mimeType", inputFormat.sampleMimeType);
      }

      dumper
          .addIfNonDefault("pcmEncoding", inputFormat.pcmEncoding, Format.NO_VALUE)
          .addIfNonDefault("channelCount", inputFormat.channelCount, Format.NO_VALUE)
          .addIfNonDefault("sampleRate", inputFormat.sampleRate, Format.NO_VALUE);
      if (outputChannels != null) {
        dumper.add("outputChannels", Arrays.toString(outputChannels));
      }
      dumper.endBlock();
    }
  }

  private static final class DumpableBuffer implements Dumper.Dumpable {

    private final int bufferCounter;
    private final long presentationTimeUs;

    /** Exactly one of this and {@link #perChannelHashCodes} is non-null. */
    @Nullable private final String dataDumpValue;

    /** Exactly one of this and {@link #dataDumpValue} is non-null. */
    @Nullable private final int[] perChannelHashCodes;

    public DumpableBuffer(
        int bufferCounter, Format format, ByteBuffer buffer, long presentationTimeUs) {
      this.bufferCounter = bufferCounter;
      this.presentationTimeUs = presentationTimeUs;
      if (buffer.remaining() == 0) {
        this.dataDumpValue = "empty";
        this.perChannelHashCodes = null;
        return;
      }
      // Store the position so we can reset it later.
      int initialPosition = buffer.position();
      if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW)
          && format.pcmEncoding != C.ENCODING_INVALID
          && format.pcmEncoding != Format.NO_VALUE) {
        int byteDepth = Util.getByteDepth(format.pcmEncoding);
        int frameSize = format.channelCount * byteDepth;
        int remainingBytes = buffer.remaining();
        checkState(
            remainingBytes % frameSize == 0,
            "buffer.remaining()=%s, channelCount=%s, pcmEncoding=%s",
            remainingBytes,
            format.channelCount,
            format.pcmEncoding);
        byte[][] perChannelData =
            new byte[format.channelCount][remainingBytes / format.channelCount];
        for (int i = 0; byteDepth <= buffer.remaining(); i += byteDepth) {
          int channel = (i / byteDepth) % format.channelCount;
          int destPos = (i / frameSize) * byteDepth;
          buffer.get(perChannelData[channel], destPos, byteDepth);
        }
        if (isAllZeroes(perChannelData)) {
          this.dataDumpValue = (perChannelData.length * perChannelData[0].length) + " zeroes";
          this.perChannelHashCodes = null;
        } else {
          this.perChannelHashCodes = new int[format.channelCount];
          for (int i = 0; i < format.channelCount; i++) {
            this.perChannelHashCodes[i] = Arrays.hashCode(perChannelData[i]);
          }
          this.dataDumpValue = null;
        }
      } else {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        this.dataDumpValue =
            isAllZeroes(data) ? data.length + " zeroes" : String.valueOf(Arrays.hashCode(data));
        this.perChannelHashCodes = null;
      }
      buffer.position(initialPosition);
    }

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("buffer #" + bufferCounter).addTime("time", presentationTimeUs);
      if (perChannelHashCodes != null) {
        for (int i = 0; i < perChannelHashCodes.length; i++) {
          dumper.add("channel[" + i + "]", perChannelHashCodes[i]);
        }
      } else {
        dumper.add("data", checkNotNull(dataDumpValue));
      }
      dumper.endBlock();
    }

    private static boolean isAllZeroes(byte[][] data) {
      for (byte[] d : data) {
        if (!isAllZeroes(d)) {
          return false;
        }
      }
      return true;
    }

    private static boolean isAllZeroes(byte[] data) {
      for (byte b : data) {
        if (b != 0) {
          return false;
        }
      }
      return true;
    }
  }

  private static final class DumpableDiscontinuity implements Dumper.Dumpable {

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("discontinuity").endBlock();
    }
  }
}
