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
package androidx.media3.exoplayer.audio;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import java.util.Objects;

/**
 * An {@link AudioOutputProvider} that plays Dolby TrueHD through a {@link TrueHdMatAudioOutput},
 * passing it through as IEC 61937 MAT bursts, and delegates all other formats to a wrapped {@link
 * AudioOutputProvider}.
 *
 * <p>Tunneled playback is passed on to the {@link TrueHdMatAudioOutput}.
 */
@UnstableApi
public final class TrueHdMatAudioOutputProvider extends ForwardingAudioOutputProvider {

  // Default IEC 61937 track buffer size, 12 MAT frames (240 ms for 48 kHz based streams).
  private static final int DEFAULT_BUFFER_SIZE = 12 * TrueHdMatPacker.MAT_FRAME_SIZE;
  // This is a hack necessary because I don't have access to the FormatConfig in getOutputConfig()
  // This is overridden with the correct value in TrueHdMatAudioOutput
  static final int CHANNEL_MASK_IEC61937 = -1;

  @Nullable private final AudioManager audioManager;
  private Clock clock;

  /**
   * Creates an instance that delegates formats other than TrueHD to an {@link
   * AudioTrackAudioOutputProvider}.
   *
   * @param context The {@link Context}.
   */
  public TrueHdMatAudioOutputProvider(Context context) {
    this(context, new AudioTrackAudioOutputProvider.Builder(context).build());
  }

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   * @param audioOutputProvider The {@link AudioOutputProvider} for formats other than TrueHD.
   */
  public TrueHdMatAudioOutputProvider(Context context, AudioOutputProvider audioOutputProvider) {
    super(audioOutputProvider);
    audioManager =
        (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    clock = Clock.DEFAULT;
  }

  @Override
  public FormatSupport getFormatSupport(FormatConfig formatConfig) {
    if (isTrueHd(formatConfig.format) && isTrueHdIec61937Supported(formatConfig)) {
      return new FormatSupport.Builder().setFormatSupportLevel(FORMAT_SUPPORTED_DIRECTLY).build();
    }
    return super.getFormatSupport(formatConfig);
  }

  @Override
  public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
    Format format = formatConfig.format;
    if (isTrueHd(format) && isTrueHdIec61937Supported(formatConfig)) {
      return new OutputConfig.Builder()
          .setEncoding(C.ENCODING_DOLBY_TRUEHD)
          .setSampleRate(format.sampleRate)
          .setChannelMask(CHANNEL_MASK_IEC61937)
          .setBufferSize(
              formatConfig.preferredBufferSize != C.LENGTH_UNSET
                  ? formatConfig.preferredBufferSize
                  : DEFAULT_BUFFER_SIZE)
          .setAudioSessionId(formatConfig.audioSessionId)
          .setAudioAttributes(formatConfig.audioAttributes)
          .setVirtualDeviceId(formatConfig.virtualDeviceId)
          .setIsTunneling(formatConfig.enableTunneling)
          .setIsOffload(false)
          .setUsePlaybackParameters(false)
          .build();
    }
    return super.getOutputConfig(formatConfig);
  }

  @Override
  public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
    if (config.encoding == C.ENCODING_DOLBY_TRUEHD && SDK_INT >= 24 && config.channelMask == CHANNEL_MASK_IEC61937) {
      return TrueHdMatAudioOutput.create(config, /* capabilityChangeListener= */ null, clock);
    }
    return super.getAudioOutput(config);
  }

  @Override
  public void setClock(Clock clock) {
    this.clock = clock;
    super.setClock(clock);
  }

  private boolean isTrueHdIec61937Supported(FormatConfig formatConfig) {
    if (audioManager != null) {
      if (SDK_INT >= 33) {
        // This is a likely point of failure in the future.  As far as I know, no device currently
        // exists that meets true criteria.
        List<AudioProfile> profiles =
            audioManager.getDirectProfilesForAttributes(formatConfig.audioAttributes.getPlatformAudioAttributes());
        for (AudioProfile profile : profiles) {
          if (profile.getFormat() == AudioFormat.ENCODING_DOLBY_TRUEHD &&
              profile.getEncapsulationType() == AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937) {
            return true;
          }
        }
        return false;
      }
      if (SDK_INT >= 24) {
        AudioDeviceInfo[] infos = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (infos != null) {
          boolean trueHd = false;
          boolean iec61937 = false;
          for (AudioDeviceInfo info : infos) {
            for (int encoding : info.getEncodings()) {
              switch (encoding) {
                case AudioFormat.ENCODING_DOLBY_TRUEHD:
                  trueHd = true;
                  break;
                case AudioFormat.ENCODING_IEC61937:
                  iec61937 = true;
                  break;
              }
            }
            if (trueHd && iec61937) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static boolean isTrueHd(Format format) {
    return Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_TRUEHD);
  }
}
