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
import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import androidx.annotation.IntDef;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.audio.AudioManagerCompat;
import androidx.media3.common.util.Log;
import com.google.common.collect.ImmutableSet;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

/** Constants and utils for configuring IAMF decoding. */
@RestrictTo(LIBRARY_GROUP)
public final class IamfUtil {

  private IamfUtil() {}

  private static final String TAG = "IamfUtil";

  /**
   * Used to indicate no requested Mix Presentation ID when creating a decoder.
   *
   * <p>When this value is used, the decoder will select a Mix Presentation ID based on the default
   * logic, including considering the requested OutputLayout, if provided.
   */
  public static final long REQUESTED_MIX_PRESENTATION_ID_UNSET = -1;

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
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0,
    OUTPUT_LAYOUT_BINAURAL
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

  /** Decoder rendered binaural audio. */
  public static final int OUTPUT_LAYOUT_BINAURAL = 14;

  // ===== Additionally defined Channel Masks =====
  // The following channel masks are supplements to those defined in {@link AudioFormat}, to allow
  // more permissive matching of channel masks to output layouts.  In most cases, there are small
  // differences between the channels used in AudioFormat and those used in IAMF or ITU spec.

  // The Android defined 5.1.2 and 7.1.2 use top *side* left/right which does
  // not match their ITU (ITU Sound System C) and IAMF equivalents. We will define the ITU/IAMF
  // versions here.
  @RequiresApi(32)
  private static final int CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0 =
      (AudioFormat.CHANNEL_OUT_5POINT1
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT);

  @RequiresApi(32)
  private static final int CHANNEL_OUT_IAMF_7POINT1POINT2 =
      (AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT);

  // {@link AudioFormat#CHANNEL_OUT_9POINT1POINT4} and {@link AudioFormat#CHANNEL_OUT_9POINT1POINT6}
  // use _WIDE_LEFT and _WIDE_RIGHT but the ITU spec for Sound System G says "left [/right] screen
  // edge" and the ITU spec for Sound System H, which is the basis for the IAMF 9.1.6 uses
  // LEFT/RIGHT_OF_CENTER so we'll allow permissive matching.
  @RequiresApi(32)
  private static final int CHANNEL_OUT_IAMF_9POINT1POINT4 =
      (AudioFormat.CHANNEL_OUT_7POINT1POINT4
          | AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
          | AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER);

  @RequiresApi(32)
  private static final int CHANNEL_OUT_IAMF_9POINT1POINT6 =
      (CHANNEL_OUT_IAMF_9POINT1POINT4
          | AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT);

  // Sound Systems E, F, and H are defined in ITU B.S. 2051-3 but are not defined by Android.
  // We can make them from combinations of speakers available in Android.  Note that H is equivalent
  // to AudioFormat.CHANNEL_OUT_22POINT2, which is hidden.
  @RequiresApi(32)
  private static final int CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1 =
      (AudioFormat.CHANNEL_OUT_5POINT1POINT4 | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER);

  @RequiresApi(32)
  private static final int ITU_2051_SOUND_SYSTEM_F_3_7_0 =
      (AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT
          | AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
          | AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2);

  @RequiresApi(32)
  public static final int CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3 =
      (AudioFormat.CHANNEL_OUT_7POINT1POINT4
          | AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
          | AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER
          | AudioFormat.CHANNEL_OUT_BACK_CENTER
          | AudioFormat.CHANNEL_OUT_TOP_CENTER
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER
          | AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
          | AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT
          | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT
          | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER
          | AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2);

  // This is just an IAMF layout that does not have an Android-defined version.
  @RequiresApi(32)
  private static final int CHANNEL_OUT_IAMF_3POINT1POINT2 =
      (AudioFormat.CHANNEL_OUT_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_FRONT_RIGHT
          | AudioFormat.CHANNEL_OUT_FRONT_CENTER
          | AudioFormat.CHANNEL_OUT_LOW_FREQUENCY
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
          | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT);

  // ===== End of additionally defined Channel Masks =====

  // All channel masks can be translated to IAMF output layouts.
  @VisibleForTesting
  public static final ImmutableSet<Integer> IAMF_SUPPORTED_CHANNEL_MASKS =
      (SDK_INT < 32)
          ? ImmutableSet.of()
          : ImmutableSet.of(
              // The same order as appears in getOutputLayoutForChannelMask.
              AudioFormat.CHANNEL_OUT_STEREO,
              AudioFormat.CHANNEL_OUT_5POINT1,
              AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
              AudioFormat.CHANNEL_OUT_MONO,
              AudioFormat.CHANNEL_OUT_5POINT1POINT2,
              CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0,
              AudioFormat.CHANNEL_OUT_5POINT1POINT4,
              CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1,
              ITU_2051_SOUND_SYSTEM_F_3_7_0,
              AudioFormat.CHANNEL_OUT_9POINT1POINT4,
              CHANNEL_OUT_IAMF_9POINT1POINT4,
              CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3,
              AudioFormat.CHANNEL_OUT_7POINT1POINT4,
              AudioFormat.CHANNEL_OUT_7POINT1POINT2,
              CHANNEL_OUT_IAMF_7POINT1POINT2,
              CHANNEL_OUT_IAMF_3POINT1POINT2,
              AudioFormat.CHANNEL_OUT_9POINT1POINT6,
              CHANNEL_OUT_IAMF_9POINT1POINT6);

  /**
   * Returns an IAMF output layout to try to match a given channel mask.
   *
   * <p>Where there are differences between the placement of speakers as defined by AudioFormat
   * channel masks and channel masks matching the IAMF/ITU standard, we permissively match both.
   *
   * @throws IllegalArgumentException if the channelMask has no matching IAMF output layout.
   */
  public static @OutputLayout int getOutputLayoutForChannelMask(int channelMask) {
    switch (channelMask) {
      case AudioFormat.CHANNEL_OUT_STEREO:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0;

      case AudioFormat.CHANNEL_OUT_5POINT1:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0;

      case AudioFormat.CHANNEL_OUT_7POINT1_SURROUND:
        return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0;

      case AudioFormat.CHANNEL_OUT_MONO:
        return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0;

      default:
        // Intentionally unhandled to check other cases if available.
        break;
    }
    if (SDK_INT >= 32) {
      switch (channelMask) {
        case AudioFormat.CHANNEL_OUT_5POINT1POINT2:
        case CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0;

        case AudioFormat.CHANNEL_OUT_5POINT1POINT4:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0;

        case CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1;

        case ITU_2051_SOUND_SYSTEM_F_3_7_0:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0;

        case AudioFormat.CHANNEL_OUT_9POINT1POINT4:
        case CHANNEL_OUT_IAMF_9POINT1POINT4:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0;

        case CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3;

        case AudioFormat.CHANNEL_OUT_7POINT1POINT4:
          return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0;

        case AudioFormat.CHANNEL_OUT_7POINT1POINT2:
        case CHANNEL_OUT_IAMF_7POINT1POINT2:
          return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0;

        case CHANNEL_OUT_IAMF_3POINT1POINT2:
          return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0;

        case AudioFormat.CHANNEL_OUT_9POINT1POINT6:
        case CHANNEL_OUT_IAMF_9POINT1POINT6:
          return OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0;

        default:
          // Intentionally unhandled to throw.
          break;
      }
    }
    throw new IllegalArgumentException("Unsupported channel mask: " + channelMask);
  }

  /**
   * Returns the Android channel mask that most closely matches the given IAMF output layout.
   *
   * <p>Some AudioFormat-defined channel masks do not exactly match IAMF/ITU spec. Where there are
   * differences, this method returns the more exact IAMF version.
   *
   * @throws IllegalArgumentException for invalid values of OutputLayout or OUTPUT_LAYOUT_UNSET.
   */
  public static int getChannelMaskForOutputLayout(@OutputLayout int outputLayout) {
    switch (outputLayout) {
      case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0:
        return AudioFormat.CHANNEL_OUT_STEREO;

      case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0:
        return AudioFormat.CHANNEL_OUT_5POINT1;

      case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0:
        return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;

      case OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0:
        return AudioFormat.CHANNEL_OUT_MONO;

      default:
        // Intentionally unhandled to check other cases if available.
        break;
    }
    if (SDK_INT >= 32) {
      switch (outputLayout) {
        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0:
          return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_C_2_5_0;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0:
          return AudioFormat.CHANNEL_OUT_5POINT1POINT4;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1:
          return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_E_4_5_1;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0:
          return ITU_2051_SOUND_SYSTEM_F_3_7_0;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0:
          return CHANNEL_OUT_IAMF_9POINT1POINT4;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3:
          return CHANNEL_OUT_ITU_2051_SOUND_SYSTEM_H_9_10_3;

        case OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0:
          return AudioFormat.CHANNEL_OUT_7POINT1POINT4;

        case OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0:
          return CHANNEL_OUT_IAMF_7POINT1POINT2;

        case OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0:
          return CHANNEL_OUT_IAMF_3POINT1POINT2;

        case OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0:
          return CHANNEL_OUT_IAMF_9POINT1POINT6;

        default:
          // Intentionally unhandled to throw.
          break;
      }
    }
    throw new IllegalArgumentException("Unsupported output layout: " + outputLayout);
  }

  /**
   * Returns an appropriate output layout for the current audio output configuration.
   *
   * @param context The {@link Context} to use for querying the current audio output configuration.
   * @param useIntegratedBinauralRenderer When it seems the user is using headphones (based on the
   *     {@link Context}), if {@code useIntegratedBinauralRenderer} is {@code true}, this method
   *     will return {@link OUTPUT_LAYOUT_BINAURAL} to get binaural audio directly from the decoder,
   *     otherwise, it will return an output layout appropriate for the {@link
   *     android.media.Spatializer}.
   */
  public static @OutputLayout int getOutputLayoutForCurrentConfiguration(
      Context context, boolean useIntegratedBinauralRenderer) {
    int spatializerChannelMask = getSpatializerChannelMaskIfSpatializationSupported(context);
    if (spatializerChannelMask != AudioFormat.CHANNEL_INVALID) {
      // If Spatializer enabled and available, we will use that as a signal that the user is likely
      // using headphones and wants spatial audio, but we will use decoder built-in binaural.
      if (useIntegratedBinauralRenderer) {
        // Directly request binaural audio from the decoder.
        return OUTPUT_LAYOUT_BINAURAL;
      }
      return getOutputLayoutForChannelMask(spatializerChannelMask);
    }

    return OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0; // Default to stereo.
    // TODO(b/392950453): Define other branches for other device types like HDMI, built-in, etc.
  }

  /**
   * Returns an appropriate channel mask for the current audio output configuration.
   *
   * <p>This is used to set the channel count and channel mask when using the framework Codec2
   * software decoder, which takes a ChannelMask to indicate the output layout and does not
   * currently support direct binaural output from the decoder.
   */
  public static int getOutputChannelMaskForCurrentConfiguration(Context context) {
    int spatializerChannelMask = getSpatializerChannelMaskIfSpatializationSupported(context);
    if (spatializerChannelMask != AudioFormat.CHANNEL_INVALID) {
      return spatializerChannelMask;
    }

    return AudioFormat.CHANNEL_OUT_STEREO; // Default to stereo.
    // TODO(b/392950453): Define shared logic for other branches for other device types, as above.
  }

  /** Returns the first ChannelMask that the Spatializer (if usable) inherently supports. */
  private static int getSpatializerChannelMaskIfSpatializationSupported(Context context) {
    // Spatializer is only available on API 32 and above.
    if (SDK_INT < 32) {
      Log.w(TAG, "Spatializer is not available on API < 32.");
      return AudioFormat.CHANNEL_INVALID;
    }
    AudioManager audioManager = AudioManagerCompat.getAudioManager(context);
    if (audioManager == null) {
      Log.w(TAG, "Audio Manager is null.");
      return AudioFormat.CHANNEL_INVALID;
    }
    Spatializer spatializer = audioManager.getSpatializer();
    boolean canSpatialize =
        spatializer.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            && spatializer.isAvailable()
            && spatializer.isEnabled();
    if (!canSpatialize) {
      return AudioFormat.CHANNEL_INVALID;
    }
    if (SDK_INT >= 36) {
      // Starting in 36, we can query the Spatializer for its inherently supported layouts, that is
      // to say, the highest channel-count layouts which the Spatializer is able to use as input
      // without downsampling.
      List<Integer> supportedChannelMasks = spatializer.getSpatializedChannelMasks();
      for (int channelMask : supportedChannelMasks) {
        if (IAMF_SUPPORTED_CHANNEL_MASKS.contains(channelMask)) {
          return channelMask;
        }
      }
      Log.w(TAG, "No Spatializer channel mask is supported by IAMF Decoder.");
    }
    // If we are not in 36 or greater, we assume 5.1 is the inherently supported layout.
    return AudioFormat.CHANNEL_OUT_5POINT1;
  }
}
