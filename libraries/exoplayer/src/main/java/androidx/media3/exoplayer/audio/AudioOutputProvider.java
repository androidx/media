/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.AudioDeviceInfo;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Clock;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/** A provider for {@link AudioOutput} instances and for querying their support. */
/* package */ interface AudioOutputProvider {

  /** Listener for {@link AudioOutputProvider} events. */
  interface Listener {
    /**
     * Called when the return value of {@link AudioOutputProvider#getFormatSupport(FormatConfig)}
     * may have changed.
     */
    void onFormatSupportChanged();
  }

  /** Configuration for an audio format and its output preferences. */
  final class FormatConfig {

    /** The {@link Format} of the audio. */
    public final Format format;

    /** The {@link AudioAttributes} to use for playback. */
    public final AudioAttributes audioAttributes;

    /** The preferred {@link AudioDeviceInfo} for audio output. */
    @Nullable public final AudioDeviceInfo preferredDevice;

    /** Sets whether to enable high resolution PCM output with more than 16 bits. */
    public final boolean enableHighResolutionPcmOutput;

    /** Whether to control the playback speed using the audio output. */
    public final boolean enableAudioOutputPlaybackParameters;

    /** The {@link OffloadMode}. */
    public final @OffloadMode int offloadMode;

    /** The audio session ID, or {@link C#AUDIO_SESSION_ID_UNSET} if not set. */
    public final int audioSessionId;

    /** The virtual device ID, or {@link C#INDEX_UNSET} if not specified. */
    public final int virtualDeviceId;

    /** Whether tunneling is enabled. */
    public final boolean isTunneling;

    /**
     * The preferred buffer size in bytes, or {@link C#LENGTH_UNSET} if no preference is specified.
     */
    public final int preferredBufferSize;

    private FormatConfig(Builder builder) {
      this.format = builder.format;
      this.audioAttributes = builder.audioAttributes;
      this.preferredDevice = builder.preferredDevice;
      this.enableHighResolutionPcmOutput = builder.enableHighResolutionPcmOutput;
      this.enableAudioOutputPlaybackParameters = builder.enableAudioOutputPlaybackParameters;
      this.offloadMode = builder.offloadMode;
      this.audioSessionId = builder.audioSessionId;
      this.virtualDeviceId = builder.virtualDeviceId;
      this.isTunneling = builder.isTunneling;
      this.preferredBufferSize = builder.preferredBufferSize;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    /** Builder for {@link FormatConfig} instances. */
    public static final class Builder {
      private final Format format;
      private AudioAttributes audioAttributes;
      @Nullable private AudioDeviceInfo preferredDevice;
      private boolean enableHighResolutionPcmOutput;
      private boolean enableAudioOutputPlaybackParameters;
      private @OffloadMode int offloadMode;
      private int audioSessionId;
      private int virtualDeviceId;
      private boolean isTunneling;
      private int preferredBufferSize;

      /** Creates a new builder. */
      public Builder(Format format) {
        this.format = format;
        this.audioAttributes = AudioAttributes.DEFAULT;
        this.offloadMode = OFFLOAD_MODE_DISABLED;
        this.audioSessionId = C.AUDIO_SESSION_ID_UNSET;
        this.virtualDeviceId = C.INDEX_UNSET;
        this.isTunneling = false;
        this.preferredBufferSize = C.LENGTH_UNSET;
      }

      private Builder(FormatConfig config) {
        this.format = config.format;
        this.audioAttributes = config.audioAttributes;
        this.preferredDevice = config.preferredDevice;
        this.enableHighResolutionPcmOutput = config.enableHighResolutionPcmOutput;
        this.enableAudioOutputPlaybackParameters = config.enableAudioOutputPlaybackParameters;
        this.offloadMode = config.offloadMode;
        this.audioSessionId = config.audioSessionId;
        this.virtualDeviceId = config.virtualDeviceId;
        this.isTunneling = config.isTunneling;
        this.preferredBufferSize = config.preferredBufferSize;
      }

      /** Sets the {@link AudioAttributes}. */
      @CanIgnoreReturnValue
      public Builder setAudioAttributes(AudioAttributes audioAttributes) {
        this.audioAttributes = audioAttributes;
        return this;
      }

      /** Sets the preferred {@link AudioDeviceInfo}. */
      @CanIgnoreReturnValue
      public Builder setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
        this.preferredDevice = preferredDevice;
        return this;
      }

      /** Sets whether to enable high resolution PCM output with more than 16 bits. */
      @CanIgnoreReturnValue
      public Builder setEnableHighResolutionPcmOutput(boolean enableHighResolutionPcmOutput) {
        this.enableHighResolutionPcmOutput = enableHighResolutionPcmOutput;
        return this;
      }

      /**
       * Sets whether to control the playback parameters using the {@link AudioOutput}
       * implementation.
       */
      @CanIgnoreReturnValue
      public Builder setEnableAudioOutputPlaybackParameters(
          boolean enableAudioOutputPlaybackParams) {
        this.enableAudioOutputPlaybackParameters = enableAudioOutputPlaybackParams;
        return this;
      }

      /** Sets the {@link OffloadMode}. */
      @CanIgnoreReturnValue
      public Builder setOffloadMode(@OffloadMode int offloadMode) {
        this.offloadMode = offloadMode;
        return this;
      }

      /** Sets the audio session ID. The default value is {@link C#AUDIO_SESSION_ID_UNSET}. */
      @CanIgnoreReturnValue
      public Builder setAudioSessionId(int audioSessionId) {
        this.audioSessionId = audioSessionId;
        return this;
      }

      /**
       * Sets the virtual device ID, or {@link C#INDEX_UNSET} if not specified. The default value is
       * {@link C#INDEX_UNSET}.
       */
      @CanIgnoreReturnValue
      public Builder setVirtualDeviceId(int virtualDeviceId) {
        this.virtualDeviceId = virtualDeviceId;
        return this;
      }

      /** Sets whether tunneling is enabled. */
      @CanIgnoreReturnValue
      public Builder setIsTunneling(boolean isTunneling) {
        this.isTunneling = isTunneling;
        return this;
      }

      /**
       * Sets the preferred buffer size in bytes, or {@link C#LENGTH_UNSET} if no preference is
       * specified.
       */
      @CanIgnoreReturnValue
      public Builder setPreferredBufferSize(int preferredBufferSize) {
        this.preferredBufferSize = preferredBufferSize;
        return this;
      }

      /** Builds the {@link FormatConfig}. */
      public FormatConfig build() {
        return new FormatConfig(this);
      }
    }
  }

  /** Configuration for an {@link AudioOutput}. */
  final class OutputConfig {

    /** The {@link C.Encoding} of the audio data. */
    public final @C.Encoding int encoding;

    /** The sample rate of the audio data. */
    public final int sampleRate;

    /**
     * The channel configuration of the output. See {@code AudioFormat.CHANNEL_OUT_XXX} constants
     * like {@link android.media.AudioFormat#CHANNEL_OUT_5POINT1}.
     */
    public final int channelConfig;

    /** Whether tunneling is enabled for this output. */
    public final boolean isTunneling;

    /** Whether offload is enabled for this output. */
    public final boolean isOffload;

    /** The buffer size of the output in bytes. */
    public final int bufferSize;

    /** The {@link AudioAttributes} for the audio output. */
    public final AudioAttributes audioAttributes;

    /** The audio session ID. */
    public final int audioSessionId;

    /** The virtual device ID, or {@link C#INDEX_UNSET} if not specified. */
    public final int virtualDeviceId;

    /** Whether playback speed control is enabled for this output. */
    public final boolean usePlaybackParameters;

    /**
     * Whether gapless offload playback is enabled for this output via {@link
     * AudioOutput#setOffloadDelayPadding}.
     */
    public final boolean useOffloadGapless;

    private OutputConfig(Builder builder) {
      this.encoding = builder.encoding;
      this.sampleRate = builder.sampleRate;
      this.channelConfig = builder.channelConfig;
      this.isTunneling = builder.isTunneling;
      this.isOffload = builder.isOffload;
      this.bufferSize = builder.bufferSize;
      this.audioAttributes = builder.audioAttributes;
      this.audioSessionId = builder.audioSessionId;
      this.virtualDeviceId = builder.virtualDeviceId;
      this.usePlaybackParameters = builder.usePlaybackParameters;
      this.useOffloadGapless = builder.useOffloadGapless;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      OutputConfig that = (OutputConfig) o;
      return encoding == that.encoding
          && sampleRate == that.sampleRate
          && channelConfig == that.channelConfig
          && isTunneling == that.isTunneling
          && isOffload == that.isOffload
          && bufferSize == that.bufferSize
          && audioSessionId == that.audioSessionId
          && virtualDeviceId == that.virtualDeviceId
          && usePlaybackParameters == that.usePlaybackParameters
          && useOffloadGapless == that.useOffloadGapless
          && audioAttributes.equals(that.audioAttributes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          encoding,
          sampleRate,
          channelConfig,
          isTunneling,
          isOffload,
          bufferSize,
          audioAttributes,
          audioSessionId,
          virtualDeviceId,
          useOffloadGapless,
          usePlaybackParameters);
    }

    /** Builder for {@link OutputConfig} instances. */
    public static final class Builder {
      private @C.Encoding int encoding;
      private int sampleRate;
      private int channelConfig;
      private boolean isTunneling;
      private boolean isOffload;
      private int bufferSize;
      private AudioAttributes audioAttributes;
      private int audioSessionId;
      private int virtualDeviceId;
      private boolean usePlaybackParameters;
      private boolean useOffloadGapless;

      /** Creates a new instance. */
      public Builder() {
        audioAttributes = AudioAttributes.DEFAULT;
        audioSessionId = C.AUDIO_SESSION_ID_UNSET;
        virtualDeviceId = C.INDEX_UNSET;
      }

      private Builder(OutputConfig config) {
        this.encoding = config.encoding;
        this.sampleRate = config.sampleRate;
        this.channelConfig = config.channelConfig;
        this.isTunneling = config.isTunneling;
        this.isOffload = config.isOffload;
        this.bufferSize = config.bufferSize;
        this.audioAttributes = config.audioAttributes;
        this.audioSessionId = config.audioSessionId;
        this.virtualDeviceId = config.virtualDeviceId;
        this.usePlaybackParameters = config.usePlaybackParameters;
        this.useOffloadGapless = config.useOffloadGapless;
      }

      /** Sets the {@link C.Encoding} of the audio data. */
      @CanIgnoreReturnValue
      public Builder setEncoding(@C.Encoding int encoding) {
        this.encoding = encoding;
        return this;
      }

      /** Sets the sample rate of the audio data. */
      @CanIgnoreReturnValue
      public Builder setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
      }

      /** Sets the channel configuration of the output. */
      @CanIgnoreReturnValue
      public Builder setChannelConfig(int channelConfig) {
        this.channelConfig = channelConfig;
        return this;
      }

      /** Sets whether tunneling is enabled for this output. */
      @CanIgnoreReturnValue
      public Builder setIsTunneling(boolean isTunneling) {
        this.isTunneling = isTunneling;
        return this;
      }

      /** Sets whether offload is enabled for this output. */
      @CanIgnoreReturnValue
      public Builder setIsOffload(boolean isOffload) {
        this.isOffload = isOffload;
        return this;
      }

      /** Sets the buffer size of the output in bytes. */
      @CanIgnoreReturnValue
      public Builder setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
      }

      /** Sets the {@link AudioAttributes}. */
      @CanIgnoreReturnValue
      public Builder setAudioAttributes(AudioAttributes audioAttributes) {
        this.audioAttributes = audioAttributes;
        return this;
      }

      /** Sets the audio session ID. */
      @CanIgnoreReturnValue
      public Builder setAudioSessionId(int audioSessionId) {
        this.audioSessionId = audioSessionId;
        return this;
      }

      /**
       * Sets the virtual device ID, or {@link C#INDEX_UNSET} if not specified. The default value is
       * {@link C#INDEX_UNSET}.
       */
      @CanIgnoreReturnValue
      public Builder setVirtualDeviceId(int virtualDeviceId) {
        this.virtualDeviceId = virtualDeviceId;
        return this;
      }

      /** Sets whether playback speed control is enabled for this output. */
      @CanIgnoreReturnValue
      public Builder setUsePlaybackParameters(boolean usePlaybackParameters) {
        this.usePlaybackParameters = usePlaybackParameters;
        return this;
      }

      /**
       * Sets whether gapless offload playback is enabled for this output via {@link
       * AudioOutput#setOffloadDelayPadding}.
       */
      @CanIgnoreReturnValue
      public Builder setUseOffloadGapless(boolean useOffloadGapless) {
        this.useOffloadGapless = useOffloadGapless;
        return this;
      }

      /** Builds the {@link OutputConfig}. */
      public OutputConfig build() {
        return new OutputConfig(this);
      }
    }
  }

  /** Level of support for an audio format by the provider. */
  final class FormatSupport {

    /** Level of support for an unsupported format. */
    public static final FormatSupport UNSUPPORTED = new Builder().build();

    /** A builder to create {@link FormatSupport} instances. */
    public static final class Builder {
      private boolean isFormatSupportedForOffload;
      private boolean isGaplessSupportedForOffload;
      private boolean isSpeedChangeSupportedForOffload;
      private @SupportLevel int supportLevel;

      public Builder() {
        supportLevel = FORMAT_UNSUPPORTED;
      }

      private Builder(FormatSupport other) {
        isFormatSupportedForOffload = other.isFormatSupportedForOffload;
        isGaplessSupportedForOffload = other.isGaplessSupportedForOffload;
        isSpeedChangeSupportedForOffload = other.isSpeedChangeSupportedForOffload;
        supportLevel = other.supportLevel;
      }

      /**
       * Sets if media format is supported in offload playback.
       *
       * <p>Default is {@code false}.
       */
      @CanIgnoreReturnValue
      public Builder setIsFormatSupportedForOffload(boolean isFormatSupportedForOffload) {
        this.isFormatSupportedForOffload = isFormatSupportedForOffload;
        return this;
      }

      /**
       * Sets whether playback of the format is supported with gapless transitions for offload.
       *
       * <p>Default is {@code false}.
       */
      @CanIgnoreReturnValue
      public Builder setIsGaplessSupportedForOffload(boolean isGaplessSupportedForOffload) {
        this.isGaplessSupportedForOffload = isGaplessSupportedForOffload;
        return this;
      }

      /**
       * Sets whether playback of the format is supported with speed changes for offload.
       *
       * <p>Default is {@code false}.
       */
      @CanIgnoreReturnValue
      public Builder setIsSpeedChangeSupportedForOffload(boolean isSpeedChangeSupportedForOffload) {
        this.isSpeedChangeSupportedForOffload = isSpeedChangeSupportedForOffload;
        return this;
      }

      /** Sets the {@linkplain SupportLevel level of support} for this support. */
      @CanIgnoreReturnValue
      public Builder setFormatSupportLevel(@SupportLevel int supportLevel) {
        this.supportLevel = supportLevel;
        return this;
      }

      /**
       * Builds the {@link FormatSupport}.
       *
       * @throws IllegalStateException If either {@link #isGaplessSupportedForOffload} or {@link
       *     #isSpeedChangeSupportedForOffload} are true when {@link #isFormatSupportedForOffload}
       *     is false.
       */
      public FormatSupport build() {
        if (!isFormatSupportedForOffload
            && (isGaplessSupportedForOffload || isSpeedChangeSupportedForOffload)) {
          throw new IllegalStateException(
              "Secondary offload attribute fields are true but primary isFormatSupportedForOffload"
                  + " is false");
        }
        return new FormatSupport(this);
      }
    }

    /** Whether the format is supported with offload playback. */
    public final boolean isFormatSupportedForOffload;

    /** Whether playback of the format is supported with gapless transitions for offload. */
    public final boolean isGaplessSupportedForOffload;

    /** Whether playback of the format is supported with speed changes for offload. */
    public final boolean isSpeedChangeSupportedForOffload;

    /** The level of support for the format. */
    public final @SupportLevel int supportLevel;

    private FormatSupport(Builder builder) {
      this.isFormatSupportedForOffload = builder.isFormatSupportedForOffload;
      this.isGaplessSupportedForOffload = builder.isGaplessSupportedForOffload;
      this.isSpeedChangeSupportedForOffload = builder.isSpeedChangeSupportedForOffload;
      this.supportLevel = builder.supportLevel;
    }

    /** Creates a new {@link Builder}, copying the initial values from this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }
  }

  /** Thrown when a failure occurs configuring the output. */
  final class ConfigurationException extends Exception {

    /** Creates a new configuration exception with the specified {@code message}. */
    public ConfigurationException(String message) {
      super(message);
    }
  }

  /** Thrown when a failure occurs initializing the output. */
  final class InitializationException extends Exception {

    /** Creates a new initialization exception with the specified {@code cause}. */
    public InitializationException(@Nullable Throwable cause) {
      super(cause);
    }
  }

  /**
   * Audio offload mode configuration. One of {@link #OFFLOAD_MODE_DISABLED}, {@link
   * #OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED} or {@link #OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    OFFLOAD_MODE_DISABLED,
    OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED,
    OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
  })
  @interface OffloadMode {}

  /** The audio output will never play in offload mode. */
  int OFFLOAD_MODE_DISABLED = 0;

  /**
   * The audio output will prefer offload playback except in the case where both the track is
   * gapless and the device does support gapless offload playback.
   *
   * <p>Use this option to prioritize uninterrupted playback of consecutive audio tracks over power
   * savings.
   */
  int OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED = 1;

  /**
   * The audio output will prefer offload playback even if this might result in silence gaps between
   * tracks.
   *
   * <p>Use this option to prioritize battery saving at the cost of a possible non seamless
   * transitions between tracks of the same album.
   */
  int OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED = 2;

  /**
   * The level of support the provider has for a format. One of {@link #FORMAT_SUPPORTED_DIRECTLY},
   * {@link #FORMAT_SUPPORTED_WITH_TRANSCODING} or {@link #FORMAT_UNSUPPORTED}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({FORMAT_SUPPORTED_DIRECTLY, FORMAT_SUPPORTED_WITH_TRANSCODING, FORMAT_UNSUPPORTED})
  @interface SupportLevel {}

  /** The provider supports the format directly, without the need for internal transcoding. */
  int FORMAT_SUPPORTED_DIRECTLY = 2;

  /**
   * The provider supports the format, but needs to transcode it internally to do so. Internal
   * transcoding may result in lower quality and higher CPU load in some cases.
   */
  int FORMAT_SUPPORTED_WITH_TRANSCODING = 1;

  /** The provider does not support the format. */
  int FORMAT_UNSUPPORTED = 0;

  /**
   * Returns the {@linkplain FormatSupport level of support} that this provider has for a given
   * {@link FormatConfig}.
   *
   * @param formatConfig The {@link FormatConfig}.
   * @return The {@link FormatSupport} for this config.
   */
  FormatSupport getFormatSupport(FormatConfig formatConfig);

  /**
   * Returns the {@link OutputConfig} for the given format.
   *
   * @param formatConfig The {@link FormatConfig}.
   * @return The {@link OutputConfig} for this format.
   */
  OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException;

  /**
   * Returns an {@link AudioOutput} for the given config.
   *
   * @param config The {@link OutputConfig}.
   * @return The {@link AudioOutput}.
   */
  AudioOutput getAudioOutput(OutputConfig config) throws InitializationException;

  /**
   * Adds a {@link Listener}.
   *
   * @param listener The listener to add.
   */
  void addListener(Listener listener);

  /**
   * Removes a {@link Listener}.
   *
   * @param listener The listener to remove.
   */
  void removeListener(Listener listener);

  /** Sets the {@link Clock} to use in the provider. */
  void setClock(Clock clock);

  /** Releases resources held by the provider. */
  void release();
}
