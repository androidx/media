/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test IAMF native functions. */
@RunWith(AndroidJUnit4.class)
public final class IamfDecoderTest {
  // Sample configOBUs data from sample_iamf.mp4 file.
  private static final byte[] iacbObus = {
    -8, 6, 105, 97, 109, 102, 0, 0, 0, 15, -56, 1, 105, 112, 99, 109, 64, 0, 0, 1, 16, 0, 0, 62,
    -128, 8, 12, -84, 2, 0, -56, 1, 1, 0, 0, 32, 16, 1, 1, 16, 78, 42, 1, 101, 110, 45, 117, 115, 0,
    116, 101, 115, 116, 95, 109, 105, 120, 95, 112, 114, 101, 115, 0, 1, 1, -84, 2, 116, 101, 115,
    116, 95, 115, 117, 98, 95, 109, 105, 120, 95, 48, 95, 97, 117, 100, 105, 111, 95, 101, 108, 101,
    109, 101, 110, 116, 95, 48, 0, 0, 0, 100, -128, 125, -128, 0, 0, 100, -128, 125, -128, 0, 0, 1,
    -128, 0, -54, 81, -51, -79
  };
  // First temporal unit from the same file, sample_iamf.mp4.
  private static final byte[] temporalUnit1 = {
    24, 6, 100, 64, 64, 0, 0, 0, 48, -128, 2, -100, -1, 100, 0, -99, -1, 99, 0, -98, -1, 98, 0, -97,
    -1, 97, 0, -96, -1, 96, 0, -95, -1, 95, 0, -94, -1, 94, 0, -93, -1, 93, 0, -92, -1, 92, 0, -91,
    -1, 91, 0, -90, -1, 90, 0, -89, -1, 89, 0, -88, -1, 88, 0, -87, -1, 87, 0, -86, -1, 86, 0, -85,
    -1, 85, 0, -84, -1, 84, 0, -83, -1, 83, 0, -82, -1, 82, 0, -81, -1, 81, 0, -80, -1, 80, 0, -79,
    -1, 79, 0, -78, -1, 78, 0, -77, -1, 77, 0, -76, -1, 76, 0, -75, -1, 75, 0, -74, -1, 74, 0, -73,
    -1, 73, 0, -72, -1, 72, 0, -71, -1, 71, 0, -70, -1, 70, 0, -69, -1, 69, 0, -68, -1, 68, 0, -67,
    -1, 67, 0, -66, -1, 66, 0, -65, -1, 65, 0, -64, -1, 64, 0, -63, -1, 63, 0, -62, -1, 62, 0, -61,
    -1, 61, 0, -60, -1, 60, 0, -59, -1, 59, 0, -58, -1, 58, 0, -57, -1, 57, 0, -56, -1, 56, 0, -55,
    -1, 55, 0, -54, -1, 54, 0, -53, -1, 53, 0, -52, -1, 52, 0, -51, -1, 51, 0, -50, -1, 50, 0, -49,
    -1, 49, 0, -48, -1, 48, 0, -47, -1, 47, 0, -46, -1, 46, 0, -45, -1, 45, 0, -44, -1, 44, 0, -43,
    -1, 43, 0, -42, -1, 42, 0, -41, -1, 41, 0, -40, -1, 40, 0, -39, -1, 39, 0, -38, -1, 38, 0, -37,
    -1, 37, 0,
  };
  // Frame size of the content in the above configuration OBUs.
  private static final int FRAME_SIZE = 64;
  // Sample rate of the content in the above configuration OBUs.
  private static final int SAMPLE_RATE = 16000;
  // Mix Presentation ID of the content in the above configuration OBUs.
  private static final long MIX_PRESENTATION_ID = 42;

  @Before
  public void setUp() {
    assumeTrue(IamfLibrary.isAvailable());
  }

  @Test
  public void decoderCreate_withUnsetParameters_usesDefaults() throws Exception {
    // By default, decoder will output stereo.
    int expectedNumOutputChannels = 2;

    IamfDecoder decoder =
        new IamfDecoder(
            ImmutableList.of(iacbObus),
            IamfDecoder.OUTPUT_LAYOUT_UNSET,
            IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            IamfDecoder.OUTPUT_SAMPLE_TYPE_UNSET,
            IamfDecoder.CHANNEL_ORDERING_UNSET);

    assertThat(decoder.isDescriptorProcessingComplete()).isTrue();
    assertThat(decoder.getNumberOfOutputChannels()).isEqualTo(expectedNumOutputChannels);
    assertThat(decoder.getSelectedOutputLayout())
        .isEqualTo(IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0);
    assertThat(decoder.getSelectedMixPresentationId()).isEqualTo(MIX_PRESENTATION_ID);
    assertThat(decoder.getSampleRate()).isEqualTo(SAMPLE_RATE);
    assertThat(decoder.getFrameSize()).isEqualTo(FRAME_SIZE);
    assertThat(decoder.getOutputSampleType())
        .isEqualTo(IamfDecoder.OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN);
    int bytesPerSample = 4; // size in bytes of int32.
    assertThat(decoder.getOutputBufferSizeBytes())
        .isEqualTo(FRAME_SIZE * expectedNumOutputChannels * bytesPerSample);

    decoder.release();
  }

  @Test
  public void decoderCreate_willAcceptParameters() throws Exception {
    // If we request 5.1 output, we should expect 6 channels.
    int expectedNumOutputChannels = 6;

    IamfDecoder decoder =
        new IamfDecoder(
            ImmutableList.of(iacbObus),
            IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0,
            IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN,
            IamfDecoder.CHANNEL_ORDERING_ANDROID_ORDERING);

    assertThat(decoder.isDescriptorProcessingComplete()).isTrue();
    assertThat(decoder.getNumberOfOutputChannels()).isEqualTo(expectedNumOutputChannels);
    assertThat(decoder.getSelectedOutputLayout())
        .isEqualTo(IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0);
    assertThat(decoder.getSelectedMixPresentationId()).isEqualTo(MIX_PRESENTATION_ID);
    assertThat(decoder.getSampleRate()).isEqualTo(SAMPLE_RATE);
    assertThat(decoder.getFrameSize()).isEqualTo(FRAME_SIZE);
    assertThat(decoder.getOutputSampleType())
        .isEqualTo(IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN);
    int bytesPerSample = 2; // size in bytes of int16.
    assertThat(decoder.getOutputBufferSizeBytes())
        .isEqualTo(FRAME_SIZE * expectedNumOutputChannels * bytesPerSample);

    decoder.release();
  }

  @Test
  public void decoderDecode_succeeds() throws Exception {
    IamfDecoder decoder =
        new IamfDecoder(
            ImmutableList.of(iacbObus),
            IamfDecoder.OUTPUT_LAYOUT_UNSET,
            IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            IamfDecoder.OUTPUT_SAMPLE_TYPE_UNSET,
            IamfDecoder.CHANNEL_ORDERING_UNSET);
    assertThat(decoder.isDescriptorProcessingComplete()).isTrue();
    DecoderInputBuffer inputBuffer = decoder.createInputBuffer();
    inputBuffer.data = getTemporalUnit();
    SimpleDecoderOutputBuffer outputBuffer = decoder.createOutputBuffer();

    assertThat(decoder.decode(inputBuffer, outputBuffer, /* reset= */ false)).isNull();
    assertThat(outputBuffer.data.hasRemaining()).isTrue();

    decoder.release();
  }

  @Test
  public void decoderResetWithNewMix_succeeds() throws Exception {
    // Initially request stereo
    int initialOutputLayout = IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0;
    int initialNumChannels = 2;
    IamfDecoder decoder =
        new IamfDecoder(
            ImmutableList.of(iacbObus),
            initialOutputLayout,
            IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN,
            IamfDecoder.CHANNEL_ORDERING_ANDROID_ORDERING);

    // Verify stereo output before reset.
    assertThat(decoder.isDescriptorProcessingComplete()).isTrue();
    assertThat(decoder.getNumberOfOutputChannels()).isEqualTo(initialNumChannels);
    assertThat(decoder.getSelectedOutputLayout()).isEqualTo(initialOutputLayout);

    // Request switch to 5.1.
    int finalOutputLayout = IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0;
    int finalNumChannels = 6;
    decoder.resetWithNewMix(
        IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0,
        IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET);

    // Verify we see the requested output type after reset.
    assertThat(decoder.getNumberOfOutputChannels()).isEqualTo(finalNumChannels);
    assertThat(decoder.getSelectedOutputLayout()).isEqualTo(finalOutputLayout);
    int bytesPerSample = 2; // size in bytes of int16.
    assertThat(decoder.getOutputBufferSizeBytes())
        .isEqualTo(FRAME_SIZE * finalNumChannels * bytesPerSample);

    decoder.release();
  }

  private ByteBuffer getTemporalUnit() {
    ByteBuffer buffer = ByteBuffer.allocateDirect(temporalUnit1.length);
    buffer.put(temporalUnit1);
    buffer.flip();
    return buffer;
  }
}
