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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.DEFAULT_PLAYBACK_SPEED;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.MAX_PLAYBACK_SPEED;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.OUTPUT_MODE_OFFLOAD;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.OUTPUT_MODE_PCM;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig;
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioOffloadSupportProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink.OutputMode;
import androidx.media3.extractor.DtsUtil;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** A default implementation of {@link AudioOutputProvider}. */
public final class AudioTrackAudioOutputProvider implements AudioOutputProvider {

  private static final String TAG = "ATAudioOutputProvider";

  /**
   * Whether to throw an {@link AudioTrackAudioOutput.InvalidAudioTrackTimestampException} when a
   * spurious timestamp is reported from {@link AudioTrack#getTimestamp}.
   *
   * <p>The flag must be set before creating a player. Should be set to {@code true} for testing and
   * debugging purposes only.
   */
  @SuppressWarnings("NonFinalStaticField") // Test-only access
  @UnstableApi
  public static boolean failOnSpuriousAudioTimestamp = false;

  /** A builder to create {@link AudioTrackAudioOutputProvider} instances. */
  public static final class Builder {

    @Nullable private final Context context;

    @Nullable private BiConsumer<AudioTrack.Builder, OutputConfig> audioTrackBuilderModifier;
    private @MonotonicNonNull AudioOffloadSupportProvider audioOffloadSupportProvider;
    private AudioTrackBufferSizeProvider bufferSizeProvider;
    @Nullable private AudioCapabilities audioCapabilities;
    private float maxPlaybackSpeed;

    @SuppressWarnings("deprecation") // Supporting deprecated AudioTrack customization path.
    @Nullable
    private AudioTrackProvider audioTrackProvider;

    /**
     * Creates a new builder.
     *
     * @param context The {@link Context}.
     */
    public Builder(@Nullable Context context) {
      this.context = context != null ? context.getApplicationContext() : null;
      bufferSizeProvider = AudioTrackBufferSizeProvider.DEFAULT;
      if (context == null) {
        audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES;
      }
      maxPlaybackSpeed = MAX_PLAYBACK_SPEED;
    }

    /**
     * Sets a {@link BiConsumer} to modify the {@link AudioTrack.Builder} before building the {@link
     * AudioTrack}.
     *
     * @param audioTrackBuilderModifier A {@link BiConsumer} that accepts an {@link
     *     AudioTrack.Builder} and an {@link OutputConfig}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    @RequiresApi(24)
    public Builder setAudioTrackBuilderModifier(
        BiConsumer<AudioTrack.Builder, OutputConfig> audioTrackBuilderModifier) {
      this.audioTrackBuilderModifier = audioTrackBuilderModifier;
      return this;
    }

    /**
     * Sets an {@link AudioOffloadSupportProvider} to provide the provider's offload support
     * capabilities for a given {@link AudioOutputProvider.FormatConfig} for calls to {@link
     * #getFormatSupport(FormatConfig)}.
     *
     * <p>The default is an instance of {@link DefaultAudioOffloadSupportProvider}.
     *
     * @param audioOffloadSupportProvider The {@link AudioOffloadSupportProvider} to use.
     * @return This builder.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setAudioOffloadSupportProvider(
        AudioOffloadSupportProvider audioOffloadSupportProvider) {
      this.audioOffloadSupportProvider = audioOffloadSupportProvider;
      return this;
    }

    /**
     * Sets an {@link AudioTrackBufferSizeProvider} to compute the buffer size when {@link
     * DefaultAudioSink#configure} is called with {@code specifiedBufferSize == 0}.
     *
     * <p>The default value is {@link AudioTrackBufferSizeProvider#DEFAULT}.
     *
     * @param bufferSizeProvider The {@link AudioTrackBufferSizeProvider} to use.
     * @return This builder.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setAudioTrackBufferSizeProvider(
        AudioTrackBufferSizeProvider bufferSizeProvider) {
      this.bufferSizeProvider = bufferSizeProvider;
      return this;
    }

    /**
     * Sets the maximum playback speed that {@link AudioTrackAudioOutput} provided by this instance
     * are going to be configured for. This is used to allocate buffers that are big enough to not
     * underrun at the maximum playback speed. This value has no effect if {@code
     * useAudioOutputPlaybackParams} is disabled.
     *
     * <p>The default value is {@link DefaultAudioSink#MAX_PLAYBACK_SPEED}.
     *
     * @param maxPlaybackSpeed The maximum playback speed to use. Must be equal to or between {@code
     *     1f} and {@link DefaultAudioSink#MAX_PLAYBACK_SPEED}.
     * @return This builder.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setMaxPlaybackSpeed(float maxPlaybackSpeed) {
      checkArgument(maxPlaybackSpeed >= 1f && maxPlaybackSpeed <= MAX_PLAYBACK_SPEED);
      this.maxPlaybackSpeed = maxPlaybackSpeed;
      return this;
    }

    /** Sets the static {@link AudioCapabilities} for backwards compatibility. */
    @UnstableApi
    @CanIgnoreReturnValue
    /* package */ Builder setAudioCapabilities(@Nullable AudioCapabilities audioCapabilities) {
      if (context == null) {
        this.audioCapabilities = audioCapabilities;
      }
      return this;
    }

    /** Sets the {@link AudioTrackProvider} for backwards compatibility. */
    @UnstableApi
    @CanIgnoreReturnValue
    @SuppressWarnings("deprecation") // Supporting deprecated AudioTrack customization path.
    /* package */ Builder setAudioTrackProvider(@Nullable AudioTrackProvider audioTrackProvider) {
      this.audioTrackProvider = audioTrackProvider;
      return this;
    }

    /** Builds the {@link AudioTrackAudioOutputProvider}. */
    public AudioTrackAudioOutputProvider build() {
      if (audioOffloadSupportProvider == null) {
        audioOffloadSupportProvider = new DefaultAudioOffloadSupportProvider(context);
      }

      return new AudioTrackAudioOutputProvider(this);
    }
  }

  @Nullable private final Context context;

  @SuppressWarnings("deprecation") // Supporting deprecated AudioTrack customization path.
  @Nullable
  private final AudioTrackProvider audioTrackProvider;

  @Nullable private final BiConsumer<AudioTrack.Builder, OutputConfig> builderModifier;
  private final AudioTrackBufferSizeProvider audioTrackBufferSizeProvider;
  private final AudioOffloadSupportProvider audioOffloadSupportProvider;
  @Nullable private final CapabilityChangeListener capabilityChangeListener;
  private final float maxPlaybackSpeed;

  private @MonotonicNonNull ListenerSet<Listener> listeners;
  private Clock clock;
  private @MonotonicNonNull AudioCapabilities audioCapabilities;
  private @MonotonicNonNull AudioCapabilitiesReceiver audioCapabilitiesReceiver;
  @Nullable private Looper playbackLooper;
  @Nullable private Context contextWithDeviceId;

  private AudioTrackAudioOutputProvider(Builder builder) {
    this.context = builder.context;
    this.builderModifier = builder.audioTrackBuilderModifier;
    this.audioOffloadSupportProvider = checkNotNull(builder.audioOffloadSupportProvider);
    this.audioTrackBufferSizeProvider = builder.bufferSizeProvider;
    this.audioCapabilities = builder.audioCapabilities;
    this.audioTrackProvider = builder.audioTrackProvider;
    this.capabilityChangeListener = builder.context == null ? null : new CapabilityChangeListener();
    this.maxPlaybackSpeed = builder.maxPlaybackSpeed;
    this.clock = Clock.DEFAULT;
  }

  @Override
  public FormatSupport getFormatSupport(FormatConfig formatConfig) {
    updateAudioCapabilitiesReceiver(formatConfig);
    AudioOffloadSupport offloadSupport =
        audioOffloadSupportProvider.getAudioOffloadSupport(
            formatConfig.format, formatConfig.audioAttributes);
    return new FormatSupport.Builder()
        .setFormatSupportLevel(getFormatSupportLevel(formatConfig))
        .setIsFormatSupportedForOffload(offloadSupport.isFormatSupported)
        .setIsGaplessSupportedForOffload(offloadSupport.isGaplessSupported)
        .setIsSpeedChangeSupportedForOffload(offloadSupport.isSpeedChangeSupported)
        .build();
  }

  @Override
  public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
    Format format = formatConfig.format;
    updateAudioCapabilitiesReceiver(formatConfig);

    @OutputMode int outputMode;
    @C.Encoding int outputEncoding;
    int outputSampleRate;
    int outputChannelConfig;
    int outputPcmFrameSize;
    boolean usePlaybackParameters;
    boolean useOffloadGapless = false;

    if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW)) {
      checkArgument(Util.isEncodingLinearPcm(format.pcmEncoding));
      outputMode = OUTPUT_MODE_PCM;
      outputEncoding = format.pcmEncoding;
      outputSampleRate = format.sampleRate;
      outputChannelConfig = getAudioOutputChannelConfig(format.channelCount);
      outputPcmFrameSize = Util.getPcmFrameSize(outputEncoding, format.channelCount);
      usePlaybackParameters = formatConfig.enablePlaybackParameters;
    } else {
      outputSampleRate = format.sampleRate;
      outputPcmFrameSize = C.LENGTH_UNSET;
      AudioOffloadSupport audioOffloadSupport =
          formatConfig.enableOffload
              ? audioOffloadSupportProvider.getAudioOffloadSupport(
                  format, formatConfig.audioAttributes)
              : AudioOffloadSupport.DEFAULT_UNSUPPORTED;
      if (formatConfig.enableOffload && audioOffloadSupport.isFormatSupported) {
        outputMode = OUTPUT_MODE_OFFLOAD;
        outputEncoding = MimeTypes.getEncoding(checkNotNull(format.sampleMimeType), format.codecs);
        outputChannelConfig = getAudioOutputChannelConfig(format.channelCount);
        // Offload requires AudioTrack playback parameters to apply speed changes quickly.
        usePlaybackParameters = true;
        useOffloadGapless = audioOffloadSupport.isGaplessSupported;
      } else {
        outputMode = OUTPUT_MODE_PASSTHROUGH;
        @Nullable
        Pair<Integer, Integer> encodingAndChannelConfig =
            audioCapabilities.getEncodingAndChannelConfigForPassthrough(
                format, formatConfig.audioAttributes);
        if (encodingAndChannelConfig == null) {
          throw new ConfigurationException("Unable to configure passthrough for: " + format);
        }
        outputEncoding = encodingAndChannelConfig.first;
        outputChannelConfig = encodingAndChannelConfig.second;
        // Passthrough only supports audio output playback parameters, but we only enable it this
        // was specifically requested by the app.
        usePlaybackParameters = formatConfig.enablePlaybackParameters;
      }
    }

    // Replace unknown bitrate by maximum allowed bitrate for DTS Express to avoid allocating an
    // AudioTrack buffer for the much larger maximum bitrate of the underlying DTS-HD encoding.
    int bitrate = format.bitrate;
    if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_DTS_EXPRESS)
        && bitrate == Format.NO_VALUE) {
      bitrate = DtsUtil.DTS_EXPRESS_MAX_RATE_BITS_PER_SECOND;
    }

    int bufferSize =
        formatConfig.preferredBufferSize != C.LENGTH_UNSET
            ? formatConfig.preferredBufferSize
            : audioTrackBufferSizeProvider.getBufferSizeInBytes(
                getAudioTrackMinBufferSize(outputSampleRate, outputChannelConfig, outputEncoding),
                outputEncoding,
                outputMode,
                outputPcmFrameSize != C.LENGTH_UNSET ? outputPcmFrameSize : 1,
                outputSampleRate,
                bitrate,
                usePlaybackParameters ? maxPlaybackSpeed : DEFAULT_PLAYBACK_SPEED);

    return new OutputConfig.Builder()
        .setSampleRate(outputSampleRate)
        .setChannelMask(outputChannelConfig)
        .setEncoding(outputEncoding)
        .setBufferSize(bufferSize)
        .setAudioSessionId(formatConfig.audioSessionId)
        .setAudioAttributes(formatConfig.audioAttributes)
        .setIsOffload(outputMode == OUTPUT_MODE_OFFLOAD)
        .setIsTunneling(formatConfig.enableTunneling)
        .setUsePlaybackParameters(usePlaybackParameters)
        .setUseOffloadGapless(useOffloadGapless)
        .setVirtualDeviceId(formatConfig.virtualDeviceId)
        .build();
  }

  @SuppressWarnings("CatchingUnchecked") // Catching generic Exception from AudioTrack.release
  @Override
  public AudioTrackAudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
    AudioTrack audioTrack;
    try {
      @Nullable Context contextForAudioTrack = null;
      int audioSessionId = config.audioSessionId;
      if (config.virtualDeviceId != C.INDEX_UNSET && context != null && SDK_INT >= 34) {
        if (contextWithDeviceId == null
            || contextWithDeviceId.getDeviceId() != config.virtualDeviceId) {
          contextWithDeviceId = context.createDeviceContext(config.virtualDeviceId);
        }
        contextForAudioTrack = contextWithDeviceId;
        audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
      }
      if (audioTrackProvider != null) {
        AudioTrackConfig audioTrackConfig = getAudioTrackConfig(config);
        audioTrack =
            audioTrackProvider.getAudioTrack(
                audioTrackConfig, config.audioAttributes, audioSessionId, contextForAudioTrack);
      } else {
        @SuppressLint("WrongConstant") // Using C.Encoding as AudioFormat encoding
        AudioFormat format =
            new AudioFormat.Builder()
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelMask)
                .setEncoding(config.encoding)
                .build();
        android.media.AudioAttributes audioTrackAttributes =
            getAudioTrackAttributes(config.audioAttributes, config.isTunneling);
        AudioTrack.Builder audioTrackBuilder =
            new AudioTrack.Builder()
                .setAudioAttributes(audioTrackAttributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(config.bufferSize)
                .setSessionId(audioSessionId);
        if (SDK_INT >= 29) {
          audioTrackBuilder.setOffloadedPlayback(config.isOffload);
        }
        if (SDK_INT >= 34 && contextForAudioTrack != null) {
          audioTrackBuilder.setContext(contextForAudioTrack);
        }
        if (builderModifier != null && SDK_INT >= 24) {
          builderModifier.accept(audioTrackBuilder, config);
        }
        audioTrack = audioTrackBuilder.build();
      }
    } catch (UnsupportedOperationException | IllegalArgumentException e) {
      throw new InitializationException(e);
    }
    if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
      try {
        audioTrack.release();
      } catch (Exception e) {
        // The track has already failed to initialize, so it wouldn't be that surprising if
        // release were to fail too. Swallow the exception.
      }
      throw new InitializationException();
    }
    return new AudioTrackAudioOutput(audioTrack, config, capabilityChangeListener, clock);
  }

  @Override
  public void addListener(Listener listener) {
    verifySinglePlaybackLooper();
    if (listeners == null) {
      listeners = new ListenerSet<>(Thread.currentThread());
      // TODO: b/450556896 - remove this line once threading in CompositionPlayer is fixed.
      listeners.setThrowsWhenUsingWrongThread(false);
    }
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    if (listeners != null) {
      listeners.remove(listener);
    }
  }

  @UnstableApi
  @Override
  public void setClock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void release() {
    if (listeners != null) {
      listeners.release();
    }
    if (audioCapabilitiesReceiver != null) {
      audioCapabilitiesReceiver.unregister();
    }
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

  void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
    verifySinglePlaybackLooper();
    if (this.audioCapabilities != null && !audioCapabilities.equals(this.audioCapabilities)) {
      this.audioCapabilities = audioCapabilities;
      if (listeners != null) {
        listeners.sendEvent(Listener::onFormatSupportChanged);
      }
    }
  }

  private int getAudioOutputChannelConfig(int channelCount) {
    if (audioTrackProvider != null) {
      return audioTrackProvider.getAudioTrackChannelConfig(channelCount);
    }

    return Util.getAudioTrackChannelConfig(channelCount);
  }

  private int getAudioTrackMinBufferSize(int sampleRateInHz, int channelConfig, int encoding) {
    int minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, encoding);
    checkState(minBufferSize != AudioTrack.ERROR_BAD_VALUE);
    return minBufferSize;
  }

  @EnsuresNonNull("audioCapabilities")
  private void updateAudioCapabilitiesReceiver(FormatConfig formatConfig) {
    verifySinglePlaybackLooper();
    if (audioCapabilitiesReceiver == null && context != null) {
      // Must be lazily initialized to receive audio capabilities receiver listener event on the
      // current (playback) thread as the constructor is not called in the playback thread.
      audioCapabilitiesReceiver =
          new AudioCapabilitiesReceiver(
              context,
              this::onAudioCapabilitiesChanged,
              formatConfig.audioAttributes,
              formatConfig.preferredDevice);
      audioCapabilities = audioCapabilitiesReceiver.register();
    } else if (audioCapabilitiesReceiver != null) {
      if (formatConfig.preferredDevice != null) {
        audioCapabilitiesReceiver.setRoutedDevice(formatConfig.preferredDevice);
      }
      audioCapabilitiesReceiver.setAudioAttributes(formatConfig.audioAttributes);
    }
    checkNotNull(audioCapabilities);
  }

  private void verifySinglePlaybackLooper() {
    if (context == null) {
      // Don't verify threads in deprecated mode without context.
      return;
    }
    @Nullable Looper myLooper = Looper.myLooper();
    checkState(
        playbackLooper == null || playbackLooper == myLooper,
        "AudioTrackAudioOutputProvider accessed on multiple threads: %s and %s",
        getLooperThreadName(playbackLooper),
        getLooperThreadName(myLooper));
    playbackLooper = myLooper;
  }

  @RequiresNonNull("audioCapabilities")
  private @SupportLevel int getFormatSupportLevel(FormatConfig formatConfig) {
    Format format = formatConfig.format;
    if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW)) {
      if (format.pcmEncoding == C.ENCODING_PCM_16BIT) {
        // Always supported.
        return FORMAT_SUPPORTED_DIRECTLY;
      }
      if (!formatConfig.enableHighResolutionPcmOutput) {
        // Other PCM formats explicitly disabled, so claim no support.
        return FORMAT_UNSUPPORTED;
      }
      if (!Util.isEncodingLinearPcm(format.pcmEncoding)) {
        Log.w(TAG, "Invalid PCM encoding: " + format.pcmEncoding);
        return FORMAT_UNSUPPORTED;
      }
      if (SDK_INT < Util.getApiLevelThatAudioFormatIntroducedAudioEncoding(format.pcmEncoding)) {
        // Format not yet supported by AudioTrack on this SDK level.
        return FORMAT_UNSUPPORTED;
      }
      // AudioTrack can play this PCM format. It may internally resample to other PCM formats, but
      // this is outside of our control and knowledge.
      return FORMAT_SUPPORTED_DIRECTLY;
    }
    if (audioCapabilities.isPassthroughPlaybackSupported(format, formatConfig.audioAttributes)) {
      return FORMAT_SUPPORTED_DIRECTLY;
    }

    return FORMAT_UNSUPPORTED;
  }

  private AudioTrackConfig getAudioTrackConfig(OutputConfig config) {
    return new AudioTrackConfig(
        config.encoding,
        config.sampleRate,
        config.channelMask,
        config.isTunneling,
        config.isOffload,
        config.bufferSize);
  }

  private static String getLooperThreadName(@Nullable Looper looper) {
    return looper == null ? "null" : looper.getThread().getName();
  }

  private final class CapabilityChangeListener
      implements AudioTrackAudioOutput.CapabilityChangeListener {

    @Override
    public void onRecoverableWriteError() {
      if (audioCapabilitiesReceiver != null) {
        // Change to the audio capabilities supported by all the devices during the error recovery.
        audioCapabilities = DEFAULT_AUDIO_CAPABILITIES;
        audioCapabilitiesReceiver.overrideCapabilities(DEFAULT_AUDIO_CAPABILITIES);
      }
    }

    @Override
    public void onRoutedDeviceChanged(AudioDeviceInfo routedDevice) {
      if (audioCapabilitiesReceiver != null) {
        audioCapabilitiesReceiver.setRoutedDevice(routedDevice);
      }
    }
  }
}
