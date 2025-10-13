/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.test.proguard;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView;
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import androidx.media3.ui.TrackSelectionDialogBuilder;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Class exercising reflection code in the UI module that relies on a correct proguard config.
 *
 * <p>Note on adding tests: Verify that tests fail without the relevant proguard config. Be careful
 * with adding new direct class references that may let other tests pass without proguard config.
 */
public final class UiModuleProguard {

  private UiModuleProguard() {}

  /** Inflates a {@link PlayerView} using {@link SphericalGLSurfaceView}. */
  public static void inflatePlayerViewWithSphericalGLSurfaceView(Context context) {
    LayoutInflater.from(context)
        .inflate(R.layout.spherical_gl_surface_view_player_view, /* root= */ null);
  }

  /** Inflates a {@link PlayerView} using {@link VideoDecoderGLSurfaceView}. */
  public static void inflatePlayerViewWithVideoDecoderGLSurfaceView(Context context) {
    LayoutInflater.from(context)
        .inflate(R.layout.video_decoder_gl_surface_view_player_view, /* root= */ null);
  }

  /**
   * Inflates and returns a {@link PlayerControlView} using {@link
   * ProgrammaticallyScrubbableTimeBar} and {@link ExoPlayer}, scrubs on the time bar and asserts
   * the suppression reason change and seeks happen.
   */
  public static void scrubOnTimeBarWithExoPlayerAndCheckThatSuppressionReasonChangesAndSeeksHappen(
      Context context) throws InterruptedException {
    ConditionVariable playerReady = new ConditionVariable();
    List<@Player.PlaybackSuppressionReason Integer> playbackSuppressionReasons =
        Collections.synchronizedList(new ArrayList<>());
    List<Player.PositionInfo> newDiscontinuityPositionInfos =
        Collections.synchronizedList(new ArrayList<>());
    Player.Listener listener =
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(@Player.State int playbackState) {
            if (playbackState == Player.STATE_READY) {
              playerReady.open();
            }
          }

          @Override
          public void onPlaybackSuppressionReasonChanged(
              @Player.PlaybackSuppressionReason int playbackSuppressionReason) {
            playbackSuppressionReasons.add(playbackSuppressionReason);
          }

          @Override
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition,
              Player.PositionInfo newPosition,
              @Player.DiscontinuityReason int reason) {
            newDiscontinuityPositionInfos.add(newPosition);
          }
        };
    Handler mainHandler = new Handler(Looper.getMainLooper());
    mainHandler.post(
        () -> {
          ExoPlayer player = new ExoPlayer.Builder(context).build();
          player.addListener(listener);
          PlayerControlView playerControlView =
              (PlayerControlView)
                  LayoutInflater.from(context)
                      .inflate(
                          R.layout.player_control_view_with_scrubbable_timebar, /* root= */ null);
          playerControlView.setPlayer(player);
          player.setMediaItem(MediaItem.fromUri("/android_asset/media/mp4/sample.mp4"));
          player.prepare();
          player.play();
        });
    playerReady.block(/* timeoutMs= */ 10_000);

    ConditionVariable scrubberActionFinished = new ConditionVariable();
    mainHandler.post(
        () -> {
          ProgrammaticallyScrubbableTimeBar programmaticallyScrubbableTimeBar =
              ProgrammaticallyScrubbableTimeBar.getConstructedInstance();
          programmaticallyScrubbableTimeBar.startScrubbing(/* positionMs= */ 123);
          programmaticallyScrubbableTimeBar.moveScrubber(/* positionMs= */ 456);
          scrubberActionFinished.open();
        });
    scrubberActionFinished.block(/* timeoutMs= */ 10_000);

    checkState(playbackSuppressionReasons.contains(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING));
    checkState(
        newDiscontinuityPositionInfos.stream()
            .map(positionInfo -> positionInfo.positionMs)
            .collect(toImmutableList())
            .equals(ImmutableList.of(123L, 456L)));
  }

  /**
   * Inflates and returns a {@link PlayerControlView} using {@link
   * ProgrammaticallyScrubbableTimeBar} and {@link CompositionPlayer}, scrubs on the time bar and
   * asserts the suppression reason change and seeks happen.
   */
  public static void
      scrubOnTimeBarWithCompositionPlayerAndCheckThatSuppressionReasonChangesAndSeeksHappen(
          Context context) throws InterruptedException {
    ConditionVariable playerReady = new ConditionVariable();
    List<@Player.PlaybackSuppressionReason Integer> playbackSuppressionReasons =
        Collections.synchronizedList(new ArrayList<>());
    List<Player.PositionInfo> newDiscontinuityPositionInfos =
        Collections.synchronizedList(new ArrayList<>());
    Player.Listener listener =
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(@Player.State int playbackState) {
            if (playbackState == Player.STATE_READY) {
              playerReady.open();
            }
          }

          @Override
          public void onPlaybackSuppressionReasonChanged(
              @Player.PlaybackSuppressionReason int playbackSuppressionReason) {
            playbackSuppressionReasons.add(playbackSuppressionReason);
          }

          @Override
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition,
              Player.PositionInfo newPosition,
              @Player.DiscontinuityReason int reason) {
            newDiscontinuityPositionInfos.add(newPosition);
          }
        };
    Handler mainHandler = new Handler(Looper.getMainLooper());
    mainHandler.post(
        () -> {
          CompositionPlayer player = new CompositionPlayer.Builder(context).build();
          player.addListener(listener);
          PlayerControlView playerControlView =
              (PlayerControlView)
                  LayoutInflater.from(context)
                      .inflate(
                          R.layout.player_control_view_with_scrubbable_timebar, /* root= */ null);
          playerControlView.setPlayer(player);
          EditedMediaItem video =
              new EditedMediaItem.Builder(MediaItem.fromUri("/android_asset/media/mp4/sample.mp4"))
                  .setDurationUs(1_000_000)
                  .build();
          player.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(video)))
                  .build());
          player.prepare();
          player.play();
        });
    playerReady.block(/* timeoutMs= */ 10_000);

    ConditionVariable scrubberActionFinished = new ConditionVariable();
    mainHandler.post(
        () -> {
          ProgrammaticallyScrubbableTimeBar programmaticallyScrubbableTimeBar =
              ProgrammaticallyScrubbableTimeBar.getConstructedInstance();
          programmaticallyScrubbableTimeBar.startScrubbing(/* positionMs= */ 123);
          programmaticallyScrubbableTimeBar.moveScrubber(/* positionMs= */ 456);
          scrubberActionFinished.open();
        });
    scrubberActionFinished.block(/* timeoutMs= */ 10_000);

    checkState(playbackSuppressionReasons.contains(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING));
    checkState(
        newDiscontinuityPositionInfos.stream()
            .map(positionInfo -> positionInfo.positionMs)
            .collect(toImmutableList())
            .equals(ImmutableList.of(123L, 456L)));
  }

  /**
   * Creates an AndroidX AlertDialog using {@link TrackSelectionDialogBuilder}.
   *
   * @param context A {@link Context}.
   */
  public static void createAndroidXDialogWithTrackSelectionDialogBuilder(Context context)
      throws InterruptedException {
    Tracks.Group trackGroup =
        new Tracks.Group(
            new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
            /* adaptiveSupported= */ false,
            new int[] {C.FORMAT_HANDLED},
            /* trackSelected= */ new boolean[] {true});

    HandlerThread uiThread = new HandlerThread("ui-proguard-test");
    uiThread.start();
    ConditionVariable dialogCreatedCondition = new ConditionVariable();
    AtomicReference<Dialog> dialog = new AtomicReference<>();
    new Handler(uiThread.getLooper())
        .post(
            () -> {
              try {
                dialog.set(
                    new TrackSelectionDialogBuilder(
                            context,
                            "title",
                            ImmutableList.of(trackGroup),
                            (isDisabled, overrides) -> {
                              /* Do nothing. */
                            })
                        .build());
              } finally {
                dialogCreatedCondition.open();
              }
            });
    dialogCreatedCondition.block();
    uiThread.quit();

    // Ensure it's not a platform AlertDialog.
    checkState(!(dialog.get() instanceof AlertDialog));
  }

  public static void setPlayerOnPlayerViewAndCheckThatImageRendererReceivesOutput(Context context)
      throws InterruptedException {
    ConditionVariable imageOutputSet = new ConditionVariable();
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              ExoPlayer exoPlayer =
                  new ExoPlayer.Builder(
                          context,
                          (eventHandler,
                              videoRendererEventListener,
                              audioRendererEventListener,
                              textRendererOutput,
                              metadataRendererOutput) ->
                              new Renderer[] {
                                new BaseRenderer(C.TRACK_TYPE_IMAGE) {
                                  @Override
                                  public String getName() {
                                    return "test";
                                  }

                                  @Override
                                  public void render(long positionUs, long elapsedRealtimeUs) {}

                                  @Override
                                  public boolean isReady() {
                                    return false;
                                  }

                                  @Override
                                  public boolean isEnded() {
                                    return false;
                                  }

                                  @Override
                                  public @Capabilities int supportsFormat(Format format) {
                                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
                                  }

                                  @Override
                                  public void handleMessage(
                                      @MessageType int messageType, Object message) {
                                    if (messageType == Renderer.MSG_SET_IMAGE_OUTPUT) {
                                      imageOutputSet.open();
                                    }
                                  }
                                }
                              })
                      .build();
              PlayerView playerView =
                  (PlayerView)
                      LayoutInflater.from(context)
                          .inflate(
                              R.layout.spherical_gl_surface_view_player_view, /* root= */ null);
              playerView.setPlayer(exoPlayer);
            });
    imageOutputSet.block();
  }

  /**
   * A {@link TimeBar} which allows {@linkplain #addListener(OnScrubListener) registered scrub
   * listeners} to be programmatically invoked.
   */
  /* package */ static final class ProgrammaticallyScrubbableTimeBar extends View
      implements TimeBar {

    /**
     * This mutable static state is used so the test can access the {@code CustomTimeBar} instance
     * constructed during {@linkplain LayoutInflater#inflate inflation} of the {@link
     * PlayerControlView}, in order to synthesis scrubbing operations.
     */
    private static final AtomicReference<ProgrammaticallyScrubbableTimeBar> lastInstance =
        new AtomicReference<>();

    private final Set<OnScrubListener> scrubListeners;

    public ProgrammaticallyScrubbableTimeBar(Context context) {
      super(context);
      scrubListeners = new HashSet<>();
      lastInstance.set(this);
    }

    public ProgrammaticallyScrubbableTimeBar(Context context, @Nullable AttributeSet attrs) {
      super(context, attrs);
      scrubListeners = new HashSet<>();
      lastInstance.set(this);
    }

    public ProgrammaticallyScrubbableTimeBar(
        Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
      super(context, attrs, defStyleAttr);
      scrubListeners = new HashSet<>();
      lastInstance.set(this);
    }

    public ProgrammaticallyScrubbableTimeBar(
        Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
      super(context, attrs, defStyleAttr, defStyleRes);
      scrubListeners = new HashSet<>();
      lastInstance.set(this);
    }

    @Override
    public void addListener(OnScrubListener listener) {
      scrubListeners.add(listener);
    }

    @Override
    public void removeListener(OnScrubListener listener) {
      scrubListeners.remove(listener);
    }

    @Override
    public void setKeyTimeIncrement(long time) {}

    @Override
    public void setKeyCountIncrement(int count) {}

    @Override
    public void setPosition(long position) {}

    @Override
    public void setBufferedPosition(long bufferedPosition) {}

    @Override
    public void setDuration(long duration) {}

    @Override
    public long getPreferredUpdateDelay() {
      return Long.MAX_VALUE;
    }

    @Override
    public void setAdGroupTimesMs(
        @Nullable long[] adGroupTimesMs, @Nullable boolean[] playedAdGroups, int adGroupCount) {}

    /**
     * Calls {@link OnScrubListener#onScrubStart(TimeBar, long)} on all {@linkplain
     * #addListener(OnScrubListener) registered listeners}.
     */
    public void startScrubbing(long positionMs) {
      for (OnScrubListener scrubListener : scrubListeners) {
        scrubListener.onScrubStart(this, positionMs);
      }
    }

    /**
     * Calls {@link OnScrubListener#onScrubMove(TimeBar, long)} on all {@linkplain
     * #addListener(OnScrubListener) registered listeners}.
     */
    public void moveScrubber(long positionMs) {
      for (OnScrubListener scrubListener : scrubListeners) {
        scrubListener.onScrubMove(this, positionMs);
      }
    }

    /**
     * Returns the last instance that has been constructed in this JVM. Throws an exception if no
     * instances have been constructed.
     */
    public static ProgrammaticallyScrubbableTimeBar getConstructedInstance() {
      return checkNotNull(lastInstance.get());
    }
  }
}
