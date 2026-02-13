/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static androidx.media3.common.util.Util.constrainValue;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.Ac4Util;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.ExtractorUtil;
import androidx.media3.extractor.MpegAudioUtil;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Plays audio data. The implementation delegates to an {@link AudioOutput} and handles playback
 * position smoothing, non-blocking writes and reconfiguration.
 *
 * <p>If tunneling mode is enabled, care must be taken that audio processors do not output buffers
 * with a different duration than their input, and buffer processors must produce output
 * corresponding to their last input immediately after that input is queued. This means that, for
 * example, speed adjustment is not possible while using tunneling.
 */
@UnstableApi
public final class DefaultAudioSink implements AudioSink {

  /**
   * @deprecated Use {@link AudioTrackAudioOutputProvider} instead.
   */
  @Deprecated
  public interface AudioTrackProvider {

    /** The default provider for {@link AudioTrack} instances. */
    @SuppressWarnings("deprecation") // Creating instance of deprecated class.
    AudioTrackProvider DEFAULT = new DefaultAudioTrackProvider();

    /**
     * Returns a new {@link AudioTrack} for the given parameters.
     *
     * @param audioTrackConfig The {@link AudioTrackConfig}.
     * @param audioAttributes The {@link AudioAttributes}.
     * @param audioSessionId The audio session ID.
     * @param context The {@link Context} to be used for the {@link AudioTrack} creation, or null to
     *     not set a {@link Context}.
     */
    AudioTrack getAudioTrack(
        AudioTrackConfig audioTrackConfig,
        AudioAttributes audioAttributes,
        int audioSessionId,
        @Nullable Context context);

    /** Returns the channel mask config for the given channel count. */
    default int getAudioTrackChannelConfig(int channelCount) {
      return Util.getAudioTrackChannelConfig(channelCount);
    }
  }

  /**
   * If an attempt to instantiate an AudioOutput with a buffer size larger than this value fails, a
   * second attempt is made using half of that failed buffer size.
   */
  private static final int AUDIO_OUTPUT_RETRY_BUFFER_SIZE_THRESHOLD = 1_000_000;

  /** The minimum duration of the skipped silence to be reported as discontinuity. */
  private static final int MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US = 300_000;

  /**
   * The delay of reporting the skipped silence, during which the default audio sink checks if there
   * is any further skipped silence that is close to the delayed silence. If any, the further
   * skipped silence will be concatenated to the delayed one.
   */
  private static final int REPORT_SKIPPED_SILENCE_DELAY_MS = 100;

  /** The time used to ramp up the AudioOutput's volume when starting to play. */
  private static final int AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20;

  /**
   * @deprecated Use {@link androidx.media3.common.audio.AudioProcessorChain}.
   */
  @Deprecated
  public interface AudioProcessorChain extends androidx.media3.common.audio.AudioProcessorChain {}

  /**
   * The default audio processor chain, which applies a (possibly empty) chain of user-defined audio
   * processors followed by {@link SilenceSkippingAudioProcessor} and {@link SonicAudioProcessor}.
   */
  @SuppressWarnings("deprecation")
  public static class DefaultAudioProcessorChain implements AudioProcessorChain {

    private final AudioProcessor[] audioProcessors;
    private final SilenceSkippingAudioProcessor silenceSkippingAudioProcessor;
    private final SonicAudioProcessor sonicAudioProcessor;

    /**
     * Creates a new default chain of audio processors, with the user-defined {@code
     * audioProcessors} applied before silence skipping and speed adjustment processors.
     */
    public DefaultAudioProcessorChain(AudioProcessor... audioProcessors) {
      this(audioProcessors, new SilenceSkippingAudioProcessor(), new SonicAudioProcessor());
    }

    /**
     * Creates a new default chain of audio processors, with the user-defined {@code
     * audioProcessors} applied before silence skipping and speed adjustment processors.
     */
    public DefaultAudioProcessorChain(
        AudioProcessor[] audioProcessors,
        SilenceSkippingAudioProcessor silenceSkippingAudioProcessor,
        SonicAudioProcessor sonicAudioProcessor) {
      // The passed-in type may be more specialized than AudioProcessor[], so allocate a new array
      // rather than using Arrays.copyOf.
      this.audioProcessors = new AudioProcessor[audioProcessors.length + 2];
      System.arraycopy(
          /* src= */ audioProcessors,
          /* srcPos= */ 0,
          /* dest= */ this.audioProcessors,
          /* destPos= */ 0,
          /* length= */ audioProcessors.length);
      this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor;
      this.sonicAudioProcessor = sonicAudioProcessor;
      this.audioProcessors[audioProcessors.length] = silenceSkippingAudioProcessor;
      this.audioProcessors[audioProcessors.length + 1] = sonicAudioProcessor;
    }

    @Override
    public AudioProcessor[] getAudioProcessors() {
      return audioProcessors;
    }

    @Override
    public PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters) {
      sonicAudioProcessor.setSpeed(playbackParameters.speed);
      sonicAudioProcessor.setPitch(playbackParameters.pitch);
      return playbackParameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
      silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled);
      return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
      return sonicAudioProcessor.isActive()
          ? sonicAudioProcessor.getMediaDuration(playoutDuration)
          : playoutDuration;
    }

    @Override
    public long getSkippedOutputFrameCount() {
      return silenceSkippingAudioProcessor.getSkippedFrames();
    }
  }

  /** Provides the buffer size to use when creating an {@link AudioTrack}. */
  public interface AudioTrackBufferSizeProvider {
    /** Default instance. */
    AudioTrackBufferSizeProvider DEFAULT =
        new DefaultAudioTrackBufferSizeProvider.Builder().build();

    /**
     * Returns the buffer size to use when creating an {@link AudioTrack} for a specific format and
     * output mode.
     *
     * @param minBufferSizeInBytes The minimum buffer size in bytes required to play this format.
     *     See {@link AudioTrack#getMinBufferSize}.
     * @param encoding The {@link C.Encoding} of the format.
     * @param outputMode How the audio will be played. One of the {@link OutputMode output modes}.
     * @param pcmFrameSize The size of the PCM frames if the {@code encoding} is PCM, 1 otherwise,
     *     in bytes.
     * @param sampleRate The sample rate of the format, in Hz.
     * @param bitrate The bitrate of the audio stream if the stream is compressed, or {@link
     *     Format#NO_VALUE} if {@code encoding} is PCM or the bitrate is not known.
     * @param maxAudioTrackPlaybackSpeed The maximum speed the content will be played using {@link
     *     AudioTrack#setPlaybackParams}. 0.5 is 2x slow motion, 1 is real time, 2 is 2x fast
     *     forward, etc. This will be {@code 1} unless {@link
     *     Builder#setEnableAudioOutputPlaybackParameters(boolean)} is enabled.
     * @return The computed buffer size in bytes. It should always be {@code >=
     *     minBufferSizeInBytes}. The computed buffer size must contain an integer number of frames:
     *     {@code bufferSizeInBytes % pcmFrameSize == 0}.
     */
    int getBufferSizeInBytes(
        int minBufferSizeInBytes,
        @C.Encoding int encoding,
        @OutputMode int outputMode,
        int pcmFrameSize,
        int sampleRate,
        int bitrate,
        double maxAudioTrackPlaybackSpeed);
  }

  /**
   * Provides the {@link AudioOffloadSupport} to convey the level of offload support the sink can
   * provide.
   */
  public interface AudioOffloadSupportProvider {
    /**
     * Returns the {@link AudioOffloadSupport} the audio sink can provide for the media based on its
     * {@link Format} and {@link AudioAttributes}
     *
     * @param format The {@link Format}.
     * @param audioAttributes The {@link AudioAttributes}.
     * @return The {@link AudioOffloadSupport} the sink can provide for the media based on its
     *     {@link Format} and {@link AudioAttributes}.
     */
    AudioOffloadSupport getAudioOffloadSupport(Format format, AudioAttributes audioAttributes);
  }

  /** A builder to create {@link DefaultAudioSink} instances. */
  @SuppressWarnings("deprecation") // Keeping deprecated AudioTrackProvider as field.
  public static final class Builder {

    @Nullable private final Context context;
    private AudioCapabilities audioCapabilities;
    @Nullable private androidx.media3.common.audio.AudioProcessorChain audioProcessorChain;
    private boolean enableFloatOutput;
    private boolean enableAudioOutputPlaybackParameters;

    private boolean buildCalled;
    private @MonotonicNonNull AudioTrackBufferSizeProvider audioTrackBufferSizeProvider;
    private @MonotonicNonNull AudioOutputProvider audioOutputProvider;
    private @MonotonicNonNull AudioTrackProvider audioTrackProvider;
    private @MonotonicNonNull AudioOffloadSupportProvider audioOffloadSupportProvider;
    @Nullable private AudioOffloadListener audioOffloadListener;

    /**
     * @deprecated Use {@link #Builder(Context)} instead.
     */
    @Deprecated
    public Builder() {
      this.context = null;
      audioCapabilities = DEFAULT_AUDIO_CAPABILITIES;
    }

    /**
     * Creates a new builder.
     *
     * @param context The {@link Context}.
     */
    public Builder(Context context) {
      this.context = context;
      audioCapabilities = DEFAULT_AUDIO_CAPABILITIES;
    }

    /**
     * @deprecated These {@linkplain AudioCapabilities audio capabilities} are only used in the
     *     absence of a {@linkplain Context context}. In the case when the {@code Context} is {@code
     *     null} and the {@code audioCapabilities} is not set to the {@code Builder}, the default
     *     capabilities (no encoded audio passthrough support) should be assumed.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setAudioCapabilities(AudioCapabilities audioCapabilities) {
      checkNotNull(audioCapabilities);
      this.audioCapabilities = audioCapabilities;
      return this;
    }

    /**
     * Sets an array of {@link AudioProcessor AudioProcessors}s that will process PCM audio before
     * output. May be empty. Equivalent of {@code setAudioProcessorChain(new
     * DefaultAudioProcessorChain(audioProcessors)}.
     *
     * <p>The default value is an empty array.
     */
    @CanIgnoreReturnValue
    public Builder setAudioProcessors(AudioProcessor[] audioProcessors) {
      checkNotNull(audioProcessors);
      return setAudioProcessorChain(new DefaultAudioProcessorChain(audioProcessors));
    }

    /**
     * Sets the {@link androidx.media3.common.audio.AudioProcessorChain} to process audio before
     * playback. The instance passed in must not be reused in other sinks. Processing chains are
     * only supported for PCM playback (not passthrough or offload).
     *
     * <p>By default, no processing will be applied.
     */
    @CanIgnoreReturnValue
    public Builder setAudioProcessorChain(
        androidx.media3.common.audio.AudioProcessorChain audioProcessorChain) {
      checkNotNull(audioProcessorChain);
      this.audioProcessorChain = audioProcessorChain;
      return this;
    }

    /**
     * Sets whether to enable 32-bit float output or integer output. Where possible, 32-bit float
     * output will be used if the input is 32-bit float, and also if the input is high resolution
     * (24-bit or 32-bit) integer PCM. Audio processing (for example, speed adjustment) will not be
     * available when float output is in use.
     *
     * <p>The default value is {@code false}.
     */
    @CanIgnoreReturnValue
    public Builder setEnableFloatOutput(boolean enableFloatOutput) {
      this.enableFloatOutput = enableFloatOutput;
      return this;
    }

    /**
     * @deprecated Use {@link #setEnableAudioOutputPlaybackParameters(boolean)} instead.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setEnableAudioTrackPlaybackParams(boolean enableAudioTrackPlaybackParams) {
      return setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams);
    }

    /**
     * Sets whether to control the playback speed using the {@link AudioOutput} implementation
     * (using {@link AudioTrack#setPlaybackParams(PlaybackParams)} by default), if supported. If set
     * to {@code false}, speed up/down of the audio will be done by ExoPlayer (see {@link
     * SonicAudioProcessor}). The {@link AudioOutput} speed adjustment is lower latency, but
     * device-dependent, less reliable or may offer fewer available speeds.
     *
     * <p>The default value is {@code false}.
     */
    @CanIgnoreReturnValue
    public Builder setEnableAudioOutputPlaybackParameters(
        boolean enableAudioOutputPlaybackParameters) {
      this.enableAudioOutputPlaybackParameters = enableAudioOutputPlaybackParameters;
      return this;
    }

    /**
     * Sets an {@link AudioTrackBufferSizeProvider} to compute the buffer size when {@link
     * #configure} is called with {@code specifiedBufferSize == 0}.
     *
     * <p>The default value is {@link AudioTrackBufferSizeProvider#DEFAULT}.
     *
     * <p>Must not be called if {@link #setAudioOutputProvider(AudioOutputProvider)} is used.
     *
     * @deprecated Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
     *     {@link
     *     AudioTrackAudioOutputProvider.Builder#setAudioTrackBufferSizeProvider(AudioTrackBufferSizeProvider)}.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setAudioTrackBufferSizeProvider(
        AudioTrackBufferSizeProvider audioTrackBufferSizeProvider) {
      this.audioTrackBufferSizeProvider = audioTrackBufferSizeProvider;
      return this;
    }

    /**
     * Sets an {@link AudioOffloadSupportProvider} to provide the sink's offload support
     * capabilities for a given {@link Format} and {@link AudioAttributes} for calls to {@link
     * #getFormatOffloadSupport(Format)}.
     *
     * <p>If this setter is not called, then the {@link DefaultAudioSink} uses an instance of {@link
     * DefaultAudioOffloadSupportProvider}.
     *
     * <p>Must not be called if {@link #setAudioOutputProvider(AudioOutputProvider)} is used.
     *
     * @deprecated Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
     *     {@link
     *     AudioTrackAudioOutputProvider.Builder#setAudioOffloadSupportProvider(AudioOffloadSupportProvider)}.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setAudioOffloadSupportProvider(
        AudioOffloadSupportProvider audioOffloadSupportProvider) {
      this.audioOffloadSupportProvider = audioOffloadSupportProvider;
      return this;
    }

    /**
     * Sets an optional {@link AudioOffloadListener} to receive events relevant to offloaded
     * playback.
     *
     * <p>The default value is null.
     */
    @CanIgnoreReturnValue
    @ExperimentalApi // TODO: b/470374585 - Make method non-experimental.
    public Builder setExperimentalAudioOffloadListener(
        @Nullable AudioOffloadListener audioOffloadListener) {
      this.audioOffloadListener = audioOffloadListener;
      return this;
    }

    /**
     * Sets the {@link AudioTrackProvider} used to create {@link AudioTrack} instances.
     *
     * <p>Must not be called if {@link #setAudioOutputProvider(AudioOutputProvider)} is used.
     *
     * @param audioTrackProvider The {@link AudioTrackProvider}.
     * @return This builder.
     * @deprecated Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
     *     {@link AudioTrackAudioOutputProvider.Builder#setAudioTrackBuilderModifier(BiConsumer)} or
     *     wrap {@link AudioTrackAudioOutputProvider} in a {@link ForwardingAudioOutputProvider}.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setAudioTrackProvider(AudioTrackProvider audioTrackProvider) {
      this.audioTrackProvider = audioTrackProvider;
      return this;
    }

    /**
     * Sets the {@link AudioOutputProvider} used to create {@link AudioOutput} instances.
     *
     * <p>Must not be used with the deprecated {@link #Builder()} constructor.
     *
     * @param audioOutputProvider The {@link AudioOutputProvider}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAudioOutputProvider(AudioOutputProvider audioOutputProvider) {
      checkState(context != null, "Cannot set AudioOutputProvider without a Context");
      this.audioOutputProvider = audioOutputProvider;
      return this;
    }

    /** Builds the {@link DefaultAudioSink}. Must only be called once per Builder instance. */
    public DefaultAudioSink build() {
      checkState(!buildCalled);
      buildCalled = true;
      if (audioProcessorChain == null) {
        audioProcessorChain = new DefaultAudioProcessorChain();
      }
      if (audioOutputProvider == null) {
        if (audioOffloadSupportProvider == null) {
          audioOffloadSupportProvider = new DefaultAudioOffloadSupportProvider(context);
        }
        if (audioTrackBufferSizeProvider == null) {
          audioTrackBufferSizeProvider = AudioTrackBufferSizeProvider.DEFAULT;
        }
        audioOutputProvider =
            new AudioTrackAudioOutputProvider.Builder(context)
                .setAudioCapabilities(context != null ? null : audioCapabilities)
                .setAudioOffloadSupportProvider(audioOffloadSupportProvider)
                .setAudioTrackBufferSizeProvider(audioTrackBufferSizeProvider)
                .setAudioTrackProvider(audioTrackProvider)
                .build();
      } else {
        checkState(audioOffloadSupportProvider == null);
        checkState(audioTrackBufferSizeProvider == null);
        checkState(audioTrackProvider == null);
      }
      return new DefaultAudioSink(this);
    }
  }

  /** The default playback speed. */
  public static final float DEFAULT_PLAYBACK_SPEED = 1f;

  /**
   * The minimum allowed playback speed. Lower values will be constrained to fall in range unless
   * the {@link AudioOutput} supports other values.
   */
  public static final float MIN_PLAYBACK_SPEED = 0.1f;

  /**
   * The maximum allowed playback speed. Higher values will be constrained to fall in range unless
   * the {@link AudioOutput} supports other values.
   */
  public static final float MAX_PLAYBACK_SPEED = 8f;

  /**
   * The minimum allowed pitch factor. Lower values will be constrained to fall in range unless the
   * {@link AudioOutput} supports other values.
   */
  public static final float MIN_PITCH = 0.1f;

  /**
   * The maximum allowed pitch factor. Higher values will be constrained to fall in range unless the
   * {@link AudioOutput} supports other values.
   */
  public static final float MAX_PITCH = 8f;

  /** The default skip silence flag. */
  private static final boolean DEFAULT_SKIP_SILENCE = false;

  /** Output mode of the audio sink. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({OUTPUT_MODE_PCM, OUTPUT_MODE_OFFLOAD, OUTPUT_MODE_PASSTHROUGH})
  public @interface OutputMode {}

  /** The audio sink plays PCM audio. */
  public static final int OUTPUT_MODE_PCM = 0;

  /** The audio sink plays encoded audio in offload. */
  public static final int OUTPUT_MODE_OFFLOAD = 1;

  /** The audio sink plays encoded audio in passthrough. */
  public static final int OUTPUT_MODE_PASSTHROUGH = 2;

  private static final String TAG = "DefaultAudioSink";

  private static final AtomicInteger pendingReleaseCount = new AtomicInteger();

  @Nullable private final Context context;
  private final androidx.media3.common.audio.AudioProcessorChain audioProcessorChain;
  private final boolean enableFloatOutput;
  private final ChannelMappingAudioProcessor channelMappingAudioProcessor;
  private final TrimmingAudioProcessor trimmingAudioProcessor;
  private final ToInt16PcmAudioProcessor toInt16PcmAudioProcessor;
  private final ToFloatPcmAudioProcessor toFloatPcmAudioProcessor;
  private final ImmutableList<AudioProcessor> availableAudioProcessors;
  private final ArrayDeque<MediaPositionParameters> mediaPositionParametersCheckpoints;
  private final boolean preferAudioOutputPlaybackParameters;
  private @OffloadMode int offloadMode;
  @Nullable private AudioOutputListener audioOutputListener;
  private final PendingExceptionHolder<InitializationException>
      initializationExceptionPendingExceptionHolder;
  private final PendingExceptionHolder<WriteException> writeExceptionPendingExceptionHolder;
  @Nullable private final AudioOffloadListener audioOffloadListener;

  @Nullable private PlayerId playerId;
  @Nullable private Listener listener;
  @Nullable private Configuration pendingConfiguration;
  private @MonotonicNonNull Configuration configuration;
  private @MonotonicNonNull AudioProcessingPipeline audioProcessingPipeline;
  private AudioOutputProvider audioOutputProvider;
  private AudioOutputProvider.@MonotonicNonNull Listener audioOutputProviderListener;
  @Nullable private AudioOutput audioOutput;

  private AudioAttributes audioAttributes;
  @Nullable private MediaPositionParameters afterDrainParameters;
  private MediaPositionParameters mediaPositionParameters;
  private PlaybackParameters playbackParameters;
  private boolean skipSilenceEnabled;

  private long submittedPcmBytes;
  private long submittedEncodedFrames;
  private long writtenPcmBytes;
  private long writtenEncodedFrames;
  private int framesPerEncodedSample;
  private boolean startMediaTimeUsNeedsSync;
  private boolean startMediaTimeUsNeedsInit;
  private long startMediaTimeUs;
  private float volume;

  @Nullable private ByteBuffer inputBuffer;
  private int inputBufferAccessUnitCount;
  @Nullable private ByteBuffer outputBuffer;
  private boolean handledEndOfStream;
  private boolean stoppedAudioOutput;
  private boolean handledOffloadOnPresentationEnded;

  private boolean playing;
  private boolean externalAudioSessionIdProvided;
  private int audioSessionId;
  private boolean pendingAudioSessionIdChangeConfirmation;
  private AuxEffectInfo auxEffectInfo;
  @Nullable private AudioDeviceInfo preferredDevice;
  private int virtualDeviceId;
  private boolean tunneling;
  private long lastFeedElapsedRealtimeMs;
  private boolean offloadDisabledUntilNextConfiguration;
  private boolean isWaitingForOffloadEndOfStreamHandled;
  private long skippedOutputFrameCountAtLastPosition;
  private long accumulatedSkippedSilenceDurationUs;
  private @MonotonicNonNull Handler reportSkippedSilenceHandler;

  @RequiresNonNull("#1.audioProcessorChain")
  private DefaultAudioSink(Builder builder) {
    context = builder.context == null ? null : builder.context.getApplicationContext();
    audioAttributes = AudioAttributes.DEFAULT;
    audioProcessorChain = builder.audioProcessorChain;
    enableFloatOutput = builder.enableFloatOutput;
    preferAudioOutputPlaybackParameters = builder.enableAudioOutputPlaybackParameters;
    offloadMode = OFFLOAD_MODE_DISABLED;
    audioOutputProvider = builder.audioOutputProvider;
    channelMappingAudioProcessor = new ChannelMappingAudioProcessor();
    trimmingAudioProcessor = new TrimmingAudioProcessor();
    toInt16PcmAudioProcessor = new ToInt16PcmAudioProcessor();
    toFloatPcmAudioProcessor = new ToFloatPcmAudioProcessor();
    availableAudioProcessors =
        ImmutableList.of(trimmingAudioProcessor, channelMappingAudioProcessor);
    volume = 1f;
    audioSessionId = C.AUDIO_SESSION_ID_UNSET;
    auxEffectInfo = new AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f);
    mediaPositionParameters =
        new MediaPositionParameters(
            PlaybackParameters.DEFAULT, /* mediaTimeUs= */ 0, /* audioOutputPositionUs= */ 0);
    playbackParameters = PlaybackParameters.DEFAULT;
    skipSilenceEnabled = DEFAULT_SKIP_SILENCE;
    mediaPositionParametersCheckpoints = new ArrayDeque<>();
    initializationExceptionPendingExceptionHolder = new PendingExceptionHolder<>();
    writeExceptionPendingExceptionHolder = new PendingExceptionHolder<>();
    audioOffloadListener = builder.audioOffloadListener;
    virtualDeviceId =
        SDK_INT < 34 || builder.context == null
            ? C.INDEX_UNSET
            : getDeviceIdFromContext(builder.context);
  }

  // AudioSink implementation.

  @Override
  public void setListener(Listener listener) {
    this.listener = listener;
  }

  @Override
  public void setPlayerId(@Nullable PlayerId playerId) {
    this.playerId = playerId;
  }

  @Override
  public void setClock(Clock clock) {
    audioOutputProvider.setClock(clock);
  }

  @Override
  public boolean supportsFormat(Format format) {
    return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  public @SinkFormatSupport int getFormatSupport(Format format) {
    // For PCM formats, convert the format to what our audio processors will produce.
    boolean transcodingViaAudioProcessors = false;
    if (Util.isEncodingLinearPcm(format.pcmEncoding)) {
      boolean usesFloatPcm = shouldUseFloatOutput(format.pcmEncoding);
      if (usesFloatPcm && format.pcmEncoding != C.ENCODING_PCM_FLOAT) {
        format = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build();
        transcodingViaAudioProcessors = true;
      }
      if (!usesFloatPcm && format.pcmEncoding != C.ENCODING_PCM_16BIT) {
        format = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build();
        transcodingViaAudioProcessors = true;
      }
    }
    switch (audioOutputProvider.getFormatSupport(getFormatConfig(format)).supportLevel) {
      case AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY:
        return transcodingViaAudioProcessors
            ? SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            : SINK_FORMAT_SUPPORTED_DIRECTLY;
      case AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING:
        return SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
      case AudioOutputProvider.FORMAT_UNSUPPORTED:
      default:
        return SINK_FORMAT_UNSUPPORTED;
    }
  }

  @Override
  public AudioOffloadSupport getFormatOffloadSupport(Format format) {
    if (offloadDisabledUntilNextConfiguration) {
      return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
    }
    FormatSupport formatSupport = audioOutputProvider.getFormatSupport(getFormatConfig(format));
    return new AudioOffloadSupport.Builder()
        .setIsFormatSupported(formatSupport.isFormatSupportedForOffload)
        .setIsGaplessSupported(formatSupport.isGaplessSupportedForOffload)
        .setIsSpeedChangeSupported(formatSupport.isSpeedChangeSupportedForOffload)
        .build();
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    if (!isAudioOutputInitialized() || startMediaTimeUsNeedsInit) {
      return CURRENT_POSITION_NOT_SET;
    }
    long positionUs = audioOutput.getPositionUs();
    positionUs = min(positionUs, configuration.framesToDurationUs(getWrittenFrames()));
    return applySkipping(applyMediaPositionParameters(positionUs));
  }

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    AudioProcessingPipeline audioProcessingPipeline;
    int inputPcmFrameSize;
    int outputPcmFrameSize;
    Format afterProcessingFormat;

    maybeAddAudioOutputProviderListener();

    if (MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType)) {
      checkArgument(Util.isEncodingLinearPcm(inputFormat.pcmEncoding));

      inputPcmFrameSize = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount);

      ImmutableList.Builder<AudioProcessor> pipelineProcessors = new ImmutableList.Builder<>();
      pipelineProcessors.addAll(availableAudioProcessors);
      if (shouldUseFloatOutput(inputFormat.pcmEncoding)) {
        pipelineProcessors.add(toFloatPcmAudioProcessor);
      } else {
        pipelineProcessors.add(toInt16PcmAudioProcessor);
        pipelineProcessors.add(audioProcessorChain.getAudioProcessors());
      }
      audioProcessingPipeline = new AudioProcessingPipeline(pipelineProcessors.build());

      // If the underlying processors of the new pipeline are the same as the existing pipeline,
      // then use the existing one when the configuration is used.
      if (audioProcessingPipeline.equals(this.audioProcessingPipeline)) {
        audioProcessingPipeline = this.audioProcessingPipeline;
      }

      trimmingAudioProcessor.setTrimFrameCount(
          inputFormat.encoderDelay, inputFormat.encoderPadding);

      channelMappingAudioProcessor.setChannelMap(outputChannels);

      AudioProcessor.AudioFormat outputFormat = new AudioProcessor.AudioFormat(inputFormat);
      try {
        outputFormat = audioProcessingPipeline.configure(outputFormat);
      } catch (UnhandledAudioFormatException e) {
        throw new ConfigurationException(e, inputFormat);
      }

      afterProcessingFormat =
          inputFormat
              .buildUpon()
              .setPcmEncoding(outputFormat.encoding)
              .setSampleRate(outputFormat.sampleRate)
              .setChannelCount(outputFormat.channelCount)
              .build();
      outputPcmFrameSize = Util.getPcmFrameSize(outputFormat.encoding, outputFormat.channelCount);
    } else {
      // Audio processing is not supported in offload or passthrough mode.
      audioProcessingPipeline = new AudioProcessingPipeline(ImmutableList.of());
      inputPcmFrameSize = C.LENGTH_UNSET;
      outputPcmFrameSize = C.LENGTH_UNSET;
      afterProcessingFormat = inputFormat;
    }

    OutputConfig outputConfig;
    int preferredBufferSize = specifiedBufferSize != 0 ? specifiedBufferSize : C.LENGTH_UNSET;
    FormatConfig formatConfig = getFormatConfig(afterProcessingFormat, preferredBufferSize);
    try {
      outputConfig = audioOutputProvider.getOutputConfig(formatConfig);
    } catch (AudioOutputProvider.ConfigurationException e) {
      throw new ConfigurationException(e, inputFormat);
    }

    if (outputConfig.encoding == C.ENCODING_INVALID) {
      throw new ConfigurationException(
          "Invalid output encoding (isOffload=" + outputConfig.isOffload + ")",
          formatConfig.format);
    }
    if (outputConfig.channelMask == AudioFormat.CHANNEL_INVALID) {
      throw new ConfigurationException(
          "Invalid output channel config (isOffload=" + outputConfig.isOffload + ")",
          formatConfig.format);
    }

    offloadDisabledUntilNextConfiguration = false;
    Configuration pendingConfiguration =
        new Configuration(
            inputFormat,
            afterProcessingFormat,
            inputPcmFrameSize,
            outputPcmFrameSize,
            outputConfig,
            audioProcessingPipeline);
    if (isAudioOutputInitialized()) {
      this.pendingConfiguration = pendingConfiguration;
    } else {
      configuration = pendingConfiguration;
    }
  }

  private void setupAudioProcessors() {
    audioProcessingPipeline = configuration.audioProcessingPipeline;
    audioProcessingPipeline.flush();
  }

  private boolean initializeAudioOutput() throws InitializationException {
    if (initializationExceptionPendingExceptionHolder.shouldWaitBeforeRetry()) {
      return false;
    }

    audioOutput = buildAudioOutputWithRetry();
    this.audioOutputListener = new AudioOutputListener(configuration.outputConfig);
    audioOutput.addListener(audioOutputListener);
    if (audioOffloadListener != null) {
      audioOffloadListener.onOffloadedPlayback(audioOutput.isOffloadedPlayback());
    }
    if (audioOutput.isOffloadedPlayback()) {
      if (configuration.outputConfig.useOffloadGapless) {
        audioOutput.setOffloadDelayPadding(
            configuration.inputFormat.encoderDelay, configuration.inputFormat.encoderPadding);
      }
    }
    if (playerId != null) {
      audioOutput.setPlayerId(playerId);
    }
    setVolumeInternal();

    if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
      audioOutput.attachAuxEffect(auxEffectInfo.effectId);
      audioOutput.setAuxEffectSendLevel(auxEffectInfo.sendLevel);
    }
    if (preferredDevice != null) {
      audioOutput.setPreferredDevice(preferredDevice);
    }
    startMediaTimeUsNeedsInit = true;

    int newAudioSessionId = audioOutput.getAudioSessionId();
    boolean audioSessionIdChanged = newAudioSessionId != audioSessionId;
    audioSessionId = newAudioSessionId;

    if (listener != null) {
      listener.onAudioTrackInitialized(configuration.buildAudioTrackConfig());
      if (audioSessionIdChanged) {
        pendingAudioSessionIdChangeConfirmation = true;
        configuration =
            configuration.copyWithOutputConfig(
                configuration.outputConfig.buildUpon().setAudioSessionId(audioSessionId).build());
        if (pendingConfiguration != null) {
          pendingConfiguration =
              pendingConfiguration.copyWithOutputConfig(
                  pendingConfiguration
                      .outputConfig
                      .buildUpon()
                      .setAudioSessionId(audioSessionId)
                      .build());
        }
        listener.onAudioSessionIdChanged(audioSessionId);
      }
    }

    return true;
  }

  @Override
  public void play() {
    playing = true;
    if (isAudioOutputInitialized()) {
      audioOutput.play();
    }
  }

  @Override
  public void handleDiscontinuity() {
    startMediaTimeUsNeedsSync = true;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public boolean handleBuffer(
      ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
      throws InitializationException, WriteException {
    checkArgument(inputBuffer == null || buffer == inputBuffer);

    if (pendingConfiguration != null) {
      if (!drainToEndOfStream()) {
        // There's still pending data in audio processors to write to the output.
        return false;
      } else if (!pendingConfiguration.canReuseAudioOutput(configuration)) {
        playPendingData();
        if (hasPendingData()) {
          // We're waiting for playout on the current audio output to finish.
          return false;
        }
        flush();
      } else {
        // The current audio output can be reused for the new configuration.
        configuration = pendingConfiguration;
        pendingConfiguration = null;
        if (audioOutput != null
            && audioOutput.isOffloadedPlayback()
            && configuration.outputConfig.useOffloadGapless) {
          audioOutput.setOffloadEndOfStream();
          audioOutput.setOffloadDelayPadding(
              configuration.inputFormat.encoderDelay, configuration.inputFormat.encoderPadding);
          isWaitingForOffloadEndOfStreamHandled = true;
        }
      }
      // Re-apply playback parameters.
      applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
    }

    if (!isAudioOutputInitialized()) {
      try {
        if (!initializeAudioOutput()) {
          // Not yet ready for initialization of a new audio output.
          return false;
        }
      } catch (InitializationException e) {
        if (e.isRecoverable) {
          throw e; // Do not delay the exception if it can be recovered at higher level.
        }
        initializationExceptionPendingExceptionHolder.throwExceptionIfDeadlineIsReached(e);
        return false;
      }
    }
    initializationExceptionPendingExceptionHolder.clear();

    if (startMediaTimeUsNeedsInit) {
      startMediaTimeUs = max(0, presentationTimeUs);
      startMediaTimeUsNeedsSync = false;
      startMediaTimeUsNeedsInit = false;

      if (useAudioOutputPlaybackParams()) {
        setAudioOutputPlaybackParameters();
      }
      applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);

      if (playing) {
        play();
      }
    }

    if (inputBuffer == null) {
      // We are seeing this buffer for the first time.
      checkArgument(buffer.order() == ByteOrder.LITTLE_ENDIAN);
      if (!buffer.hasRemaining()) {
        // The buffer is empty.
        return true;
      }

      if (!configuration.isPcm() && framesPerEncodedSample == 0) {
        // If this is the first encoded sample, calculate the sample size in frames.
        framesPerEncodedSample =
            getFramesPerEncodedSample(configuration.outputConfig.encoding, buffer);
        if (framesPerEncodedSample == 0) {
          // We still don't know the number of frames per sample, so drop the buffer.
          // For TrueHD this can occur after some seek operations, as not every sample starts with
          // a syncframe header. If we chunked samples together so the extracted samples always
          // started with a syncframe header, the chunks would be too large.
          return true;
        }
      }

      if (afterDrainParameters != null) {
        if (!drainToEndOfStream()) {
          // Don't process any more input until draining completes.
          return false;
        }
        applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
        afterDrainParameters = null;
      }

      // Check that presentationTimeUs is consistent with the expected value.
      long expectedPresentationTimeUs =
          startMediaTimeUs
              + configuration.inputFramesToDurationUs(
                  getSubmittedFrames() - trimmingAudioProcessor.getTrimmedFrameCount());
      if (!startMediaTimeUsNeedsSync
          && Math.abs(expectedPresentationTimeUs - presentationTimeUs) > 200000) {
        if (listener != null) {
          listener.onAudioSinkError(
              new AudioSink.UnexpectedDiscontinuityException(
                  presentationTimeUs, expectedPresentationTimeUs));
        }
        startMediaTimeUsNeedsSync = true;
      }
      if (startMediaTimeUsNeedsSync) {
        if (!drainToEndOfStream()) {
          // Don't update timing until pending AudioProcessor buffers are completely drained.
          return false;
        }
        // Adjust startMediaTimeUs to be consistent with the current buffer's start time and the
        // number of bytes submitted.
        long adjustmentUs = presentationTimeUs - expectedPresentationTimeUs;
        startMediaTimeUs += adjustmentUs;
        startMediaTimeUsNeedsSync = false;
        // Re-apply playback parameters because the startMediaTimeUs changed.
        applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
        if (listener != null && adjustmentUs != 0) {
          listener.onPositionDiscontinuity();
        }
      }

      if (configuration.isPcm()) {
        submittedPcmBytes += buffer.remaining();
      } else {
        submittedEncodedFrames += (long) framesPerEncodedSample * encodedAccessUnitCount;
      }

      inputBuffer = buffer;
      inputBufferAccessUnitCount = encodedAccessUnitCount;
    }

    processBuffers(presentationTimeUs);

    if (!inputBuffer.hasRemaining()) {
      inputBuffer = null;
      inputBufferAccessUnitCount = 0;
      return true;
    }

    if (audioOutput.isStalled()) {
      Log.w(TAG, "Resetting stalled audio output");
      flush();
      return true;
    }

    return false;
  }

  private AudioOutput buildAudioOutputWithRetry() throws InitializationException {
    try {
      return buildAudioOutput(configuration.outputConfig);
    } catch (InitializationException initialFailure) {
      int bufferSize = configuration.outputConfig.bufferSize;
      while (bufferSize > AUDIO_OUTPUT_RETRY_BUFFER_SIZE_THRESHOLD) {
        // Retry with a smaller buffer size, which is the half of the original buffer size.
        bufferSize /= 2;
        int frameSize =
            configuration.outputPcmFrameSize != C.LENGTH_UNSET
                ? configuration.outputPcmFrameSize
                : 1;
        int partialFrameSize = bufferSize % frameSize;
        if (partialFrameSize != 0) {
          // Increase buffer size to hold an integer number of frames.
          bufferSize += frameSize - partialFrameSize;
        }
        OutputConfig retryConfiguration =
            configuration.outputConfig.buildUpon().setBufferSize(bufferSize).build();
        try {
          AudioOutput audioOutput = buildAudioOutput(retryConfiguration);
          configuration = configuration.copyWithOutputConfig(retryConfiguration);
          return audioOutput;
        } catch (InitializationException retryFailure) {
          initialFailure.addSuppressed(retryFailure);
        }
      }
      maybeDisableOffload();
      throw initialFailure;
    }
  }

  private AudioOutput buildAudioOutput(OutputConfig outputConfig) throws InitializationException {
    try {
      return audioOutputProvider.getAudioOutput(outputConfig);
    } catch (AudioOutputProvider.InitializationException e) {
      InitializationException exception =
          new InitializationException(
              AudioTrack.STATE_UNINITIALIZED,
              outputConfig.sampleRate,
              outputConfig.channelMask,
              outputConfig.encoding,
              outputConfig.bufferSize,
              configuration.inputFormat,
              /* isRecoverable= */ outputConfig.isOffload,
              e);
      if (listener != null) {
        listener.onAudioSinkError(exception);
      }
      throw exception;
    }
  }

  /**
   * Repeatedly drains and feeds the {@link AudioProcessingPipeline} until {@link
   * #drainOutputBuffer(long)} is not able to fully drain the output or there is no more input to
   * feed into the pipeline.
   *
   * <p>If the {@link AudioProcessingPipeline} is not {@linkplain
   * AudioProcessingPipeline#isOperational() operational}, input buffers are passed straight to
   * {@link #setOutputBuffer(ByteBuffer)}.
   *
   * @param avSyncPresentationTimeUs The tunneling AV sync presentation time for the current buffer,
   *     or {@link C#TIME_END_OF_SOURCE} when draining remaining buffers at the end of the stream.
   */
  private void processBuffers(long avSyncPresentationTimeUs) throws WriteException {
    // Drain existing buffer first.
    drainOutputBuffer(avSyncPresentationTimeUs);
    if (outputBuffer != null) {
      // The existing output buffer is not fully processed.
      return;
    }

    // Obtain new output buffer and start draining.
    if (!audioProcessingPipeline.isOperational()) {
      if (inputBuffer != null) {
        setOutputBuffer(inputBuffer);
        drainOutputBuffer(avSyncPresentationTimeUs);
      }
      return;
    }

    while (!audioProcessingPipeline.isEnded()) {
      ByteBuffer bufferToWrite;
      while ((bufferToWrite = audioProcessingPipeline.getOutput()).hasRemaining()) {
        setOutputBuffer(bufferToWrite);
        drainOutputBuffer(avSyncPresentationTimeUs);
        if (outputBuffer != null) {
          // drainOutputBuffer method is providing back pressure.
          return;
        }
      }
      if (inputBuffer == null || !inputBuffer.hasRemaining()) {
        return;
      }
      audioProcessingPipeline.queueInput(inputBuffer);
    }
  }

  /**
   * Queues end of stream and then fully drains all buffers.
   *
   * @return Whether the buffers have been fully drained.
   */
  private boolean drainToEndOfStream() throws WriteException {
    if (!audioProcessingPipeline.isOperational()) {
      drainOutputBuffer(C.TIME_END_OF_SOURCE);
      return outputBuffer == null;
    }

    audioProcessingPipeline.queueEndOfStream();
    processBuffers(C.TIME_END_OF_SOURCE);
    return audioProcessingPipeline.isEnded()
        && (outputBuffer == null || !outputBuffer.hasRemaining());
  }

  /**
   * Sets a new output buffer.
   *
   * <p>Must only be called if the existing {@link #outputBuffer} is null (i.e. has been fully
   * drained with {@link #drainOutputBuffer}.
   *
   * @param buffer The buffer to set.
   */
  private void setOutputBuffer(ByteBuffer buffer) {
    checkState(outputBuffer == null);
    if (!buffer.hasRemaining()) {
      return;
    }
    outputBuffer = maybeRampUpVolume(buffer);
  }

  /**
   * Drains the {@link #outputBuffer} by writing it to the audio output.
   *
   * <p>{@link #outputBuffer} will be set to null if it has been fully drained.
   *
   * @param avSyncPresentationTimeUs The tunneling AV sync presentation time for the buffer, or
   *     {@link C#TIME_END_OF_SOURCE} when draining remaining buffers at the end of the stream.
   */
  @SuppressWarnings("ReferenceEquality")
  private void drainOutputBuffer(long avSyncPresentationTimeUs) throws WriteException {
    if (outputBuffer == null) {
      return;
    }
    if (writeExceptionPendingExceptionHolder.shouldWaitBeforeRetry()) {
      return;
    }
    int bytesRemaining = outputBuffer.remaining();
    boolean fullyHandled;
    try {
      fullyHandled =
          audioOutput.write(outputBuffer, inputBufferAccessUnitCount, avSyncPresentationTimeUs);
    } catch (AudioOutput.WriteException e) {
      // Treat a write error on a previously successful offload channel as recoverable
      // without disabling offload. Offload will be disabled if offload channel was not successfully
      // written to or when a new AudioOutput is created, if no longer supported.
      boolean shouldRetry = false;
      if (e.isRecoverable) {
        if (getWrittenFrames() > 0) {
          shouldRetry = true;
        } else if (audioOutput.isOffloadedPlayback()) {
          maybeDisableOffload();
          shouldRetry = true;
        }
      }

      WriteException error =
          new WriteException(e.errorCode, configuration.inputFormat, shouldRetry);
      if (listener != null) {
        listener.onAudioSinkError(error);
      }
      if (e.isRecoverable) {
        throw error; // Do not delay the exception if it can be recovered at a higher level.
      }
      writeExceptionPendingExceptionHolder.throwExceptionIfDeadlineIsReached(error);
      return;
    }

    lastFeedElapsedRealtimeMs = SystemClock.elapsedRealtime();
    writeExceptionPendingExceptionHolder.clear();

    if (audioOutput.isOffloadedPlayback()) {
      // After calling AudioOutput.setOffloadEndOfStream, the AudioOutput internally stops and
      // restarts during which AudioOutput.write will return 0. This situation must be detected to
      // prevent reporting the buffer as full even though it is not which could lead ExoPlayer to
      // sleep forever waiting for a onDataRequest that will never come.
      if (writtenEncodedFrames > 0) {
        isWaitingForOffloadEndOfStreamHandled = false;
      }

      // Consider the offload buffer as full if the AudioOutput is playing and AudioOutput.write
      // could not write all the data provided to it. This relies on the assumption that
      // AudioOutput.write always writes as much as possible.
      if (playing && listener != null && !fullyHandled && !isWaitingForOffloadEndOfStreamHandled) {
        listener.onOffloadBufferFull();
      }
    }

    if (configuration.isPcm()) {
      writtenPcmBytes += bytesRemaining - outputBuffer.remaining();
    }
    if (fullyHandled) {
      if (!configuration.isPcm()) {
        // When playing non-PCM, the inputBuffer is never processed, thus the last inputBuffer
        // must be the current input buffer.
        checkState(outputBuffer == inputBuffer);
        writtenEncodedFrames += (long) framesPerEncodedSample * inputBufferAccessUnitCount;
      }
      outputBuffer = null;
    }
  }

  @Override
  public void playToEndOfStream() throws WriteException {
    if (!handledEndOfStream && isAudioOutputInitialized() && drainToEndOfStream()) {
      playPendingData();
      handledEndOfStream = true;
    }
  }

  private void maybeDisableOffload() {
    if (!configuration.outputConfig.isOffload) {
      return;
    }
    // Offload was requested, but may not be available. There are cases when this can occur even if
    // AudioManager.isOffloadedPlaybackSupported returned true. For example, due to use of an
    // AudioPlaybackCaptureConfiguration. Disable offload until the sink is next configured.
    offloadDisabledUntilNextConfiguration = true;
  }

  @Override
  public boolean isEnded() {
    return !isAudioOutputInitialized() || (handledEndOfStream && !hasPendingData());
  }

  @Override
  public boolean hasPendingData() {
    return isAudioOutputInitialized()
        && (SDK_INT < 29
            || !audioOutput.isOffloadedPlayback()
            || !handledOffloadOnPresentationEnded)
        && hasAudioOutputPendingData(getWrittenFrames());
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    if (useAudioOutputPlaybackParams()) {
      this.playbackParameters = playbackParameters;
      setAudioOutputPlaybackParameters();
    } else {
      this.playbackParameters =
          new PlaybackParameters(
              constrainValue(playbackParameters.speed, MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
              constrainValue(playbackParameters.pitch, MIN_PITCH, MAX_PITCH));
      setAudioProcessorPlaybackParameters(this.playbackParameters);
    }
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return playbackParameters;
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    this.skipSilenceEnabled = skipSilenceEnabled;
    // Skip silence is applied together with the AudioProcessor playback parameters after draining
    // the pipeline. Force a drain by re-applying the current playback parameters.
    setAudioProcessorPlaybackParameters(
        useAudioOutputPlaybackParams() ? PlaybackParameters.DEFAULT : playbackParameters);
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    return skipSilenceEnabled;
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    if (this.audioAttributes.equals(audioAttributes)) {
      return;
    }
    this.audioAttributes = audioAttributes;
    if (tunneling) {
      // The audio attributes are ignored in tunneling mode, so no need to reset.
      return;
    }
    reconfigureAndFlush();
  }

  @Override
  public AudioAttributes getAudioAttributes() {
    return audioAttributes;
  }

  @Nullable
  @Override
  public AudioCapabilities getAudioCapabilities() {
    if (audioOutputProvider instanceof AudioTrackAudioOutputProvider) {
      return ((AudioTrackAudioOutputProvider) audioOutputProvider).getAudioCapabilities();
    }
    return null;
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    if (pendingAudioSessionIdChangeConfirmation) {
      if (this.audioSessionId == audioSessionId) {
        pendingAudioSessionIdChangeConfirmation = false;
      } else {
        return;
      }
    }
    if (this.audioSessionId != audioSessionId) {
      this.audioSessionId = audioSessionId;
      externalAudioSessionIdProvided = audioSessionId != C.AUDIO_SESSION_ID_UNSET;
      reconfigureAndFlush();
    }
  }

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    if (this.auxEffectInfo.equals(auxEffectInfo)) {
      return;
    }
    int effectId = auxEffectInfo.effectId;
    float sendLevel = auxEffectInfo.sendLevel;
    if (audioOutput != null) {
      if (this.auxEffectInfo.effectId != effectId) {
        audioOutput.attachAuxEffect(effectId);
      }
      if (effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
        audioOutput.setAuxEffectSendLevel(sendLevel);
      }
    }
    this.auxEffectInfo = auxEffectInfo;
  }

  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
    this.preferredDevice = audioDeviceInfo;
    if (audioOutput != null) {
      audioOutput.setPreferredDevice(this.preferredDevice);
    }
  }

  @Override
  public void setVirtualDeviceId(int virtualDeviceId) {
    virtualDeviceId = resolveDefaultVirtualDeviceIds(virtualDeviceId);
    if (this.virtualDeviceId == virtualDeviceId) {
      return;
    }
    this.virtualDeviceId = virtualDeviceId;
    reconfigureAndFlush();
  }

  @Override
  public long getAudioTrackBufferSizeUs() {
    if (!isAudioOutputInitialized()) {
      return C.TIME_UNSET;
    }
    return configuration.isPcm()
        ? configuration.framesToDurationUs(audioOutput.getBufferSizeInFrames())
        : Util.scaleLargeValue(
            audioOutput.getBufferSizeInFrames(),
            C.MICROS_PER_SECOND,
            getNonPcmMaximumEncodedRateBytesPerSecond(configuration.outputConfig.encoding),
            RoundingMode.DOWN);
  }

  @Override
  public void enableTunnelingV21() {
    checkState(externalAudioSessionIdProvided);
    if (!tunneling) {
      tunneling = true;
      reconfigureAndFlush();
    }
  }

  @Override
  public void disableTunneling() {
    if (tunneling) {
      tunneling = false;
      reconfigureAndFlush();
    }
  }

  @RequiresApi(29)
  @Override
  public void setOffloadMode(@OffloadMode int offloadMode) {
    checkState(SDK_INT >= 29);
    this.offloadMode = offloadMode;
  }

  @RequiresApi(29)
  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    if (audioOutput != null
        && audioOutput.isOffloadedPlayback()
        && configuration != null
        && configuration.outputConfig.useOffloadGapless) {
      audioOutput.setOffloadDelayPadding(delayInFrames, paddingInFrames);
    }
  }

  @Override
  public void setAudioOutputProvider(AudioOutputProvider audioOutputProvider) {
    if (audioOutputProvider.equals(this.audioOutputProvider)) {
      return;
    }
    this.audioOutputProvider.release();
    this.audioOutputProvider = audioOutputProvider;
    if (audioOutputProviderListener != null) {
      audioOutputProvider.addListener(audioOutputProviderListener);
    }
    reconfigureAndFlush();
  }

  @Override
  public void setVolume(float volume) {
    if (this.volume != volume) {
      this.volume = volume;
      setVolumeInternal();
    }
  }

  private void setVolumeInternal() {
    if (isAudioOutputInitialized()) {
      audioOutput.setVolume(volume);
    }
  }

  @Override
  public void pause() {
    playing = false;
    if (isAudioOutputInitialized()) {
      audioOutput.pause();
    }
  }

  @Override
  public void flush() {
    if (isAudioOutputInitialized()) {
      resetSinkStateForFlush();
      audioOutputListener = null;
      if (pendingConfiguration != null) {
        configuration = pendingConfiguration;
        pendingConfiguration = null;
      }
      // We need to release the audio output on every flush because of known AudioTrack flush issues
      // on some devices. See b/7941810 or b/19193985.
      // TODO: b/143500232 - Experiment with not releasing AudioOutput on flush.
      pendingReleaseCount.incrementAndGet();
      audioOutput.release();
      audioOutput = null;
    }
    writeExceptionPendingExceptionHolder.clear();
    initializationExceptionPendingExceptionHolder.clear();
    skippedOutputFrameCountAtLastPosition = 0;
    accumulatedSkippedSilenceDurationUs = 0;
    if (reportSkippedSilenceHandler != null) {
      checkNotNull(reportSkippedSilenceHandler).removeCallbacksAndMessages(null);
    }
  }

  @Override
  public void reset() {
    flush();
    for (AudioProcessor audioProcessor : availableAudioProcessors) {
      audioProcessor.reset();
    }
    toInt16PcmAudioProcessor.reset();
    toFloatPcmAudioProcessor.reset();

    if (audioProcessingPipeline != null) {
      audioProcessingPipeline.reset();
    }
    playing = false;
    offloadDisabledUntilNextConfiguration = false;
  }

  @Override
  public void release() {
    audioOutputProvider.release();
  }

  // Internal methods.

  private void reconfigureAndFlush() {
    if (configuration != null) {
      if (pendingConfiguration != null) {
        configuration = pendingConfiguration;
        pendingConfiguration = null;
      }
      OutputConfig outputConfig;
      try {
        outputConfig =
            audioOutputProvider.getOutputConfig(
                getFormatConfig(configuration.afterProcessingInputFormat));
      } catch (AudioOutputProvider.ConfigurationException e) {
        // Shouldn't usually happen if the configuration succeeded with same setup before.
        throw new IllegalStateException(new ConfigurationException(e, configuration.inputFormat));
      }
      configuration =
          new Configuration(
              configuration.inputFormat,
              configuration.afterProcessingInputFormat,
              configuration.inputPcmFrameSize,
              configuration.outputPcmFrameSize,
              outputConfig,
              configuration.audioProcessingPipeline);
    }
    flush();
  }

  private void resetSinkStateForFlush() {
    submittedPcmBytes = 0;
    submittedEncodedFrames = 0;
    writtenPcmBytes = 0;
    writtenEncodedFrames = 0;
    isWaitingForOffloadEndOfStreamHandled = false;
    framesPerEncodedSample = 0;
    mediaPositionParameters =
        new MediaPositionParameters(
            playbackParameters, /* mediaTimeUs= */ 0, /* audioOutputPositionUs= */ 0);
    startMediaTimeUs = 0;
    afterDrainParameters = null;
    mediaPositionParametersCheckpoints.clear();
    inputBuffer = null;
    inputBufferAccessUnitCount = 0;
    outputBuffer = null;
    stoppedAudioOutput = false;
    handledEndOfStream = false;
    handledOffloadOnPresentationEnded = false;
    trimmingAudioProcessor.resetTrimmedFrameCount();
    setupAudioProcessors();
  }

  private void setAudioOutputPlaybackParameters() {
    if (isAudioOutputInitialized()) {
      audioOutput.setPlaybackParameters(playbackParameters);
      // Update the speed using the actual effective speed from the audio output.
      playbackParameters = audioOutput.getPlaybackParameters();
    }
  }

  private void setAudioProcessorPlaybackParameters(PlaybackParameters playbackParameters) {
    MediaPositionParameters mediaPositionParameters =
        new MediaPositionParameters(
            playbackParameters,
            /* mediaTimeUs= */ C.TIME_UNSET,
            /* audioOutputPositionUs= */ C.TIME_UNSET);
    if (isAudioOutputInitialized()) {
      // Drain the audio processors so we can determine the frame position at which the new
      // parameters apply.
      this.afterDrainParameters = mediaPositionParameters;
    } else {
      // Update the audio processor chain parameters now. They will be applied to the audio
      // processors during initialization.
      this.mediaPositionParameters = mediaPositionParameters;
    }
  }

  private void applyAudioProcessorPlaybackParametersAndSkipSilence(long presentationTimeUs) {
    PlaybackParameters audioProcessorPlaybackParameters;
    if (!useAudioOutputPlaybackParams()) {
      playbackParameters =
          shouldApplyAudioProcessorPlaybackParameters()
              ? audioProcessorChain.applyPlaybackParameters(playbackParameters)
              : PlaybackParameters.DEFAULT;
      audioProcessorPlaybackParameters = playbackParameters;
    } else {
      audioProcessorPlaybackParameters = PlaybackParameters.DEFAULT;
    }
    skipSilenceEnabled =
        shouldApplyAudioProcessorPlaybackParameters()
            ? audioProcessorChain.applySkipSilenceEnabled(skipSilenceEnabled)
            : DEFAULT_SKIP_SILENCE;
    mediaPositionParametersCheckpoints.add(
        new MediaPositionParameters(
            audioProcessorPlaybackParameters,
            /* mediaTimeUs= */ max(0, presentationTimeUs),
            /* audioOutputPositionUs= */ configuration.framesToDurationUs(getWrittenFrames())));
    setupAudioProcessors();
    if (listener != null) {
      listener.onSkipSilenceEnabledChanged(skipSilenceEnabled);
    }
  }

  /**
   * Returns whether audio processor playback parameters should be applied in the current
   * configuration.
   */
  private boolean shouldApplyAudioProcessorPlaybackParameters() {
    // We don't apply speed/pitch adjustment using an audio processor in the following cases:
    // - in tunneling mode, because audio processing can change the duration of audio yet the video
    //   frame presentation times are currently not modified (see also
    //   https://github.com/google/ExoPlayer/issues/4803);
    // - when playing encoded audio via passthrough/offload, because modifying the audio stream
    //   would require decoding/re-encoding; and
    // - when outputting float PCM audio, because SonicAudioProcessor outputs 16-bit integer PCM.
    return !tunneling
        && configuration.isPcm()
        && !shouldUseFloatOutput(configuration.inputFormat.pcmEncoding);
  }

  private boolean useAudioOutputPlaybackParams() {
    return configuration != null && configuration.outputConfig.usePlaybackParameters;
  }

  /**
   * Returns whether audio in the specified PCM encoding should be written to the audio output as
   * float PCM.
   */
  private boolean shouldUseFloatOutput(@C.PcmEncoding int pcmEncoding) {
    return enableFloatOutput && Util.isEncodingHighResolutionPcm(pcmEncoding);
  }

  /**
   * Applies and updates media position parameters.
   *
   * @param positionUs The current audio output position, in microseconds.
   * @return The current media time, in microseconds.
   */
  private long applyMediaPositionParameters(long positionUs) {
    while (!mediaPositionParametersCheckpoints.isEmpty()
        && positionUs >= mediaPositionParametersCheckpoints.getFirst().audioOutputPositionUs) {
      // We are playing (or about to play) media with the new parameters, so update them.
      mediaPositionParameters = mediaPositionParametersCheckpoints.remove();
    }

    long playoutDurationSinceLastCheckpointUs =
        positionUs - mediaPositionParameters.audioOutputPositionUs;
    long estimatedMediaDurationSinceLastCheckpointUs =
        Util.getMediaDurationForPlayoutDuration(
            playoutDurationSinceLastCheckpointUs, mediaPositionParameters.playbackParameters.speed);
    if (mediaPositionParametersCheckpoints.isEmpty()) {
      long actualMediaDurationSinceLastCheckpointUs =
          audioProcessorChain.getMediaDuration(playoutDurationSinceLastCheckpointUs);
      long currentMediaPositionUs =
          mediaPositionParameters.mediaTimeUs + actualMediaDurationSinceLastCheckpointUs;
      mediaPositionParameters.mediaPositionDriftUs =
          actualMediaDurationSinceLastCheckpointUs - estimatedMediaDurationSinceLastCheckpointUs;
      return currentMediaPositionUs;
    } else {
      // The processor chain has been configured with new parameters, but we're still playing audio
      // that was processed using previous parameters. We can't scale the playout duration using the
      // processor chain in this case, so we fall back to scaling using the previous parameters'
      // target speed instead.
      return mediaPositionParameters.mediaTimeUs
          + estimatedMediaDurationSinceLastCheckpointUs
          + mediaPositionParameters.mediaPositionDriftUs;
    }
  }

  private long applySkipping(long positionUs) {
    long skippedOutputFrameCountAtCurrentPosition =
        audioProcessorChain.getSkippedOutputFrameCount();
    long adjustedPositionUs =
        positionUs + configuration.framesToDurationUs(skippedOutputFrameCountAtCurrentPosition);
    if (skippedOutputFrameCountAtCurrentPosition > skippedOutputFrameCountAtLastPosition) {
      long silenceDurationUs =
          configuration.framesToDurationUs(
              skippedOutputFrameCountAtCurrentPosition - skippedOutputFrameCountAtLastPosition);
      skippedOutputFrameCountAtLastPosition = skippedOutputFrameCountAtCurrentPosition;
      handleSkippedSilence(silenceDurationUs);
    }
    return adjustedPositionUs;
  }

  private void handleSkippedSilence(long silenceDurationUs) {
    accumulatedSkippedSilenceDurationUs += silenceDurationUs;
    if (reportSkippedSilenceHandler == null) {
      reportSkippedSilenceHandler = new Handler(Looper.myLooper());
    }
    reportSkippedSilenceHandler.removeCallbacksAndMessages(null);
    reportSkippedSilenceHandler.postDelayed(
        this::maybeReportSkippedSilence, /* delayMillis= */ REPORT_SKIPPED_SILENCE_DELAY_MS);
  }

  private boolean isAudioOutputInitialized() {
    return audioOutput != null;
  }

  private long getSubmittedFrames() {
    return configuration.isPcm()
        ? (submittedPcmBytes / configuration.inputPcmFrameSize)
        : submittedEncodedFrames;
  }

  private long getWrittenFrames() {
    return configuration.isPcm()
        ? Util.ceilDivide(writtenPcmBytes, configuration.outputPcmFrameSize)
        : writtenEncodedFrames;
  }

  private void maybeAddAudioOutputProviderListener() {
    if (audioOutputProviderListener == null && context != null) {
      // Must be lazily initialized to receive listener events on the current (playback) thread as
      // the constructor is not called in the playback thread.
      audioOutputProviderListener =
          () -> {
            if (listener != null) {
              listener.onAudioCapabilitiesChanged();
            }
          };
      audioOutputProvider.addListener(audioOutputProviderListener);
    }
  }

  private FormatConfig getFormatConfig(Format format) {
    return getFormatConfig(format, /* preferredBufferSize= */ C.LENGTH_UNSET);
  }

  private FormatConfig getFormatConfig(Format format, int preferredBufferSize) {
    return new FormatConfig.Builder(format)
        .setAudioAttributes(audioAttributes)
        .setEnableHighResolutionPcmOutput(enableFloatOutput)
        .setEnablePlaybackParameters(preferAudioOutputPlaybackParameters)
        .setEnableOffload(offloadMode != AudioSink.OFFLOAD_MODE_DISABLED)
        .setPreferredDevice(preferredDevice)
        .setAudioSessionId(audioSessionId)
        .setEnableTunneling(tunneling)
        .setPreferredBufferSize(preferredBufferSize)
        .setVirtualDeviceId(virtualDeviceId)
        .build();
  }

  /* package */ static int getFramesPerEncodedSample(@C.Encoding int encoding, ByteBuffer buffer) {
    switch (encoding) {
      case C.ENCODING_MP3:
        int headerDataInBigEndian = Util.getBigEndianInt(buffer, buffer.position());
        int frameCount = MpegAudioUtil.parseMpegAudioFrameSampleCount(headerDataInBigEndian);
        if (frameCount == C.LENGTH_UNSET) {
          throw new IllegalArgumentException();
        }
        return frameCount;
      case C.ENCODING_AAC_LC:
        return AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_HE_V1:
      case C.ENCODING_AAC_HE_V2:
        return AacUtil.AAC_HE_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_XHE:
        return AacUtil.AAC_XHE_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_ELD:
        return AacUtil.AAC_LD_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_DTS:
      case C.ENCODING_DTS_HD:
      case C.ENCODING_DTS_UHD_P2:
        return DtsUtil.parseDtsAudioSampleCount(buffer);
      case C.ENCODING_AC3:
      case C.ENCODING_E_AC3:
      case C.ENCODING_E_AC3_JOC:
        return Ac3Util.parseAc3SyncframeAudioSampleCount(buffer);
      case C.ENCODING_AC4:
        return Ac4Util.parseAc4SyncframeAudioSampleCount(buffer);
      case C.ENCODING_DOLBY_TRUEHD:
        int syncframeOffset = Ac3Util.findTrueHdSyncframeOffset(buffer);
        return syncframeOffset == C.INDEX_UNSET
            ? 0
            : (Ac3Util.parseTrueHdSyncframeAudioSampleCount(buffer, syncframeOffset)
                * Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT);
      case C.ENCODING_OPUS:
        return OpusUtil.parseOggPacketAudioSampleCount(buffer);
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_8BIT:
      case C.ENCODING_PCM_FLOAT:
      case C.ENCODING_PCM_DOUBLE:
      case C.ENCODING_AAC_ER_BSAC:
      case C.ENCODING_DSD:
      case C.ENCODING_INVALID:
      case Format.NO_VALUE:
      default:
        throw new IllegalStateException("Unexpected audio encoding: " + encoding);
    }
  }

  private void playPendingData() {
    if (!stoppedAudioOutput) {
      stoppedAudioOutput = true;
      if (audioOutput.isOffloadedPlayback()) {
        // Reset handledOffloadOnPresentationEnded to track completion after
        // this following stop call.
        handledOffloadOnPresentationEnded = false;
      }
      audioOutput.stop();
    }
  }

  private ByteBuffer maybeRampUpVolume(ByteBuffer buffer) {
    if (!configuration.isPcm()) {
      return buffer;
    }
    long rampDurationUs = msToUs(AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS);
    int rampFrameCount =
        (int) Util.durationUsToSampleCount(rampDurationUs, configuration.outputConfig.sampleRate);
    long writtenFrames = getWrittenFrames();
    if (writtenFrames >= rampFrameCount) {
      return buffer;
    }
    return PcmAudioUtil.rampUpVolume(
        buffer,
        configuration.outputConfig.encoding,
        configuration.outputPcmFrameSize,
        (int) writtenFrames,
        rampFrameCount);
  }

  private boolean hasAudioOutputPendingData(long writtenFrames) {
    long currentPositionFrames =
        Util.durationUsToSampleCount(
            audioOutput.getPositionUs(), checkNotNull(audioOutput).getSampleRate());
    return writtenFrames > currentPositionFrames;
  }

  private static boolean hasPendingAudioOutputReleases() {
    return pendingReleaseCount.get() > 0;
  }

  @RequiresApi(34)
  private static int getDeviceIdFromContext(Context context) {
    return resolveDefaultVirtualDeviceIds(context.getDeviceId());
  }

  private static int resolveDefaultVirtualDeviceIds(int deviceId) {
    return deviceId != Context.DEVICE_ID_DEFAULT && deviceId != Context.DEVICE_ID_INVALID
        ? deviceId
        : C.INDEX_UNSET;
  }

  private final class AudioOutputListener implements AudioOutput.Listener {

    private final OutputConfig outputConfig;

    private AudioOutputListener(OutputConfig outputConfig) {
      this.outputConfig = outputConfig;
    }

    @Override
    public void onPositionAdvancing(long playoutStartSystemTimeMs) {
      if (!this.equals(audioOutputListener)) {
        // Stale event.
        return;
      }
      if (listener != null) {
        listener.onPositionAdvancing(playoutStartSystemTimeMs);
      }
    }

    @Override
    public void onOffloadDataRequest() {
      if (!this.equals(audioOutputListener)) {
        // Stale event.
        return;
      }
      if (listener != null && playing) {
        // Do not signal that the buffer is emptying if not playing as it is a transient
        // state.
        listener.onOffloadBufferEmptying();
      }
    }

    @Override
    public void onOffloadPresentationEnded() {
      if (!this.equals(audioOutputListener)) {
        // Stale event.
        return;
      }
      handledOffloadOnPresentationEnded = true;
    }

    @Override
    public void onUnderrun() {
      if (!this.equals(audioOutputListener)) {
        // Stale event.
        return;
      }
      if (listener != null) {
        long bufferSizeUs =
            configuration.outputPcmFrameSize != C.LENGTH_UNSET
                ? Util.sampleCountToDurationUs(
                    configuration.outputConfig.bufferSize / configuration.outputPcmFrameSize,
                    checkNotNull(audioOutput).getSampleRate())
                : C.TIME_UNSET;
        long elapsedSinceLastFeedMs = SystemClock.elapsedRealtime() - lastFeedElapsedRealtimeMs;
        listener.onUnderrun(
            configuration.outputConfig.bufferSize,
            Util.usToMs(bufferSizeUs),
            elapsedSinceLastFeedMs);
      }
    }

    @Override
    public void onReleased() {
      // Don't check for stale events. It's expected that this event arrives after the class field
      // has been updated to null or a new listener.
      pendingReleaseCount.getAndDecrement();
      if (listener != null) {
        listener.onAudioTrackReleased(
            new AudioTrackConfig(
                outputConfig.encoding,
                outputConfig.sampleRate,
                outputConfig.channelMask,
                outputConfig.isTunneling,
                outputConfig.isOffload,
                outputConfig.bufferSize));
      }
    }
  }

  /** Stores parameters used to calculate the current media position. */
  private static final class MediaPositionParameters {

    /** The playback parameters. */
    public final PlaybackParameters playbackParameters;

    /** The media time from which the playback parameters apply, in microseconds. */
    public final long mediaTimeUs;

    /** The audio output position from which the playback parameters apply, in microseconds. */
    public final long audioOutputPositionUs;

    /**
     * An updatable value for the observed drift between the actual media time and the one that can
     * be calculated from the other parameters.
     */
    public long mediaPositionDriftUs;

    private MediaPositionParameters(
        PlaybackParameters playbackParameters, long mediaTimeUs, long audioOutputPositionUs) {
      this.playbackParameters = playbackParameters;
      this.mediaTimeUs = mediaTimeUs;
      this.audioOutputPositionUs = audioOutputPositionUs;
    }
  }

  /** Stores configuration relating to the audio format. */
  private static final class Configuration {

    private final Format inputFormat;
    private final Format afterProcessingInputFormat;
    private final int inputPcmFrameSize;
    private final int outputPcmFrameSize;
    private final OutputConfig outputConfig;
    private final AudioProcessingPipeline audioProcessingPipeline;

    private Configuration(
        Format inputFormat,
        Format afterProcessingInputFormat,
        int inputPcmFrameSize,
        int outputPcmFrameSize,
        OutputConfig outputConfig,
        AudioProcessingPipeline audioProcessingPipeline) {
      this.inputFormat = inputFormat;
      this.afterProcessingInputFormat = afterProcessingInputFormat;
      this.inputPcmFrameSize = inputPcmFrameSize;
      this.outputPcmFrameSize = outputPcmFrameSize;
      this.outputConfig = outputConfig;
      this.audioProcessingPipeline = audioProcessingPipeline;
    }

    private Configuration copyWithOutputConfig(OutputConfig outputConfig) {
      return new Configuration(
          inputFormat,
          afterProcessingInputFormat,
          inputPcmFrameSize,
          outputPcmFrameSize,
          outputConfig,
          audioProcessingPipeline);
    }

    /** Returns if the configurations are sufficiently compatible to reuse the audio output. */
    private boolean canReuseAudioOutput(Configuration newConfiguration) {
      return newConfiguration.outputConfig.equals(outputConfig);
    }

    private long inputFramesToDurationUs(long frameCount) {
      return Util.sampleCountToDurationUs(frameCount, inputFormat.sampleRate);
    }

    private long framesToDurationUs(long frameCount) {
      return Util.sampleCountToDurationUs(frameCount, outputConfig.sampleRate);
    }

    private AudioTrackConfig buildAudioTrackConfig() {
      return new AudioTrackConfig(
          outputConfig.encoding,
          outputConfig.sampleRate,
          outputConfig.channelMask,
          outputConfig.isTunneling,
          outputConfig.isOffload,
          outputConfig.bufferSize);
    }

    private boolean isPcm() {
      return Objects.equals(inputFormat.sampleMimeType, MimeTypes.AUDIO_RAW);
    }
  }

  private static final class PendingExceptionHolder<T extends Exception> {

    /**
     * The duration for which failed audio output operations may be retried before throwing an
     * exception, in milliseconds. This duration is needed because audio outputs may retain some
     * resources for a short time even after they are released. For example, waiting a bit longer
     * allows the AudioFlinger to close all HAL streams that still hold resources. See b/167682058
     * and https://github.com/google/ExoPlayer/issues/4448.
     */
    private static final int RETRY_DURATION_MS = 200;

    /** Minimum delay between two retries. */
    private static final int RETRY_DELAY_MS = 50;

    @Nullable private T pendingException;
    private long throwDeadlineMs;
    private long earliestNextRetryTimeMs;

    public PendingExceptionHolder() {
      this.throwDeadlineMs = C.TIME_UNSET;
      this.earliestNextRetryTimeMs = C.TIME_UNSET;
    }

    public void throwExceptionIfDeadlineIsReached(T exception) throws T {
      long nowMs = SystemClock.elapsedRealtime();
      if (pendingException == null) {
        pendingException = exception;
      }
      if (throwDeadlineMs == C.TIME_UNSET && !hasPendingAudioOutputReleases()) {
        // The audio system has limited shared memory. If there is an ongoing release, the audio
        // output operation could be failing because this shared memory is exhausted (see
        // b/12565083). Only start the retry timer once all pending audio output releases are done.
        throwDeadlineMs = nowMs + RETRY_DURATION_MS;
      }
      if (throwDeadlineMs != C.TIME_UNSET && nowMs >= throwDeadlineMs) {
        if (pendingException != exception) {
          // All retry exception are probably the same, thus only save the last one to save memory.
          pendingException.addSuppressed(exception);
        }
        T pendingException = this.pendingException;
        clear();
        throw pendingException;
      }
      earliestNextRetryTimeMs = nowMs + RETRY_DELAY_MS;
    }

    public boolean shouldWaitBeforeRetry() {
      if (pendingException == null) {
        // No pending exception.
        return false;
      }
      if (hasPendingAudioOutputReleases()) {
        // Wait until other outputs are released before retrying.
        return true;
      }
      return SystemClock.elapsedRealtime() < earliestNextRetryTimeMs;
    }

    public void clear() {
      pendingException = null;
      throwDeadlineMs = C.TIME_UNSET;
      earliestNextRetryTimeMs = C.TIME_UNSET;
    }
  }

  private void maybeReportSkippedSilence() {
    if (accumulatedSkippedSilenceDurationUs >= MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US) {
      // If the existing silence is already long enough, report the silence
      listener.onSilenceSkipped();
      accumulatedSkippedSilenceDurationUs = 0;
    }
  }

  private static int getNonPcmMaximumEncodedRateBytesPerSecond(@C.Encoding int encoding) {
    int rate = ExtractorUtil.getMaximumEncodedRateBytesPerSecond(encoding);
    checkState(rate != C.RATE_UNSET_INT);
    return rate;
  }
}
