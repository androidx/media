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

import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.robolectric.IdlingMediaCodecAdapterFactory;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Tests for {@linkplain ExoPlayer#setScrubbingModeEnabled(boolean) scrubbing mode}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerScrubbingTest {

  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void scrubbingMode_getterWorks() throws Exception {
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();

    assertThat(player.isScrubbingModeEnabled()).isFalse();
    player.setScrubbingModeEnabled(true);
    assertThat(player.isScrubbingModeEnabled()).isTrue();
    player.setScrubbingModeEnabled(false);
    assertThat(player.isScrubbingModeEnabled()).isFalse();
    player.release();
  }

  @Test
  public void scrubbingMode_pendingSeekIsNotPreempted() throws Exception {
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);
    player.setMediaSource(create30Fps2sGop10sDurationVideoSource());
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
    VideoFrameMetadataListener mockVideoFrameMetadataListener =
        mock(VideoFrameMetadataListener.class);
    player.setVideoFrameMetadataListener(mockVideoFrameMetadataListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(2500);
    player.seekTo(3000);
    player.seekTo(3500);
    // Allow the 2500 and 3500 seeks to complete (the 3000 seek should be dropped).
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 3500);
    // The dropped seek won't be reported immediately, only after exiting scrubbing mode.
    verify(mockAnalyticsListener, never()).onDroppedSeeksWhileScrubbing(any(), anyInt());

    player.seekTo(4000);
    player.seekTo(4500);
    // Disabling scrubbing mode should immediately execute the last received seek (pre-empting a
    // previous one), so we expect the 4500 seek to be resolved and the 4000 seek to be dropped.
    player.setScrubbingModeEnabled(false);
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 4500);
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

    // Check the dropped 3000 and 4000 seeks are reported
    verify(mockAnalyticsListener).onDroppedSeeksWhileScrubbing(any(), eq(2));
  }

  /**
   * TODO: b/451939261- Adjust expected test behavior when skip intermittent seeks is supported for
   * scrubbing image playback.
   */
  @Test
  public void scrubbingMode_withThumbnails_pendingSeekIsPreempted() throws Exception {
    List<Pair<Long, Bitmap>> renderedBitmaps = new ArrayList<>();
    ImageDecoder.Factory fakeDecoderFactory =
        new ExternallyLoadedImageDecoder.Factory(
            (request) -> {
              /*
               * Thumbnail grid image is as depicted below.
               *    0 1 2 3 4 5 6 7 8
               *    -----------------
               * 0 | T0  | T1  | T2  |
               * 1 |     |     |     |
               *    -----------------
               * 2 | T3  | T4  | T5  |
               * 3 |     |     |     |
               *    -----------------
               */
              Bitmap bm =
                  Bitmap.createBitmap(/* width= */ 9, /* height= */ 4, Bitmap.Config.ARGB_8888);
              bm.setPixel(1, 2, Color.rgb(100, 0, 0));
              bm.setPixel(4, 3, Color.rgb(0, 100, 0));
              return Futures.immediateFuture(bm);
            });
    ImageOutput queuingImageOutput =
        new ImageOutput() {
          @Override
          public void onImageAvailable(long presentationTimeUs, Bitmap bitmap) {
            renderedBitmaps.add(Pair.create(presentationTimeUs, bitmap));
          }

          @Override
          public void onDisabled() {
            // Do nothing.
          }
        };
    ImageRenderer renderer = new ImageRenderer(fakeDecoderFactory, queuingImageOutput);
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            // Set to retain back buffer else thumbnail will be discarded after successful render as
            // FakeMediaPeriod does not "reload".
            .setLoadControl(new DefaultLoadControl.Builder().setBackBuffer(0, true).build())
            .setRenderers(renderer)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    AnalyticsListener mockAnalyticsListener = mock(AnalyticsListener.class);
    player.addAnalyticsListener(mockAnalyticsListener);
    player.setMediaSource(create60sDurationImageSource());
    player.prepare();
    player.play();
    advance(player).untilBackgroundThreadCondition(() -> !renderedBitmaps.isEmpty());

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(10_000L);
    player.seekTo(20_000L);
    player.seekTo(30_000L);

    // TODO: After implementing ignore for intermittent seeks, the 10s seek will also be completed.
    advance(player).untilBackgroundThreadCondition(() -> renderedBitmaps.size() > 1);

    player.seekTo(40_000L);
    player.seekTo(50_000L);
    // Disabling scrubbing mode should immediately execute the last received seek (pre-empting a
    // previous one), so we expect the 50s seek to be resolved and the 40s seek to be dropped.
    player.setScrubbingModeEnabled(false);
    advance(player).untilBackgroundThreadCondition(() -> renderedBitmaps.size() > 2);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    // TODO: After implementing ignore for intermittent seeks, renderedBitmaps should have size 4.
    assertThat(renderedBitmaps).hasSize(3);
    assertThat(renderedBitmaps.get(1).first).isEqualTo(30_000_000L);
    assertThat(renderedBitmaps.get(2).first).isEqualTo(50_000_000L);

    // Confirm every seek request still resulted in a position discontinuity callback.
    ArgumentCaptor<PositionInfo> newPositionCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    verify(mockListener, atLeastOnce())
        .onPositionDiscontinuity(
            /* oldPosition= */ any(),
            newPositionCaptor.capture(),
            eq(Player.DISCONTINUITY_REASON_SEEK));
    assertThat(newPositionCaptor.getAllValues().stream().map(p -> p.positionMs))
        .containsExactly(10_000L, 20_000L, 30_000L, 40_000L, 50_000L)
        .inOrder();

    // TODO: After implementing ignore for intermittent seeks, an onDroppedSeeksWhileScrubbing event
    // will be reported.
    verify(mockAnalyticsListener, never()).onDroppedSeeksWhileScrubbing(any(), anyInt());
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
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
  public void customizeDisabledTracks_beforeScrubbingModeEnabled() throws Exception {
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
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
  public void customizeDisabledTracks_duringScrubbingMode() throws Exception {
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
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

  @Test
  public void fractionalSeekTolerance_isPropagated() throws Exception {
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setScrubbingModeParameters(
        new ScrubbingModeParameters.Builder()
            .setFractionalSeekTolerance(/* toleranceBefore= */ 0.1, /* toleranceAfter= */ 0.1)
            .build());
    player.setMediaSource(create30Fps2sGop10sDurationVideoSource());
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
    VideoFrameMetadataListener mockVideoFrameMetadataListener =
        mock(VideoFrameMetadataListener.class);
    player.setVideoFrameMetadataListener(mockVideoFrameMetadataListener);

    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(2500);
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2000);
    player.setScrubbingModeEnabled(false);
    player.clearVideoFrameMetadataListener(mockVideoFrameMetadataListener);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    verify(mockVideoFrameMetadataListener)
        .onVideoFrameAboutToBeRendered(eq(2_000_000L), anyLong(), any(), any());
  }

  @Test
  public void operatingRateOverride_propagatedToMediaCodec() throws Exception {
    AtomicReference<MediaCodecAdapter> spyVideoMediaCodecAdapter = new AtomicReference<>();
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext()) {
          @Override
          protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
            MediaCodecAdapter.Factory codecAdapterFactory = super.getCodecAdapterFactory();
            return configuration -> {
              MediaCodecAdapter codecAdapter = codecAdapterFactory.createAdapter(configuration);
              if (MimeTypes.isVideo(configuration.codecInfo.mimeType)) {
                codecAdapter = mock(MediaCodecAdapter.class, delegatesTo(codecAdapter));
                checkState(
                    spyVideoMediaCodecAdapter.compareAndSet(
                        /* expectedValue= */ null, /* newValue= */ codecAdapter));
              }
              return codecAdapter;
            };
          }
        };
    // This test needs to include MCVR, so we don't use TestExoPlayerBuilder (which uses
    // FakeVideoRenderer).
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext(), renderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 300);
    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 800);
    advance(player).untilPosition(0, 800);
    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.release();
    surface.release();

    ArgumentCaptor<Bundle> codecParametersCaptor = ArgumentCaptor.forClass(Bundle.class);
    verify(spyVideoMediaCodecAdapter.get(), times(2))
        .setParameters(codecParametersCaptor.capture());
    List<Bundle> mediaCodecParameters = codecParametersCaptor.getAllValues();
    assertThat(mediaCodecParameters.get(0).containsKey(MediaFormat.KEY_OPERATING_RATE)).isTrue();
    assertThat(mediaCodecParameters.get(0).getFloat(MediaFormat.KEY_OPERATING_RATE)).isEqualTo(960);
    assertThat(mediaCodecParameters.get(1).containsKey(MediaFormat.KEY_OPERATING_RATE)).isTrue();
    assertThat(mediaCodecParameters.get(1).getFloat(MediaFormat.KEY_OPERATING_RATE))
        .isWithin(0.01f)
        .of(29.97f);
  }

  @Test
  public void operatingRateIncreaseDisabled() throws Exception {
    AtomicReference<MediaCodecAdapter> spyVideoMediaCodecAdapter = new AtomicReference<>();
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext()) {
          @Override
          protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
            MediaCodecAdapter.Factory codecAdapterFactory = super.getCodecAdapterFactory();
            return configuration -> {
              MediaCodecAdapter codecAdapter = codecAdapterFactory.createAdapter(configuration);
              if (MimeTypes.isVideo(configuration.codecInfo.mimeType)) {
                codecAdapter = mock(MediaCodecAdapter.class, delegatesTo(codecAdapter));
                checkState(
                    spyVideoMediaCodecAdapter.compareAndSet(
                        /* expectedValue= */ null, /* newValue= */ codecAdapter));
              }
              return codecAdapter;
            };
          }
        };
    // This test needs to include MCVR, so we don't use TestExoPlayerBuilder (which uses
    // FakeVideoRenderer).
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext(), renderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
    player.setScrubbingModeParameters(
        player
            .getScrubbingModeParameters()
            .buildUpon()
            .setShouldIncreaseCodecOperatingRate(false)
            .build());
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 300);
    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 800);
    advance(player).untilPosition(0, 800);
    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.release();
    surface.release();

    verify(spyVideoMediaCodecAdapter.get(), never())
        .setParameters(argThat(b -> b.containsKey(MediaFormat.KEY_OPERATING_RATE)));
  }

  @Test
  public void dynamicSchedulingInScrubbingMode_renderCalledMoreFrequentlyThan10ms()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    IdlingMediaCodecAdapterFactory codecAdapterFactory =
        new IdlingMediaCodecAdapterFactory(context, clock);
    AtomicInteger renderCounter = new AtomicInteger();
    RenderersFactory renderersFactory =
        new DefaultRenderersFactory(context) {
          @Override
          protected void buildVideoRenderers(
              Context context,
              @ExtensionRendererMode int extensionRendererMode,
              MediaCodecSelector mediaCodecSelector,
              boolean enableDecoderFallback,
              Handler eventHandler,
              VideoRendererEventListener eventListener,
              long allowedVideoJoiningTimeMs,
              ArrayList<Renderer> out) {
            MediaCodecVideoRenderer videoRenderer =
                new MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .build();
            out.add(new RenderCountingRenderer(videoRenderer, renderCounter));
          }
        };

    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setRenderersFactory(renderersFactory)
            .setDynamicSchedulingEnabled(false)
            .setClock(clock)
            .build();
    player.setMediaSource(create30Fps2sGop10sDurationVideoSource());
    Surface surface = new Surface(new SurfaceTexture(1));
    player.setVideoSurface(surface);
    player.prepare();
    player.play();

    advance(player).untilState(Player.STATE_READY);
    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    long playerReadyTimeMs = clock.currentTimeMillis();
    renderCounter.set(0);

    // This seeks to near the end of a GoP, requiring decoding 58 frames.
    player.seekTo(3950);

    advance(player)
        .untilBackgroundThreadCondition(() -> clock.currentTimeMillis() - playerReadyTimeMs >= 500);

    // With dynamic scheduling enabled, and lots of decoding work to do for the seeks, we should
    // be triggering the renderer more frequently than every 10ms.
    assertThat(renderCounter.get()).isGreaterThan(55);

    player.release();
    surface.release();
  }

  @Test
  public void dynamicSchedulingDisabledInScrubbingMode_renderCalledEvery10ms() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    IdlingMediaCodecAdapterFactory codecAdapterFactory =
        new IdlingMediaCodecAdapterFactory(context, clock);
    AtomicInteger renderCounter = new AtomicInteger();
    RenderersFactory renderersFactory =
        new DefaultRenderersFactory(context) {
          @Override
          protected void buildVideoRenderers(
              Context context,
              @ExtensionRendererMode int extensionRendererMode,
              MediaCodecSelector mediaCodecSelector,
              boolean enableDecoderFallback,
              Handler eventHandler,
              VideoRendererEventListener eventListener,
              long allowedVideoJoiningTimeMs,
              ArrayList<Renderer> out) {
            MediaCodecVideoRenderer videoRenderer =
                new MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .build();
            out.add(new RenderCountingRenderer(videoRenderer, renderCounter));
          }
        };

    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setRenderersFactory(renderersFactory)
            .setDynamicSchedulingEnabled(false)
            .setClock(clock)
            .build();
    player.setMediaSource(create30Fps2sGop10sDurationVideoSource());
    Surface surface = new Surface(new SurfaceTexture(1));
    player.setVideoSurface(surface);
    player.setScrubbingModeParameters(
        new ScrubbingModeParameters.Builder().setShouldEnableDynamicScheduling(false).build());
    player.prepare();
    player.play();

    advance(player).untilState(Player.STATE_READY);
    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    long playerReadyTimeMs = clock.currentTimeMillis();
    renderCounter.set(0);

    // This seeks to near the end of a GoP, requiring decoding 58 frames.
    player.seekTo(3950);

    advance(player)
        .untilBackgroundThreadCondition(() -> clock.currentTimeMillis() - playerReadyTimeMs >= 500);

    // Expect about one render call every 10ms.
    assertThat(renderCounter.get()).isWithin(5).of(50);

    player.release();
    surface.release();
  }

  private static FakeMediaSource create30Fps2sGop10sDurationVideoSource() {
    return new FakeMediaSource.Builder()
        .setTimeline(
            new FakeTimeline(
                new TimelineWindowDefinition.Builder().setWindowPositionInFirstPeriodUs(0).build()))
        .setFormats(ExoPlayerTestRunner.VIDEO_FORMAT)
        .setTrackDataFactory(
            TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
                /* initialSampleTimeUs= */ 0,
                /* sampleRate= */ 30,
                /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
                /* keyFrameInterval= */ 60))
        .setSyncSampleTimesUs(new long[] {0, 2_000_000, 4_000_000, 6_000_000, 8_000_000})
        .build();
  }

  private static FakeMediaSource create60sDurationImageSource() {
    return new FakeMediaSource.Builder()
        .setTimeline(
            new FakeTimeline(
                new TimelineWindowDefinition.Builder()
                    .setWindowPositionInFirstPeriodUs(0)
                    .setDurationUs(60_000_000)
                    .build()))
        .setFormats(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
                .setTileCountVertical(2)
                .setTileCountHorizontal(3)
                .build())
        .setTrackDataFactory(
            (unusedFormat, unusedMediaPeriodId) ->
                ImmutableList.of(
                    oneByteSample(/* timeUs= */ 0L, /* flags= */ C.BUFFER_FLAG_KEY_FRAME),
                    sample(/* timeUs= */ 10_000_000L, /* flags= */ 0, new byte[] {}),
                    sample(/* timeUs= */ 20_000_000L, /* flags= */ 0, new byte[] {}),
                    sample(/* timeUs= */ 30_000_000L, /* flags= */ 0, new byte[] {}),
                    sample(/* timeUs= */ 40_000_000L, /* flags= */ 0, new byte[] {}),
                    sample(/* timeUs= */ 50_000_000L, /* flags= */ 0, new byte[] {}),
                    END_OF_STREAM_ITEM))
        .setSyncSampleTimesUs(new long[] {0})
        .build();
  }

  private static class RenderCountingRenderer extends ForwardingRenderer {
    private final AtomicInteger renderCounter;

    public RenderCountingRenderer(Renderer renderer, AtomicInteger renderCounter) {
      super(renderer);
      this.renderCounter = renderCounter;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      renderCounter.getAndIncrement();
    }
  }
}
