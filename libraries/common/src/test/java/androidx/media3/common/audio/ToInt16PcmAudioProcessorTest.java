/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static androidx.media3.test.utils.TestUtil.createShortArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link ToInt16PcmAudioProcessor}. */
@RunWith(RobolectricTestParameterInjector.class)
public class ToInt16PcmAudioProcessorTest {

  @Test
  public void configure_with16BitInputFormat_makesProcessorInactive() throws Exception {
    ToInt16PcmAudioProcessor processor = new ToInt16PcmAudioProcessor();
    AudioFormat outputFormat =
        processor.configure(
            new AudioFormat(/* sampleRate= */ 48000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT));
    assertThat(outputFormat).isEqualTo(AudioFormat.NOT_SET);
    processor.flush(StreamMetadata.DEFAULT);
    assertThat(processor.isActive()).isFalse();
  }

  @Test
  public void configure_withNon16BitInputFormat_makesProcessorActive(
      @TestParameter(valuesProvider = PcmEncodingProvider.class) @C.PcmEncoding int pcmEncoding)
      throws Exception {
    ToInt16PcmAudioProcessor processor = new ToInt16PcmAudioProcessor();
    AudioFormat outputFormat =
        processor.configure(
            new AudioFormat(/* sampleRate= */ 48000, /* channelCount= */ 1, pcmEncoding));
    assertThat(outputFormat)
        .isEqualTo(
            new AudioFormat(/* sampleRate= */ 48000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT));
    processor.flush(StreamMetadata.DEFAULT);
    assertThat(processor.isActive()).isTrue();
  }

  @Test
  public void queueInput_returnsCorrectConvertedValues(
      @TestParameter(valuesProvider = PcmEncodingProvider.class) @C.PcmEncoding int pcmEncoding)
      throws Exception {
    ToInt16PcmAudioProcessor processor = new ToInt16PcmAudioProcessor();
    processor.configure(
        new AudioFormat(/* sampleRate= */ 48000, /* channelCount= */ 1, pcmEncoding));
    processor.flush(StreamMetadata.DEFAULT);

    processor.queueInput(getTestSamplesForEncoding(pcmEncoding));

    assertThat(createShortArray(processor.getOutput()))
        .asList()
        .comparingElementsUsing(Correspondence.tolerance(getToleranceForEncoding(pcmEncoding)))
        .containsExactly(
            Short.MIN_VALUE, Short.MIN_VALUE / 2, (short) 0, Short.MAX_VALUE / 2, Short.MAX_VALUE)
        .inOrder();
  }

  private static ByteBuffer getTestSamplesForEncoding(int pcmEncoding) {
    switch (pcmEncoding) {
      case C.ENCODING_PCM_8BIT:
        return createByteBuffer(new byte[] {0, 64, (byte) 128, (byte) 192, (byte) 0xFF});
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
        return createByteBuffer(
            new short[] {
              Short.reverseBytes(Short.MIN_VALUE),
              Short.reverseBytes((short) (Short.MIN_VALUE / 2)),
              0,
              Short.reverseBytes((short) (Short.MAX_VALUE / 2)),
              Short.reverseBytes(Short.MAX_VALUE)
            });
      case C.ENCODING_PCM_32BIT:
        return createByteBuffer(
            new int[] {
              Integer.MIN_VALUE, Integer.MIN_VALUE / 2, 0, Integer.MAX_VALUE / 2, Integer.MAX_VALUE
            });
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
        return createByteBuffer(
            new int[] {
              Integer.reverseBytes(Integer.MIN_VALUE),
              Integer.reverseBytes(Integer.MIN_VALUE / 2),
              0,
              Integer.reverseBytes(Integer.MAX_VALUE / 2),
              Integer.reverseBytes(Integer.MAX_VALUE)
            });
      case C.ENCODING_PCM_24BIT:
        return createByteBuffer(
            new byte[] {
              0x00,
              0x00,
              (byte) 0x80,
              0x00,
              0x00,
              (byte) 0xC0,
              0x00,
              0x00,
              0x00,
              (byte) 0xFF,
              (byte) 0xFF,
              0x3F,
              (byte) 0xFF,
              (byte) 0xFF,
              0x7F
            });
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return createByteBuffer(
            new byte[] {
              (byte) 0x80,
              0x00,
              0x00,
              (byte) 0xC0,
              0x00,
              0x00,
              0x00,
              0x00,
              0x00,
              0x3F,
              (byte) 0xFF,
              (byte) 0xFF,
              0x7F,
              (byte) 0xFF,
              (byte) 0xFF
            });
      case C.ENCODING_PCM_FLOAT:
        return createByteBuffer(new float[] {-1.0f, -0.5f, 0.0f, 0.5f, 1.0f});
      default:
        throw new IllegalArgumentException();
    }
  }

  private static float getToleranceForEncoding(int pcmEncoding) {
    if (pcmEncoding == C.ENCODING_PCM_8BIT) {
      return 256;
    }
    if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
      return 1;
    }
    return 0;
  }

  private static final class PcmEncodingProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(Context context) {
      return ImmutableList.of(
          value(C.ENCODING_PCM_8BIT).withName("ENCODING_PCM_8BIT"),
          value(C.ENCODING_PCM_16BIT_BIG_ENDIAN).withName("ENCODING_PCM_16BIT_BIG_ENDIAN"),
          value(C.ENCODING_PCM_24BIT).withName("ENCODING_PCM_24BIT"),
          value(C.ENCODING_PCM_24BIT_BIG_ENDIAN).withName("ENCODING_PCM_24BIT_BIG_ENDIAN"),
          value(C.ENCODING_PCM_32BIT).withName("ENCODING_PCM_32BIT"),
          value(C.ENCODING_PCM_32BIT_BIG_ENDIAN).withName("ENCODING_PCM_32BIT_BIG_ENDIAN"),
          value(C.ENCODING_PCM_FLOAT).withName("ENCODING_PCM_FLOAT"));
    }
  }
}
