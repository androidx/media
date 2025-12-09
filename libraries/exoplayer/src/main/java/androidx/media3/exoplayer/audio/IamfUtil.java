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

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.media.AudioFormat;
import androidx.annotation.IntDef;
import androidx.annotation.RestrictTo;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Constants and utils for configuring IAMF decoding. */
@RestrictTo(LIBRARY_GROUP)
public final class IamfUtil {

  private IamfUtil() {}

  /**
   * Represents the different output sound systems supported by IAMF.
   *
   * <p>NOTE: Values are defined by iamf_tools_api_types.h.
   */
  @Documented
  @Retention(SOURCE)
  @Target(ElementType.TYPE_USE)
  @IntDef({
    OUTPUT_LAYOUT_UNSET,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0
  })
  public @interface OutputLayout {}

  /** Value to be used to not specify an output layout. */
  public static final int OUTPUT_LAYOUT_UNSET = -1;

  /** ITU-R B.S. 2051-3 sound system A (0+2+0), commonly known as stereo. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0 = 0;

  /** ITU-R B.S. 2051-3 sound system B (0+5+0), commonly known as 5.1. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0 = 1;

  /** ITU-R B.S. 2051-3 sound system C (2+5+0), commonly known as 5.1.2. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0 = 2;

  /** ITU-R B.S. 2051-3 sound system D (4+5+0), commonly known as 5.1.4. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0 = 3;

  /** ITU-R B.S. 2051-3 sound system E (4+5+1). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1 = 4;

  /** ITU-R B.S. 2051-3 sound system F (3+7+0). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0 = 5;

  /** ITU-R B.S. 2051-3 sound system G (4+9+0). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0 = 6;

  /** ITU-R B.S. 2051-3 sound system H (9+10+3). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3 = 7;

  /** ITU-R B.S. 2051-3 sound system I (0+7+0), commonly known as 7.1. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0 = 8;

  /** ITU-R B.S. 2051-3 sound system J (4+7+0), commonly known as 7.1.4. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0 = 9;

  /** IAMF extension 7.1.2. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0 = 10;

  /** IAMF extension 3.1.2. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0 = 11;

  /** Mono. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0 = 12;

  /** IAMF Extension 9.1.6. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0 = 13;

  /**
   * Returns an IAMF output layout to try to match a given channel mask.
   *
   * <p>Where there are differences between the placement of speakers as defined by AudioFormat
   * channel masks and channel masks matching the IAMF/ITU standard, we permissively match both.
   *
   * @throws IllegalArgumentException if the channelMask has no equivalent.
   */
  public static @OutputLayout int getOutputLayoutForChannelMask(int channelMask) {
    switch (channelMask) {
      case AudioFormat.CHANNEL_OUT_MONO:
        return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0;
      case AudioFormat.CHANNEL_OUT_STEREO:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0;
      case AudioFormat.CHANNEL_OUT_5POINT1:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0;
      case AudioFormat.CHANNEL_OUT_5POINT1POINT2:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0;
      case AudioFormat.CHANNEL_OUT_5POINT1POINT4:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0;
      case AudioFormat.CHANNEL_OUT_7POINT1_SURROUND:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0;
      case AudioFormat.CHANNEL_OUT_7POINT1POINT2:
        return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0;
      case AudioFormat.CHANNEL_OUT_7POINT1POINT4:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0;
      case AudioFormat.CHANNEL_OUT_9POINT1POINT4:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0;
      case AudioFormat.CHANNEL_OUT_9POINT1POINT6:
        return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0;
      default:
        throw new IllegalArgumentException("Unsupported channel mask: " + channelMask);
    }
  }
}
