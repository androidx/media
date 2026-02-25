/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.util.Util.getPcmFormat;
import static androidx.media3.test.utils.TestUtil.buildTestData;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.test.utils.PassthroughAudioProcessor;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AudioGraph}. */
@RunWith(AndroidJUnit4.class)
public class AudioGraphTest {
  private static final EditedMediaItem FAKE_ITEM =
      new EditedMediaItem.Builder(MediaItem.EMPTY).build();
  private static final AudioFormat MONO_44100 =
      new AudioFormat(/* sampleRate= */ 44_100, /* channelCount= */ 1, C.ENCODING_PCM_16BIT);
  private static final AudioFormat MONO_48000 =
      new AudioFormat(/* sampleRate= */ 48_000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT);
  private static final AudioFormat STEREO_44100 =
      new AudioFormat(/* sampleRate= */ 44_100, /* channelCount= */ 2, C.ENCODING_PCM_16BIT);
  private static final AudioFormat STEREO_48000 =
      new AudioFormat(/* sampleRate= */ 48_000, /* channelCount= */ 2, C.ENCODING_PCM_16BIT);
  private static final AudioFormat SURROUND_50000 =
      new AudioFormat(/* sampleRate= */ 50_000, /* channelCount= */ 6, C.ENCODING_PCM_16BIT);

  @Test
  public void gap_outputsExpectedSilenceDuration() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    GraphInput input = audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));
    input.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 3_000_000,
        /* decodedFormat= */ null,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    // 3 second stream with 50_000 frames per second.
    assertThat(outputBytes).hasSize(3 * 50_000 * SURROUND_50000.bytesPerFrame);
    assertThat(ImmutableSet.copyOf(outputBytes)).containsExactly((byte) 0);
  }

  @Test
  public void gap_withSampleRateChange_outputsExpectedSilenceDuration() throws Exception {
    SonicAudioProcessor changeTo100000Hz = new SonicAudioProcessor();
    changeTo100000Hz.setOutputSampleRateHz(100_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(changeTo100000Hz));

    GraphInput input = audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));
    input.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 3_000_000,
        /* decodedFormat= */ null,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    // 3 second stream with 100_000 frames per second.
    assertThat(outputBytes).hasSize(3 * 100_000 * SURROUND_50000.bytesPerFrame);
    assertThat(ImmutableSet.copyOf(outputBytes)).containsExactly((byte) 0);
  }

  @Test
  public void onMediaItemChanged_withExactDurationAndNoInput_outputsSilence() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    AudioGraphInput input = audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));
    input.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 3_000_000,
        /* decodedFormat= */ getPcmFormat(SURROUND_50000),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(input.getOutput().hasRemaining()).isFalse();
    DecoderInputBuffer inputBuffer = checkNotNull(input.getInputBuffer());
    inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    assertThat(input.queueInputBuffer()).isTrue();

    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    // 3 second stream with 50_000 frames per second.
    assertThat(outputBytes).hasSize(3 * 50_000 * SURROUND_50000.bytesPerFrame);
    assertThat(ImmutableSet.copyOf(outputBytes)).containsExactly((byte) 0);
    assertThat(audioGraph.isEnded()).isTrue();
  }

  @Test
  public void onMediaItemChanged_withExactDurationAndQueuedInput_outputsInputAndSilence()
      throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));
    byte[] input = buildTestData(50_000 * SURROUND_50000.bytesPerFrame);
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 3_000_000,
        /* decodedFormat= */ getPcmFormat(SURROUND_50000),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(audioGraphInput.getOutput().hasRemaining()).isFalse();
    DecoderInputBuffer inputBuffer = checkNotNull(audioGraphInput.getInputBuffer());
    inputBuffer.ensureSpaceForWrite(input.length);
    inputBuffer.data.put(input).flip();
    assertThat(audioGraphInput.queueInputBuffer()).isTrue();

    inputBuffer = checkNotNull(audioGraphInput.getInputBuffer());
    inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    assertThat(audioGraphInput.queueInputBuffer()).isTrue();

    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    // 3 second stream with 50_000 frames per second.
    assertThat(outputBytes).hasSize(3 * 50_000 * SURROUND_50000.bytesPerFrame);
    assertThat(outputBytes.subList(0, input.length))
        .containsExactlyElementsIn(Bytes.asList(input))
        .inOrder();
    assertThat(ImmutableSet.copyOf(outputBytes.subList(input.length, outputBytes.size())))
        .containsExactly((byte) 0);
    assertThat(audioGraph.isEnded()).isTrue();
  }

  @Test
  public void onMediaItemChanged_withUnsetDurationAndQueuedInput_outputsInput() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));
    byte[] input = buildTestData(50_000 * SURROUND_50000.bytesPerFrame);
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        /* decodedFormat= */ getPcmFormat(SURROUND_50000),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(audioGraphInput.getOutput().hasRemaining()).isFalse();
    DecoderInputBuffer inputBuffer = checkNotNull(audioGraphInput.getInputBuffer());
    inputBuffer.ensureSpaceForWrite(input.length);
    inputBuffer.data.put(input).flip();
    assertThat(audioGraphInput.queueInputBuffer()).isTrue();

    inputBuffer = checkNotNull(audioGraphInput.getInputBuffer());
    inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    assertThat(audioGraphInput.queueInputBuffer()).isTrue();

    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    // 3 second stream with 50_000 frames per second.
    assertThat(outputBytes).hasSize(input.length);
    assertThat(outputBytes).containsExactlyElementsIn(Bytes.asList(input)).inOrder();
    assertThat(audioGraph.isEnded()).isTrue();
  }

  @Test
  public void audioGraphInputOutputtingInvalidFormat_audioGraphThrows() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioProcessor audioProcessor =
        new BaseAudioProcessor() {
          @Override
          public void queueInput(ByteBuffer inputBuffer) {}

          @Override
          protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
            return new AudioFormat(
                /* sampleRate= */ 44_100,
                /* channelCount= */ Format.NO_VALUE,
                C.ENCODING_PCM_16BIT);
          }
        };
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.EMPTY)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(audioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    audioGraph.registerInput(editedMediaItem, getPcmFormat(SURROUND_50000));

    assertThrows(ExportException.class, audioGraph::getOutput);
  }

  @Test
  public void getOutputAudioFormat_afterInitialization_isNotSet() {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    assertThat(audioGraph.getOutputAudioFormat()).isEqualTo(AudioFormat.NOT_SET);
  }

  @Test
  public void getOutputAudioFormat_afterRegisterInput_matchesInputFormat() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_48000));

    assertThat(audioGraph.getOutputAudioFormat()).isEqualTo(MONO_48000);
  }

  @Test
  public void getOutputAudioFormat_afterFlush_isSet() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_48000));

    audioGraph.flush(/* positionOffsetUs= */ 0);

    assertThat(audioGraph.getOutputAudioFormat()).isEqualTo(MONO_48000);
  }

  @Test
  public void registerInput_afterRegisterInput_doesNotChangeOutputFormat() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_48000));
    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_44100));

    assertThat(audioGraph.getOutputAudioFormat()).isEqualTo(STEREO_48000);
  }

  @Test
  public void registerInput_afterReset_changesOutputFormat() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_48000));
    audioGraph.reset();
    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_44100));

    assertThat(audioGraph.getOutputAudioFormat()).isEqualTo(MONO_44100);
  }

  @Test
  public void registerInput_withAudioProcessor_affectsOutputFormat() throws Exception {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(sonicAudioProcessor));

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));

    assertThat(audioGraph.getOutputAudioFormat().sampleRate).isEqualTo(48_000);
  }

  @Test
  public void registerInput_withMultipleAudioProcessors_affectsOutputFormat() throws Exception {
    SonicAudioProcessor changeTo96000Hz = new SonicAudioProcessor();
    changeTo96000Hz.setOutputSampleRateHz(96_000);
    SonicAudioProcessor changeTo48000Hz = new SonicAudioProcessor();
    changeTo48000Hz.setOutputSampleRateHz(48_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(),
            /* effects= */ ImmutableList.of(changeTo96000Hz, changeTo48000Hz));

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(SURROUND_50000));

    assertThat(audioGraph.getOutputAudioFormat().sampleRate).isEqualTo(48_000);
  }

  @Test
  public void registerInput_withUnsupportedFormat_throws() {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioFormat audioFormat =
        new AudioFormat(/* sampleRate= */ 44_100, /* channelCount= */ 1, C.ENCODING_PCM_8BIT);

    assertThrows(
        IllegalArgumentException.class,
        () -> audioGraph.registerInput(FAKE_ITEM, getPcmFormat(audioFormat)));
  }

  @Test
  public void createAudioGraphWithEffect_changesOutputFormat() throws Exception {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(sonicAudioProcessor));

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));

    assertThat(audioGraph.getOutputAudioFormat().sampleRate).isEqualTo(48_000);
  }

  @Test
  public void blockInput_blocksInputData() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 1_000_000,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();

    audioGraph.blockInput();

    assertThat(audioGraphInput.queueInputBuffer()).isFalse();
  }

  @Test
  public void unblockInput_unblocksInputData() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 1_000_000,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();
    audioGraph.blockInput();

    audioGraph.unblockInput();

    assertThat(audioGraphInput.queueInputBuffer()).isTrue();
  }

  @Test
  public void flush_withNonZeroPositionOffset_discardsPrecedingData() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.

    audioGraph.flush(/* positionOffsetUs= */ 500_000);
    // Queue input buffer with timestamp 0.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();
    checkState(audioGraphInput.queueInputBuffer());
    // Queue EOS.
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer());
    // Drain output.
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    assertThat(outputBytes).isEmpty();
  }

  @Test
  public void flush_withNonZeroPositionOffset_doesNotDiscardFollowingData() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 1_000_000,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.

    audioGraph.flush(/* positionOffsetUs= */ 500_000);
    // Queue input buffer with timestamp 600 ms.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();
    inputBuffer.timeUs = 600_000;
    checkState(audioGraphInput.queueInputBuffer());
    // Queue EOS.
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer());
    // Drain output.
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    assertThat(outputBytes).isNotEmpty();
  }

  @Test
  public void flush_withoutAudioProcessor_clearsPendingData() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();
    checkState(audioGraphInput.queueInputBuffer());
    checkState(audioGraph.getOutput().hasRemaining());

    audioGraph.flush(/* positionOffsetUs= */ 0);
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer()); // Queue EOS.
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    assertThat(outputBytes).isEmpty();
  }

  @Test
  public void flush_withAudioProcessor_clearsPendingData() throws Exception {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(sonicAudioProcessor));
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    DecoderInputBuffer inputBuffer = audioGraphInput.getInputBuffer();
    byte[] inputData = TestUtil.buildTestData(/* length= */ 100 * STEREO_44100.bytesPerFrame);
    inputBuffer.ensureSpaceForWrite(inputData.length);
    inputBuffer.data.put(inputData).flip();
    checkState(audioGraphInput.queueInputBuffer());
    checkState(audioGraph.getOutput().hasRemaining());

    audioGraph.flush(/* positionOffsetUs= */ 0);
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer()); // Queue EOS.
    ImmutableList<Byte> outputBytes = drainAudioGraph(audioGraph);

    assertThat(outputBytes).isEmpty();
  }

  @Test
  public void isEnded_afterFlushAndWithoutAudioProcessor_isFalse() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 1_000_000,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer()); // Queue EOS.
    drainAudioGraph(audioGraph);
    checkState(audioGraph.isEnded());

    audioGraph.flush(/* positionOffsetUs= */ 0);

    assertThat(audioGraph.isEnded()).isFalse();
  }

  @Test
  public void isEnded_afterFlushAndWithAudioProcessor_isFalse() throws Exception {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48_000);
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(sonicAudioProcessor));
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ 1_000_000,
        /* decodedFormat= */ getPcmFormat(STEREO_44100),
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);
    audioGraphInput.getOutput(); // Force the media item change to be processed.
    audioGraphInput.getInputBuffer().setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    checkState(audioGraphInput.queueInputBuffer()); // Queue EOS.
    drainAudioGraph(audioGraph);
    checkState(audioGraph.isEnded());

    audioGraph.flush(/* positionOffsetUs= */ 0);

    assertThat(audioGraph.isEnded()).isFalse();
  }

  @Test
  public void flush_propagatesStartTimeToAudioProcessors() throws Exception {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    BaseAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(fakeProcessor));
    audioGraph.flush(/* positionOffsetUs= */ 500_000);
    assertThat(lastPositionOffsetUs.get()).isEqualTo(500_000);
  }

  @Test
  public void flush_beforeRegisteringFirstInput_maintainsPreviousPositionOffset() throws Exception {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    BaseAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(fakeProcessor));

    audioGraph.flush(/* positionOffsetUs= */ 500_000);
    lastPositionOffsetUs.set(C.TIME_UNSET);
    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_44100));

    assertThat(lastPositionOffsetUs.get()).isEqualTo(500_000);
  }

  @Test
  public void flush_multipleTimes_propagatesLastPositionOffsetAfterFirstInput() throws Exception {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    BaseAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(fakeProcessor));

    audioGraph.flush(/* positionOffsetUs= */ 500_000);
    audioGraph.flush(/* positionOffsetUs= */ 600_000);
    audioGraph.flush(/* positionOffsetUs= */ 700_000);
    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_44100));

    assertThat(lastPositionOffsetUs.get()).isEqualTo(700_000);
  }

  @Test
  public void flush_afterRegisteringFirstInput_updatesPositionOffset() throws Exception {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    BaseAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    AudioGraph audioGraph =
        new AudioGraph(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(fakeProcessor));

    audioGraph.registerInput(FAKE_ITEM, getPcmFormat(MONO_44100));
    assertThat(lastPositionOffsetUs.get()).isEqualTo(0);
    audioGraph.flush(/* positionOffsetUs= */ 500_000);

    assertThat(lastPositionOffsetUs.get()).isEqualTo(500_000);
  }

  @Test
  public void isEnded_afterAllInputsReleased_returnsTrue() throws Exception {
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));

    audioGraphInput.release();
    assertThat(audioGraph.getOutput().hasRemaining()).isFalse();
    assertThat(audioGraph.isEnded()).isTrue();
  }

  @Test
  public void releaseInput_afterQueuingBuffers_endsGraph() throws Exception {
    long outputBytes = 0;
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput audioGraphInput =
        audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    audioGraphInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        getPcmFormat(STEREO_44100),
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);
    // Force output to be processed and handle the new configuration.
    assertThat(audioGraph.getOutput().hasRemaining()).isFalse();

    // Queue 1024 frames.
    assertThat(
            queueBufferIntoInput(buildTestData(STEREO_44100.bytesPerFrame * 1024), audioGraphInput))
        .isTrue();

    // Consume all the queued input.
    ByteBuffer output = audioGraph.getOutput();
    assertThat(output.hasRemaining()).isTrue();
    outputBytes += output.remaining();
    output.position(output.limit());
    assertThat(outputBytes).isEqualTo(1024 * STEREO_44100.bytesPerFrame);

    // Queue another 1024 frames.
    assertThat(
            queueBufferIntoInput(buildTestData(STEREO_44100.bytesPerFrame * 1024), audioGraphInput))
        .isTrue();

    // Release the input before the second buffer is processed.
    audioGraphInput.release();

    // Force AudioGraph to process the input's release and make sure that no more output is
    // available.
    assertThat(audioGraph.getOutput().hasRemaining()).isFalse();
    assertThat(audioGraph.isEnded()).isTrue();
  }

  @Test
  public void releaseInput_withOtherActiveInputs_graphContinuesProcessingUntilEoS()
      throws Exception {
    long outputBytes = 0;
    AudioGraph audioGraph =
        new AudioGraph(new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of());
    AudioGraphInput firstInput = audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    AudioGraphInput secondInput = audioGraph.registerInput(FAKE_ITEM, getPcmFormat(STEREO_44100));
    firstInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        getPcmFormat(STEREO_44100),
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);
    secondInput.onMediaItemChanged(
        FAKE_ITEM,
        /* durationUs= */ C.TIME_UNSET,
        getPcmFormat(STEREO_44100),
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);

    // Force output to be processed and handle the new configuration.
    assertThat(audioGraph.getOutput().hasRemaining()).isFalse();

    // Queue 1024 frames on first input.
    assertThat(queueBufferIntoInput(buildTestData(STEREO_44100.bytesPerFrame * 1024), firstInput))
        .isTrue();

    // Queue 1024 frames on second input.
    assertThat(queueBufferIntoInput(buildTestData(STEREO_44100.bytesPerFrame * 1024), secondInput))
        .isTrue();

    // Consume all the queued input.
    ByteBuffer output = audioGraph.getOutput();
    assertThat(output.hasRemaining()).isTrue();
    outputBytes += output.remaining();
    output.position(output.limit());
    assertThat(outputBytes).isEqualTo(1024 * STEREO_44100.bytesPerFrame);

    // Queue another 1024 frames on first input.
    assertThat(queueBufferIntoInput(buildTestData(STEREO_44100.bytesPerFrame * 1024), firstInput))
        .isTrue();

    // Release the input before the second buffer is processed.
    firstInput.release();

    // AudioGraph will not produce more output until second input queues a buffer.
    assertThat(audioGraph.getOutput().hasRemaining()).isFalse();
    assertThat(audioGraph.isEnded()).isFalse();

    // Queue EoS on second input and end AudioGraph.
    DecoderInputBuffer inputBuffer = checkNotNull(secondInput.getInputBuffer());
    inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
    assertThat(secondInput.queueInputBuffer()).isTrue();

    // AudioGraph should not produce any more output.
    assertThat(drainAudioGraph(audioGraph)).isEmpty();
    assertThat(audioGraph.isEnded()).isTrue();
  }

  /** Drains the graph and returns the number of bytes output. */
  private static ImmutableList<Byte> drainAudioGraph(AudioGraph audioGraph) throws ExportException {
    ImmutableList.Builder<Byte> bytes = new ImmutableList.Builder<>();
    ByteBuffer output;
    while ((output = audioGraph.getOutput()).hasRemaining() || !audioGraph.isEnded()) {
      bytes.addAll(Bytes.asList(createByteArray(output)));
    }
    return bytes.build();
  }

  private static boolean queueBufferIntoInput(byte[] buffer, AudioGraphInput input) {
    DecoderInputBuffer inputBuffer = checkNotNull(input.getInputBuffer());
    inputBuffer.ensureSpaceForWrite(buffer.length);
    inputBuffer.data.put(buffer).flip();
    return input.queueInputBuffer();
  }
}
