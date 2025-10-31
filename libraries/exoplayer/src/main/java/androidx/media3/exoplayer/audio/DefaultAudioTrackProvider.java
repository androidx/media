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
package androidx.media3.exoplayer.audio;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * @deprecated Use {@link AudioTrackAudioOutputProvider} instead.
 */
@Deprecated
@UnstableApi
public class DefaultAudioTrackProvider implements DefaultAudioSink.AudioTrackProvider {

  @Override
  public final AudioTrack getAudioTrack(
      AudioSink.AudioTrackConfig audioTrackConfig,
      AudioAttributes audioAttributes,
      int audioSessionId,
      @Nullable Context context) {
    AudioFormat audioFormat =
        Util.getAudioFormat(
            audioTrackConfig.sampleRate, audioTrackConfig.channelConfig, audioTrackConfig.encoding);
    android.media.AudioAttributes audioTrackAttributes =
        getAudioTrackAttributes(audioAttributes, audioTrackConfig.tunneling);
    AudioTrack.Builder audioTrackBuilder =
        new AudioTrack.Builder()
            .setAudioAttributes(audioTrackAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(audioTrackConfig.bufferSize)
            .setSessionId(audioSessionId);
    if (SDK_INT >= 29) {
      setOffloadedPlaybackV29(audioTrackBuilder, audioTrackConfig.offload);
    }
    if (SDK_INT >= 34 && context != null) {
      audioTrackBuilder.setContext(context);
    }
    return customizeAudioTrackBuilder(audioTrackBuilder).build();
  }

  @RequiresApi(29)
  private void setOffloadedPlaybackV29(AudioTrack.Builder audioTrackBuilder, boolean isOffloaded) {
    audioTrackBuilder.setOffloadedPlayback(isOffloaded);
  }

  /**
   * Optionally customize {@link AudioTrack.Builder} with other parameters.
   *
   * @param audioTrackBuilder The {@link AudioTrack.Builder} on which to set the attributes.
   * @return The same {@link AudioTrack.Builder} instance provided.
   */
  @CanIgnoreReturnValue
  protected AudioTrack.Builder customizeAudioTrackBuilder(AudioTrack.Builder audioTrackBuilder) {
    return audioTrackBuilder;
  }

  private android.media.AudioAttributes getAudioTrackAttributes(
      AudioAttributes audioAttributes, boolean tunneling) {
    if (tunneling) {
      return getAudioTrackTunnelingAttributes();
    } else {
      return audioAttributes.getPlatformAudioAttributes();
    }
  }

  private android.media.AudioAttributes getAudioTrackTunnelingAttributes() {
    return new android.media.AudioAttributes.Builder()
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .setFlags(android.media.AudioAttributes.FLAG_HW_AV_SYNC)
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .build();
  }
}
