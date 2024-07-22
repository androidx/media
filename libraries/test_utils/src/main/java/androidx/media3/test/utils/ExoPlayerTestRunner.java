/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static junit.framework.TestCase.assertFalse;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Helper class to run an ExoPlayer test. */
@UnstableApi
public final class ExoPlayerTestRunner implements Player.Listener, ActionSchedule.Callback {

  /** A generic video {@link Format} which can be used to set up a {@link FakeMediaSource}. */
  public static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setAverageBitrate(800_000)
          .setWidth(1280)
          .setHeight(720)
          .build();

  /** A generic audio {@link Format} which can be used to set up a {@link FakeMediaSource}. */
  public static final Format AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setAverageBitrate(100_000)
          .setChannelCount(2)
          .setSampleRate(44100)
          .build();

  /**
   * Builder to set-up an {@link ExoPlayerTestRunner}. Default fake implementations will be used for
   * unset test properties.
   */
  public static final class Builder {
    private final TestExoPlayerBuilder testPlayerBuilder;
    private @MonotonicNonNull Timeline timeline;
    private List<MediaSource> mediaSources;
    private Format[] supportedFormats;
    private @MonotonicNonNull Object manifest;
    private @MonotonicNonNull ActionSchedule actionSchedule;
    private @MonotonicNonNull Surface surface;
    private Player.@MonotonicNonNull Listener playerListener;
    private @MonotonicNonNull AnalyticsListener analyticsListener;
    private @MonotonicNonNull Integer expectedPlayerEndedCount;
    private boolean pauseAtEndOfMediaItems;
    private int initialMediaItemIndex;
    private long initialPositionMs;
    private boolean skipSettingMediaSources;

    public Builder(Context context) {
      testPlayerBuilder = new TestExoPlayerBuilder(context);
      mediaSources = new ArrayList<>();
      supportedFormats = new Format[] {VIDEO_FORMAT};
      initialMediaItemIndex = C.INDEX_UNSET;
      initialPositionMs = C.TIME_UNSET;
    }

    /**
     * Sets a {@link Timeline} to be used by a {@link FakeMediaSource} in the test runner. The
     * default value is a seekable, non-dynamic {@link FakeTimeline} with a duration of {@link
     * FakeTimeline.TimelineWindowDefinition#DEFAULT_WINDOW_DURATION_US}. Setting the timeline is
     * not allowed after a call to {@link #setMediaSources(MediaSource...)} or {@link
     * #skipSettingMediaSources()}.
     *
     * @param timeline A {@link Timeline} to be used by a {@link FakeMediaSource} in the test
     *     runner.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTimeline(Timeline timeline) {
      assertThat(mediaSources).isEmpty();
      assertFalse(skipSettingMediaSources);
      this.timeline = timeline;
      return this;
    }

    /**
     * Sets a manifest to be used by a {@link FakeMediaSource} in the test runner. The default value
     * is null. Setting the manifest is not allowed after a call to {@link
     * #setMediaSources(MediaSource...)} or {@link #skipSettingMediaSources()}.
     *
     * @param manifest A manifest to be used by a {@link FakeMediaSource} in the test runner.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setManifest(Object manifest) {
      assertThat(mediaSources).isEmpty();
      assertFalse(skipSettingMediaSources);
      this.manifest = manifest;
      return this;
    }

    /**
     * Seeks before setting the media sources and preparing the player.
     *
     * @param mediaItemIndex The media item index to seek to.
     * @param positionMs The position in milliseconds to seek to.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder initialSeek(int mediaItemIndex, long positionMs) {
      this.initialMediaItemIndex = mediaItemIndex;
      this.initialPositionMs = positionMs;
      return this;
    }

    /**
     * Sets the {@link MediaSource}s to be used by the test runner. The default value is a {@link
     * FakeMediaSource} with the timeline and manifest provided by {@link #setTimeline(Timeline)}
     * and {@link #setManifest(Object)}. Setting media sources is not allowed after calls to {@link
     * #skipSettingMediaSources()}, {@link #setTimeline(Timeline)} and/or {@link
     * #setManifest(Object)}.
     *
     * @param mediaSources The {@link MediaSource}s to be used by the test runner.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSources(MediaSource... mediaSources) {
      assertThat(timeline).isNull();
      assertThat(manifest).isNull();
      assertFalse(skipSettingMediaSources);
      this.mediaSources = Arrays.asList(mediaSources);
      return this;
    }

    /**
     * Sets a list of {@link Format}s to be used by a {@link FakeMediaSource} to create media
     * periods. The default value is a single {@link #VIDEO_FORMAT}. Note that this parameter
     * doesn't have any influence if a media source with {@link #setMediaSources(MediaSource...)} is
     * set.
     *
     * @param supportedFormats A list of supported {@link Format}s.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSupportedFormats(Format... supportedFormats) {
      this.supportedFormats = supportedFormats;
      return this;
    }

    /**
     * Skips calling {@link ExoPlayer#setMediaSources(List)} before preparing. Calling this method
     * is not allowed after calls to {@link #setMediaSources(MediaSource...)}, {@link
     * #setTimeline(Timeline)} and/or {@link #setManifest(Object)}.
     *
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder skipSettingMediaSources() {
      assertThat(timeline).isNull();
      assertThat(manifest).isNull();
      assertThat(mediaSources).isEmpty();
      skipSettingMediaSources = true;
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setUseLazyPreparation(boolean)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setUseLazyPreparation(boolean useLazyPreparation) {
      testPlayerBuilder.setUseLazyPreparation(useLazyPreparation);
      return this;
    }

    /**
     * Sets whether to enable pausing at the end of media items.
     *
     * @param pauseAtEndOfMediaItems Whether to pause at the end of media items.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
      this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems;
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setTrackSelector(DefaultTrackSelector)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTrackSelector(DefaultTrackSelector trackSelector) {
      testPlayerBuilder.setTrackSelector(trackSelector);
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setLoadControl(LoadControl)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setLoadControl(LoadControl loadControl) {
      testPlayerBuilder.setLoadControl(loadControl);
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setBandwidthMeter(BandwidthMeter)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBandwidthMeter(BandwidthMeter bandwidthMeter) {
      this.testPlayerBuilder.setBandwidthMeter(bandwidthMeter);
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setRenderers(Renderer...)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setRenderers(Renderer... renderers) {
      testPlayerBuilder.setRenderers(renderers);
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setRenderersFactory(RenderersFactory)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setRenderersFactory(RenderersFactory renderersFactory) {
      testPlayerBuilder.setRenderersFactory(renderersFactory);
      return this;
    }

    /**
     * @see TestExoPlayerBuilder#setClock(Clock)
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      testPlayerBuilder.setClock(clock);
      return this;
    }

    /**
     * Sets an {@link ActionSchedule} to be run by the test runner. The first action will be
     * executed immediately before {@link ExoPlayer#prepare()}.
     *
     * @param actionSchedule An {@link ActionSchedule} to be used by the test runner.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setActionSchedule(ActionSchedule actionSchedule) {
      this.actionSchedule = actionSchedule;
      return this;
    }

    /**
     * Sets the video {@link Surface}. The default value is {@code null}.
     *
     * @param surface The {@link Surface} to be used by the player.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setVideoSurface(Surface surface) {
      this.surface = surface;
      return this;
    }

    /**
     * Sets an {@link Player.Listener} to be registered to listen to player events.
     *
     * @param playerListener A {@link Player.Listener} to be registered by the test runner to listen
     *     to player events.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setPlayerListener(Player.Listener playerListener) {
      this.playerListener = playerListener;
      return this;
    }

    /**
     * Sets an {@link AnalyticsListener} to be registered.
     *
     * @param analyticsListener An {@link AnalyticsListener} to be registered.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAnalyticsListener(AnalyticsListener analyticsListener) {
      this.analyticsListener = analyticsListener;
      return this;
    }

    /**
     * Sets the number of times the test runner is expected to reach the {@link Player#STATE_ENDED}
     * or {@link Player#STATE_IDLE}. The default is 1. This affects how long {@link
     * ExoPlayerTestRunner#blockUntilEnded(long)} waits.
     *
     * @param expectedPlayerEndedCount The number of times the player is expected to reach the ended
     *     or idle state.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setExpectedPlayerEndedCount(int expectedPlayerEndedCount) {
      this.expectedPlayerEndedCount = expectedPlayerEndedCount;
      return this;
    }

    /**
     * Builds an {@link ExoPlayerTestRunner} using the provided values or their defaults.
     *
     * @return The built {@link ExoPlayerTestRunner}.
     */
    public ExoPlayerTestRunner build() {
      if (mediaSources.isEmpty() && !skipSettingMediaSources) {
        if (timeline == null) {
          timeline =
              new FakeTimeline(
                  /* windowCount= */ 1,
                  manifest != null ? new Object[] {manifest} : new Object[] {});
        }
        mediaSources.add(new FakeMediaSource(timeline, supportedFormats));
      }
      if (expectedPlayerEndedCount == null) {
        expectedPlayerEndedCount = 1;
      }
      return new ExoPlayerTestRunner(
          testPlayerBuilder,
          mediaSources,
          skipSettingMediaSources,
          initialMediaItemIndex,
          initialPositionMs,
          surface,
          actionSchedule,
          playerListener,
          analyticsListener,
          expectedPlayerEndedCount,
          pauseAtEndOfMediaItems);
    }
  }

  private final TestExoPlayerBuilder playerBuilder;
  private final List<MediaSource> mediaSources;
  private final boolean skipSettingMediaSources;
  private final int initialMediaItemIndex;
  private final long initialPositionMs;
  @Nullable private final Surface surface;
  @Nullable private final ActionSchedule actionSchedule;
  @Nullable private final Player.Listener playerListener;
  @Nullable private final AnalyticsListener analyticsListener;

  private final Clock clock;
  private final HandlerThread playerThread;
  private final HandlerWrapper handler;
  private final CountDownLatch endedCountDownLatch;
  private final CountDownLatch actionScheduleFinishedCountDownLatch;
  private final ArrayList<Timeline> timelines;
  private final ArrayList<Integer> timelineChangeReasons;
  private final ArrayList<MediaItem> mediaItems;
  private final ArrayList<Integer> mediaItemTransitionReasons;
  private final ArrayList<Integer> periodIndices;
  private final ArrayList<Integer> discontinuityReasons;
  private final ArrayList<Integer> playbackStates;
  private final boolean pauseAtEndOfMediaItems;

  private @MonotonicNonNull ExoPlayer player;
  private @MonotonicNonNull Exception exception;
  private boolean playerWasPrepared;

  private ExoPlayerTestRunner(
      TestExoPlayerBuilder playerBuilder,
      List<MediaSource> mediaSources,
      boolean skipSettingMediaSources,
      int initialMediaItemIndex,
      long initialPositionMs,
      @Nullable Surface surface,
      @Nullable ActionSchedule actionSchedule,
      @Nullable Player.Listener playerListener,
      @Nullable AnalyticsListener analyticsListener,
      int expectedPlayerEndedCount,
      boolean pauseAtEndOfMediaItems) {
    this.playerBuilder = playerBuilder;
    this.mediaSources = mediaSources;
    this.skipSettingMediaSources = skipSettingMediaSources;
    this.initialMediaItemIndex = initialMediaItemIndex;
    this.initialPositionMs = initialPositionMs;
    this.surface = surface;
    this.actionSchedule = actionSchedule;
    this.playerListener = playerListener;
    this.analyticsListener = analyticsListener;
    this.clock = playerBuilder.getClock();
    timelines = new ArrayList<>();
    timelineChangeReasons = new ArrayList<>();
    mediaItems = new ArrayList<>();
    mediaItemTransitionReasons = new ArrayList<>();
    periodIndices = new ArrayList<>();
    discontinuityReasons = new ArrayList<>();
    playbackStates = new ArrayList<>();
    endedCountDownLatch = new CountDownLatch(expectedPlayerEndedCount);
    actionScheduleFinishedCountDownLatch = new CountDownLatch(actionSchedule != null ? 1 : 0);
    playerThread = new HandlerThread("ExoPlayerTest thread");
    playerThread.start();
    handler = clock.createHandler(playerThread.getLooper(), /* callback= */ null);
    this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems;
  }

  // Called on the test thread to run the test.

  /**
   * Starts the test runner on its own thread. This will trigger the creation of the player, the
   * listener registration, the start of the action schedule, the initial set of media items and the
   * preparation of the player.
   *
   * @return This test runner.
   */
  @CanIgnoreReturnValue
  public ExoPlayerTestRunner start() {
    return start(/* doPrepare= */ true);
  }

  /**
   * Starts the test runner on its own thread. This will trigger the creation of the player, the
   * listener registration, the start of the action schedule and the initial set of media items.
   *
   * @param doPrepare Whether the player should be prepared.
   * @return This test runner.
   */
  @CanIgnoreReturnValue
  public ExoPlayerTestRunner start(boolean doPrepare) {
    handler.post(
        () -> {
          try {
            player = playerBuilder.setLooper(checkNotNull(Looper.myLooper())).build();
            if (surface != null) {
              player.setVideoSurface(surface);
            }
            if (pauseAtEndOfMediaItems) {
              player.setPauseAtEndOfMediaItems(true);
            }
            player.addListener(ExoPlayerTestRunner.this);
            if (playerListener != null) {
              player.addListener(playerListener);
            }
            if (analyticsListener != null) {
              player.addAnalyticsListener(analyticsListener);
            }
            player.play();
            if (actionSchedule != null) {
              actionSchedule.start(
                  player,
                  playerBuilder.getTrackSelector(),
                  surface,
                  handler,
                  /* callback= */ ExoPlayerTestRunner.this);
            }
            if (initialMediaItemIndex != C.INDEX_UNSET) {
              player.seekTo(initialMediaItemIndex, initialPositionMs);
            }
            if (!skipSettingMediaSources) {
              player.setMediaSources(mediaSources, /* resetPosition= */ false);
            }
            if (doPrepare) {
              player.prepare();
            }
          } catch (Exception e) {
            handleException(e);
          }
        });
    return this;
  }

  /**
   * Blocks the current thread until the test runner finishes. A test is deemed to be finished when
   * the playback state transitioned to {@link Player#STATE_ENDED} or {@link Player#STATE_IDLE} for
   * the specified number of times. The test also finishes when an {@link ExoPlaybackException} is
   * thrown.
   *
   * @param timeoutMs The maximum time to wait for the test runner to finish. If this time elapsed
   *     the method will throw a {@link TimeoutException}.
   * @return This test runner.
   * @throws Exception If any exception occurred during playback, release, or due to a timeout.
   */
  @CanIgnoreReturnValue
  public ExoPlayerTestRunner blockUntilEnded(long timeoutMs) throws Exception {
    clock.onThreadBlocked();
    if (!endedCountDownLatch.await(timeoutMs, MILLISECONDS)) {
      exception = new TimeoutException("Test playback timed out waiting for playback to end.");
    }
    release();
    // Throw any pending exception (from playback, timing out or releasing).
    if (exception != null) {
      throw exception;
    }
    return this;
  }

  /**
   * Blocks the current thread until the action schedule finished. This does not release the test
   * runner and the test must still call {@link #blockUntilEnded(long)}.
   *
   * @param timeoutMs The maximum time to wait for the action schedule to finish.
   * @return This test runner.
   * @throws TimeoutException If the action schedule did not finish within the specified timeout.
   * @throws InterruptedException If the test thread gets interrupted while waiting.
   */
  @CanIgnoreReturnValue
  public ExoPlayerTestRunner blockUntilActionScheduleFinished(long timeoutMs)
      throws TimeoutException, InterruptedException {
    clock.onThreadBlocked();
    if (!actionScheduleFinishedCountDownLatch.await(timeoutMs, MILLISECONDS)) {
      throw new TimeoutException("Test playback timed out waiting for action schedule to finish.");
    }
    return this;
  }

  // Assertions called on the test thread after test finished.

  /**
   * Asserts that the timelines reported by {@link Player.Listener#onTimelineChanged(Timeline, int)}
   * are the same to the provided timelines. This assert differs from testing equality by not
   * comparing period ids which may be different due to id mapping of child source period ids.
   *
   * @param timelines A list of expected {@link Timeline}s.
   */
  public void assertTimelinesSame(Timeline... timelines) {
    TestUtil.assertTimelinesSame(
        ImmutableList.copyOf(this.timelines), ImmutableList.copyOf(timelines));
  }

  /**
   * Asserts that the timeline change reasons reported by {@link
   * Player.Listener#onTimelineChanged(Timeline, int)} are equal to the provided timeline change
   * reasons.
   */
  public void assertTimelineChangeReasonsEqual(Integer... reasons) {
    assertThat(timelineChangeReasons).containsExactlyElementsIn(Arrays.asList(reasons)).inOrder();
  }

  /**
   * Asserts that the playback states reported by {@link
   * Player.Listener#onPlaybackStateChanged(int)} are equal to the provided playback states.
   */
  public void assertPlaybackStatesEqual(Integer... states) {
    assertThat(playbackStates).containsExactlyElementsIn(states).inOrder();
  }

  /**
   * Asserts that {@link Player.Listener#onPositionDiscontinuity(Player.PositionInfo,
   * Player.PositionInfo, int)} was not called.
   */
  public void assertNoPositionDiscontinuities() {
    assertThat(discontinuityReasons).isEmpty();
  }

  /**
   * Asserts that the discontinuity reasons reported by {@link
   * Player.Listener#onPositionDiscontinuity(Player.PositionInfo, Player.PositionInfo, int)} are
   * equal to the provided values.
   *
   * @param discontinuityReasons The expected discontinuity reasons.
   */
  public void assertPositionDiscontinuityReasonsEqual(Integer... discontinuityReasons) {
    assertThat(this.discontinuityReasons)
        .containsExactlyElementsIn(Arrays.asList(discontinuityReasons))
        .inOrder();
  }

  /**
   * Asserts that the indices of played periods is equal to the provided list of periods. A period
   * is considered to be played if it was the current period after a position discontinuity or a
   * media source preparation. When the same period is repeated automatically due to enabled repeat
   * modes, it is reported twice. Seeks within the current period are not reported.
   *
   * @param periodIndices A list of expected period indices.
   */
  public void assertPlayedPeriodIndices(Integer... periodIndices) {
    assertThat(this.periodIndices)
        .containsExactlyElementsIn(Arrays.asList(periodIndices))
        .inOrder();
  }

  // Private implementation details.

  private void release() throws InterruptedException {
    handler.post(
        () -> {
          try {
            if (player != null) {
              player.release();
            }
          } catch (Exception e) {
            handleException(e);
          } finally {
            playerThread.quit();
          }
        });
    clock.onThreadBlocked();
    playerThread.join();
  }

  private void handleException(Exception exception) {
    if (this.exception == null) {
      this.exception = exception;
    }
    while (endedCountDownLatch.getCount() > 0) {
      endedCountDownLatch.countDown();
    }
  }

  // Player.Listener

  @Override
  public void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
    timelineChangeReasons.add(reason);
    timelines.add(timeline);
    int currentIndex = checkNotNull(player).getCurrentPeriodIndex();
    if (periodIndices.isEmpty() || periodIndices.get(periodIndices.size() - 1) != currentIndex) {
      // Ignore timeline changes that do not change the period index.
      periodIndices.add(currentIndex);
    }
  }

  @Override
  public void onMediaItemTransition(
      @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
    if (mediaItem != null) {
      mediaItems.add(mediaItem);
    }
    mediaItemTransitionReasons.add(reason);
  }

  @Override
  public void onPlaybackStateChanged(@Player.State int playbackState) {
    playbackStates.add(playbackState);
    playerWasPrepared |= playbackState != Player.STATE_IDLE;
    if (playbackState == Player.STATE_ENDED
        || (playbackState == Player.STATE_IDLE && playerWasPrepared)) {
      endedCountDownLatch.countDown();
    }
  }

  @Override
  public void onPlayerError(PlaybackException error) {
    handleException(error);
  }

  @Override
  public void onPositionDiscontinuity(
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    discontinuityReasons.add(reason);
    int currentIndex = checkNotNull(player).getCurrentPeriodIndex();
    if ((reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            && oldPosition.adGroupIndex != C.INDEX_UNSET
            && newPosition.adGroupIndex != C.INDEX_UNSET)
        || periodIndices.isEmpty()
        || periodIndices.get(periodIndices.size() - 1) != currentIndex) {
      // Ignore seek or internal discontinuities within a period.
      periodIndices.add(currentIndex);
    }
  }

  // ActionSchedule.Callback

  @Override
  public void onActionScheduleFinished() {
    actionScheduleFinishedCountDownLatch.countDown();
  }
}
