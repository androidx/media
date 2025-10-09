/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.util.Util.constrainValue;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_COMPOSITION_PLAYER;
import static androidx.media3.effect.DebugTraceUtil.EVENT_RELEASE;
import static androidx.media3.effect.DebugTraceUtil.EVENT_SEEK_TO;
import static androidx.media3.effect.DebugTraceUtil.EVENT_SET_COMPOSITION;
import static androidx.media3.effect.DebugTraceUtil.EVENT_SET_VIDEO_OUTPUT;
import static androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper.LATE_US_TO_DROP_INPUT_FRAME;
import static androidx.media3.transformer.TransformerUtil.containsSpeedChangingEffects;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.SparseBooleanArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.AudioFocusManager;
import androidx.media3.exoplayer.AudioFocusManager.PlayerCommand;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ExternallyLoadedMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link Player} implementation that plays {@linkplain Composition compositions} of media assets.
 * The {@link Composition} specifies how the assets should be arranged, and the audio and video
 * effects to apply to them.
 *
 * <p>{@code CompositionPlayer} instances must be accessed from a single application thread. For the
 * vast majority of cases this should be the application's main thread. The thread on which a
 * CompositionPlayer instance must be accessed can be explicitly specified by passing a {@link
 * Looper} when creating the player. If no {@link Looper} is specified, then the {@link Looper} of
 * the thread that the player is created on is used, or if that thread does not have a {@link
 * Looper}, the {@link Looper} of the application's main thread is used. In all cases the {@link
 * Looper} of the thread from which the player must be accessed can be queried using {@link
 * #getApplicationLooper()}.
 *
 * <p>This player only supports setting the {@linkplain #setRepeatMode(int) repeat mode} as
 * {@linkplain Player#REPEAT_MODE_ALL all} of the {@link Composition}, or {@linkplain
 * Player#REPEAT_MODE_OFF off}.
 */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public final class CompositionPlayer extends SimpleBasePlayer {

  /** A builder for {@link CompositionPlayer} instances. */
  public static final class Builder {
    private final Context context;

    private Looper looper;
    private Clock clock;
    private Supplier<AudioSink> audioSinkSupplier;
    private Supplier<AudioMixer.Factory> audioMixerFactorySupplier;
    private Supplier<MediaSource.Factory> mediaSourceFactorySupplier;
    private Supplier<ImageDecoder.Factory> imageDecoderFactorySupplier;
    private Supplier<GlObjectsProvider> glObjectsProviderSupplier;
    @Nullable private ExecutorService glExecutorService;
    private Supplier<LoadControl> loadControlSupplier;
    private AudioAttributes audioAttributes;
    private boolean handleAudioFocus;
    private VideoGraph.@MonotonicNonNull Factory videoGraphFactory;

    private boolean videoPrewarmingEnabled;
    private boolean enableReplayableCache;
    private long lateThresholdToDropInputUs;
    private boolean built;

    /**
     * Creates an instance
     *
     * @param context The application context.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      looper = Util.getCurrentOrMainLooper();
      audioSinkSupplier = () -> new DefaultAudioSink.Builder(context).build();
      glObjectsProviderSupplier = DefaultGlObjectsProvider::new;
      audioMixerFactorySupplier = DefaultAudioMixer.Factory::new;
      mediaSourceFactorySupplier = () -> new DefaultMediaSourceFactory(context);
      loadControlSupplier = DefaultLoadControl::new;
      imageDecoderFactorySupplier =
          () ->
              new BitmapFactoryImageDecoder.Factory(context)
                  .setMaxOutputSize(GlUtil.MAX_BITMAP_DECODING_SIZE);
      videoPrewarmingEnabled = true;
      lateThresholdToDropInputUs = LATE_US_TO_DROP_INPUT_FRAME;
      clock = Clock.DEFAULT;
      audioAttributes = AudioAttributes.DEFAULT;
    }

    /**
     * Sets the {@link Looper} from which the player can be accessed and {@link Listener} callbacks
     * are dispatched too.
     *
     * <p>By default, the builder uses the looper of the thread that calls {@link #build()}.
     *
     * @param looper The {@link Looper}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setLooper(Looper looper) {
      this.looper = looper;
      return this;
    }

    /**
     * Sets the {@link AudioSink} that will be used to play out audio.
     *
     * <p>By default, a {@link DefaultAudioSink} with its default configuration is used.
     *
     * @param audioSink The {@link AudioSink}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setAudioSink(AudioSink audioSink) {
      checkNotNull(audioSink);
      this.audioSinkSupplier = () -> audioSink;
      return this;
    }

    /**
     * Sets the {@link AudioMixer.Factory} to be used when {@linkplain AudioMixer audio mixing} is
     * needed.
     *
     * <p>The default value is a {@link DefaultAudioMixer.Factory} with default values.
     *
     * @param audioMixerFactory A {@link AudioMixer.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAudioMixerFactory(AudioMixer.Factory audioMixerFactory) {
      checkNotNull(audioMixerFactory);
      this.audioMixerFactorySupplier = () -> audioMixerFactory;
      return this;
    }

    /**
     * Sets the {@link MediaSource.Factory} that *creates* the {@link MediaSource} for {@link
     * EditedMediaItem#mediaItem MediaItems} in a {@link Composition}.
     *
     * <p>To use an external image loader, one could create a {@link DefaultMediaSourceFactory},
     * {@linkplain DefaultMediaSourceFactory#setExternalImageLoader set the external image loader},
     * and pass in the {@link DefaultMediaSourceFactory} here.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory}
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      checkNotNull(mediaSourceFactory);
      this.mediaSourceFactorySupplier = () -> mediaSourceFactory;
      return this;
    }

    /**
     * Sets an {@link ImageDecoder.Factory} that will create the {@link ImageDecoder} instances to
     * decode images.
     *
     * <p>By default, an instance of {@link BitmapFactoryImageDecoder.Factory} is used, with a
     * {@linkplain BitmapFactoryImageDecoder.Factory#setMaxOutputSize(int) max output size} set to
     * be consistent with {@link Transformer}.
     *
     * @param imageDecoderFactory The {@link ImageDecoder.Factory}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setImageDecoderFactory(ImageDecoder.Factory imageDecoderFactory) {
      checkNotNull(imageDecoderFactory);
      this.imageDecoderFactorySupplier = () -> imageDecoderFactory;
      return this;
    }

    /**
     * Sets whether to enable prewarming of the video renderers.
     *
     * <p>The default value is {@code true}.
     *
     * @param videoPrewarmingEnabled Whether to enable video prewarming.
     * @return This builder, for convenience.
     */
    @VisibleForTesting
    @CanIgnoreReturnValue
    /* package */ Builder setVideoPrewarmingEnabled(boolean videoPrewarmingEnabled) {
      // TODO: b/369817794 - Remove this setter once the tests are run on a device with API < 23.
      this.videoPrewarmingEnabled = videoPrewarmingEnabled;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used by the player.
     *
     * <p>By default, {@link Clock#DEFAULT} is used.
     *
     * @param clock The {@link Clock}.
     * @return This builder, for convenience.
     */
    @VisibleForTesting
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Sets the {@link VideoGraph.Factory} that will be used by the player.
     *
     * <p>By default, a {@link SingleInputVideoGraph.Factory} is used.
     *
     * @param videoGraphFactory The {@link VideoGraph.Factory}.
     * @return This builder, for convenience.
     * @throws IllegalStateException if an {@link ExecutorService} is {@linkplain
     *     #setGlThreadExecutorService set}.
     */
    @CanIgnoreReturnValue
    public Builder setVideoGraphFactory(VideoGraph.Factory videoGraphFactory) {
      checkState(glExecutorService == null);
      this.videoGraphFactory = videoGraphFactory;
      return this;
    }

    /**
     * Sets the {@link GlObjectsProvider} to be used by the effect processing pipeline.
     *
     * <p>Setting a {@link GlObjectsProvider} is no-op if a {@link VideoGraph.Factory} is
     * {@linkplain #setVideoGraphFactory set}. By default, a {@link DefaultGlObjectsProvider} is
     * used.
     *
     * @param glObjectsProvider The {@link GlObjectsProvider}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setGlObjectsProvider(GlObjectsProvider glObjectsProvider) {
      checkNotNull(glObjectsProvider);
      this.glObjectsProviderSupplier = () -> glObjectsProvider;
      return this;
    }

    /**
     * Sets the {@link ExecutorService} to execute GL commands from.
     *
     * <p>By default, a {@link Util#newSingleThreadScheduledExecutor}, owned and {@link
     * ExecutorService#shutdown} by the effects pipeline is used.
     *
     * <p>If set, the {@link ExecutorService} must be {@linkplain ExecutorService#shutdown shut
     * down} by the caller after {@linkplain CompositionPlayer} has been {@linkplain #release
     * released}.
     *
     * @param glExecutorService The {@link ExecutorService}.
     * @return This builder, for convenience.
     * @throws IllegalStateException if a {@link VideoGraph.Factory} is {@linkplain
     *     #setVideoGraphFactory set}.
     */
    @CanIgnoreReturnValue
    public Builder setGlThreadExecutorService(ExecutorService glExecutorService) {
      checkNotNull(glExecutorService);
      checkState(videoGraphFactory == null);
      this.glExecutorService = glExecutorService;
      return this;
    }

    /**
     * Sets the {@link LoadControl} that will be used by the player to control buffering of all
     * {@linkplain EditedMediaItem#mediaItem media items} in a {@link Composition}.
     *
     * <p>By default, a {@link DefaultLoadControl} is used.
     *
     * @param loadControl A {@link LoadControl}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setLoadControl(LoadControl loadControl) {
      checkNotNull(loadControl);
      this.loadControlSupplier = () -> loadControl;
      return this;
    }

    /**
     * Sets {@link AudioAttributes} that will be used by the player and whether to handle audio
     * focus.
     *
     * <p>If audio focus should be handled, the {@link AudioAttributes#usage} must be {@link
     * C#USAGE_MEDIA} or {@link C#USAGE_GAME}. Other usages will throw an {@link
     * IllegalArgumentException}.
     *
     * @param audioAttributes {@link AudioAttributes}.
     * @param handleAudioFocus Whether the player should handle audio focus.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
      this.audioAttributes = checkNotNull(audioAttributes);
      this.handleAudioFocus = handleAudioFocus;
      return this;
    }

    /**
     * Sets whether to enable replayable cache.
     *
     * <p>By default, the replayable cache is not enabled. Enable it to achieve accurate effect
     * update, at the cost of using more power and computing resources.
     *
     * @param enableReplayableCache Whether replayable cache is enabled.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetEnableReplayableCache(boolean enableReplayableCache) {
      this.enableReplayableCache = enableReplayableCache;
      return this;
    }

    /**
     * Sets the late threshold for decoded frames, in microseconds, after which frames may be
     * dropped before applying effects.
     *
     * <p>The default value is {@link PlaybackVideoGraphWrapper#LATE_US_TO_DROP_INPUT_FRAME}.
     *
     * <p>Set this threshold to {@link C#TIME_UNSET} to disable frame dropping before effects are
     * applied.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     *
     * @param lateThresholdToDropInputUs The threshold.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetLateThresholdToDropInputUs(long lateThresholdToDropInputUs) {
      this.lateThresholdToDropInputUs = lateThresholdToDropInputUs;
      return this;
    }

    /**
     * Builds the {@link CompositionPlayer} instance. Must be called at most once.
     *
     * <p>If no {@link Looper} has been called with {@link #setLooper(Looper)}, then this method
     * must be called within a {@link Looper} thread which is the thread that can access the player
     * instance and where {@link Listener} callbacks are dispatched.
     */
    public CompositionPlayer build() {
      checkState(!built);
      if (videoGraphFactory == null) {
        DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
            new DefaultVideoFrameProcessor.Factory.Builder()
                .setEnableReplayableCache(enableReplayableCache)
                .setGlObjectsProvider(glObjectsProviderSupplier.get())
                .setExecutorService(glExecutorService)
                .build();
        videoGraphFactory = new SingleInputVideoGraph.Factory(videoFrameProcessorFactory);
      }
      CompositionPlayer compositionPlayer = new CompositionPlayer(this);
      built = true;
      return compositionPlayer;
    }
  }

  private static final Commands AVAILABLE_COMMANDS =
      new Commands.Builder()
          .addAll(
              COMMAND_PLAY_PAUSE,
              COMMAND_PREPARE,
              COMMAND_STOP,
              COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
              COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
              COMMAND_SEEK_TO_DEFAULT_POSITION,
              COMMAND_SEEK_BACK,
              COMMAND_SEEK_FORWARD,
              COMMAND_GET_CURRENT_MEDIA_ITEM,
              COMMAND_GET_TIMELINE,
              COMMAND_SET_REPEAT_MODE,
              COMMAND_SET_VIDEO_SURFACE,
              COMMAND_GET_VOLUME,
              COMMAND_SET_VOLUME,
              COMMAND_RELEASE,
              COMMAND_SET_AUDIO_ATTRIBUTES)
          .build();

  private static final @Event int[] SUPPORTED_LISTENER_EVENTS =
      new int[] {
        EVENT_PLAYBACK_STATE_CHANGED,
        EVENT_PLAY_WHEN_READY_CHANGED,
        EVENT_PLAYER_ERROR,
        EVENT_POSITION_DISCONTINUITY,
        EVENT_MEDIA_ITEM_TRANSITION,
      };

  private static final String TAG = "CompositionPlayer";
  private static final String BLANK_FRAMES_MEDIA_SOURCE_TYPE = "composition_player_blank_frames";
  private static final long SURFACE_DESTROY_TIMEOUT_MS = 2_000;

  private final Context context;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final List<SequencePlayerHolder> playerHolders;
  private final AudioSink finalAudioSink;
  private final AudioMixer.Factory audioMixerFactory;
  private final MediaSource.Factory mediaSourceFactory;
  private final ImageDecoder.Factory imageDecoderFactory;
  private final VideoGraph.Factory videoGraphFactory;
  private final boolean videoPrewarmingEnabled;
  private final HandlerWrapper compositionInternalListenerHandler;
  private final LoadControl loadControl;
  private final boolean enableReplayableCache;
  private final long lateThresholdToDropInputUs;
  private final AudioFocusManager audioFocusManager;
  private final InternalListener internalListener;

  /** Maps from input index to whether the video track is selected in that sequence. */
  private final SparseBooleanArray videoTracksSelected;

  private @MonotonicNonNull HandlerThread playbackThread;
  private @MonotonicNonNull HandlerWrapper playbackThreadHandler;
  private @MonotonicNonNull CompositionPlayerInternal compositionPlayerInternal;
  private @MonotonicNonNull ImmutableList<MediaItemData> playlist;
  private @MonotonicNonNull Composition composition;
  private @MonotonicNonNull Size videoOutputSize;
  private @MonotonicNonNull PlaybackVideoGraphWrapper playbackVideoGraphWrapper;
  private @MonotonicNonNull PlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  private @MonotonicNonNull VideoFrameMetadataListener videoFrameMetadataListener;

  private long compositionDurationUs;
  private boolean playWhenReady;
  private @PlayWhenReadyChangeReason int playWhenReadyChangeReason;
  private @RepeatMode int repeatMode;
  private float volume;
  private boolean renderedFirstFrame;
  @Nullable private Object videoOutput;
  @Nullable private PlaybackException playbackException;
  private @Player.State int playbackState;
  private @PlaybackSuppressionReason int playbackSuppressionReason;
  @Nullable private SurfaceHolder surfaceHolder;
  @Nullable private Surface displaySurface;
  private boolean repeatingCompositionSeekInProgress;
  private LivePositionSupplier positionSupplier;
  private LivePositionSupplier bufferedPositionSupplier;
  private LivePositionSupplier totalBufferedDurationSupplier;
  private boolean compositionPlayerInternalPrepared;
  private boolean scrubbingModeEnabled;
  // Whether prepare() needs to be called to prepare the underlying sequence players.
  private boolean appNeedsToPrepareCompositionPlayer;
  private boolean playWhenReadyBeforeScrubbingEnabled;
  private AudioAttributes audioAttributes;
  private boolean handleAudioFocus;

  // "this" reference for position suppliers.
  @SuppressWarnings("initialization:methodref.receiver.bound.invalid")
  private CompositionPlayer(Builder builder) {
    super(checkNotNull(builder.looper), builder.clock);
    context = builder.context;
    clock = builder.clock;
    applicationHandler = clock.createHandler(builder.looper, /* callback= */ null);
    finalAudioSink = builder.audioSinkSupplier.get();
    audioMixerFactory = builder.audioMixerFactorySupplier.get();
    mediaSourceFactory = builder.mediaSourceFactorySupplier.get();
    imageDecoderFactory = new GapHandlingDecoderFactory(builder.imageDecoderFactorySupplier.get());
    videoGraphFactory = checkNotNull(builder.videoGraphFactory);
    videoPrewarmingEnabled = builder.videoPrewarmingEnabled;
    compositionInternalListenerHandler = clock.createHandler(builder.looper, /* callback= */ null);
    loadControl = builder.loadControlSupplier.get();
    this.enableReplayableCache = builder.enableReplayableCache;
    lateThresholdToDropInputUs = builder.lateThresholdToDropInputUs;
    videoTracksSelected = new SparseBooleanArray();
    playerHolders = new ArrayList<>();
    compositionDurationUs = C.TIME_UNSET;
    playbackState = STATE_IDLE;
    volume = 1.0f;
    positionSupplier = new LivePositionSupplier(this::getContentPositionMs);
    bufferedPositionSupplier = new LivePositionSupplier(this::getBufferedPositionMs);
    totalBufferedDurationSupplier = new LivePositionSupplier(this::getTotalBufferedDurationMs);
    audioAttributes = builder.audioAttributes;
    handleAudioFocus = builder.handleAudioFocus;
    appNeedsToPrepareCompositionPlayer = true;
    internalListener = new InternalListener();
    audioFocusManager =
        new AudioFocusManager(context, applicationHandler.getLooper(), internalListener);
    AnalyticsCollector analyticsCollector = new DefaultAnalyticsCollector(clock);
    analyticsCollector.setPlayer(this, builder.looper);
    analyticsCollector.addListener(new EventLogger(TAG));
    addListener(analyticsCollector);
  }

  /**
   * Sets the {@link Composition} to play from the beginning.
   *
   * <p>Calling this method is equivalent to calling {@link #setComposition(Composition, long)} with
   * a start position at zero.
   *
   * @param composition The {@link Composition} to play. Every {@link EditedMediaItem} in the {@link
   *     Composition} must have its {@link EditedMediaItem#durationUs} set.
   */
  public void setComposition(Composition composition) {
    setComposition(composition, /* startPositionMs= */ 0);
  }

  /**
   * Sets the {@link Composition} to play.
   *
   * @param composition The {@link Composition} to play. Every {@link EditedMediaItem} in the {@link
   *     Composition} must have its {@link EditedMediaItem#durationUs} set.
   * @param startPositionMs The position at which playback should start, in milliseconds.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void setComposition(Composition composition, @IntRange(from = 0) long startPositionMs) {
    verifyApplicationThread();
    checkArgument(!composition.sequences.isEmpty());
    checkArgument(startPositionMs >= 0, "Invalid start position %s", startPositionMs);
    checkArgument(
        !compositionContainsIllegalSpeedChangingEffects(composition),
        "CompositionPlayer only allows speed changing effects created from"
            + " Effects#createExperimentalSpeedChangingEffect() placed as first effects within an"
            + " EditedMediaItem.");
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITION_PLAYER,
        EVENT_SET_COMPOSITION,
        /* presentationTimeUs= */ C.TIME_UNSET,
        composition.toJsonObject());

    composition = deactivateSpeedAdjustingVideoEffects(composition);

    if (composition.sequences.size() > 1 && !videoGraphFactory.supportsMultipleInputs()) {
      Log.w(TAG, "Setting multi-sequence Composition with single input video graph.");
    }

    // TODO: b/412585856 - Skip reconfiguration when setting the exact same Composition
    setCompositionInternal(composition, startPositionMs);
    // Update the composition field at the end after everything else has been set.
    this.composition = composition;
    maybeSetVideoOutput();
  }

  /**
   * Sets whether to optimize the player for scrubbing (many frequent seeks).
   *
   * <p>The player may consume more resources in this mode, so it should only be used for short
   * periods of time in response to user interaction (e.g. dragging on a progress bar UI element).
   *
   * <p>During scrubbing mode playback is {@linkplain Player#getPlaybackSuppressionReason()
   * suppressed} with {@link Player#PLAYBACK_SUPPRESSION_REASON_SCRUBBING}.
   *
   * @param scrubbingModeEnabled Whether scrubbing mode should be enabled.
   */
  public void setScrubbingModeEnabled(boolean scrubbingModeEnabled) {
    verifyApplicationThread();
    if (this.scrubbingModeEnabled == scrubbingModeEnabled) {
      return;
    }
    this.scrubbingModeEnabled = scrubbingModeEnabled;
    if (scrubbingModeEnabled) {
      this.playWhenReadyBeforeScrubbingEnabled = this.playWhenReady;
    }

    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.setScrubbingModeEnabled(scrubbingModeEnabled);
    }

    if (scrubbingModeEnabled) {
      updatePlayWhenReadyWithAudioFocus(
          this.playWhenReady,
          PLAYBACK_SUPPRESSION_REASON_SCRUBBING,
          this.playWhenReadyChangeReason);
    } else {
      // Disabling scrubbing mode when scrubbing was enabled in a "playing" state is considered an
      // implicit "play".
      updatePlayWhenReadyWithAudioFocus(
          /* playWhenReady= */ this.playWhenReadyBeforeScrubbingEnabled || this.playWhenReady,
          PLAYBACK_SUPPRESSION_REASON_NONE,
          this.playWhenReadyBeforeScrubbingEnabled
              ? PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
              : this.playWhenReadyChangeReason);
    }

    // This is not a SimpleBasePlayer method, so we need to manually invalidate the state.
    invalidateState();
  }

  /**
   * Returns whether the player is optimized for scrubbing (many frequent seeks).
   *
   * <p>See {@link #setScrubbingModeEnabled(boolean)}.
   */
  public boolean isScrubbingModeEnabled() {
    return scrubbingModeEnabled;
  }

  /**
   * Forces the effect pipeline to redraw the effects immediately.
   *
   * <p>The player must be {@linkplain Builder#experimentalSetEnableReplayableCache built with
   * replayable cache support}.
   */
  public void experimentalRedrawLastFrame() {
    checkState(enableReplayableCache);
    if (playbackThreadHandler == null || playbackVideoGraphWrapper == null) {
      // Ignore replays before setting a composition.
      return;
    }
    playbackThreadHandler.post(() -> checkNotNull(playbackVideoGraphWrapper).getSink(0).redraw());
  }

  /** Sets the {@link Surface} and {@link Size} to render to. */
  public void setVideoSurface(Surface surface, Size videoOutputSize) {
    videoOutput = surface;
    this.videoOutputSize = videoOutputSize;
    setVideoSurfaceInternal(surface, videoOutputSize);
  }

  /**
   * Returns the {@link Looper} associated with the playback thread or null if the internal player
   * has not been prepared.
   *
   * <p>This method may be called from any thread.
   */
  @Nullable
  public Looper getPlaybackLooper() {
    return playbackThread != null ? playbackThread.getLooper() : null;
  }

  /**
   * Returns the {@link Clock} used for playback.
   *
   * <p>This method can be called from any thread.
   */
  public Clock getClock() {
    return clock;
  }

  // SimpleBasePlayer methods

  @Override
  protected State getState() {
    // TODO: b/328219481 - Report video size change to app.
    State.Builder state =
        new State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayerError(playbackException)
            .setPlayWhenReady(playWhenReady, playWhenReadyChangeReason)
            .setRepeatMode(repeatMode)
            .setVolume(volume)
            .setContentPositionMs(positionSupplier)
            .setContentBufferedPositionMs(bufferedPositionSupplier)
            .setTotalBufferedDurationMs(totalBufferedDurationSupplier)
            .setNewlyRenderedFirstFrame(getRenderedFirstFrameAndReset())
            .setPlaybackSuppressionReason(playbackSuppressionReason);
    if (repeatingCompositionSeekInProgress) {
      state.setPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION, C.TIME_UNSET);
      repeatingCompositionSeekInProgress = false;
    }
    if (playlist != null) {
      // Update the playlist only after it has been set so that SimpleBasePlayer announces a
      // timeline change with reason TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
      state.setPlaylist(playlist);
    }
    return state.build();
  }

  @Override
  protected ListenableFuture<?> handlePrepare() {
    checkNotNull(composition, "No composition set");

    if (playbackState != Player.STATE_IDLE) {
      // The player has been prepared already.
      return Futures.immediateVoidFuture();
    }

    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.prepare();
    }
    appNeedsToPrepareCompositionPlayer = false;

    updatePlayWhenReadyWithAudioFocus(
        this.playWhenReady, this.playbackSuppressionReason, this.playWhenReadyChangeReason);

    updatePlaybackState();
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    updatePlayWhenReadyWithAudioFocus(
        playWhenReady, playbackSuppressionReason, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetRepeatMode(@RepeatMode int repeatMode) {
    // Composition is treated as a single item, so only supports being repeated as a whole.
    checkArgument(repeatMode != REPEAT_MODE_ONE);
    this.repeatMode = repeatMode;
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleStop() {
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.stop();
    }
    appNeedsToPrepareCompositionPlayer = true;
    updatePlaybackState();
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleRelease() {
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITION_PLAYER, EVENT_RELEASE, /* presentationTimeUs= */ C.TIME_UNSET);
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }

    checkState(checkNotNull(playbackThread).isAlive());
    // Release the players first so that they stop rendering.
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.release();
    }
    playerHolders.clear();
    boolean internalPlayerSuccessfullyReleased = checkNotNull(compositionPlayerInternal).release();
    removeSurfaceCallbacks();
    // Remove any queued callback from the internal player.
    compositionInternalListenerHandler.removeCallbacksAndMessages(/* token= */ null);
    displaySurface = null;
    checkNotNull(playbackThread).quitSafely();
    applicationHandler.removeCallbacksAndMessages(/* token= */ null);
    if (!internalPlayerSuccessfullyReleased) {
      // The parent class will call getState() after this method returns, where the exception will
      // surface.
      playbackException =
          new PlaybackException(
              "InternalPlayer release timeout",
              /* cause= */ null,
              PlaybackException.ERROR_CODE_TIMEOUT);
      updatePlaybackState();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
    checkArgument(Objects.equals(videoOutput, this.videoOutput));

    this.videoOutput = null;
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }
    removeSurfaceCallbacks();
    clearVideoSurfaceInternal();
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITION_PLAYER,
        EVENT_SET_VIDEO_OUTPUT,
        /* presentationTimeUs= */ C.TIME_UNSET,
        "%s",
        videoOutput);
    if (!(videoOutput instanceof SurfaceHolder || videoOutput instanceof SurfaceView)) {
      throw new UnsupportedOperationException(
          videoOutput.getClass() + ". Use CompositionPlayer.setVideoSurface() for Surface output.");
    }
    this.videoOutput = videoOutput;
    return maybeSetVideoOutput();
  }

  @Override
  protected ListenableFuture<?> handleSetVolume(
      float volume, @C.VolumeOperationType int volumeOperationType) {
    volume = constrainValue(volume, /* min= */ 0.0f, /* max= */ 1.0f);
    if (this.volume != volume) {
      setVolumeInternal(volume);
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSeek(
      int mediaItemIndex, long positionMs, @Command int seekCommand) {
    resetLivePositionSuppliers();
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITION_PLAYER,
        EVENT_SEEK_TO,
        /* presentationTimeUs= */ C.TIME_UNSET,
        "positionMs=%d",
        positionMs);
    CompositionPlayerInternal compositionPlayerInternal =
        checkNotNull(this.compositionPlayerInternal);
    compositionPlayerInternal.startSeek(positionMs);
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.seekTo(positionMs);
    }
    compositionPlayerInternal.endSeek();
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetAudioAttributes(
      AudioAttributes audioAttributes, boolean handleAudioFocus) {
    setAudioAttributesInternal(audioAttributes, handleAudioFocus);
    return Futures.immediateVoidFuture();
  }

  /** Sets the {@link VideoFrameMetadataListener}. */
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
    if (playerHolders.isEmpty()) {
      return;
    }
    playerHolders.get(0).player.setVideoFrameMetadataListener(videoFrameMetadataListener);
  }

  // Internal methods

  private static @Player.PlayWhenReadyChangeReason int updatePlayWhenReadyChangeReason(
      @AudioFocusManager.PlayerCommand int playerCommand,
      @Player.PlayWhenReadyChangeReason int playWhenReadyChangeReason) {
    if (playerCommand == AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY) {
      return Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS;
    }
    if (playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
      return Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    }
    return playWhenReadyChangeReason;
  }

  private static @Player.PlaybackSuppressionReason int updatePlaybackSuppressionReason(
      @AudioFocusManager.PlayerCommand int playerCommand,
      @Player.PlaybackSuppressionReason int playbackSuppressionReason,
      boolean isScrubbingModeEnabled) {
    if (playerCommand == AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK) {
      return Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS;
    }
    if (playbackSuppressionReason
        != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
      return playbackSuppressionReason;
    }

    if (isScrubbingModeEnabled) {
      return PLAYBACK_SUPPRESSION_REASON_SCRUBBING;
    }

    return Player.PLAYBACK_SUPPRESSION_REASON_NONE;
  }

  private static Composition deactivateSpeedAdjustingVideoEffects(Composition composition) {
    List<EditedMediaItemSequence> newSequences = new ArrayList<>();
    for (EditedMediaItemSequence sequence : composition.sequences) {
      List<EditedMediaItem> newEditedMediaItems = new ArrayList<>();
      for (EditedMediaItem editedMediaItem : sequence.editedMediaItems) {
        ImmutableList<Effect> videoEffects = editedMediaItem.effects.videoEffects;
        List<Effect> newVideoEffects = new ArrayList<>();
        for (Effect videoEffect : videoEffects) {
          if (videoEffect instanceof TimestampAdjustment) {
            newVideoEffects.add(
                new InactiveTimestampAdjustment(((TimestampAdjustment) videoEffect).speedProvider));
          } else {
            newVideoEffects.add(videoEffect);
          }
        }
        newEditedMediaItems.add(
            editedMediaItem
                .buildUpon()
                .setEffects(new Effects(editedMediaItem.effects.audioProcessors, newVideoEffects))
                .build());
      }
      newSequences.add(
          new EditedMediaItemSequence.Builder(newEditedMediaItems)
              .setIsLooping(sequence.isLooping)
              .experimentalSetForceAudioTrack(sequence.forceAudioTrack)
              .experimentalSetForceVideoTrack(sequence.forceVideoTrack)
              .build());
    }
    return composition.buildUpon().setSequences(newSequences).build();
  }

  private void updatePlaybackState() {
    if (playerHolders.isEmpty() || playbackException != null) {
      playbackState = STATE_IDLE;
      return;
    }

    @Player.State int oldPlaybackState = playbackState;

    int idleCount = 0;
    int bufferingCount = 0;
    int endedCount = 0;
    for (int i = 0; i < playerHolders.size(); i++) {
      @Player.State int playbackState = playerHolders.get(i).player.getPlaybackState();
      switch (playbackState) {
        case STATE_IDLE:
          idleCount++;
          break;
        case STATE_BUFFERING:
          bufferingCount++;
          break;
        case STATE_READY:
          // ignore
          break;
        case STATE_ENDED:
          endedCount++;
          break;
        default:
          throw new IllegalStateException(String.valueOf(playbackState));
      }
    }
    if (appNeedsToPrepareCompositionPlayer) {
      // State is IDLE before prepare is called.
      playbackState = STATE_IDLE;
    } else if (bufferingCount > 0 || idleCount > 0) {
      // After calling prepare, transition into buffering, and stay until either all players are
      // ready, error is thrown or stop is called.
      playbackState = STATE_BUFFERING;
      if (oldPlaybackState == STATE_READY && shouldPlayWhenReady()) {
        // We were playing but a player got in buffering state, pause the players.
        setPlayWhenReadyInternal(
            /* playWhenReady= */ false, /* shouldUpdateInternalPlayers= */ true);
      }
    } else if (endedCount == playerHolders.size()) {
      playbackState = STATE_ENDED;
      checkNotNull(compositionPlayerInternal).stopRendering();
    } else {
      playbackState = STATE_READY;
      if (oldPlaybackState != STATE_READY && shouldPlayWhenReady()) {
        setPlayWhenReadyInternal(
            /* playWhenReady= */ true, /* shouldUpdateInternalPlayers= */ true);
      }
    }
  }

  private void setAudioAttributesInternal(
      AudioAttributes audioAttributes, boolean handleAudioFocus) {
    this.handleAudioFocus = handleAudioFocus;

    if (!Objects.equals(audioAttributes, this.audioAttributes)) {
      this.audioAttributes = audioAttributes;
      if (compositionPlayerInternalPrepared) {
        checkNotNull(compositionPlayerInternal).setAudioAttributes(audioAttributes);
      }
      // CompositionPlayer handles audio focus, so only set AudioAttributes to internal players.
      for (SequencePlayerHolder playerHolder : playerHolders) {
        playerHolder.player.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false);
      }
    }

    audioFocusManager.setAudioAttributes(handleAudioFocus ? audioAttributes : null);

    updatePlayWhenReadyWithAudioFocus(
        this.playWhenReady, this.playbackSuppressionReason, this.playWhenReadyChangeReason);
  }

  private void setVolumeInternal(float volume) {
    this.volume = volume;
    if (compositionPlayerInternal != null) {
      compositionPlayerInternal.setVolume(this.volume * audioFocusManager.getVolumeMultiplier());
    }
  }

  /**
   * Toggles rendering on {@link #compositionPlayerInternal} and {@link ExoPlayer#setPlayWhenReady}
   * on internal players.
   *
   * <p>This method has no effect on {@link #playWhenReady}.
   *
   * @param playWhenReady Whether to enable or disable rendering.
   * @param shouldUpdateInternalPlayers Whether to modify {@link ExoPlayer#setPlayWhenReady} on
   *     internal players.
   */
  private void setPlayWhenReadyInternal(
      boolean playWhenReady, boolean shouldUpdateInternalPlayers) {
    if (!compositionPlayerInternalPrepared) {
      return;
    }
    // This method is also called on sequence player state change, so rendering will be started once
    // CompositionPlayer is ready.
    if (playbackState == STATE_READY && playWhenReady) {
      checkNotNull(compositionPlayerInternal).startRendering();
    } else {
      checkNotNull(compositionPlayerInternal).stopRendering();
    }

    if (shouldUpdateInternalPlayers) {
      for (int i = 0; i < playerHolders.size(); i++) {
        playerHolders.get(i).player.setPlayWhenReady(playWhenReady);
      }
    }
  }

  private void updatePlayWhenReadyWithAudioFocus(
      boolean playWhenReady,
      @PlaybackSuppressionReason int playbackSuppressionReason,
      @PlayWhenReadyChangeReason int playWhenReadyChangeReason) {
    int playerCommand = audioFocusManager.updateAudioFocus(playWhenReady, playbackState);
    updatePlayWhenReadyWithAudioFocus(
        playWhenReady, playerCommand, playbackSuppressionReason, playWhenReadyChangeReason);
  }

  private void updatePlayWhenReadyWithAudioFocus(
      boolean playWhenReady,
      @AudioFocusManager.PlayerCommand int playerCommand,
      @PlaybackSuppressionReason int playbackSuppressionReason,
      @Player.PlayWhenReadyChangeReason int playWhenReadyChangeReason) {
    playWhenReady &= playerCommand != AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY;
    playWhenReadyChangeReason =
        updatePlayWhenReadyChangeReason(playerCommand, playWhenReadyChangeReason);
    playbackSuppressionReason =
        updatePlaybackSuppressionReason(
            playerCommand, playbackSuppressionReason, this.scrubbingModeEnabled);
    if (this.playWhenReady == playWhenReady
        && this.playbackSuppressionReason == playbackSuppressionReason
        && this.playWhenReadyChangeReason == playWhenReadyChangeReason) {
      return;
    }

    int previousPlaybackSuppressionReason = this.playbackSuppressionReason;

    this.playWhenReady = playWhenReady;
    this.playWhenReadyChangeReason = playWhenReadyChangeReason;
    this.playbackSuppressionReason = playbackSuppressionReason;

    boolean shouldUpdateInternalPlayers =
        previousPlaybackSuppressionReason != PLAYBACK_SUPPRESSION_REASON_SCRUBBING
            && this.playbackSuppressionReason != PLAYBACK_SUPPRESSION_REASON_SCRUBBING;

    setPlayWhenReadyInternal(shouldPlayWhenReady(), shouldUpdateInternalPlayers);
  }

  private boolean shouldPlayWhenReady() {
    return this.playWhenReady
        && this.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE;
  }

  private void prepareCompositionPlayerInternal() {
    // PlaybackAudioGraphWrapper needs to be recreated everytime a new composition is set.
    if (playbackAudioGraphWrapper != null) {
      playbackAudioGraphWrapper.release();
    }
    playbackAudioGraphWrapper =
        new PlaybackAudioGraphWrapper(audioMixerFactory, checkNotNull(finalAudioSink));
    if (compositionPlayerInternalPrepared) {
      checkNotNull(compositionPlayerInternal)
          .setPlaybackAudioGraphWrapper(playbackAudioGraphWrapper);
      return;
    }

    playbackThread = new HandlerThread("CompositionPlaybackThread", Process.THREAD_PRIORITY_AUDIO);
    playbackThread.start();
    playbackThreadHandler = clock.createHandler(playbackThread.getLooper(), /* callback= */ null);

    setAudioAttributesInternal(audioAttributes, handleAudioFocus);

    // Once this method returns, further access to the audio and video graph wrappers must done on
    // the playback thread only, to ensure related components are accessed from one thread only.
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, new CompositionFrameTimingEvaluator(), /* allowedJoiningTimeMs= */ 0);
    playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setVideoGraphFactory(checkNotNull(videoGraphFactory))
            .setClock(clock)
            .setEnableReplayableCache(enableReplayableCache)
            .experimentalSetLateThresholdToDropInputUs(lateThresholdToDropInputUs)
            .build();
    playbackVideoGraphWrapper.addListener(internalListener);

    // From here after, composition player accessed the audio and video pipelines via the internal
    // player. The internal player ensures access to the components is done on the playback thread.
    compositionPlayerInternal =
        new CompositionPlayerInternal(
            playbackThread.getLooper(),
            clock,
            playbackAudioGraphWrapper,
            playbackVideoGraphWrapper,
            internalListener,
            compositionInternalListenerHandler);
    setVolumeInternal(volume);
    compositionPlayerInternalPrepared = true;
  }

  private void setCompositionInternal(Composition composition, long startPositionMs) {
    for (int i = 0; i < playerHolders.size(); i++) {
      // TODO: b/412585856 - Optimize for the case where we can keep some resources.
      playerHolders.get(i).player.release();
    }
    playerHolders.clear();

    prepareCompositionPlayerInternal();
    CompositionPlayerInternal compositionPlayerInternal =
        checkNotNull(this.compositionPlayerInternal);
    compositionDurationUs = getCompositionDurationUs(composition);
    long primarySequenceDurationUs =
        getSequenceDurationUs(checkNotNull(composition.sequences.get(0)));
    for (int i = 0; i < composition.sequences.size(); i++) {
      createSequencePlayer(
          composition, /* sequenceIndex= */ i, primarySequenceDurationUs, startPositionMs);
    }
    compositionPlayerInternal.setComposition(composition);
    compositionPlayerInternal.startSeek(startPositionMs);
    compositionPlayerInternal.endSeek();

    if (appNeedsToPrepareCompositionPlayer) {
      return;
    }
    // After the app calls prepare() for the first time, subsequent calls to setComposition()
    // doesn't require apps to call prepare() again (unless stop() is called, or player error
    // reported), hence the need to prepare the underlying players here.
    for (int i = 0; i < playerHolders.size(); i++) {
      SequencePlayerHolder sequencePlayerHolder = playerHolders.get(i);
      sequencePlayerHolder.player.prepare();
    }
    updatePlaybackState();
    invalidateState();
  }

  private void createSequencePlayer(
      Composition newComposition,
      int sequenceIndex,
      long primarySequenceDurationUs,
      long startPositionMs) {
    EditedMediaItemSequence sequence = newComposition.sequences.get(sequenceIndex);
    // The underlying player needs to be recreated so that the audio renderer can use the new
    // AudioSink from the newly created PlaybackAudioGraphWrapper.
    SequencePlayerHolder playerHolder =
        createSequencePlayer(
            sequenceIndex,
            newComposition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC);
    playerHolders.add(playerHolder);
    playerHolder.setSequence(sequence);
    ExoPlayer player = playerHolder.player;

    // Starts from zero - internal player will discard current progress, re-preparing it by
    // setting new media sources.
    boolean shouldGenerateBlankFrames = sequence.forceVideoTrack;
    if (sequenceIndex == 0) {
      player.setMediaSource(
          createPrimarySequenceMediaSource(sequence, mediaSourceFactory, shouldGenerateBlankFrames),
          startPositionMs);
      if (videoFrameMetadataListener != null) {
        player.setVideoFrameMetadataListener(videoFrameMetadataListener);
      }
    } else {
      player.setMediaSource(
          createSecondarySequenceMediaSource(
              sequence, mediaSourceFactory, primarySequenceDurationUs, shouldGenerateBlankFrames),
          startPositionMs);
    }

    if (sequenceIndex == 0) {
      // Invalidate the player state before initializing the playlist to force SimpleBasePlayer
      // to collect a state while the playlist is null. Consequently, once the playlist is
      // initialized, SimpleBasePlayer will raise a timeline change callback with reason
      // TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
      invalidateState();
      playlist = createPlaylist();
    }
  }

  private SequencePlayerHolder createSequencePlayer(
      int sequenceIndex, boolean requestMediaCodecToneMapping) {
    SequencePlayerHolder playerHolder =
        new SequencePlayerHolder(
            context,
            getApplicationLooper(),
            checkNotNull(playbackThread).getLooper(),
            clock,
            SequenceRenderersFactory.create(
                context,
                checkNotNull(playbackAudioGraphWrapper),
                checkNotNull(playbackVideoGraphWrapper).getSink(/* inputIndex= */ sequenceIndex),
                imageDecoderFactory,
                /* inputIndex= */ sequenceIndex,
                videoPrewarmingEnabled),
            /* inputIndex= */ sequenceIndex);
    playerHolder.player.addListener(new PlayerListener(sequenceIndex));
    playerHolder.player.addAnalyticsListener(new EventLogger(TAG + "-" + sequenceIndex));
    // Audio focus is handled directly by CompositionPlayer, not by sequence players.
    playerHolder.player.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ false);
    playerHolder.player.setPauseAtEndOfMediaItems(true);
    playerHolder.renderersFactory.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
    return playerHolder;
  }

  private static MediaSource createPrimarySequenceMediaSource(
      EditedMediaItemSequence sequence,
      MediaSource.Factory mediaSourceFactory,
      boolean shouldGenerateBlankFrames) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder = new ConcatenatingMediaSource2.Builder();

    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      checkArgument(editedMediaItem.durationUs != C.TIME_UNSET);

      MediaSource blankFramesAndSilenceGeneratedMediaSource =
          createMediaSourceWithBlankFramesAndSilence(
              mediaSourceFactory, editedMediaItem, shouldGenerateBlankFrames);

      MediaSource itemMediaSource =
          wrapWithVideoEffectsBasedMediaSources(
              blankFramesAndSilenceGeneratedMediaSource,
              editedMediaItem.effects.videoEffects,
              editedMediaItem.mediaItem.clippingConfiguration);
      mediaSourceBuilder.add(
          itemMediaSource,
          /* initialPlaceholderDurationMs= */ usToMs(editedMediaItem.getPresentationDurationUs()));
    }
    return mediaSourceBuilder.build();
  }

  private static MediaSource createMediaSourceWithBlankFramesAndSilence(
      MediaSource.Factory mediaSourceFactory,
      EditedMediaItem editedMediaItem,
      boolean shouldGenerateBlankFrames) {
    MediaSource silenceMediaSource =
        new ClippingMediaSource.Builder(new SilenceMediaSource(editedMediaItem.durationUs))
            .setStartPositionUs(editedMediaItem.mediaItem.clippingConfiguration.startPositionUs)
            .setEndPositionUs(editedMediaItem.mediaItem.clippingConfiguration.endPositionUs)
            .build();

    MediaSource blankFramesMediaSource =
        new ClippingMediaSource.Builder(
                new ExternallyLoadedMediaSource.Factory(
                        editedMediaItem.durationUs, loadRequest -> Futures.immediateVoidFuture())
                    .createMediaSource(
                        new MediaItem.Builder()
                            .setMimeType(BLANK_FRAMES_MEDIA_SOURCE_TYPE)
                            .setUri("compositionPlayer://" + BLANK_FRAMES_MEDIA_SOURCE_TYPE)
                            .build()))
            .setStartPositionUs(editedMediaItem.mediaItem.clippingConfiguration.startPositionUs)
            .setEndPositionUs(editedMediaItem.mediaItem.clippingConfiguration.endPositionUs)
            .build();

    // The order of these MediaSource instances is critical. The CompositionTrackSelector
    // relies on this fixed order to correctly identify the silence and blank image track groups.
    // LINT.IfChange
    if (editedMediaItem.isGap()) {
      if (shouldGenerateBlankFrames) {
        return new MergingMediaSource(silenceMediaSource, blankFramesMediaSource);
      } else {
        return silenceMediaSource;
      }
    } else {
      // The MediaSource that loads the MediaItem
      MediaSource mainMediaSource = mediaSourceFactory.createMediaSource(editedMediaItem.mediaItem);
      if (shouldGenerateBlankFrames) {
        return new MergingMediaSource(silenceMediaSource, blankFramesMediaSource, mainMediaSource);
      } else {
        return new MergingMediaSource(silenceMediaSource, mainMediaSource);
      }
    }
    // LINT.ThenChange(CompositionTrackSelector.java)
  }

  private static MediaSource createSecondarySequenceMediaSource(
      EditedMediaItemSequence sequence,
      MediaSource.Factory mediaSourceFactory,
      long primarySequenceDurationUs,
      boolean shouldGenerateBlankFrames) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder = new ConcatenatingMediaSource2.Builder();
    if (!sequence.isLooping) {
      for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
        EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
        mediaSourceBuilder.add(
            createMediaSourceWithBlankFramesAndSilence(
                mediaSourceFactory, editedMediaItem, shouldGenerateBlankFrames),
            /* initialPlaceholderDurationMs= */ usToMs(
                editedMediaItem.getPresentationDurationUs()));
      }
      return mediaSourceBuilder.build();
    }

    long accumulatedDurationUs = 0;
    int i = 0;
    while (accumulatedDurationUs < primarySequenceDurationUs) {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      long itemPresentationDurationUs = editedMediaItem.getPresentationDurationUs();
      if (accumulatedDurationUs + itemPresentationDurationUs <= primarySequenceDurationUs) {
        mediaSourceBuilder.add(
            createMediaSourceWithBlankFramesAndSilence(
                mediaSourceFactory, editedMediaItem, shouldGenerateBlankFrames),
            /* initialPlaceholderDurationMs= */ usToMs(itemPresentationDurationUs));
        accumulatedDurationUs += itemPresentationDurationUs;
      } else {
        long remainingDurationUs = primarySequenceDurationUs - accumulatedDurationUs;
        // TODO: b/289989542 - Handle already clipped, or speed adjusted media.
        mediaSourceBuilder.add(
            createMediaSourceWithBlankFramesAndSilence(
                mediaSourceFactory,
                clipToDuration(editedMediaItem, remainingDurationUs),
                shouldGenerateBlankFrames));
        break;
      }
      i = (i + 1) % sequence.editedMediaItems.size();
    }
    return mediaSourceBuilder.build();
  }

  private static EditedMediaItem clipToDuration(EditedMediaItem editedMediaItem, long durationUs) {
    MediaItem.ClippingConfiguration clippingConfiguration =
        editedMediaItem.mediaItem.clippingConfiguration;
    return editedMediaItem
        .buildUpon()
        .setMediaItem(
            editedMediaItem
                .mediaItem
                .buildUpon()
                .setClippingConfiguration(
                    clippingConfiguration
                        .buildUpon()
                        .setEndPositionUs(clippingConfiguration.startPositionUs + durationUs)
                        .build())
                .build())
        .build();
  }

  private static MediaSource wrapWithVideoEffectsBasedMediaSources(
      MediaSource mediaSource,
      ImmutableList<Effect> videoEffects,
      ClippingConfiguration clippingConfiguration) {
    MediaSource newMediaSource = mediaSource;
    for (Effect videoEffect : videoEffects) {
      if (videoEffect instanceof InactiveTimestampAdjustment) {
        newMediaSource =
            new SpeedChangingMediaSource(
                newMediaSource,
                ((InactiveTimestampAdjustment) videoEffect).speedProvider,
                clippingConfiguration);
      }
    }
    return newMediaSource;
  }

  private ListenableFuture<?> maybeSetVideoOutput() {
    if (videoOutput == null || composition == null) {
      return Futures.immediateVoidFuture();
    }
    if (videoOutput instanceof SurfaceHolder) {
      setVideoSurfaceHolderInternal((SurfaceHolder) videoOutput);
    } else if (videoOutput instanceof SurfaceView) {
      SurfaceView surfaceView = (SurfaceView) videoOutput;
      setVideoSurfaceHolderInternal(surfaceView.getHolder());
    } else if (videoOutput instanceof Surface) {
      setVideoSurfaceInternal(
          (Surface) videoOutput,
          checkNotNull(videoOutputSize, "VideoOutputSize must be set when using Surface output"));
    } else {
      throw new IllegalStateException(videoOutput.getClass().toString());
    }
    return Futures.immediateVoidFuture();
  }

  private long getContentPositionMs() {
    if (playerHolders.isEmpty()) {
      return 0;
    }

    long lastContentPositionMs = 0;
    for (int i = 0; i < playerHolders.size(); i++) {
      lastContentPositionMs =
          max(lastContentPositionMs, playerHolders.get(i).player.getContentPosition());
    }
    return lastContentPositionMs;
  }

  private long getBufferedPositionMs() {
    if (playerHolders.isEmpty()) {
      return 0;
    }
    // Return the minimum buffered position among players.
    long minBufferedPositionMs = Integer.MAX_VALUE;
    for (int i = 0; i < playerHolders.size(); i++) {
      @Player.State int playbackState = playerHolders.get(i).player.getPlaybackState();
      if (playbackState == STATE_READY || playbackState == STATE_BUFFERING) {
        minBufferedPositionMs =
            min(minBufferedPositionMs, playerHolders.get(i).player.getBufferedPosition());
      }
    }
    return minBufferedPositionMs == Integer.MAX_VALUE
        // All players are ended or idle.
        ? 0
        : minBufferedPositionMs;
  }

  private long getTotalBufferedDurationMs() {
    if (playerHolders.isEmpty()) {
      return 0;
    }
    // Return the minimum total buffered duration among players.
    long minTotalBufferedDurationMs = Integer.MAX_VALUE;
    for (int i = 0; i < playerHolders.size(); i++) {
      @Player.State int playbackState = playerHolders.get(i).player.getPlaybackState();
      if (playbackState == STATE_READY || playbackState == STATE_BUFFERING) {
        minTotalBufferedDurationMs =
            min(minTotalBufferedDurationMs, playerHolders.get(i).player.getTotalBufferedDuration());
      }
    }
    return minTotalBufferedDurationMs == Integer.MAX_VALUE
        // All players are ended or idle.
        ? 0
        : minTotalBufferedDurationMs;
  }

  private boolean getRenderedFirstFrameAndReset() {
    boolean value = renderedFirstFrame;
    renderedFirstFrame = false;
    return value;
  }

  private void maybeUpdatePlaybackError(
      String errorMessage, Exception cause, @PlaybackException.ErrorCode int errorCode) {
    if (playbackException == null) {
      playbackException = new PlaybackException(errorMessage, cause, errorCode);
      for (int i = 0; i < playerHolders.size(); i++) {
        playerHolders.get(i).player.stop();
      }
      appNeedsToPrepareCompositionPlayer = true;
      updatePlaybackState();
      // Invalidate the parent class state.
      invalidateState();
    } else {
      Log.w(TAG, errorMessage, cause);
    }
  }

  private void setVideoSurfaceHolderInternal(SurfaceHolder surfaceHolder) {
    removeSurfaceCallbacks();
    this.surfaceHolder = surfaceHolder;
    surfaceHolder.addCallback(internalListener);
    Surface surface = surfaceHolder.getSurface();
    if (surface != null && surface.isValid()) {
      videoOutputSize =
          new Size(
              surfaceHolder.getSurfaceFrame().width(), surfaceHolder.getSurfaceFrame().height());
      setVideoSurfaceInternal(surface, videoOutputSize);
    } else {
      clearVideoSurfaceInternal();
    }
  }

  private void setVideoSurfaceInternal(Surface surface, Size videoOutputSize) {
    displaySurface = surface;
    maybeSetOutputSurfaceInfo(videoOutputSize.getWidth(), videoOutputSize.getHeight());
  }

  private void maybeSetOutputSurfaceInfo(int width, int height) {
    Surface surface = displaySurface;
    if (width == 0 || height == 0 || surface == null || compositionPlayerInternal == null) {
      return;
    }
    compositionPlayerInternal.setOutputSurfaceInfo(surface, new Size(width, height));
  }

  /**
   * This method blocks the calling thread until the internal player has removed the surface from
   * use.
   */
  private void clearVideoSurfaceInternal() {
    displaySurface = null;
    if (compositionPlayerInternal != null) {
      ConditionVariable surfaceCleared = new ConditionVariable();
      compositionPlayerInternal.clearOutputSurface(surfaceCleared);
      surfaceCleared.blockUninterruptible(SURFACE_DESTROY_TIMEOUT_MS);
    }
  }

  private void removeSurfaceCallbacks() {
    if (surfaceHolder != null) {
      surfaceHolder.removeCallback(internalListener);
      surfaceHolder = null;
    }
  }

  private void repeatCompositionPlayback() {
    repeatingCompositionSeekInProgress = true;
    seekToDefaultPosition();
  }

  private ImmutableList<MediaItemData> createPlaylist() {
    checkState(compositionDurationUs != C.TIME_UNSET);
    return ImmutableList.of(
        new MediaItemData.Builder("CompositionTimeline")
            .setMediaItem(MediaItem.EMPTY)
            .setDurationUs(compositionDurationUs)
            .build());
  }

  private void resetLivePositionSuppliers() {
    positionSupplier.disconnect(getContentPositionMs());
    bufferedPositionSupplier.disconnect(getBufferedPositionMs());
    totalBufferedDurationSupplier.disconnect(getTotalBufferedDurationMs());
    positionSupplier = new LivePositionSupplier(this::getContentPositionMs);
    bufferedPositionSupplier = new LivePositionSupplier(this::getBufferedPositionMs);
    totalBufferedDurationSupplier = new LivePositionSupplier(this::getTotalBufferedDurationMs);
  }

  private static long getCompositionDurationUs(Composition composition) {
    checkState(!composition.sequences.isEmpty());
    long longestSequenceDurationUs = Integer.MIN_VALUE;
    for (int i = 0; i < composition.sequences.size(); i++) {
      EditedMediaItemSequence sequence = composition.sequences.get(i);
      if (sequence.isLooping) {
        continue;
      }
      longestSequenceDurationUs = max(longestSequenceDurationUs, getSequenceDurationUs(sequence));
    }
    return longestSequenceDurationUs;
  }

  private static long getSequenceDurationUs(EditedMediaItemSequence sequence) {
    long compositionDurationUs = 0;
    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      compositionDurationUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
    }
    checkState(compositionDurationUs > 0, String.valueOf(compositionDurationUs));
    return compositionDurationUs;
  }

  /**
   * Returns whether the provided {@link Composition} contains any speed changing effect in an
   * unsupported configuration.
   *
   * <p>Speed changing effects are only supported as the first effect of an {@link EditedMediaItem}.
   */
  private static boolean compositionContainsIllegalSpeedChangingEffects(Composition composition) {
    if (containsSpeedChangingEffects(composition.effects, /* ignoreFirstEffect= */ false)) {
      return true;
    }

    for (EditedMediaItemSequence sequence : composition.sequences) {
      for (EditedMediaItem item : sequence.editedMediaItems) {
        if (containsSpeedChangingEffects(item.effects, /* ignoreFirstEffect= */ true)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * A {@link VideoFrameReleaseControl.FrameTimingEvaluator} for composition frames.
   *
   * <ul>
   *   <li>Signals to {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldForceReleaseFrame(long, long) force
   *       release} a frame if the frame is late by more than {@link #FRAME_LATE_THRESHOLD_US} and
   *       the elapsed time since the previous frame release is greater than {@link
   *       #FRAME_RELEASE_THRESHOLD_US}.
   *   <li>Signals to {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldDropFrame(long, long, boolean) drop a
   *       frame} if the frame is late by more than {@link #FRAME_LATE_THRESHOLD_US} and the frame
   *       is not marked as the last one.
   *   <li>Signals to never {@linkplain
   *       VideoFrameReleaseControl.FrameTimingEvaluator#shouldIgnoreFrame(long, long, long,
   *       boolean, boolean) ignore} a frame.
   * </ul>
   */
  private static final class CompositionFrameTimingEvaluator
      implements VideoFrameReleaseControl.FrameTimingEvaluator {

    /** The time threshold, in microseconds, after which a frame is considered late. */
    private static final long FRAME_LATE_THRESHOLD_US = -30_000;

    /**
     * The maximum elapsed time threshold, in microseconds, since last releasing a frame after which
     * a frame can be force released.
     */
    private static final long FRAME_RELEASE_THRESHOLD_US = 100_000;

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return earlyUs < FRAME_LATE_THRESHOLD_US
          && elapsedSinceLastReleaseUs > FRAME_RELEASE_THRESHOLD_US;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return earlyUs < FRAME_LATE_THRESHOLD_US && !isLastFrame;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped) {
      // TODO: b/293873191 - Handle very late buffers and drop to key frame.
      return false;
    }
  }

  private final class PlayerListener implements Listener {
    private final int playerIndex;

    public PlayerListener(int playerIndex) {
      this.playerIndex = playerIndex;
    }

    @Override
    public void onEvents(Player player, Events events) {
      if (events.containsAny(SUPPORTED_LISTENER_EVENTS)) {
        invalidateState();
      }
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
      updatePlaybackState();
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      if (reason == PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
          && repeatMode != REPEAT_MODE_OFF
          && playerIndex == 0) {
        repeatCompositionPlayback();
      }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      maybeUpdatePlaybackError("error from player " + playerIndex, error, error.errorCode);
    }
  }

  private void onVideoTrackSelection(boolean selected, int inputIndex) {
    videoTracksSelected.put(inputIndex, selected);

    if (videoTracksSelected.size() == checkNotNull(composition).sequences.size()) {
      int selectedVideoTracks = 0;
      for (int i = 0; i < videoTracksSelected.size(); i++) {
        if (videoTracksSelected.get(videoTracksSelected.keyAt(i))) {
          selectedVideoTracks++;
        }
      }

      checkNotNull(playbackVideoGraphWrapper).setTotalVideoInputCount(selectedVideoTracks);
    }
  }

  private final class SequencePlayerHolder {
    public final ExoPlayer player;
    public final SequenceRenderersFactory renderersFactory;
    public final CompositionTrackSelector trackSelector;

    private SequencePlayerHolder(
        Context context,
        Looper applicationLooper,
        Looper playbackLooper,
        Clock clock,
        SequenceRenderersFactory renderersFactory,
        int inputIndex) {
      trackSelector =
          new CompositionTrackSelector(
              context,
              /* listener= */ CompositionPlayer.this::onVideoTrackSelection,
              /* sequenceIndex= */ inputIndex);

      @SuppressLint("VisibleForTests") // Calls ExoPlayer.Builder.setClock()
      ExoPlayer.Builder playerBuilder =
          new ExoPlayer.Builder(context)
              .setLooper(applicationLooper)
              .setPlaybackLooper(playbackLooper)
              .setRenderersFactory(renderersFactory)
              .setHandleAudioBecomingNoisy(true)
              .setLoadControl(loadControl)
              .setClock(clock)
              // Use dynamic scheduling to show the first video/image frame more promptly when the
              // player is paused (which is common in editing applications).
              .experimentalSetDynamicSchedulingEnabled(true);
      playerBuilder.setTrackSelector(trackSelector);
      player = playerBuilder.build();
      this.renderersFactory = renderersFactory;
    }

    public void setSequence(EditedMediaItemSequence sequence) {
      renderersFactory.setSequence(sequence);
      trackSelector.setSequence(sequence);
    }
  }

  private static final class GapHandlingDecoderFactory implements ImageDecoder.Factory {
    private static final String BLANK_FRAMES_MEDIA_SOURCE_TYPE = "composition_player_blank_frames";
    private static final int BLANK_IMAGE_BITMAP_WIDTH = 1;
    private static final int BLANK_IMAGE_BITMAP_HEIGHT = 1;

    private final ImageDecoder.Factory imageDecoderFactory;
    private @MonotonicNonNull Format format;

    public GapHandlingDecoderFactory(@Nullable ImageDecoder.Factory imageDecoderFactory) {
      this.imageDecoderFactory = checkNotNull(imageDecoderFactory);
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
      // TODO: b/429411914 - Investigate a better way to get the output format
      this.format = format;
      if (format.sampleMimeType != null
          && format.sampleMimeType.equals(BLANK_FRAMES_MEDIA_SOURCE_TYPE)) {
        return RendererCapabilities.create(C.FORMAT_HANDLED);
      }
      return imageDecoderFactory.supportsFormat(format);
    }

    @Override
    public ImageDecoder createImageDecoder() {
      if (format != null
          && format.sampleMimeType != null
          && format.sampleMimeType.equals(BLANK_FRAMES_MEDIA_SOURCE_TYPE)) {
        return new ExternallyLoadedImageDecoder.Factory(
                request ->
                    immediateFuture(
                        Bitmap.createBitmap(
                            BLANK_IMAGE_BITMAP_WIDTH,
                            BLANK_IMAGE_BITMAP_HEIGHT,
                            Bitmap.Config.ARGB_8888)))
            .createImageDecoder();
      }
      return imageDecoderFactory.createImageDecoder();
    }
  }

  /** Class that holds internal listener methods for {@link CompositionPlayer}. */
  @SuppressWarnings("UngroupedOverloads") // onError() methods represent different callbacks.
  private final class InternalListener
      implements AudioFocusManager.PlayerControl,
          CompositionPlayerInternal.Listener,
          SurfaceHolder.Callback,
          PlaybackVideoGraphWrapper.Listener {

    // AudioFocusManager.PlayerControl methods. Called on the application thread.

    @Override
    public void setVolumeMultiplier(float volumeMultiplier) {
      setVolumeInternal(volume);
      invalidateState();
    }

    @Override
    public void executePlayerCommand(@PlayerCommand int playerCommand) {
      updatePlayWhenReadyWithAudioFocus(
          playWhenReady, playerCommand, playbackSuppressionReason, playWhenReadyChangeReason);
      invalidateState();
    }

    // CompositionPlayerInternal.Listener method. Called on the application thread.

    @Override
    public void onError(String message, Exception cause, int errorCode) {
      maybeUpdatePlaybackError(message, cause, errorCode);
    }

    // SurfaceHolder.Callback methods. Called on application thread.

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      videoOutputSize =
          new Size(holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
      setVideoSurfaceInternal(holder.getSurface(), videoOutputSize);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      maybeSetOutputSurfaceInfo(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      clearVideoSurfaceInternal();
    }

    // PlaybackVideoGraphWrapper.Listener methods. Called on the playback thread.

    @Override
    public void onFirstFrameRendered() {
      applicationHandler.post(
          () -> {
            CompositionPlayer.this.renderedFirstFrame = true;
            invalidateState();
          });
    }

    @Override
    public void onFrameDropped() {
      // Do not post to application thread on each dropped frame, because onFrameDropped
      // may be called frequently when resources are already scarce.
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      // TODO: b/328219481 - Report video size change to app.
    }

    @Override
    public void onError(VideoFrameProcessingException videoFrameProcessingException) {
      // The error will also be surfaced from the underlying ExoPlayer instance via
      // PlayerListener.onPlayerError, and it will arrive to the composition player twice.
      applicationHandler.post(
          () ->
              maybeUpdatePlaybackError(
                  "Error processing video frames",
                  videoFrameProcessingException,
                  PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED));
    }
  }
}
