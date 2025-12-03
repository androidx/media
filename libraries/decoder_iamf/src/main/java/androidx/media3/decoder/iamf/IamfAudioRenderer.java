/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioManagerCompat;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Objects;

/** Decodes and renders audio using the native IAMF decoder. */
@UnstableApi
public class IamfAudioRenderer extends DecoderAudioRenderer<IamfDecoder> {

  /** Builds {@link IamfAudioRenderer} instances. */
  public static final class Builder {
    private final Context context;
    private final AudioSink audioSink;
    @Nullable private Handler eventHandler;
    @Nullable private AudioRendererEventListener eventListener;
    @Nullable private Integer requestedChannelMask;
    private @IamfDecoder.OutputSampleType int outputSampleType;
    private @IamfDecoder.ChannelOrdering int channelOrdering;

    /**
     * Creates a builder.
     *
     * @param context The {@link Context}.
     * @param audioSink The sink to which audio will be output.
     */
    public Builder(Context context, AudioSink audioSink) {
      this.context = context;
      this.audioSink = audioSink;
      this.outputSampleType = IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN;
      this.channelOrdering = IamfDecoder.CHANNEL_ORDERING_ANDROID_ORDERING;
    }

    /** Sets an event handler and listener to use when delivering events. */
    @CanIgnoreReturnValue
    public Builder setEventHandlerAndListener(
        Handler eventHandler, AudioRendererEventListener eventListener) {
      this.eventListener = eventListener;
      this.eventHandler = eventHandler;
      return this;
    }

    /**
     * Sets the desired {@link IamfDecoder.OutputSampleType}. The default value is {@link
     * IamfDecoder#OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN}.
     */
    @CanIgnoreReturnValue
    public Builder setOutputSampleType(@IamfDecoder.OutputSampleType int outputSampleType) {
      this.outputSampleType = outputSampleType;
      return this;
    }

    /**
     * Sets the desired {@link IamfDecoder.ChannelOrdering}. The default value is {@link
     * IamfDecoder#CHANNEL_ORDERING_ANDROID_ORDERING}.
     */
    @CanIgnoreReturnValue
    public Builder setChannelOrdering(@IamfDecoder.ChannelOrdering int channelOrdering) {
      this.channelOrdering = channelOrdering;
      return this;
    }

    /**
     * Sets a requested channel mask to target for the output audio.
     *
     * <p>If not set, the renderer will use its own logic to decide what channel mask to request.
     * The decoder might not be able to produce the channel mask requested, so the actual channel
     * mask used might be different. To get the actual channel mask used by the decoder, use {@link
     * IamfDecoder#getSelectedOutputLayout()}.
     */
    @CanIgnoreReturnValue
    public Builder setRequestedChannelMask(int requestedChannelMask) {
      this.requestedChannelMask = requestedChannelMask;
      return this;
    }

    /** Builds an {@link IamfAudioRenderer}. */
    public IamfAudioRenderer build() {
      return new IamfAudioRenderer(this);
    }
  }

  private static final String TAG = "IamfAudioRenderer";
  // Determined by what ChannelMasks can be translated to what IAMF output layouts.
  private static final ImmutableSet<Integer> IAMF_SUPPORTED_LAYOUTS =
      (SDK_INT < 32)
          ? ImmutableSet.of()
          : ImmutableSet.of(
              AudioFormat.CHANNEL_OUT_MONO,
              AudioFormat.CHANNEL_OUT_STEREO,
              AudioFormat.CHANNEL_OUT_5POINT1,
              AudioFormat.CHANNEL_OUT_5POINT1POINT2,
              AudioFormat.CHANNEL_OUT_5POINT1POINT4,
              AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
              AudioFormat.CHANNEL_OUT_7POINT1POINT2,
              AudioFormat.CHANNEL_OUT_7POINT1POINT4,
              AudioFormat.CHANNEL_OUT_9POINT1POINT4,
              AudioFormat.CHANNEL_OUT_9POINT1POINT6);

  private final Context context;
  private final @IamfDecoder.OutputSampleType int outputSampleType;
  private final @IamfDecoder.ChannelOrdering int channelOrdering;
  @Nullable private final Integer requestedChannelMask;
  private final int currentChannelMask;

  private IamfAudioRenderer(Builder builder) {
    super(builder.eventHandler, builder.eventListener, builder.audioSink);
    this.context = builder.context;
    this.outputSampleType = builder.outputSampleType;
    this.channelOrdering = builder.channelOrdering;
    this.requestedChannelMask = builder.requestedChannelMask;
    this.currentChannelMask = determineOutputChannelMask(context, requestedChannelMask);
  }

  @Override
  protected int supportsFormatInternal(Format format) {
    return !IamfLibrary.isAvailable()
            || !Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_IAMF)
        ? C.FORMAT_UNSUPPORTED_TYPE
        : C.FORMAT_HANDLED;
  }

  @Override
  protected IamfDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws DecoderException {
    TraceUtil.beginSection("createIamfDecoder");
    IamfDecoder decoder =
        new IamfDecoder(
            format.initializationData,
            getOutputLayoutForChannelMask(currentChannelMask),
            IamfDecoder.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            outputSampleType,
            channelOrdering);
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected Format getOutputFormat(IamfDecoder decoder) {
    try {
      @IamfDecoder.OutputSampleType int sampleType = decoder.getOutputSampleType();
      int channelCount = decoder.getNumberOfOutputChannels();
      int sampleRate = decoder.getSampleRate();
      int encoding = getEncodingForSampleType(sampleType);
      return Util.getPcmFormat(encoding, channelCount, sampleRate);
    } catch (IamfDecoderException e) {
      Log.e(TAG, "Failed to get output sample type from decoder", e);
      return new Format.Builder().build();
    }
  }

  @Override
  public String getName() {
    return "IamfAudioRenderer";
  }

  private static int getEncodingForSampleType(@IamfDecoder.OutputSampleType int sampleType) {
    switch (sampleType) {
      case IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN:
        return AudioFormat.ENCODING_PCM_16BIT;
      case IamfDecoder.OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN:
        return AudioFormat.ENCODING_PCM_FLOAT;
      default:
        throw new IllegalArgumentException("Unsupported sample type: " + sampleType);
    }
  }

  private static @IamfDecoder.OutputLayout int getOutputLayoutForChannelMask(int channelMask) {
    switch (channelMask) {
      case AudioFormat.CHANNEL_OUT_MONO:
        return IamfDecoder.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0;
      case AudioFormat.CHANNEL_OUT_STEREO:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0;
      case AudioFormat.CHANNEL_OUT_5POINT1:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0;
      case AudioFormat.CHANNEL_OUT_5POINT1POINT2:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0;
      case AudioFormat.CHANNEL_OUT_5POINT1POINT4:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0;
      case AudioFormat.CHANNEL_OUT_7POINT1_SURROUND:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0;
      case AudioFormat.CHANNEL_OUT_7POINT1POINT2:
        return IamfDecoder.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0;
      case AudioFormat.CHANNEL_OUT_7POINT1POINT4:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0;
      case AudioFormat.CHANNEL_OUT_9POINT1POINT4:
        return IamfDecoder.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0;
      case AudioFormat.CHANNEL_OUT_9POINT1POINT6:
        return IamfDecoder.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0;
      default:
        throw new IllegalArgumentException("Unsupported channel mask: " + channelMask);
    }
  }

  private static int determineOutputChannelMask(
      Context context, @Nullable Integer requestedChannelMask) {
    // If the user has requested a specific channel mask, use that.
    if (requestedChannelMask != null) {
      return requestedChannelMask;
    }

    List<Integer> spatializerChannelMasks =
        getSpatializerChannelMasksIfSpatializationSupported(context);
    if (!spatializerChannelMasks.isEmpty()) {
      for (Integer channelMask : spatializerChannelMasks) {
        if (IAMF_SUPPORTED_LAYOUTS.contains(channelMask)) {
          return channelMask;
        }
      }
    }

    return AudioFormat.CHANNEL_OUT_STEREO;
    // TODO(b/392950453): Define other branches for other device types like HDMI, built-in, etc.
  }

  private static List<Integer> getSpatializerChannelMasksIfSpatializationSupported(
      Context context) {
    Log.i(TAG, "Checking for Spatializer");
    // Spatializer is only available on API 32 and above.
    if (SDK_INT < 32) {
      return ImmutableList.of();
    }
    AudioManager audioManager = AudioManagerCompat.getAudioManager(context);
    if (audioManager == null) {
      Log.i(TAG, "Audio Manager is null");
      return ImmutableList.of();
    }
    Spatializer spatializer = audioManager.getSpatializer();
    boolean canSpatialize =
        spatializer.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            && spatializer.isAvailable()
            && spatializer.isEnabled();
    Log.d(TAG, "canSpatialize: " + canSpatialize);
    Log.d(TAG, "spatializer.getImmersiveAudioLevel(): " + spatializer.getImmersiveAudioLevel());
    Log.d(TAG, "spatializer.isAvailable(): " + spatializer.isAvailable());
    Log.d(TAG, "spatializer.isEnabled(): " + spatializer.isEnabled());
    if (!canSpatialize) {
      return ImmutableList.of();
    }
    if (SDK_INT >= 36) {
      // Starting in 36, we can query the Spatializer for the supported layouts.
      Log.d(
          TAG,
          "Spatializer reports supporting ChannelMasks: "
              + spatializer.getSpatializedChannelMasks());
      return spatializer.getSpatializedChannelMasks();
    }
    // If we are not in 36 or greater, assume 5.1.
    return ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1);
  }
}
