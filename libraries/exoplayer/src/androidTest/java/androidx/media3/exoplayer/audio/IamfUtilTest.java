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

import android.content.Context;
import android.media.AudioFormat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
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
  public void getOutputLayoutForCurrentConfiguration_returnsDefaultWithoutSpatializer() {
    Context context = ApplicationProvider.getApplicationContext();
    boolean useIntegratedBinauralRenderer = true; // Does not matter, no Spatializer.

    assertThat(
            IamfUtil.getOutputLayoutForCurrentConfiguration(context, useIntegratedBinauralRenderer))
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0);
  }

  @Test
  public void getOutputChannelMaskForCurrentConfiguration_returnsDefaultWithoutSpatializer() {
    Context context = ApplicationProvider.getApplicationContext();

    assertThat(IamfUtil.getOutputChannelMaskForCurrentConfiguration(context))
        .isEqualTo(AudioFormat.CHANNEL_OUT_STEREO);
  }

  @Test
  public void iamfSupportedLayouts_allAreConvertableToLayout() {
    for (int channelMask : IamfUtil.IAMF_SUPPORTED_CHANNEL_MASKS) {
      assertThat(IamfUtil.getOutputLayoutForChannelMask(channelMask))
          .isNotEqualTo(IamfUtil.OUTPUT_LAYOUT_UNSET);
    }
  }
}
