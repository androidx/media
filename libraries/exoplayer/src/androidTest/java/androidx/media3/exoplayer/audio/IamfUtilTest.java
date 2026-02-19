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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.AudioFormat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests IAMF utils. */
@RunWith(AndroidJUnit4.class)
public final class IamfUtilTest {

  // Test that both ChannelMask versions of 5.1.2 give the same IAMF OutputLayout.
  @Test
  @SdkSuppress(minSdkVersion = 32)
  public void getOutputLayoutForChannelMask_equivalenceOf5point1point2() {
    int audioFormat5p1p2 = 0b1100000000000011111100;
    int iamfVersion5p1p2 = 0b0000010100000011111100;

    assertThat(IamfUtil.getOutputLayoutForChannelMask(audioFormat5p1p2))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0);
    assertThat(IamfUtil.getOutputLayoutForChannelMask(iamfVersion5p1p2))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0);
  }

  // Test that both ChannelMask versions of 7.1.2 give the same IAMF OutputLayout.
  @Test
  @SdkSuppress(minSdkVersion = 32)
  public void getOutputLayoutForChannelMask_equivalenceOf7point1point2() {
    int audioFormat7p1p2 = 0b1100000001100011111100;
    int iamfVersion7p1p2 = 0b0000010101100011111100;

    assertThat(IamfUtil.getOutputLayoutForChannelMask(audioFormat7p1p2))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0);
    assertThat(IamfUtil.getOutputLayoutForChannelMask(iamfVersion7p1p2))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0);
  }

  // Test that both ChannelMask versions of 9.1.4 give the same IAMF OutputLayout.
  @Test
  @SdkSuppress(minSdkVersion = 32)
  public void getOutputLayoutForChannelMask_equivalenceOf9point1point4() {
    int audioFormat9p1p4 = 0b1100000010110101100011111100;
    int iamfVersion9p1p4 = 0b10110101101111111100;

    assertThat(IamfUtil.getOutputLayoutForChannelMask(audioFormat9p1p4))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0);
    assertThat(IamfUtil.getOutputLayoutForChannelMask(iamfVersion9p1p4))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0);
  }

  // Test that both ChannelMask versions of 9.1.6 give the same IAMF OutputLayout.
  @Test
  @SdkSuppress(minSdkVersion = 32)
  public void getOutputLayoutForChannelMask_equivalenceOf9point1point6() {
    int audioFormat9p1p6 = 0b1100001110110101100011111100;
    int iamfVersion9p1p6 = 0b0000001110110101101111111100;

    assertThat(IamfUtil.getOutputLayoutForChannelMask(audioFormat9p1p6))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0);
    assertThat(IamfUtil.getOutputLayoutForChannelMask(iamfVersion9p1p6))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0);
  }

  @Test
  public void getOutputLayoutForChannelMask_nonMatchingChannelMaskThrows() {
    final int arbitraryValue = 0b101010101010101010;

    assertThrows(
        IllegalArgumentException.class,
        () -> IamfUtil.getOutputLayoutForChannelMask(arbitraryValue));
  }

  @Test
  public void getChannelMaskForOutputLayout_invalidThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> IamfUtil.getChannelMaskForOutputLayout(IamfUtil.OUTPUT_LAYOUT_UNSET));
  }

  @Test
  public void iamfSupportedLayouts_allAreConvertableToLayout() {
    for (int channelMask : IamfUtil.IAMF_SUPPORTED_CHANNEL_MASKS) {
      assertThat(IamfUtil.getOutputLayoutForChannelMask(channelMask))
          .isNotEqualTo(IamfUtil.OUTPUT_LAYOUT_UNSET);
    }
  }

  @Test
  public void getOutputLayoutForCurrentConfiguration_defaultWhenNoInformation() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(),
            /* spatializerChannelMasks= */ ImmutableList.of());
    boolean useIntegratedBinauralRenderer = true; // Does not matter, no Spatializer.

    assertThat(
            IamfUtil.getOutputLayoutForCurrentConfiguration(
                audioCapabilities, useIntegratedBinauralRenderer))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0);
  }

  @Test
  public void getOutputLayoutForCurrentConfiguration_picksFirstCompatibleSpeakerLayout() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_QUAD, // Not an IAMF layout.
                AudioFormat.CHANNEL_OUT_5POINT1, // Chosen.
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND // Never reached.
                ),
            /* spatializerChannelMasks= */ ImmutableList.of());
    boolean useIntegratedBinauralRenderer = true; // Does not matter, no Spatializer.

    assertThat(
            IamfUtil.getOutputLayoutForCurrentConfiguration(
                audioCapabilities, useIntegratedBinauralRenderer))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0);
  }

  @Test
  public void getOutputLayoutForCurrentConfiguration_prefersSpatializerOverSpeakerLayout() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_5POINT1 // Not chosen.
                ),
            /* spatializerChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND // Chosen.
                ));
    // We won't use decoder built-in binaural, and we'll expect the Spatializer layout.
    boolean useIntegratedBinauralRenderer = false;

    assertThat(
            IamfUtil.getOutputLayoutForCurrentConfiguration(
                audioCapabilities, useIntegratedBinauralRenderer))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0);
  }

  @Test
  public void getOutputLayoutForCurrentConfiguration_usesIntegratedBinauralRenderer() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1),
            /* spatializerChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND));
    // Spatializer is enabled and available, so we use decoder built-in binaural.
    boolean useIntegratedBinauralRenderer = true;

    assertThat(
            IamfUtil.getOutputLayoutForCurrentConfiguration(
                audioCapabilities, useIntegratedBinauralRenderer))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_BINAURAL);
  }

  @Test
  public void getOutputChannelMaskForCurrentConfiguration_defaultWhenNoInformation() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(),
            /* spatializerChannelMasks= */ ImmutableList.of());

    assertThat(IamfUtil.getOutputChannelMaskForCurrentConfiguration(audioCapabilities))
        .isEqualTo(AudioFormat.CHANNEL_OUT_STEREO);
  }

  @Test
  public void getOutputChannelMaskForCurrentConfiguration_picksFirstCompatibleSpeakerLayout() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_QUAD, // Not an IAMF layout.
                AudioFormat.CHANNEL_OUT_5POINT1, // Chosen.
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND // Never reached.
                ),
            /* spatializerChannelMasks= */ ImmutableList.of());

    assertThat(IamfUtil.getOutputChannelMaskForCurrentConfiguration(audioCapabilities))
        .isEqualTo(AudioFormat.CHANNEL_OUT_5POINT1);
  }

  @Test
  public void getOutputChannelMaskForCurrentConfiguration_prefersSpatializerOverSpeakerLayout() {
    AudioCapabilities audioCapabilities =
        createAudioCapabilities(
            /* speakerLayoutChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_5POINT1 // Not chosen.
                ),
            /* spatializerChannelMasks= */ ImmutableList.of(
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND // Chosen.
                ));

    assertThat(IamfUtil.getOutputChannelMaskForCurrentConfiguration(audioCapabilities))
        .isEqualTo(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND);
  }

  /** Helper method to create an {@link AudioCapabilities} instance with the given channel masks. */
  private AudioCapabilities createAudioCapabilities(
      List<Integer> speakerLayoutChannelMasks, List<Integer> spatializerChannelMasks) {
    return new AudioCapabilities(
        /* supportedEncodings= */ null, // Doesn't matter for these tests.
        /* maxChannelCount= */ 99, // Doesn't matter for these tests.
        speakerLayoutChannelMasks,
        spatializerChannelMasks);
  }
}
