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
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Tests for {@linkplain ExoPlayer#setScrubbingModeEnabled(boolean) scrubbing mode}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerScrubbingTest {

  @Test
  public void scrubbingMode_suppressesPlayback() throws Exception {
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 2000);

    player.setScrubbingModeEnabled(true);
    verify(mockListener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);

    player.setScrubbingModeEnabled(false);
    verify(mockListener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);

    player.release();
  }

  @Test
  public void scrubbingMode_pendingSeekIsNotPreempted() throws Exception {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder().setWindowPositionInFirstPeriodUs(0).build());
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(
        new FakeMediaSource(
            timeline,
            DrmSessionManager.DRM_UNSUPPORTED,
            TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
                /* initialSampleTimeUs= */ 0,
                /* sampleRate= */ 30,
                /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
                /* keyFrameInterval= */ 60),
            ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    VideoFrameMetadataListener mockVideoFrameMetadataListener =
        mock(VideoFrameMetadataListener.class);
    player.setVideoFrameMetadataListener(mockVideoFrameMetadataListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(2500);
    player.seekTo(3000);
    player.seekTo(3500);
    // Allow the 2500 and 3500 seeks to complete (the 3000 seek should be dropped).
    advance(player).untilPendingCommandsAreFullyHandled();

    player.seekTo(4000);
    player.seekTo(4500);
    // Disabling scrubbing mode should immediately execute the last received seek (pre-empting a
    // previous one), so we expect the 4500 seek to be resolved and the 4000 seek to be dropped.
    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.clearVideoFrameMetadataListener(mockVideoFrameMetadataListener);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    ArgumentCaptor<Long> presentationTimeUsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(mockVideoFrameMetadataListener, atLeastOnce())
        .onVideoFrameAboutToBeRendered(presentationTimeUsCaptor.capture(), anyLong(), any(), any());
    assertThat(presentationTimeUsCaptor.getAllValues())
        .containsExactly(2_500_000L, 3_500_000L, 4_500_000L)
        .inOrder();

    // Confirm that even though we dropped some intermediate seeks, every seek request still
    // resulted in a position discontinuity callback.
    ArgumentCaptor<PositionInfo> newPositionCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    verify(mockListener, atLeastOnce())
        .onPositionDiscontinuity(
            /* oldPosition= */ any(),
            newPositionCaptor.capture(),
            eq(Player.DISCONTINUITY_REASON_SEEK));
    assertThat(newPositionCaptor.getAllValues().stream().map(p -> p.positionMs))
        .containsExactly(2500L, 3000L, 3500L, 4000L, 4500L)
        .inOrder();
  }

  @Test
  public void scrubbingMode_disablesAudioTrack_masksTrackSelectionParameters() throws Exception {
    Timeline timeline = new FakeTimeline();
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaSource(
        new FakeMediaSource(
            timeline, ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    TrackSelectionParameters trackSelectionParametersBeforeScrubbingMode =
        player.getTrackSelectionParameters();
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(mockAnalyticsListener).onAudioDisabled(any(), any());
    verify(mockAnalyticsListener)
        .onRendererReadyChanged(any(), anyInt(), eq(C.TRACK_TYPE_AUDIO), eq(false));
    verify(mockAnalyticsListener)
        .onTracksChanged(any(), argThat(t -> !t.isTypeSelected(C.TRACK_TYPE_AUDIO)));
    assertThat(player.getTrackSelectionParameters())
        .isEqualTo(trackSelectionParametersBeforeScrubbingMode);

    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(mockAnalyticsListener).onAudioEnabled(any(), any());
    verify(mockAnalyticsListener)
        .onRendererReadyChanged(any(), anyInt(), eq(C.TRACK_TYPE_AUDIO), eq(true));
    verify(mockAnalyticsListener)
        .onTracksChanged(any(), argThat(t -> t.isTypeSelected(C.TRACK_TYPE_AUDIO)));
    assertThat(player.getTrackSelectionParameters())
        .isEqualTo(trackSelectionParametersBeforeScrubbingMode);
    verify(mockAnalyticsListener, never()).onTrackSelectionParametersChanged(any(), any());

    player.release();
    surface.release();
  }

  @Test
  public void
      disableTracksDuringScrubbingMode_typeThatIsDisabledByScrubbing_staysDisabledAfterScrubbing()
          throws Exception {
    Timeline timeline = new FakeTimeline();
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaSource(
        new FakeMediaSource(
            timeline, ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    // Use InOrder so we can 'consume' verifications (see never() comment below).
    InOrder analyticsListenerInOrder = inOrder(mockAnalyticsListener);
    analyticsListenerInOrder.verify(mockAnalyticsListener).onAudioDisabled(any(), any());
    // Manually disable the audio track which is already temporarily disabled by scrubbing mode.
    // This is a no-op until scrubbing mode ends, at which point the audio track should stay
    // disabled.
    TrackSelectionParameters newTrackSelectionParameters =
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build();
    player.setTrackSelectionParameters(newTrackSelectionParameters);
    assertThat(player.getTrackSelectionParameters()).isEqualTo(newTrackSelectionParameters);
    analyticsListenerInOrder
        .verify(mockAnalyticsListener)
        .onTrackSelectionParametersChanged(any(), eq(newTrackSelectionParameters));

    player.setScrubbingModeEnabled(false);
    assertThat(player.getTrackSelectionParameters()).isEqualTo(newTrackSelectionParameters);
    advance(player).untilPendingCommandsAreFullyHandled();
    // This is never() because the InOrder verification above already 'consumed' the
    // expected onTrackSelectionParametersChanged call above, and we want to assert it's not fired
    // again when we leave scrubbing mode.
    analyticsListenerInOrder
        .verify(mockAnalyticsListener, never())
        .onTrackSelectionParametersChanged(any(), any());
    analyticsListenerInOrder.verify(mockAnalyticsListener, never()).onAudioEnabled(any(), any());

    player.release();
    surface.release();
  }

  @Test
  public void
      disableTracksDuringScrubbingMode_typeThatIsNotDisabledByScrubbing_immediatelyDisabled()
          throws Exception {
    Timeline timeline = new FakeTimeline();
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player =
        playerBuilder
            .setRenderersFactory(
                (eventHandler,
                    videoRendererEventListener,
                    audioRendererEventListener,
                    textRendererOutput,
                    metadataRendererOutput) -> {
                  HandlerWrapper clockAwareHandler =
                      playerBuilder
                          .getClock()
                          .createHandler(eventHandler.getLooper(), /* callback= */ null);
                  return new Renderer[] {
                    new FakeVideoRenderer(clockAwareHandler, videoRendererEventListener),
                    new FakeAudioRenderer(clockAwareHandler, audioRendererEventListener),
                    new FakeRenderer(C.TRACK_TYPE_TEXT)
                  };
                })
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Format textFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build();
    player.setMediaSource(
        new FakeMediaSource(
            timeline,
            ExoPlayerTestRunner.VIDEO_FORMAT,
            ExoPlayerTestRunner.AUDIO_FORMAT,
            textFormat));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    // Use InOrder so we can 'consume' verifications (see never() comment below).
    InOrder analyticsListenerInOrder = inOrder(mockAnalyticsListener);
    verify(mockAnalyticsListener).onAudioDisabled(any(), any());
    // Manually disable the text track. This should be immediately disabled, and remain disabled
    // after scrubbing mode ends.
    TrackSelectionParameters newTrackSelectionParameters =
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build();
    player.setTrackSelectionParameters(newTrackSelectionParameters);
    assertThat(player.getTrackSelectionParameters()).isEqualTo(newTrackSelectionParameters);
    analyticsListenerInOrder
        .verify(mockAnalyticsListener)
        .onTrackSelectionParametersChanged(any(), eq(newTrackSelectionParameters));
    advance(player).untilPendingCommandsAreFullyHandled();
    analyticsListenerInOrder
        .verify(mockAnalyticsListener)
        .onRendererReadyChanged(any(), anyInt(), eq(C.TRACK_TYPE_TEXT), eq(false));

    player.setScrubbingModeEnabled(false);
    assertThat(player.getTrackSelectionParameters()).isEqualTo(newTrackSelectionParameters);
    advance(player).untilPendingCommandsAreFullyHandled();
    // This is never() because the InOrder verification above already 'consumed' the
    // expected onTrackSelectionParametersChanged call above, and we want to assert it's not fired
    // again when we leave scrubbing mode.
    analyticsListenerInOrder
        .verify(mockAnalyticsListener, never())
        .onTrackSelectionParametersChanged(any(), any());
    analyticsListenerInOrder.verify(mockAnalyticsListener).onAudioEnabled(any(), any());

    player.release();
    surface.release();
  }

  @Test
  public void customizeParameters_beforeScrubbingModeEnabled() throws Exception {
    Timeline timeline = new FakeTimeline();
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    // Prevent any tracks being disabled during scrubbing
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder().setDisabledTrackTypes(ImmutableSet.of()).build();
    player.setScrubbingModeParameters(scrubbingModeParameters);
    assertThat(player.getScrubbingModeParameters()).isEqualTo(scrubbingModeParameters);
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaSource(
        new FakeMediaSource(
            timeline, ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();

    verify(mockAnalyticsListener, never()).onAudioDisabled(any(), any());

    player.release();
    surface.release();
  }

  @Test
  public void customizeParameters_duringScrubbingMode() throws Exception {
    Timeline timeline = new FakeTimeline();
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaSource(
        new FakeMediaSource(
            timeline, ExoPlayerTestRunner.VIDEO_FORMAT, ExoPlayerTestRunner.AUDIO_FORMAT));
    player.prepare();
    player.play();
    advance(player).untilPosition(0, 1000);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();

    // Prevent any tracks being disabled during scrubbing
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder().setDisabledTrackTypes(ImmutableSet.of()).build();
    player.setScrubbingModeParameters(scrubbingModeParameters);

    assertThat(player.getScrubbingModeParameters()).isEqualTo(scrubbingModeParameters);
    advance(player).untilPendingCommandsAreFullyHandled();
    // Check that the audio track gets re-enabled, because the parameters changed to configure this.
    verify(mockAnalyticsListener).onAudioEnabled(any(), any());

    player.release();
    surface.release();
  }
}
