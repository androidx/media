/*
 * Copyright 2022 The Android Open Source Project
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

import static androidx.media3.test.utils.TestUtil.buildTestData;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SilenceAppendingAudioProcessor}. */
@RunWith(AndroidJUnit4.class)
public class SilenceAppendingAudioProcessorTest {

  public static final AudioFormat INPUT_AUDIO_FORMAT =
      new AudioFormat(/* sampleRate= */ 44100, /* channelCount= */ 2, C.ENCODING_PCM_16BIT);

  @Test
  public void queueEndOfStream_withNoInputQueued_producesSilenceUpToDuration() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);

    generator.queueEndOfStream();

    ImmutableList<Byte> outputBytes = drainProcessor(generator);
    assertThat(outputBytes).hasSize(44100 * 3 * INPUT_AUDIO_FORMAT.bytesPerFrame);
    assertThat(ImmutableSet.copyOf(outputBytes)).containsExactly((byte) 0);
    assertThat(generator.getOutput().hasRemaining()).isFalse();
  }

  @Test
  public void isActive_beforeSettingDuration_returnsFalse() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);

    assertThat(generator.isActive()).isFalse();
  }

  @Test
  public void isActive_beforeConfiguring_returnsFalse() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    assertThat(generator.isActive()).isFalse();
  }

  @Test
  public void isActive_afterSettingUnsetDuration_returnsFalse() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);

    assertThat(generator.isActive()).isTrue();

    generator.setExpectedDurationUs(/* expectedDurationUs= */ C.TIME_UNSET);
    generator.flush(StreamMetadata.DEFAULT);
    assertThat(generator.isActive()).isFalse();
  }

  @Test
  public void isActive_afterConfigureAndFlush_returnsTrue() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);

    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);

    assertThat(generator.isActive()).isTrue();
  }

  @Test
  public void getOutput_beforeEndOfStream_returnsEmptyBuffer() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);

    assertThat(generator.getOutput().hasRemaining()).isFalse();
  }

  @Test
  public void getOutput_beforeEndOfStreamAfterQueueingInput_returnsOnlyQueuedInput()
      throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);
    byte[] input = buildTestData(2048);

    generator.queueInput(createByteBuffer(input));
    ByteBuffer output = generator.getOutput();

    assertThat(createByteArray(output)).isEqualTo(input);
    assertThat(generator.getOutput().hasRemaining()).isFalse();
  }

  @Test
  public void getOutput_withQueuedInputAndEndOfStream_returnsSilenceCappedAtDuration()
      throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 1_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);
    byte[] input = buildTestData(4410 * INPUT_AUDIO_FORMAT.bytesPerFrame);

    generator.queueInput(createByteBuffer(input));
    generator.queueEndOfStream();
    ImmutableList<Byte> outputBytes = drainProcessor(generator);

    assertThat(outputBytes).hasSize(44100 * INPUT_AUDIO_FORMAT.bytesPerFrame);
    assertThat(generator.getOutput().hasRemaining()).isFalse();
    assertThat(outputBytes.subList(0, input.length))
        .containsExactlyElementsIn(Bytes.asList(input))
        .inOrder();
    assertThat(ImmutableSet.copyOf(outputBytes.subList(input.length, outputBytes.size())))
        .containsExactly((byte) 0);
  }

  @Test
  public void setExpectedDurationUs_modifiesPreexistingDurationAfterFlush() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 3_000_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);
    generator.queueEndOfStream();

    ImmutableList<Byte> outputBytes = drainProcessor(generator);
    assertThat(outputBytes).hasSize(44100 * 3 * INPUT_AUDIO_FORMAT.bytesPerFrame);

    generator.setExpectedDurationUs(/* expectedDurationUs= */ 1_000_000);
    generator.flush(StreamMetadata.DEFAULT);
    generator.queueEndOfStream();
    outputBytes = drainProcessor(generator);

    assertThat(outputBytes).hasSize(44100 * INPUT_AUDIO_FORMAT.bytesPerFrame);
    assertThat(ImmutableSet.copyOf(outputBytes)).containsExactly((byte) 0);
  }

  @Test
  public void queueInput_beyondExpectedDuration_doesNotTruncateInput() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 1_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(StreamMetadata.DEFAULT);
    // Input is 0.1 seconds of frames vs 0.001s of expected duration.
    byte[] input = buildTestData(4410 * INPUT_AUDIO_FORMAT.bytesPerFrame);

    generator.queueInput(createByteBuffer(input));
    generator.queueEndOfStream();

    ImmutableList<Byte> outputBytes = drainProcessor(generator);
    assertThat(outputBytes).containsExactlyElementsIn(Bytes.asList(input)).inOrder();
    assertThat(generator.getOutput().hasRemaining()).isFalse();
  }

  @Test
  public void flush_withSetDurationAndNonZeroPositionOffset_throws() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.setExpectedDurationUs(/* expectedDurationUs= */ 1_000);
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    assertThrows(
        IllegalStateException.class,
        () -> generator.flush(new StreamMetadata(/* positionOffsetUs= */ 50_000)));
  }

  @Test
  public void flush_withUnsetDurationAndNonZeroPositionOffset_doesNotThrow() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    AudioFormat unused = generator.configure(INPUT_AUDIO_FORMAT);
    generator.flush(new StreamMetadata(/* positionOffsetUs= */ 50_000));
    assertThat(generator.isActive()).isFalse();
  }

  @Test
  public void flush_withUnsetInputFormatAndNonZeroPositionOffset_doesNotThrow() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    generator.flush(new StreamMetadata(/* positionOffsetUs= */ 50_000));
    assertThat(generator.isActive()).isFalse();
  }

  @Test
  public void configure_returnsInputFormat() throws Exception {
    SilenceAppendingAudioProcessor generator = new SilenceAppendingAudioProcessor();
    assertThat(generator.configure(INPUT_AUDIO_FORMAT)).isEqualTo(INPUT_AUDIO_FORMAT);
  }

  /** Drains the generator and returns the number of bytes output. */
  private static ImmutableList<Byte> drainProcessor(SilenceAppendingAudioProcessor generator) {
    ImmutableList.Builder<Byte> bytes = new ImmutableList.Builder<>();
    while (!generator.isEnded()) {
      ByteBuffer output = generator.getOutput();
      bytes.addAll(Bytes.asList(createByteArray(output)));
    }
    return bytes.build();
  }
}
