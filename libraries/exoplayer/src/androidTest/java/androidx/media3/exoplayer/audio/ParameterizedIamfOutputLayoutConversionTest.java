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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.truth.Truth.assertThat;

import android.media.AudioFormat;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Parameterized testing of OutputLayout conversion. */
@RunWith(Parameterized.class)
public final class ParameterizedIamfOutputLayoutConversionTest {

  @Parameter(0)
  public @IamfUtil.OutputLayout int startingOutputLayout;

  @Parameter(1)
  public int expectedNumberChannels;

  @Parameters
  public static List<Object[]> data() {
    if (SDK_INT < 32) {
      return Arrays.asList(
          new Object[][] {
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0, 2},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0, 6},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0, 8},
            {IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0, 1},
          });
    } else {
      return Arrays.asList(
          new Object[][] {
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0, 2},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0, 6},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0, 8},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0, 10},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1, 11},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0, 12},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0, 14},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3, 24},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0, 8},
            {IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0, 12},
            {IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0, 10},
            {IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0, 6},
            {IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0, 1},
            {IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0, 16},
          });
    }
  }

  @Test
  public void roundtripConversion() {
    int channelMask = IamfUtil.getChannelMaskForOutputLayout(startingOutputLayout);

    assertThat(getChannelCountForChannelMask(channelMask)).isEqualTo(expectedNumberChannels);
    assertThat(IamfUtil.getOutputLayoutForChannelMask(channelMask)).isEqualTo(startingOutputLayout);
  }

  /** Helper to get channel count from a mask. */
  private static int getChannelCountForChannelMask(int channelMask) {
    return new AudioFormat.Builder().setChannelMask(channelMask).build().getChannelCount();
  }
}
