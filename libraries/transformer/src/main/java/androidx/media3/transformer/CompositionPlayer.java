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
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.transformer.CompositionUtil.shouldRePreparePlayer;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.SparseBooleanArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
public final class CompositionPlayer extends SimpleBasePlayer
    implements CompositionPlayerInternal.Listener,
        PlaybackVideoGraphWrapper.Listener,
        SurfaceHolder.Callback {

  /** A builder for {@link CompositionPlayer} instances. */
  public static final class Builder {
    private final Context context;

    private @MonotonicNonNull Looper looper;
    private @MonotonicNonNull AudioSink audioSink;
    private MediaSource.Factory mediaSourceFactory;
    private ImageDecoder.Factory imageDecoderFactory;
    private boolean videoPrewarmingEnabled;
    private Clock clock;
    private VideoGraph.@MonotonicNonNull Factory videoGraphFactory;

    private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
    private boolean enableReplayableCache;
    private boolean built;

    /**
     * Creates an instance
     *
     * @param context The application context.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      mediaSourceFactory = new DefaultMediaSourceFactory(context);
      imageDecoderFactory =
          new BitmapFactoryImageDecoder.Factory(context)
              .setMaxOutputSize(GlUtil.MAX_BITMAP_DECODING_SIZE);
      videoPrewarmingEnabled = true;
      clock = Clock.DEFAULT;
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
      this.audioSink = audioSink;
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
      this.mediaSourceFactory = mediaSourceFactory;
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
      this.imageDecoderFactory = imageDecoderFactory;
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
     */
    @CanIgnoreReturnValue
    public Builder setVideoGraphFactory(VideoGraph.Factory videoGraphFactory) {
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
      this.glObjectsProvider = glObjectsProvider;
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
     * Builds the {@link CompositionPlayer} instance. Must be called at most once.
     *
     * <p>If no {@link Looper} has been called with {@link #setLooper(Looper)}, then this method
     * must be called within a {@link Looper} thread which is the thread that can access the player
     * instance and where {@link Listener} callbacks are dispatched.
     */
    public CompositionPlayer build() {
      checkState(!built);
      if (looper == null) {
        looper = checkStateNotNull(Looper.myLooper());
      }
      if (audioSink == null) {
        audioSink = new DefaultAudioSink.Builder(context).build();
      }
      if (videoGraphFactory == null) {
        DefaultVideoFrameProcessor.Factory.Builder videoFrameProcessorFactoryBuilder =
            new DefaultVideoFrameProcessor.Factory.Builder()
                .setEnableReplayableCache(enableReplayableCache);
        if (glObjectsProvider != null) {
          videoFrameProcessorFactoryBuilder.setGlObjectsProvider(glObjectsProvider);
        }
        videoGraphFactory =
            new SingleInputVideoGraph.Factory(videoFrameProcessorFactoryBuilder.build());
      }
      CompositionPlayer compositionPlayer = new CompositionPlayer(this);
      AnalyticsCollector analyticsCollector = new DefaultAnalyticsCollector(clock);
      analyticsCollector.setPlayer(compositionPlayer, looper);
      analyticsCollector.addListener(new EventLogger(TAG));
      compositionPlayer.addListener(analyticsCollector);
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
              COMMAND_RELEASE)
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

  private final Context context;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final List<SequencePlayerHolder> playerHolders;
  private final AudioSink finalAudioSink;
  private final MediaSource.Factory mediaSourceFactory;
  private final ImageDecoder.Factory imageDecoderFactory;
  private final VideoGraph.Factory videoGraphFactory;
  private final boolean videoPrewarmingEnabled;
  private final HandlerWrapper compositionInternalListenerHandler;
  private final boolean enableReplayableCache;

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
  private @MonotonicNonNull VideoFrameMetadataListener pendingVideoFrameMetadatListener;

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

  // "this" reference for position suppliers.
  @SuppressWarnings("initialization:methodref.receiver.bound.invalid")
  private CompositionPlayer(Builder builder) {
    super(checkNotNull(builder.looper), builder.clock);
    context = builder.context;
    clock = builder.clock;
    applicationHandler = clock.createHandler(builder.looper, /* callback= */ null);
    finalAudioSink = checkNotNull(builder.audioSink);
    mediaSourceFactory = builder.mediaSourceFactory;
    imageDecoderFactory = builder.imageDecoderFactory;
    videoGraphFactory = checkNotNull(builder.videoGraphFactory);
    videoPrewarmingEnabled = builder.videoPrewarmingEnabled;
    compositionInternalListenerHandler = clock.createHandler(builder.looper, /* callback= */ null);
    this.enableReplayableCache = builder.enableReplayableCache;
    videoTracksSelected = new SparseBooleanArray();
    playerHolders = new ArrayList<>();
    compositionDurationUs = C.TIME_UNSET;
    playbackState = STATE_IDLE;
    volume = 1.0f;
    positionSupplier = new LivePositionSupplier(this::getContentPositionMs);
    bufferedPositionSupplier = new LivePositionSupplier(this::getBufferedPositionMs);
    totalBufferedDurationSupplier = new LivePositionSupplier(this::getTotalBufferedDurationMs);
  }

  /**
   * Sets the {@link Composition} to play.
   *
   * <p>This method should only be called once.
   *
   * @param composition The {@link Composition} to play. Every {@link EditedMediaItem} in the {@link
   *     Composition} must have its {@link EditedMediaItem#durationUs} set.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void setComposition(Composition composition) {
    verifyApplicationThread();
    checkArgument(!composition.sequences.isEmpty());
    composition = deactivateSpeedAdjustingVideoEffects(composition);

    if (composition.sequences.size() > 1 && !videoGraphFactory.supportsMultipleInputs()) {
      Log.w(TAG, "Setting multi-sequence Composition with single input video graph.");
    }

    setCompositionInternal(composition);
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
    this.scrubbingModeEnabled = scrubbingModeEnabled;
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.setScrubbingModeEnabled(scrubbingModeEnabled);
    }
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

  // PlaybackVideoGraphWrapper.Listener methods. Called on playback thread.

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

  // SurfaceHolder.Callback methods. Called on application thread.

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    videoOutputSize = new Size(holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
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
    checkStateNotNull(composition, "No composition set");

    if (playbackState != Player.STATE_IDLE) {
      // The player has been prepared already.
      return Futures.immediateVoidFuture();
    }
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.prepare();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    this.playWhenReady = playWhenReady;
    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    if (playbackState == STATE_READY) {
      if (playWhenReady) {
        checkStateNotNull(compositionPlayerInternal).startRendering();
      } else {
        checkStateNotNull(compositionPlayerInternal).stopRendering();
      }
      for (int i = 0; i < playerHolders.size(); i++) {
        playerHolders.get(i).player.setPlayWhenReady(playWhenReady);
      }
    } // else, wait until all players are ready.
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
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleRelease() {
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }

    checkState(checkStateNotNull(playbackThread).isAlive());
    // Release the players first so that they stop rendering.
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.release();
    }
    checkStateNotNull(compositionPlayerInternal).release();
    removeSurfaceCallbacks();
    // Remove any queued callback from the internal player.
    compositionInternalListenerHandler.removeCallbacksAndMessages(/* token= */ null);
    displaySurface = null;
    checkStateNotNull(playbackThread).quitSafely();
    applicationHandler.removeCallbacksAndMessages(/* token= */ null);
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
    if (!(videoOutput instanceof SurfaceHolder || videoOutput instanceof SurfaceView)) {
      throw new UnsupportedOperationException(
          videoOutput.getClass() + ". Use CompositionPlayer.setVideoSurface() for Surface output.");
    }
    this.videoOutput = videoOutput;
    return maybeSetVideoOutput();
  }

  @Override
  protected ListenableFuture<?> handleSetVolume(float volume) {
    this.volume = Util.constrainValue(volume, /* min= */ 0.0f, /* max= */ 1.0f);
    if (compositionPlayerInternal != null) {
      compositionPlayerInternal.setVolume(this.volume);
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSeek(
      int mediaItemIndex, long positionMs, @Command int seekCommand) {
    resetLivePositionSuppliers();
    CompositionPlayerInternal compositionPlayerInternal =
        checkStateNotNull(this.compositionPlayerInternal);
    compositionPlayerInternal.startSeek(positionMs);
    for (int i = 0; i < playerHolders.size(); i++) {
      playerHolders.get(i).player.seekTo(positionMs);
    }
    compositionPlayerInternal.endSeek();
    return Futures.immediateVoidFuture();
  }

  /** Sets the {@link VideoFrameMetadataListener}. */
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    if (playerHolders.isEmpty()) {
      pendingVideoFrameMetadatListener = videoFrameMetadataListener;
      return;
    }
    playerHolders.get(0).player.setVideoFrameMetadataListener(videoFrameMetadataListener);
  }

  // CompositionPlayerInternal.Listener methods

  @Override
  public void onError(String message, Exception cause, int errorCode) {
    maybeUpdatePlaybackError(message, cause, errorCode);
  }

  // Internal methods

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
    playbackSuppressionReason = PLAYBACK_SUPPRESSION_REASON_NONE;
    for (int i = 0; i < playerHolders.size(); i++) {
      // TODO: b/422124120 - Determine playbackSuppressionReason by inspecting all players.
      if (playerHolders.get(i).player.getPlaybackSuppressionReason()
          != PLAYBACK_SUPPRESSION_REASON_NONE) {
        playbackSuppressionReason = playerHolders.get(i).player.getPlaybackSuppressionReason();
      }
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
    if (idleCount > 0) {
      playbackState = STATE_IDLE;
    } else if (bufferingCount > 0) {
      playbackState = STATE_BUFFERING;
      if (oldPlaybackState == STATE_READY && playWhenReady) {
        // We were playing but a player got in buffering state, pause the players.
        for (int i = 0; i < playerHolders.size(); i++) {
          playerHolders.get(i).player.setPlayWhenReady(false);
        }
        checkStateNotNull(compositionPlayerInternal).stopRendering();
      }
    } else if (endedCount == playerHolders.size()) {
      playbackState = STATE_ENDED;
      checkStateNotNull(compositionPlayerInternal).stopRendering();
    } else {
      playbackState = STATE_READY;
      if (oldPlaybackState != STATE_READY && playWhenReady) {
        for (int i = 0; i < playerHolders.size(); i++) {
          playerHolders.get(i).player.setPlayWhenReady(true);
        }
        checkStateNotNull(compositionPlayerInternal).startRendering();
      }
    }
  }

  private void prepareCompositionPlayerInternal() {
    if (compositionPlayerInternalPrepared) {
      return;
    }

    playbackThread = new HandlerThread("CompositionPlaybackThread", Process.THREAD_PRIORITY_AUDIO);
    playbackThread.start();
    playbackThreadHandler = clock.createHandler(playbackThread.getLooper(), /* callback= */ null);

    // Create the audio and video composition components now in order to setup the audio and video
    // pipelines. Once this method returns, further access to the audio and video graph wrappers
    // must done on the playback thread only, to ensure related components are accessed from one
    // thread only.
    playbackAudioGraphWrapper =
        new PlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(), checkNotNull(finalAudioSink));
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, new CompositionFrameTimingEvaluator(), /* allowedJoiningTimeMs= */ 0);
    playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setVideoGraphFactory(checkNotNull(videoGraphFactory))
            .setClock(clock)
            .setEnableReplayableCache(enableReplayableCache)
            .build();
    playbackVideoGraphWrapper.addListener(this);

    // From here after, composition player accessed the audio and video pipelines via the internal
    // player. The internal player ensures access to the components is done on the playback thread.
    compositionPlayerInternal =
        new CompositionPlayerInternal(
            playbackThread.getLooper(),
            clock,
            playbackAudioGraphWrapper,
            playbackVideoGraphWrapper,
            /* listener= */ this,
            compositionInternalListenerHandler);
    compositionPlayerInternal.setVolume(volume);
    compositionPlayerInternalPrepared = true;
  }

  private void setCompositionInternal(Composition composition) {
    prepareCompositionPlayerInternal();
    compositionDurationUs = getCompositionDurationUs(composition);
    long primarySequenceDurationUs =
        getSequenceDurationUs(checkNotNull(composition.sequences.get(0)));
    for (int i = 0; i < composition.sequences.size(); i++) {
      setSequenceInternal(composition, /* sequenceIndex= */ i, primarySequenceDurationUs);
    }
  }

  private void setSequenceInternal(
      Composition newComposition, int sequenceIndex, long primarySequenceDurationUs) {
    EditedMediaItemSequence newSequence = newComposition.sequences.get(sequenceIndex);
    @Nullable
    EditedMediaItemSequence oldSequence =
        composition == null || composition.sequences.size() <= sequenceIndex
            ? null
            : composition.sequences.get(sequenceIndex);

    SequencePlayerHolder playerHolder;
    if (playerHolders.size() <= sequenceIndex) {
      playerHolder =
          createSequencePlayer(
              sequenceIndex,
              newComposition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC);
      playerHolders.add(playerHolder);
    } else {
      playerHolder = playerHolders.get(sequenceIndex);
    }
    playerHolder.setSequence(newSequence);
    ExoPlayer player = playerHolder.player;

    if (shouldRePreparePlayer(oldSequence, newSequence)) {
      // Starts from zero - internal player will discard current progress, re-preparing it by
      // setting new media sources.
      // TODO: b/412585856 - Optimize for the case where we can keep some of the MediaSources.
      if (sequenceIndex == 0) {
        player.setMediaSource(createPrimarySequenceMediaSource(newSequence, mediaSourceFactory));
        if (pendingVideoFrameMetadatListener != null) {
          player.setVideoFrameMetadataListener(pendingVideoFrameMetadatListener);
        }
      } else {
        player.setMediaSource(
            createSecondarySequenceMediaSource(
                newSequence, mediaSourceFactory, primarySequenceDurationUs));
      }
      checkNotNull(compositionPlayerInternal)
          .setComposition(newComposition, /* startPositionUs= */ C.TIME_UNSET);

      if (sequenceIndex == 0) {
        // Invalidate the player state before initializing the playlist to force SimpleBasePlayer
        // to collect a state while the playlist is null. Consequently, once the playlist is
        // initialized, SimpleBasePlayer will raise a timeline change callback with reason
        // TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
        invalidateState();
        playlist = createPlaylist();
      }

      if (playbackState != STATE_IDLE) {
        player.stop();
        player.prepare();
      }
    } else {
      // Start from current position
      checkNotNull(compositionPlayerInternal)
          .setComposition(
              newComposition, /* startPositionUs= */ msToUs(player.getCurrentPosition()));
    }
  }

  private SequencePlayerHolder createSequencePlayer(
      int sequenceIndex, boolean requestMediaCodecToneMapping) {
    SequencePlayerHolder playerHolder =
        new SequencePlayerHolder(
            context,
            getApplicationLooper(),
            checkStateNotNull(playbackThread).getLooper(),
            clock,
            SequenceRenderersFactory.create(
                context,
                checkStateNotNull(playbackAudioGraphWrapper),
                checkStateNotNull(playbackVideoGraphWrapper)
                    .getSink(/* inputIndex= */ sequenceIndex),
                imageDecoderFactory,
                /* inputIndex= */ sequenceIndex,
                videoPrewarmingEnabled),
            /* inputIndex= */ sequenceIndex);
    playerHolder.player.addListener(new PlayerListener(sequenceIndex));
    playerHolder.player.addAnalyticsListener(new EventLogger(TAG + "-" + sequenceIndex));
    playerHolder.player.setPauseAtEndOfMediaItems(true);
    playerHolder.renderersFactory.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
    return playerHolder;
  }

  private static MediaSource createPrimarySequenceMediaSource(
      EditedMediaItemSequence sequence, MediaSource.Factory mediaSourceFactory) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder = new ConcatenatingMediaSource2.Builder();

    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      checkArgument(editedMediaItem.durationUs != C.TIME_UNSET);
      long durationUs = editedMediaItem.getPresentationDurationUs();

      MediaSource silenceGeneratedMediaSource =
          createMediaSourceWithSilence(mediaSourceFactory, editedMediaItem);

      MediaSource itemMediaSource =
          wrapWithVideoEffectsBasedMediaSources(
              silenceGeneratedMediaSource, editedMediaItem.effects.videoEffects, durationUs);
      mediaSourceBuilder.add(
          itemMediaSource, /* initialPlaceholderDurationMs= */ usToMs(durationUs));
    }
    return mediaSourceBuilder.build();
  }

  private static MediaSource createMediaSourceWithSilence(
      MediaSource.Factory mediaSourceFactory, EditedMediaItem editedMediaItem) {
    MediaSource silenceMediaSource =
        new ClippingMediaSource.Builder(new SilenceMediaSource(editedMediaItem.durationUs))
            .setStartPositionUs(editedMediaItem.mediaItem.clippingConfiguration.startPositionUs)
            .setEndPositionUs(editedMediaItem.mediaItem.clippingConfiguration.endPositionUs)
            .build();

    if (editedMediaItem.isGap()) {
      return silenceMediaSource;
    }

    // The MediaSource that loads the MediaItem
    MediaSource mainMediaSource = mediaSourceFactory.createMediaSource(editedMediaItem.mediaItem);
    return new MergingMediaSource(mainMediaSource, silenceMediaSource);
  }

  private static MediaSource createSecondarySequenceMediaSource(
      EditedMediaItemSequence sequence,
      MediaSource.Factory mediaSourceFactory,
      long primarySequenceDurationUs) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder = new ConcatenatingMediaSource2.Builder();
    if (!sequence.isLooping) {
      for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
        EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
        mediaSourceBuilder.add(
            createMediaSourceWithSilence(mediaSourceFactory, editedMediaItem),
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
            createMediaSourceWithSilence(mediaSourceFactory, editedMediaItem),
            /* initialPlaceholderDurationMs= */ usToMs(itemPresentationDurationUs));
        accumulatedDurationUs += itemPresentationDurationUs;
      } else {
        long remainingDurationUs = primarySequenceDurationUs - accumulatedDurationUs;
        // TODO: b/289989542 - Handle already clipped, or speed adjusted media.
        mediaSourceBuilder.add(
            createMediaSourceWithSilence(
                mediaSourceFactory, clipToDuration(editedMediaItem, remainingDurationUs)));
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
      MediaSource mediaSource, ImmutableList<Effect> videoEffects, long durationUs) {
    MediaSource newMediaSource = mediaSource;
    for (Effect videoEffect : videoEffects) {
      if (videoEffect instanceof InactiveTimestampAdjustment) {
        newMediaSource =
            new SpeedChangingMediaSource(
                newMediaSource,
                ((InactiveTimestampAdjustment) videoEffect).speedProvider,
                durationUs);
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
    surfaceHolder.addCallback(this);
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

  private void clearVideoSurfaceInternal() {
    displaySurface = null;
    if (compositionPlayerInternal != null) {
      compositionPlayerInternal.clearOutputSurface();
    }
  }

  private void removeSurfaceCallbacks() {
    if (surfaceHolder != null) {
      surfaceHolder.removeCallback(this);
      surfaceHolder = null;
    }
  }

  private void repeatCompositionPlayback() {
    repeatingCompositionSeekInProgress = true;
    seekToDefaultPosition();
  }

  private ImmutableList<MediaItemData> createPlaylist() {
    checkNotNull(compositionDurationUs != C.TIME_UNSET);
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
      playWhenReadyChangeReason = reason;
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
}
