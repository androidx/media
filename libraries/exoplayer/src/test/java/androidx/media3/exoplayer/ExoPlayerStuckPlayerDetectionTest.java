/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.StuckPlayerException;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.Allocation;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdaptiveDataSet;
import androidx.media3.test.utils.FakeAdaptiveMediaSource;
import androidx.media3.test.utils.FakeChunkSource;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.FakeMediaClockRenderer;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowPackageManager;

/** Unit test for stuck player detection logic in {@link ExoPlayer}. */
@RunWith(AndroidJUnit4.class)
public class ExoPlayerStuckPlayerDetectionTest {

  private static final int TIMEOUT_MS = 10_000;

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    ExoPlayer.Builder.experimentalEnableStuckPlayingDetection = true;
  }

  @Test
  public void loadControlNeverWantsToLoad_throwsStuckPlayerException() {
    LoadControl neverLoadingLoadControl =
        new DefaultLoadControl() {
          @Override
          public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
            return false;
          }

          @Override
          public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
            return true;
          }
        };

    // Use chunked data to ensure the player actually needs to continue loading and playing.
    FakeAdaptiveDataSet.Factory dataSetFactory =
        new FakeAdaptiveDataSet.Factory(
            /* chunkDurationUs= */ 500_000, /* bitratePercentStdDev= */ 10.0, new Random(0));
    MediaSource chunkedMediaSource =
        new FakeAdaptiveMediaSource(
            new FakeTimeline(),
            new TrackGroupArray(new TrackGroup(ExoPlayerTestRunner.VIDEO_FORMAT)),
            new FakeChunkSource.Factory(dataSetFactory, new FakeDataSource.Factory()));

    ExoPlaybackException exception =
        assertThrows(
            ExoPlaybackException.class,
            () ->
                new ExoPlayerTestRunner.Builder(context)
                    .setLoadControl(neverLoadingLoadControl)
                    .setMediaSources(chunkedMediaSource)
                    .build()
                    .start()
                    .blockUntilEnded(TIMEOUT_MS));
    assertThat(exception.type).isEqualTo(ExoPlaybackException.TYPE_UNEXPECTED);
    RuntimeException cause = exception.getUnexpectedException();
    assertThat(cause).isInstanceOf(StuckPlayerException.class);
    assertThat(((StuckPlayerException) cause).stuckType)
        .isEqualTo(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING);
  }

  @Test
  public void loadControlNeverWantsToPlay_playbackDoesNotGetStuck() throws Exception {
    LoadControl neverLoadingOrPlayingLoadControl =
        new DefaultLoadControl() {
          @Override
          public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
            return true;
          }

          @Override
          public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
            return false;
          }
        };

    // Use chunked data to ensure the player actually needs to continue loading and playing.
    FakeAdaptiveDataSet.Factory dataSetFactory =
        new FakeAdaptiveDataSet.Factory(
            /* chunkDurationUs= */ 500_000, /* bitratePercentStdDev= */ 10.0, new Random(0));
    MediaSource chunkedMediaSource =
        new FakeAdaptiveMediaSource(
            new FakeTimeline(),
            new TrackGroupArray(new TrackGroup(ExoPlayerTestRunner.VIDEO_FORMAT)),
            new FakeChunkSource.Factory(dataSetFactory, new FakeDataSource.Factory()));

    new ExoPlayerTestRunner.Builder(context)
        .setLoadControl(neverLoadingOrPlayingLoadControl)
        .setMediaSources(chunkedMediaSource)
        .build()
        .start()
        // This throws if playback doesn't finish within timeout.
        .blockUntilEnded(TIMEOUT_MS);
  }

  @Test
  public void
      infiniteLoading_withSmallAllocations_oomIsPreventedByLoadControl_andThrowsStuckPlayerException() {
    DefaultLoadControl loadControl =
        new DefaultLoadControl.Builder()
            .setTargetBufferBytes(10 * C.DEFAULT_BUFFER_SEGMENT_SIZE)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build();
    // Return no end of stream signal to prevent playback from ending.
    FakeMediaPeriod.TrackDataFactory trackDataWithoutEos = (format, periodId) -> ImmutableList.of();
    MediaSource continuouslyAllocatingMediaSource =
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT) {
          @Override
          protected MediaPeriod createMediaPeriod(
              MediaPeriodId id,
              TrackGroupArray trackGroupArray,
              Allocator allocator,
              MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
              DrmSessionManager drmSessionManager,
              DrmSessionEventListener.EventDispatcher drmEventDispatcher,
              @Nullable TransferListener transferListener) {
            return new FakeMediaPeriod(
                trackGroupArray,
                allocator,
                trackDataWithoutEos,
                mediaSourceEventDispatcher,
                drmSessionManager,
                drmEventDispatcher,
                /* deferOnPrepared= */ false) {

              private final List<Allocation> allocations = new ArrayList<>();

              private Callback callback;

              @Override
              public synchronized void prepare(Callback callback, long positionUs) {
                this.callback = callback;
                super.prepare(callback, positionUs);
              }

              @Override
              public long getBufferedPositionUs() {
                // Pretend not to make loading progress, so that continueLoading keeps being called.
                return 0;
              }

              @Override
              public long getNextLoadPositionUs() {
                // Pretend not to make loading progress, so that continueLoading keeps being called.
                return 0;
              }

              @Override
              public boolean continueLoading(LoadingInfo loadingInfo) {
                allocations.add(allocator.allocate());
                callback.onContinueLoadingRequested(this);
                return true;
              }
            };
          }
        };
    ExoPlayerTestRunner testRunner =
        new ExoPlayerTestRunner.Builder(context)
            .setMediaSources(continuouslyAllocatingMediaSource)
            .setLoadControl(loadControl)
            .build();

    ExoPlaybackException exception =
        assertThrows(
            ExoPlaybackException.class, () -> testRunner.start().blockUntilEnded(TIMEOUT_MS));
    assertThat(exception.type).isEqualTo(ExoPlaybackException.TYPE_UNEXPECTED);
    RuntimeException cause = exception.getUnexpectedException();
    assertThat(cause).isInstanceOf(StuckPlayerException.class);
    assertThat(((StuckPlayerException) cause).stuckType)
        .isEqualTo(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING);
  }

  @Test
  public void stuckBufferingDetectionTimeoutMs_triggersPlayerErrorWhenStuckBuffering()
      throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true))
            .setStuckBufferingDetectionTimeoutMs(45_000)
            .build();
    player.setMediaSource(
        new FakeMediaSource() {
          @Override
          protected MediaPeriod createMediaPeriod(
              MediaPeriodId id,
              TrackGroupArray trackGroupArray,
              Allocator allocator,
              MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
              DrmSessionManager drmSessionManager,
              DrmSessionEventListener.EventDispatcher drmEventDispatcher,
              @Nullable TransferListener transferListener) {
            return new FakeMediaPeriod(
                trackGroupArray,
                allocator,
                /* singleSampleTimeUs= */ 0,
                mediaSourceEventDispatcher,
                drmSessionManager,
                drmEventDispatcher,
                // Ensure the player stays in BUFFERING state.
                /* deferOnPrepared= */ true) {
              @Override
              public long getBufferedPositionUs() {
                // Return fixed value to pretend not making any loading progress.
                return 0;
              }
            };
          }
        });
    player.prepare();
    player.play();

    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = player.getClock().elapsedRealtime();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(45_000);
  }

  @Test
  public void stuckBufferingDetectionTimeoutMs_triggersPlayerErrorWhenPlaybackThreadUnresponsive()
      throws Exception {
    FakeClock clock =
        new FakeClock.Builder()
            .setInitialTimeMs(0)
            .setIsAutoAdvancing(true)
            .setMaxAutoAdvancingTimeDiffMs(100_000)
            .build();
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(clock)
            .setStuckBufferingDetectionTimeoutMs(45_000)
            .build();
    ConditionVariable blockPlaybackThread = new ConditionVariable();
    player.setMediaSource(
        new FakeMediaSource() {
          @Override
          public synchronized void prepareSourceInternal(
              @Nullable TransferListener mediaTransferListener) {
            clock.onThreadBlocked();
            blockPlaybackThread.blockUninterruptible();
            super.prepareSourceInternal(mediaTransferListener);
          }
        });
    player.prepare();
    player.play();

    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = clock.elapsedRealtime();
    blockPlaybackThread.open();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(45_000);
  }

  @Test
  public void stuckPlayingDetectionTimeoutMs_triggersPlayerErrorWhenStuckPlaying()
      throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true))
            .setStuckPlayingDetectionTimeoutMs(45_000)
            .setRenderersFactory(
                (eventHandler,
                    videoRendererEventListener,
                    audioRendererEventListener,
                    textRendererOutput,
                    metadataRendererOutput) ->
                    new Renderer[] {
                      new FakeMediaClockRenderer(C.TRACK_TYPE_VIDEO) {
                        @Override
                        public long getPositionUs() {
                          // Always return 0 to not make playback progress.
                          return 0;
                        }

                        @Override
                        public boolean isEnded() {
                          // Avoid marking the renderer as ended so that its clock keeps being
                          // used.
                          return false;
                        }

                        @Override
                        public void setPlaybackParameters(PlaybackParameters playbackParameters) {}

                        @Override
                        public PlaybackParameters getPlaybackParameters() {
                          return PlaybackParameters.DEFAULT;
                        }
                      }
                    })
            .build();
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();

    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = player.getClock().elapsedRealtime();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(45_000);
  }

  @Test
  public void stuckPlayingDetectionTimeoutMs_triggersPlayerErrorWhenPlaybackThreadUnresponsive()
      throws Exception {
    FakeClock clock =
        new FakeClock.Builder()
            .setInitialTimeMs(0)
            .setIsAutoAdvancing(true)
            .setMaxAutoAdvancingTimeDiffMs(100_000)
            .build();
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(clock)
            .setStuckPlayingDetectionTimeoutMs(45_000)
            .build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_READY);
    ConditionVariable blockPlaybackThread = new ConditionVariable();

    player
        .createMessage(
            (what, payload) -> {
              clock.onThreadBlocked();
              blockPlaybackThread.blockUninterruptible();
            })
        .send();
    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = clock.elapsedRealtime();
    blockPlaybackThread.open();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(45_000);
  }

  @Test
  public void stuckPlayingNotEndedDetectionTimeoutMs_triggersPlayerErrorWhenStuckPlayingNotEnding()
      throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true))
            .setStuckPlayingNotEndingTimeoutMs(45_000)
            .setRenderersFactory(
                (eventHandler,
                    videoRendererEventListener,
                    audioRendererEventListener,
                    textRendererOutput,
                    metadataRendererOutput) ->
                    new Renderer[] {
                      new FakeRenderer(C.TRACK_TYPE_VIDEO) {
                        @Override
                        public boolean isEnded() {
                          // Avoid marking the renderer as ended so that we keep increasing the
                          // playback time waiting for this renderer to end.
                          return false;
                        }
                      }
                    })
            .build();
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_READY);
    long durationMs = player.getDuration();

    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = player.getClock().elapsedRealtime();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(durationMs + 45_000);
  }

  // TODO: remove maxSdk once Robolectric supports MediaRouter2 (b/382017156)
  @Config(minSdk = Config.OLDEST_SDK, maxSdk = 34)
  @Test
  public void stuckSuppressedDetectionTimeoutMs_triggersPlayerErrorWhenStuckSuppressed()
      throws Exception {
    ShadowPackageManager shadowPackageManager = shadowOf(context.getPackageManager());
    shadowPackageManager.setSystemFeature(PackageManager.FEATURE_WATCH, /* supported= */ true);
    ShadowAudioManager shadowAudioManager = shadowOf(context.getSystemService(AudioManager.class));
    shadowAudioManager.setOutputDevices(
        ImmutableList.of(
            AudioDeviceInfoBuilder.newBuilder()
                .setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                .build()));
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setClock(fakeClock)
            .setStuckSuppressedDetectionTimeoutMs(45_000)
            .setSuppressPlaybackOnUnsuitableOutput(true)
            .build();
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPendingCommandsAreFullyHandled();

    fakeClock.advanceTime(45_000);
    ExoPlaybackException error = advance(player).untilPlayerError();
    long elapsedRealtimeAtErrorMs = player.getClock().elapsedRealtime();
    player.release();

    assertThat(error.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT);
    assertThat(error)
        .hasCauseThat()
        .isEqualTo(
            new StuckPlayerException(
                StuckPlayerException.STUCK_SUPPRESSED, /* timeoutMs= */ 45_000));
    assertThat(elapsedRealtimeAtErrorMs).isAtLeast(45_000);
  }
}
