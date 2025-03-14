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

import static androidx.media3.common.util.Assertions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.test.core.app.ApplicationProvider;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An {@link AudioSink} forwarding to {@link DefaultAudioSink} that captures configuration,
 * discontinuity and buffer events.
 */
@UnstableApi
public final class CapturingAudioSink extends ForwardingAudioSink implements Dumper.Dumpable {

  private final List<Dumper.Dumpable> interceptedData;

  private int bufferCount;
  private long lastPresentationTimeUs;
  @Nullable private ByteBuffer currentBuffer;

  /** Creates the capturing audio sink. */
  public static CapturingAudioSink create() {
    InterceptingBufferSink interceptingBufferSink = new InterceptingBufferSink();
    return new CapturingAudioSink(
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
            .setAudioProcessorChain(
                new DefaultAudioSink.DefaultAudioProcessorChain(
                    new TeeAudioProcessor(interceptingBufferSink)))
            .build(),
        interceptingBufferSink);
  }

  private CapturingAudioSink(AudioSink sink, InterceptingBufferSink interceptingBufferSink) {
    super(sink);
    interceptedData = new ArrayList<>();
    interceptingBufferSink.setCapturingAudioSink(this);
  }

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    interceptedData.add(
        new DumpableConfiguration(
            inputFormat.pcmEncoding,
            inputFormat.channelCount,
            inputFormat.sampleRate,
            outputChannels));
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
      interceptedData.add(new DumpableBuffer(bufferCount++, buffer, lastPresentationTimeUs));
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

  private static final class InterceptingBufferSink implements TeeAudioProcessor.AudioBufferSink {

    private @MonotonicNonNull CapturingAudioSink capturingAudioSink;

    public void setCapturingAudioSink(CapturingAudioSink capturingAudioSink) {
      this.capturingAudioSink = capturingAudioSink;
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {}

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      checkNotNull(capturingAudioSink)
          .interceptedData
          .add(
              new DumpableBuffer(
                  capturingAudioSink.bufferCount++,
                  buffer,
                  capturingAudioSink.lastPresentationTimeUs));
    }
  }

  private static final class DumpableConfiguration implements Dumper.Dumpable {

    private final @C.PcmEncoding int inputPcmEncoding;
    private final int inputChannelCount;
    private final int inputSampleRate;
    @Nullable private final int[] outputChannels;

    public DumpableConfiguration(
        @C.PcmEncoding int inputPcmEncoding,
        int inputChannelCount,
        int inputSampleRate,
        @Nullable int[] outputChannels) {
      this.inputPcmEncoding = inputPcmEncoding;
      this.inputChannelCount = inputChannelCount;
      this.inputSampleRate = inputSampleRate;
      this.outputChannels = outputChannels;
    }

    @Override
    public void dump(Dumper dumper) {
      dumper
          .startBlock("config")
          .add("pcmEncoding", inputPcmEncoding)
          .add("channelCount", inputChannelCount)
          .add("sampleRate", inputSampleRate);
      if (outputChannels != null) {
        dumper.add("outputChannels", Arrays.toString(outputChannels));
      }
      dumper.endBlock();
    }
  }

  private static final class DumpableBuffer implements Dumper.Dumpable {

    private final int bufferCounter;
    private final long presentationTimeUs;
    private final String dataDumpValue;

    public DumpableBuffer(int bufferCounter, ByteBuffer buffer, long presentationTimeUs) {
      this.bufferCounter = bufferCounter;
      this.presentationTimeUs = presentationTimeUs;
      int initialPosition = buffer.position();
      if (buffer.remaining() == 0) {
        this.dataDumpValue = "empty";
      } else {
        // Compute a hash of the buffer data without changing its position.
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        buffer.position(initialPosition);
        this.dataDumpValue =
            isAllZeroes(data) ? data.length + " zeroes" : String.valueOf(Arrays.hashCode(data));
      }
    }

    @Override
    public void dump(Dumper dumper) {
      dumper
          .startBlock("buffer #" + bufferCounter)
          .addTime("time", presentationTimeUs)
          .add("data", dataDumpValue)
          .endBlock();
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
