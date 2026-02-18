/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.util;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioManagerCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;

/**
 * SpatializerWrapper Wraps the {@link Spatializer} in order to encapsulate its APIs within an inner
 * class, to avoid runtime linking on devices with {@code API < 32}.
 *
 * <p>Also centralizes logic such as for special cases of TVs and audio formats without fixed output
 * channel counts and for default channel masks.
 */
@RequiresApi(32)
@UnstableApi
public class SpatializerWrapper {

  @Nullable private final Spatializer spatializer;
  private final boolean spatializationSupported;
  @Nullable private final Handler handler;
  @Nullable private final Spatializer.OnSpatializerStateChangedListener listener;

  /**
   * Creates an instance of the Spatializer Wrapper and registers it to listen for changes.
   *
   * @param context A context for obtaining the {@link Spatializer}.
   * @param spatializerChangedCallback A callback to run when the {@link Spatializer} state changes.
   * @param deviceIsTv Optional. Whether the device is a TV. If true, the {@link Spatializer} is not
   *     used.
   */
  public SpatializerWrapper(
      @Nullable Context context,
      @Nullable Runnable spatializerChangedCallback,
      @Nullable Boolean deviceIsTv) {
    @Nullable
    AudioManager audioManager =
        context == null ? null : AudioManagerCompat.getAudioManager(context);
    if (audioManager == null || (deviceIsTv != null && deviceIsTv)) {
      spatializer = null;
      spatializationSupported = false;
      handler = null;
      listener = null;
      return;
    }
    this.spatializer = audioManager.getSpatializer();
    this.spatializationSupported =
        spatializer.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    if (spatializerChangedCallback == null) {
      handler = null;
      listener = null;
    } else {
      this.handler = new Handler(checkNotNull(Looper.myLooper()));
      this.listener =
          new Spatializer.OnSpatializerStateChangedListener() {
            @Override
            public void onSpatializerEnabledChanged(Spatializer spatializer, boolean enabled) {
              spatializerChangedCallback.run();
            }

            @Override
            public void onSpatializerAvailableChanged(Spatializer spatializer, boolean available) {
              spatializerChangedCallback.run();
            }
          };
      spatializer.addOnSpatializerStateChangedListener(handler::post, listener);
    }
  }

  /**
   * A convenience method for the combined checks to see if spatialization is actually available.
   */
  public boolean isSupportedAvailableAndEnabled() {
    return spatializer != null && spatializationSupported && isAvailable() && isEnabled();
  }

  /**
   * Returns true if the immersive level is not {@link
   * Spatializer#SPATIALIZER_IMMERSIVE_LEVEL_NONE}.
   */
  public boolean isSpatializationSupported() {
    return spatializationSupported;
  }

  /**
   * Returns true if the {@link Spatializer} is available.
   *
   * <p>This is delegated to {@link Spatializer#isAvailable()}.
   */
  public boolean isAvailable() {
    return spatializer != null && spatializer.isAvailable();
  }

  /**
   * Returns true if the {@link Spatializer} is enabled.
   *
   * <p>This is delegated to {@link Spatializer#isEnabled()}.
   */
  public boolean isEnabled() {
    return spatializer != null && spatializer.isEnabled();
  }

  /**
   * Returns true if the given {@link AudioAttributes} and {@link Format} can be spatialized.
   *
   * <p>Includes special logic for immersive audio formats.
   */
  public boolean canBeSpatialized(AudioAttributes audioAttributes, Format format) {
    if (!isSupportedAvailableAndEnabled()) {
      return false;
    }
    int linearChannelCount;
    if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_E_AC3_JOC)) {
      // For E-AC3 JOC, the format is object based. When the channel count is 16, this maps to 12
      // linear channels and the rest are used for objects. See
      // https://github.com/google/ExoPlayer/pull/10322#discussion_r895265881
      linearChannelCount = format.channelCount == 16 ? 12 : format.channelCount;
    } else if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_IAMF)) {
      // IAMF with no channel count specified, assume 5.1 channels. This depends on
      // IamfDecoder.SPATIALIZED_OUTPUT_LAYOUT being set to AudioFormat.CHANNEL_OUT_5POINT1. Any
      // changes to that constant will require updates to this logic.
      linearChannelCount = format.channelCount == Format.NO_VALUE ? 6 : format.channelCount;
    } else if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_AC4)) {
      // For AC-4 level 3 or level 4, the format may be object based. When the channel count is
      // 18 (level 3 17.1 OBI) or 21 (level 4 20.1 OBI), it is mapped to 24 linear channels (some
      // channels are used for metadata transfer).
      linearChannelCount =
          (format.channelCount == 18 || format.channelCount == 21) ? 24 : format.channelCount;
    } else {
      linearChannelCount = format.channelCount;
    }

    int channelConfig = Util.getAudioTrackChannelConfig(linearChannelCount);
    if (channelConfig == AudioFormat.CHANNEL_INVALID) {
      return false;
    }
    AudioFormat.Builder builder =
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelConfig);
    if (format.sampleRate != Format.NO_VALUE) {
      builder.setSampleRate(format.sampleRate);
    }
    return checkNotNull(spatializer)
        .canBeSpatialized(audioAttributes.getPlatformAudioAttributes(), builder.build());
  }

  /**
   * Returns the list of channel masks that are natively supported by the {@link Spatializer}.
   *
   * <p>On API 36+, this is delegated to the {@link Spatializer#getSpatializedChannelMasks()}. On
   * API 32-35, the default channel mask is 5.1.
   */
  public List<Integer> getSpatializedChannelMasks() {
    if (!isSupportedAvailableAndEnabled()) {
      return ImmutableList.of();
    }
    if (SDK_INT >= 36) {
      return checkNotNull(spatializer).getSpatializedChannelMasks();
    }
    return ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1);
  }

  public void release() {
    if (spatializer == null || listener == null || handler == null) {
      return;
    }
    spatializer.removeOnSpatializerStateChangedListener(listener);
    handler.removeCallbacksAndMessages(/* token= */ null);
  }
}
