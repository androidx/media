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
import static androidx.media3.common.util.Util.usToMs;
import static java.lang.Math.min;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.PreviewingSingleInputVideoGraph;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ExternalLoader;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.CompositingVideoSinkProvider;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link Player} implementation that plays {@linkplain Composition compositions} of media assets.
 * The {@link Composition} specifies how the assets should be arranged, and the audio and video
 * effects to apply to them.
 *
 * <p>CompositionPlayer instances must be accessed from a single application thread. For the vast
 * majority of cases this should be the application's main thread. The thread on which a
 * CompositionPlayer instance must be accessed can be explicitly specified by passing a {@link
 * Looper} when creating the player. If no {@link Looper} is specified, then the {@link Looper} of
 * the thread that the player is created on is used, or if that thread does not have a {@link
 * Looper}, the {@link Looper} of the application's main thread is used. In all cases the {@link
 * Looper} of the thread from which the player must be accessed can be queried using {@link
 * #getApplicationLooper()}.
 */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public final class CompositionPlayer extends SimpleBasePlayer
    implements CompositionPlayerInternal.Listener,
        CompositingVideoSinkProvider.Listener,
        SurfaceHolder.Callback {

  /** A builder for {@link CompositionPlayer} instances. */
  public static final class Builder {
    private final Context context;

    private @MonotonicNonNull Looper looper;
    private @MonotonicNonNull AudioSink audioSink;
    private @MonotonicNonNull ExternalLoader externalImageLoader;
    private ImageDecoder.Factory imageDecoderFactory;
    private Clock clock;
    private PreviewingVideoGraph.@MonotonicNonNull Factory previewingVideoGraphFactory;
    private boolean built;

    /**
     * Creates an instance
     *
     * @param context The application context.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      imageDecoderFactory = ImageDecoder.Factory.DEFAULT;
      clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link Looper} from which the player can be accessed and {@link Player.Listener}
     * callbacks are dispatched too.
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
     * Sets the {@link ExternalLoader} for loading image media items with MIME type set to {@link
     * MimeTypes#APPLICATION_EXTERNALLY_LOADED_IMAGE}. When setting an external loader, also set an
     * {@link ImageDecoder.Factory} with {@link #setImageDecoderFactory(ImageDecoder.Factory)}.
     *
     * <p>By default, the player will not be able to load images with media type of {@link
     * androidx.media3.common.MimeTypes#APPLICATION_EXTERNALLY_LOADED_IMAGE}.
     *
     * @param externalImageLoader The {@link ExternalLoader}.
     * @return This builder, for convenience.
     * @see DefaultMediaSourceFactory#setExternalImageLoader(ExternalLoader)
     */
    @CanIgnoreReturnValue
    public Builder setExternalImageLoader(ExternalLoader externalImageLoader) {
      this.externalImageLoader = externalImageLoader;
      return this;
    }

    /**
     * Sets an {@link ImageDecoder.Factory} that will create the {@link ImageDecoder} instances to
     * decode images.
     *
     * <p>By default, {@link ImageDecoder.Factory#DEFAULT} is used.
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
     * Sets the {@link PreviewingVideoGraph.Factory} that will be used by the player.
     *
     * <p>By default, a {@link PreviewingSingleInputVideoGraph.Factory} is used.
     *
     * @param previewingVideoGraphFactory The {@link PreviewingVideoGraph.Factory}.
     * @return This builder, for convenience.
     */
    @VisibleForTesting
    @CanIgnoreReturnValue
    public Builder setPreviewingVideoGraphFactory(
        PreviewingVideoGraph.Factory previewingVideoGraphFactory) {
      this.previewingVideoGraphFactory = previewingVideoGraphFactory;
      return this;
    }

    /**
     * Builds the {@link CompositionPlayer} instance. Must be called at most once.
     *
     * <p>If no {@link Looper} has been called with {@link #setLooper(Looper)}, then this method
     * must be called within a {@link Looper} thread which is the thread that can access the player
     * instance and where {@link Player.Listener} callbacks are dispatched.
     */
    public CompositionPlayer build() {
      checkState(!built);
      if (looper == null) {
        looper = checkStateNotNull(Looper.myLooper());
      }
      if (audioSink == null) {
        audioSink = new DefaultAudioSink.Builder(context).build();
      }
      if (previewingVideoGraphFactory == null) {
        previewingVideoGraphFactory = new PreviewingSingleInputVideoGraph.Factory();
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
              COMMAND_SEEK_BACK,
              COMMAND_SEEK_FORWARD,
              COMMAND_GET_CURRENT_MEDIA_ITEM,
              COMMAND_GET_TIMELINE,
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
        EVENT_POSITION_DISCONTINUITY
      };

  private static final int MAX_SUPPORTED_SEQUENCES = 2;

  private static final String TAG = "CompositionPlayer";

  private final Context context;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final List<ExoPlayer> players;
  private final AudioSink finalAudioSink;
  @Nullable private final ExternalLoader externalImageLoader;
  private final ImageDecoder.Factory imageDecoderFactory;
  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private final HandlerWrapper compositionInternalListenerHandler;

  private @MonotonicNonNull HandlerThread playbackThread;
  private @MonotonicNonNull CompositionPlayerInternal compositionPlayerInternal;
  private @MonotonicNonNull ImmutableList<MediaItemData> playlist;
  private @MonotonicNonNull Composition composition;
  private @MonotonicNonNull Size videoOutputSize;
  private long compositionDurationUs;
  private boolean playWhenReady;
  private @PlayWhenReadyChangeReason int playWhenReadyChangeReason;
  private boolean renderedFirstFrame;
  @Nullable private Object videoOutput;
  @Nullable private PlaybackException playbackException;
  private @Player.State int playbackState;
  @Nullable private SurfaceHolder surfaceHolder;
  @Nullable private Surface displaySurface;

  private CompositionPlayer(Builder builder) {
    super(checkNotNull(builder.looper), builder.clock);
    context = builder.context;
    clock = builder.clock;
    applicationHandler = clock.createHandler(builder.looper, /* callback= */ null);
    finalAudioSink = checkNotNull(builder.audioSink);
    externalImageLoader = builder.externalImageLoader;
    imageDecoderFactory = builder.imageDecoderFactory;
    previewingVideoGraphFactory = checkNotNull(builder.previewingVideoGraphFactory);
    compositionInternalListenerHandler = clock.createHandler(builder.looper, /* callback= */ null);
    players = new ArrayList<>();
    compositionDurationUs = C.TIME_UNSET;
    playbackState = STATE_IDLE;
  }

  /**
   * Sets the {@link Composition} to play.
   *
   * <p>This method should only be called once.
   *
   * @param composition The {@link Composition} to play. Every {@link EditedMediaItem} in the {@link
   *     Composition} must have its {@link EditedMediaItem#durationUs} set.
   */
  public void setComposition(Composition composition) {
    verifyApplicationThread();
    checkArgument(
        !composition.sequences.isEmpty()
            && composition.sequences.size() <= MAX_SUPPORTED_SEQUENCES);
    checkState(this.composition == null);

    setCompositionInternal(composition);
    if (videoOutput != null) {
      if (videoOutput instanceof SurfaceHolder) {
        setVideoSurfaceHolderInternal((SurfaceHolder) videoOutput);
      } else if (videoOutput instanceof SurfaceView) {
        SurfaceView surfaceView = (SurfaceView) videoOutput;
        setVideoSurfaceHolderInternal(surfaceView.getHolder());
      } else if (videoOutput instanceof Surface) {
        setVideoSurfaceInternal((Surface) videoOutput, checkNotNull(videoOutputSize));
      } else {
        throw new IllegalStateException(videoOutput.getClass().toString());
      }
    }
    // Update the composition field at the end after everything else has been set.
    this.composition = composition;
  }

  /** Sets the {@link Surface} and {@link Size} to render to. */
  @VisibleForTesting
  public void setVideoSurface(Surface surface, Size videoOutputSize) {
    videoOutput = surface;
    this.videoOutputSize = videoOutputSize;
    setVideoSurfaceInternal(surface, videoOutputSize);
  }

  // CompositingVideoSinkProvider.Listener methods. Called on playback thread.

  @Override
  public void onFirstFrameRendered(CompositingVideoSinkProvider compositingVideoSinkProvider) {
    applicationHandler.post(
        () -> {
          CompositionPlayer.this.renderedFirstFrame = true;
          invalidateState();
        });
  }

  @Override
  public void onFrameDropped(CompositingVideoSinkProvider compositingVideoSinkProvider) {
    // Do not post to application thread on each dropped frame, because onFrameDropped
    // may be called frequently when resources are already scarce.
  }

  @Override
  public void onVideoSizeChanged(
      CompositingVideoSinkProvider compositingVideoSinkProvider, VideoSize videoSize) {
    // TODO: b/328219481 - Report video size change to app.
  }

  @Override
  public void onError(
      CompositingVideoSinkProvider compositingVideoSinkProvider,
      VideoFrameProcessingException videoFrameProcessingException) {
    // The error will also be surfaced from the underlying ExoPlayer instance via
    // PlayerListener.onPlayerError, and it will arrive to the composition player twice.
    applicationHandler.post(
        () ->
            maybeUpdatePlaybackError(
                "error from video sink provider",
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
    @Player.State int oldPlaybackState = playbackState;
    updatePlaybackState();
    if (oldPlaybackState != STATE_READY && playbackState == STATE_READY && playWhenReady) {
      for (int i = 0; i < players.size(); i++) {
        players.get(i).setPlayWhenReady(true);
      }
    } else if (oldPlaybackState == STATE_READY
        && playWhenReady
        && playbackState == STATE_BUFFERING) {
      // We were playing but a player got in buffering state, pause the players.
      for (int i = 0; i < players.size(); i++) {
        players.get(i).setPlayWhenReady(false);
      }
    }
    // TODO: b/328219481 - Report video size change to app.
    State.Builder state =
        new State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(playbackState)
            .setPlayerError(playbackException)
            .setPlayWhenReady(playWhenReady, playWhenReadyChangeReason)
            .setContentPositionMs(this::getContentPositionMs)
            .setContentBufferedPositionMs(this::getBufferedPositionMs)
            .setTotalBufferedDurationMs(this::getTotalBufferedDurationMs)
            .setNewlyRenderedFirstFrame(getRenderedFirstFrameAndReset());
    if (playlist != null) {
      // Update the playlist only after it has been set so that SimpleBasePlayer announces a
      // timeline
      // change with reason TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
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
    for (int i = 0; i < players.size(); i++) {
      players.get(i).prepare();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
    this.playWhenReady = playWhenReady;
    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    if (playbackState == STATE_READY) {
      for (int i = 0; i < players.size(); i++) {
        players.get(i).setPlayWhenReady(playWhenReady);
      }
    } // else, wait until all players are ready.
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleStop() {
    for (int i = 0; i < players.size(); i++) {
      players.get(i).stop();
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
    for (int i = 0; i < players.size(); i++) {
      players.get(i).release();
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
    checkArgument(Util.areEqual(videoOutput, this.videoOutput));

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
      throw new UnsupportedOperationException(videoOutput.getClass().toString());
    }
    this.videoOutput = videoOutput;
    if (composition == null) {
      return Futures.immediateVoidFuture();
    }
    if (videoOutput instanceof SurfaceHolder) {
      setVideoSurfaceHolderInternal((SurfaceHolder) videoOutput);
    } else {
      setVideoSurfaceHolderInternal(((SurfaceView) videoOutput).getHolder());
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSetVolume(float volume) {
    volume = Util.constrainValue(volume, /* min= */ 0, /* max= */ 1);
    finalAudioSink.setVolume(volume);
    return Futures.immediateVoidFuture();
  }

  @Override
  protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
    CompositionPlayerInternal compositionPlayerInternal =
        checkStateNotNull(this.compositionPlayerInternal);
    compositionPlayerInternal.startSeek(positionMs);
    for (int i = 0; i < players.size(); i++) {
      players.get(i).seekTo(positionMs);
    }
    compositionPlayerInternal.endSeek();
    return Futures.immediateVoidFuture();
  }

  // CompositionPlayerInternal.Listener methods

  @Override
  public void onError(String message, Exception cause, int errorCode) {
    maybeUpdatePlaybackError(message, cause, errorCode);
  }

  // Internal methods

  private void updatePlaybackState() {
    if (players.isEmpty() || playbackException != null) {
      playbackState = STATE_IDLE;
      return;
    }

    int idleCount = 0;
    int bufferingCount = 0;
    int endedCount = 0;
    for (int i = 0; i < players.size(); i++) {
      @Player.State int playbackState = players.get(i).getPlaybackState();
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
    } else if (endedCount == players.size()) {
      playbackState = STATE_ENDED;
    } else {
      playbackState = STATE_READY;
    }
  }

  @SuppressWarnings("VisibleForTests") // Calls ExoPlayer.Builder.setClock()
  private void setCompositionInternal(Composition composition) {
    compositionDurationUs = getCompositionDurationUs(composition);
    playbackThread = new HandlerThread("CompositionPlaybackThread", Process.THREAD_PRIORITY_AUDIO);
    playbackThread.start();
    // Create the audio and video composition components now in order to setup the audio and video
    // pipelines. Once this method returns, further access to the audio and video pipelines must
    // done on the playback thread only, to ensure related components are accessed from one thread
    // only.
    PreviewAudioPipeline previewAudioPipeline =
        new PreviewAudioPipeline(
            new DefaultAudioMixer.Factory(),
            composition.effects.audioProcessors,
            checkNotNull(finalAudioSink));
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, new CompositionFrameTimingEvaluator(), /* allowedJoiningTimeMs= */ 0);
    CompositingVideoSinkProvider compositingVideoSinkProvider =
        new CompositingVideoSinkProvider.Builder(context, videoFrameReleaseControl)
            .setPreviewingVideoGraphFactory(checkNotNull(previewingVideoGraphFactory))
            .setClock(clock)
            .build();
    compositingVideoSinkProvider.addListener(this);
    for (int i = 0; i < composition.sequences.size(); i++) {
      EditedMediaItemSequence editedMediaItemSequence = composition.sequences.get(i);
      SequencePlayerRenderersWrapper playerRenderersWrapper =
          i == 0
              ? SequencePlayerRenderersWrapper.create(
                  context,
                  editedMediaItemSequence,
                  previewAudioPipeline,
                  compositingVideoSinkProvider,
                  imageDecoderFactory)
              : SequencePlayerRenderersWrapper.createForAudio(
                  context, editedMediaItemSequence, previewAudioPipeline);
      ExoPlayer.Builder playerBuilder =
          new ExoPlayer.Builder(context)
              .setLooper(getApplicationLooper())
              .setPlaybackLooper(playbackThread.getLooper())
              .setRenderersFactory(playerRenderersWrapper)
              .setHandleAudioBecomingNoisy(true)
              .setClock(clock);

      if (i == 0) {
        playerBuilder.setTrackSelector(new CompositionTrackSelector(context));
      }

      ExoPlayer player = playerBuilder.build();
      player.addListener(new PlayerListener(i));
      player.addAnalyticsListener(new EventLogger());
      setPlayerSequence(player, editedMediaItemSequence, /* shouldGenerateSilence= */ i == 0);
      players.add(player);
      if (i == 0) {
        // Invalidate the player state before initializing the playlist to force SimpleBasePlayer
        // to collect a state while the playlist is null. Consequently, once the playlist is
        // initialized, SimpleBasePlayer will raise a timeline change callback with reason
        // TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED.
        invalidateState();
        playlist = createPlaylist();
      }
    }
    // From here after, composition player accessed the audio and video pipelines via the internal
    // player. The internal player ensures access to the components is done on the playback thread.
    compositionPlayerInternal =
        new CompositionPlayerInternal(
            playbackThread.getLooper(),
            clock,
            previewAudioPipeline,
            compositingVideoSinkProvider,
            /* listener= */ this,
            compositionInternalListenerHandler);
  }

  /** Sets the {@linkplain EditedMediaItemSequence sequence} to be played by the player. */
  private void setPlayerSequence(
      ExoPlayer player, EditedMediaItemSequence sequence, boolean shouldGenerateSilence) {
    ConcatenatingMediaSource2.Builder mediaSourceBuilder =
        new ConcatenatingMediaSource2.Builder().useDefaultMediaSourceFactory(context);

    for (int i = 0; i < sequence.editedMediaItems.size(); i++) {
      EditedMediaItem editedMediaItem = sequence.editedMediaItems.get(i);
      checkArgument(editedMediaItem.durationUs != C.TIME_UNSET);
      long durationUs = editedMediaItem.getPresentationDurationUs();

      if (shouldGenerateSilence) {
        DefaultMediaSourceFactory defaultMediaSourceFactory =
            new DefaultMediaSourceFactory(context);
        if (externalImageLoader != null) {
          defaultMediaSourceFactory.setExternalImageLoader(externalImageLoader);
        }
        mediaSourceBuilder.add(
            new MergingMediaSource(
                defaultMediaSourceFactory.createMediaSource(editedMediaItem.mediaItem),
                // Generate silence as long as the MediaItem without clipping, because the actual
                // media track starts at the clipped position. For example, if a video is 1000ms
                // long and clipped 900ms from the start, its MediaSource will be enabled at 900ms
                // during track selection, rather than at 0ms.
                new SilenceMediaSource(editedMediaItem.durationUs)),
            /* initialPlaceholderDurationMs= */ usToMs(durationUs));
      } else {
        mediaSourceBuilder.add(
            editedMediaItem.mediaItem, /* initialPlaceholderDurationMs= */ usToMs(durationUs));
      }
    }
    player.setMediaSource(mediaSourceBuilder.build());
  }

  private long getContentPositionMs() {
    return players.isEmpty() ? C.TIME_UNSET : players.get(0).getContentPosition();
  }

  private long getBufferedPositionMs() {
    if (players.isEmpty()) {
      return 0;
    }
    // Return the minimum buffered position among players.
    long minBufferedPositionMs = Integer.MAX_VALUE;
    for (int i = 0; i < players.size(); i++) {
      minBufferedPositionMs = min(minBufferedPositionMs, players.get(i).getBufferedPosition());
    }
    return minBufferedPositionMs;
  }

  private long getTotalBufferedDurationMs() {
    if (players.isEmpty()) {
      return 0;
    }
    // Return the minimum total buffered duration among players.
    long minTotalBufferedDurationMs = Integer.MAX_VALUE;
    for (int i = 0; i < players.size(); i++) {
      minTotalBufferedDurationMs =
          min(minTotalBufferedDurationMs, players.get(i).getTotalBufferedDuration());
    }
    return minTotalBufferedDurationMs;
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
      for (int i = 0; i < players.size(); i++) {
        players.get(i).stop();
      }
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

  private ImmutableList<MediaItemData> createPlaylist() {
    checkNotNull(compositionDurationUs != C.TIME_UNSET);
    return ImmutableList.of(
        new MediaItemData.Builder("CompositionTimeline")
            .setMediaItem(MediaItem.EMPTY)
            .setDurationUs(compositionDurationUs)
            .build());
  }

  private static long getCompositionDurationUs(Composition composition) {
    checkState(!composition.sequences.isEmpty());

    long compositionDurationUs = getSequenceDurationUs(composition.sequences.get(0));
    for (int i = 0; i < composition.sequences.size(); i++) {
      long sequenceDurationUs = getSequenceDurationUs(composition.sequences.get(i));
      checkArgument(
          compositionDurationUs == sequenceDurationUs,
          Util.formatInvariant(
              "Non-matching sequence durations. First sequence duration: %d us, sequence [%d]"
                  + " duration: %d us",
              compositionDurationUs, i, sequenceDurationUs));
    }

    return compositionDurationUs;
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

  private final class PlayerListener implements Player.Listener {
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
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      playWhenReadyChangeReason = reason;
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      maybeUpdatePlaybackError("error from player " + playerIndex, error, error.errorCode);
    }
  }

  /**
   * A {@link DefaultTrackSelector} extension to de-select generated audio when the audio from the
   * media is playable.
   */
  private static final class CompositionTrackSelector extends DefaultTrackSelector {

    private static final String SILENCE_AUDIO_TRACK_GROUP_ID = "1:";

    public CompositionTrackSelector(Context context) {
      super(context);
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectAudioTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        @RendererCapabilities.AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupports,
        Parameters params)
        throws ExoPlaybackException {
      int audioRenderIndex = C.INDEX_UNSET;
      for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
        if (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
          audioRenderIndex = i;
          break;
        }
      }
      checkState(audioRenderIndex != C.INDEX_UNSET);

      TrackGroupArray audioTrackGroups = mappedTrackInfo.getTrackGroups(audioRenderIndex);
      // If there's only one audio TrackGroup, it'll be silence, there's no need to override track
      // selection.
      if (audioTrackGroups.length > 1) {
        boolean mediaAudioIsPlayable = false;
        int silenceAudioTrackGroupIndex = C.INDEX_UNSET;
        for (int i = 0; i < audioTrackGroups.length; i++) {
          if (audioTrackGroups.get(i).id.startsWith(SILENCE_AUDIO_TRACK_GROUP_ID)) {
            silenceAudioTrackGroupIndex = i;
            continue;
          }
          // For non-silence tracks
          for (int j = 0; j < audioTrackGroups.get(i).length; j++) {
            mediaAudioIsPlayable |=
                RendererCapabilities.getFormatSupport(
                        rendererFormatSupports[audioRenderIndex][i][j])
                    == C.FORMAT_HANDLED;
          }
        }
        checkState(silenceAudioTrackGroupIndex != C.INDEX_UNSET);

        if (mediaAudioIsPlayable) {
          // Disable silence if the media's audio track is playable.
          int silenceAudioTrackIndex = audioTrackGroups.length - 1;
          rendererFormatSupports[audioRenderIndex][silenceAudioTrackIndex][0] =
              RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
      }

      return super.selectAudioTrack(
          mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);
    }
  }
}
