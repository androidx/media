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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
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
}
