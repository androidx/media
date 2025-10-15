/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.util.Util.getByteDepth;
import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static androidx.media3.test.utils.TestUtil.createFloatArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link ToFloatPcmAudioProcessor}. */
@RunWith(RobolectricTestParameterInjector.class)
public class ToFloatPcmAudioProcessorTest {

  /**
   * Represents the {@link C.PcmEncoding} for the parameterized test.
   *
   * <p>Can be one of:
   *
   * <ul>
   *   <li>{@link C#ENCODING_PCM_16BIT}
   *   <li>{@link C#ENCODING_PCM_32BIT}
   *   <li>{@link C#ENCODING_PCM_24BIT}
   *   <li>{@link C#ENCODING_PCM_32BIT_BIG_ENDIAN}
   *   <li>{@link C#ENCODING_PCM_24BIT_BIG_ENDIAN}
   * </ul>
   */
  @TestParameter({"2", "22", "21", "1610612736", "1342177280"})
  private int pcmEncoding;

  @Test
  public void queueInput_withNonZeroValues_returnsCorrectConvertedValues() throws Exception {
    ToFloatPcmAudioProcessor processor = new ToFloatPcmAudioProcessor();
    processor.configure(
        new AudioFormat(/* sampleRate= */ 44100, /* channelCount= */ 1, pcmEncoding));
    processor.flush(StreamMetadata.DEFAULT);

    processor.queueInput(getTestSamplesForEncoding(pcmEncoding));
    assertThat(createFloatArray(processor.getOutput()))
        .usingTolerance(getToleranceForEncoding(pcmEncoding))
        .containsExactly(new float[] {1.f, -1.f, 0.5f, -0.5f});
  }

  @Test
  public void queueInput_withZero_returnsZero() throws Exception {
    ToFloatPcmAudioProcessor processor = new ToFloatPcmAudioProcessor();
    processor.configure(
        new AudioFormat(/* sampleRate= */ 44100, /* channelCount= */ 1, pcmEncoding));
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer buffer = ByteBuffer.allocateDirect(getByteDepth(pcmEncoding));

    processor.queueInput(buffer);
    assertThat(createFloatArray(processor.getOutput())).isEqualTo(new float[] {0f});
  }

  @Test
  public void configure_returnsFloatPcmEncoding() throws Exception {
    AudioFormat input =
        new AudioFormat(/* sampleRate= */ 48000, /* channelCount= */ 6, pcmEncoding);
    ToFloatPcmAudioProcessor processor = new ToFloatPcmAudioProcessor();
    processor.configure(
        new AudioFormat(input.sampleRate, input.channelCount, C.ENCODING_PCM_FLOAT));
  }

  private static float getToleranceForEncoding(int pcmEncoding) {
    switch (pcmEncoding) {
      case C.ENCODING_PCM_16BIT:
        return 1f / 0x8000;
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
        return (float) (1.0 / (1L << 31));
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return 1f / 0x800000;
    }
    throw new IllegalArgumentException();
  }

  /**
   * Returns a {@link ByteBuffer} containing {@code {1f, -1f, 0.5f, -0.5f}} represented in the
   * specified encoding.
   */
  private static ByteBuffer getTestSamplesForEncoding(int pcmEncoding) {
    switch (pcmEncoding) {
      case C.ENCODING_PCM_16BIT:
        return createByteBuffer(
            new short[] {Short.MAX_VALUE, Short.MIN_VALUE, 0x4000, (short) 0xC000});
      case C.ENCODING_PCM_32BIT:
        return createByteBuffer(
            new int[] {
              Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2
            });
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
        return createByteBuffer(
            new int[] {
              Integer.reverseBytes(Integer.MAX_VALUE),
              Integer.reverseBytes(Integer.MIN_VALUE),
              Integer.reverseBytes(Integer.MAX_VALUE / 2),
              Integer.reverseBytes(Integer.MIN_VALUE / 2)
            });
      case C.ENCODING_PCM_24BIT:
        return createByteBuffer(
            new byte[] {
              (byte) 0xFF,
              (byte) 0xFF,
              0x7F,
              0x00,
              0x00,
              (byte) 0x80,
              0x00,
              0x00,
              0x40,
              0x00,
              0x00,
              (byte) 0xC0
            });
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return createByteBuffer(
            new byte[] {
              0x7F,
              (byte) 0xFF,
              (byte) 0xFF,
              (byte) 0x80,
              0x00,
              0x00,
              0x40,
              0x00,
              0x00,
              (byte) 0xC0,
              0x00,
              0x00
            });
    }
    throw new IllegalArgumentException();
  }
}
