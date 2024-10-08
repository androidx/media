/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.source.preload;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTrackSelector;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link PreloadMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class PreloadMediaSourceTest {

  private static final int LOADING_CHECK_INTERVAL_BYTES = 10 * 1024;
  private static final int TARGET_PRELOAD_POSITION_US = 10000;

  private Allocator allocator;
  private BandwidthMeter bandwidthMeter;
  private RenderersFactory renderersFactory;

  @Before
  public void setUp() {
    allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    bandwidthMeter =
        new DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build();
    renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) ->
            new Renderer[] {
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  videoListener),
              new FakeAudioRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  audioListener)
            };
  }

  @Test
  public void preload_loadPeriodToTargetPreloadPosition() throws Exception {
    AtomicInteger onSourcePreparedCounter = new AtomicInteger();
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    AtomicBoolean onContinueLoadingStopped = new AtomicBoolean();
    AtomicReference<PreloadMediaSource> preloadMediaSourceReference = new AtomicReference<>();
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCounter.addAndGet(1);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            preloadMediaSourceReference.set(mediaSource);
            if (bufferedPositionUs >= TARGET_PRELOAD_POSITION_US) {
              onContinueLoadingStopped.set(true);
              return false;
            }
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    mediaSourceFactory.setContinueLoadingCheckIntervalBytes(LOADING_CHECK_INTERVAL_BYTES);
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(onContinueLoadingStopped::get);

    assertThat(onSourcePreparedCounter.get()).isEqualTo(1);
    assertThat(onTracksSelectedCalled.get()).isTrue();
    assertThat(onUsedByPlayerCalled.get()).isFalse();
    assertThat(preloadMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
  }

  @Test
  public void preload_stopWhenTracksSelectedByPreloadControl() throws Exception {
    AtomicInteger onSourcePreparedCounter = new AtomicInteger();
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    AtomicReference<PreloadMediaSource> preloadMediaSourceReference = new AtomicReference<>();
    AtomicBoolean onContinueLoadingRequestedCalled = new AtomicBoolean();
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCounter.addAndGet(1);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            preloadMediaSourceReference.set(mediaSource);
            onTracksSelectedCalled.set(true);
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            onContinueLoadingRequestedCalled.set(true);
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    mediaSourceFactory.setContinueLoadingCheckIntervalBytes(LOADING_CHECK_INTERVAL_BYTES);
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(onTracksSelectedCalled::get);

    assertThat(onSourcePreparedCounter.get()).isEqualTo(1);
    assertThat(preloadMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
    assertThat(onContinueLoadingRequestedCalled.get()).isFalse();
    assertThat(onUsedByPlayerCalled.get()).isFalse();
  }

  @Test
  public void preload_stopWhenSourcePreparedByPreloadControl() throws Exception {
    AtomicInteger onSourcePreparedCounter = new AtomicInteger();
    AtomicReference<PreloadMediaSource> preloadMediaSourceReference = new AtomicReference<>();
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    AtomicBoolean onContinueLoadingRequestedCalled = new AtomicBoolean();
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            preloadMediaSourceReference.set(mediaSource);
            onSourcePreparedCounter.addAndGet(1);
            return false;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            onContinueLoadingRequestedCalled.set(true);
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(preloadMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
    assertThat(onSourcePreparedCounter.get()).isEqualTo(1);
    assertThat(onTracksSelectedCalled.get()).isFalse();
    assertThat(onContinueLoadingRequestedCalled.get()).isFalse();
    assertThat(onUsedByPlayerCalled.get()).isFalse();
  }

  @Test
  public void preload_whileSourceIsAccessedByExternalCaller_notProceedWithPreloading() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean(false);
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            new FakeMediaSourceFactory(),
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    AtomicReference<MediaSource> externalCallerMediaSourceReference = new AtomicReference<>();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerMediaSourceReference.set(source);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(externalCallerMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
    assertThat(onSourcePreparedCalled.get()).isFalse();
    assertThat(onTracksSelectedCalled.get()).isFalse();
    assertThat(onUsedByPlayerCalled.get()).isTrue();
  }

  @Test
  public void preload_loadToTheEndOfSource() throws Exception {
    AtomicInteger onSourcePreparedCounter = new AtomicInteger();
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    AtomicBoolean onContinueLoadingRequestedCalled = new AtomicBoolean();
    AtomicBoolean onLoadedToTheEndOfSourceCalled = new AtomicBoolean();
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCounter.addAndGet(1);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            // In fact, this method is not necessarily to be called if the
            // LOADING_CHECK_INTERVAL_BYTES set for the ProgressiveMediaSource.Factory is large
            // enough to have the media load to the end in one round. However, since we explicitly
            // set with a small value below, we will still expect this method to be called for at
            // least once.
            onContinueLoadingRequestedCalled.set(true);
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }

          @Override
          public void onLoadedToTheEndOfSource(PreloadMediaSource mediaSource) {
            onLoadedToTheEndOfSourceCalled.set(true);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    mediaSourceFactory.setContinueLoadingCheckIntervalBytes(LOADING_CHECK_INTERVAL_BYTES);
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(onLoadedToTheEndOfSourceCalled::get);

    assertThat(onSourcePreparedCounter.get()).isEqualTo(1);
    assertThat(onTracksSelectedCalled.get()).isTrue();
    assertThat(onContinueLoadingRequestedCalled.get()).isTrue();
    assertThat(onUsedByPlayerCalled.get()).isFalse();
  }

  @Test
  public void
      prepareSource_beforeSourceInfoRefreshedForPreloading_onlyInvokeExternalCallerOnSourceInfoRefreshed() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean(false);
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    FakeMediaSourceFactory mediaSourceFactory = new FakeMediaSourceFactory();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    FakeMediaSource wrappedMediaSource = mediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource.setAllowPreparation(false);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    AtomicReference<MediaSource> externalCallerMediaSourceReference = new AtomicReference<>();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerMediaSourceReference.set(source);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    wrappedMediaSource.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(externalCallerMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
    assertThat(onSourcePreparedCalled.get()).isFalse();
    assertThat(onTracksSelectedCalled.get()).isFalse();
    assertThat(onUsedByPlayerCalled.get()).isTrue();
  }

  @Test
  public void prepareSource_afterPreload_immediatelyInvokeExternalCallerOnSourceInfoRefreshed() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean(false);
    AtomicBoolean onUsedByPlayerCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {
            onUsedByPlayerCalled.set(true);
          }
        };
    FakeMediaSourceFactory mediaSourceFactory = new FakeMediaSourceFactory();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    AtomicReference<MediaSource> externalCallerMediaSourceReference = new AtomicReference<>();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerMediaSourceReference.set(source);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);

    assertThat(onSourcePreparedCalled.get()).isTrue();
    assertThat(onTracksSelectedCalled.get()).isTrue();
    assertThat(externalCallerMediaSourceReference.get()).isSameInstanceAs(preloadMediaSource);
    assertThat(onUsedByPlayerCalled.get()).isTrue();
  }

  @Test
  public void createPeriodWithSameMediaPeriodIdAndStartPosition_returnExistingPeriod()
      throws Exception {
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline());
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              doAnswer(
                      createPeriodInvocation -> {
                        MediaPeriod mediaPeriod = mock(MediaPeriod.class);
                        doAnswer(
                                prepareInvocation -> {
                                  MediaPeriod.Callback callback = prepareInvocation.getArgument(0);
                                  callback.onPrepared(mediaPeriod);
                                  return null;
                                })
                            .when(mediaPeriod)
                            .prepare(any(), anyLong());
                        return mediaPeriod;
                      })
                  .when(mockMediaSource)
                  .createPeriod(any(), any(), anyLong());
              return mockMediaSource;
            });
    TrackSelector mockTrackSelector = mock(TrackSelector.class);
    when(mockTrackSelector.selectTracks(any(), any(), any(), any()))
        .thenReturn(
            new TrackSelectorResult(
                new RendererConfiguration[0],
                new ExoTrackSelection[0],
                Tracks.EMPTY,
                /* info= */ null));
    mockTrackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            mockTrackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    AtomicReference<Timeline> externalCallerSourceInfoTimelineReference = new AtomicReference<>();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoTimelineReference.set(timeline);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    Pair<Object, Long> periodPosition =
        externalCallerSourceInfoTimelineReference
            .get()
            .getPeriodPositionUs(
                new Timeline.Window(),
                new Timeline.Period(),
                /* windowIndex= */ 0,
                /* windowPositionUs= */ 0L);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(periodPosition.first);
    preloadMediaSource.createPeriod(mediaPeriodId, allocator, periodPosition.second);

    assertThat(onTracksSelectedCalled.get()).isTrue();
    verify(internalSourceReference.get()).createPeriod(any(), any(), anyLong());
  }

  @Test
  public void createPeriodWithSameMediaPeriodIdAndDifferentStartPosition_returnNewPeriod()
      throws Exception {
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline());
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              doAnswer(
                      createPeriodInvocation -> {
                        MediaPeriod mediaPeriod = mock(MediaPeriod.class);
                        doAnswer(
                                prepareInvocation -> {
                                  MediaPeriod.Callback callback = prepareInvocation.getArgument(0);
                                  callback.onPrepared(mediaPeriod);
                                  return null;
                                })
                            .when(mediaPeriod)
                            .prepare(any(), anyLong());
                        return mediaPeriod;
                      })
                  .when(mockMediaSource)
                  .createPeriod(any(), any(), anyLong());
              return mockMediaSource;
            });
    TrackSelector mockTrackSelector = mock(TrackSelector.class);
    when(mockTrackSelector.selectTracks(any(), any(), any(), any()))
        .thenReturn(
            new TrackSelectorResult(
                new RendererConfiguration[0],
                new ExoTrackSelection[0],
                Tracks.EMPTY,
                /* info= */ null));
    mockTrackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            mockTrackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    AtomicReference<Timeline> externalCallerSourceInfoTimelineReference = new AtomicReference<>();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoTimelineReference.set(timeline);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    // Create a period from different position.
    Pair<Object, Long> periodPosition =
        externalCallerSourceInfoTimelineReference
            .get()
            .getPeriodPositionUs(
                new Timeline.Window(),
                new Timeline.Period(),
                /* windowIndex= */ 0,
                /* windowPositionUs= */ 1L);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(periodPosition.first);
    preloadMediaSource.createPeriod(mediaPeriodId, allocator, periodPosition.second);

    assertThat(onTracksSelectedCalled.get()).isTrue();
    verify(internalSourceReference.get(), times(2)).createPeriod(any(), any(), anyLong());
  }

  @Test
  public void clear_preloadingPeriodReleased() throws Exception {
    AtomicBoolean onTracksSelectedCalled = new AtomicBoolean();
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            onTracksSelectedCalled.set(true);
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    AtomicBoolean preloadingMediaPeriodReleased = new AtomicBoolean();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenReturn(
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
                    FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
                    mediaSourceEventDispatcher) {
                  @Override
                  public void release() {
                    preloadingMediaPeriodReleased.set(true);
                  }
                };
              }
            });
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(onTracksSelectedCalled::get);

    preloadMediaSource.clear();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(preloadingMediaPeriodReleased.get()).isTrue();
  }

  @Test
  public void releaseSourceByAllExternalCallers_preloadNotCalledBefore_releaseInternalSource() {
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline(1));
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              when(mockMediaSource.createPeriod(any(), any(), anyLong()))
                  .thenReturn(mock(MediaPeriod.class));
              return mockMediaSource;
            });
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    AtomicBoolean externalCallerSourceInfoRefreshedCalled = new AtomicBoolean();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoRefreshedCalled.set(true);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releaseSource(externalCaller);

    assertThat(externalCallerSourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource).releaseSource(any());
  }

  @Test
  public void releaseSourceByAllExternalCallers_stillPreloading_notReleaseInternalSource() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return true;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            return true;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return true;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline(1));
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              when(mockMediaSource.createPeriod(any(), any(), anyLong()))
                  .thenReturn(mock(MediaPeriod.class));
              return mockMediaSource;
            });
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    AtomicBoolean externalCallerSourceInfoRefreshedCalled = new AtomicBoolean();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoRefreshedCalled.set(true);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadMediaSource.releaseSource(externalCaller);

    assertThat(onSourcePreparedCalled.get()).isTrue();
    assertThat(externalCallerSourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource, times(0)).releaseSource(any());
  }

  @Test
  public void
      releaseSourceNotByAllExternalCallers_preloadNotCalledBefore_notReleaseInternalSource() {
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline(1));
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              when(mockMediaSource.createPeriod(any(), any(), anyLong()))
                  .thenReturn(mock(MediaPeriod.class));
              return mockMediaSource;
            });
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    AtomicBoolean externalCaller1SourceInfoRefreshedCalled = new AtomicBoolean();
    AtomicBoolean externalCaller2SourceInfoRefreshedCalled = new AtomicBoolean();
    MediaSource.MediaSourceCaller externalCaller1 =
        (source, timeline) -> externalCaller1SourceInfoRefreshedCalled.set(true);
    MediaSource.MediaSourceCaller externalCaller2 =
        (source, timeline) -> externalCaller2SourceInfoRefreshedCalled.set(true);
    preloadMediaSource.prepareSource(
        externalCaller1, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadMediaSource.prepareSource(
        externalCaller2, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    // Only releaseSource by externalCaller1.
    preloadMediaSource.releaseSource(externalCaller1);

    assertThat(externalCaller1SourceInfoRefreshedCalled.get()).isTrue();
    assertThat(externalCaller2SourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource, times(0)).releaseSource(any());
  }

  @Test
  public void releasePreloadMediaSource_notUsedByExternalCallers_releaseInternalSource() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return false;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline(1));
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              when(mockMediaSource.createPeriod(any(), any(), anyLong()))
                  .thenReturn(mock(MediaPeriod.class));
              return mockMediaSource;
            });
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releasePreloadMediaSource();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(onSourcePreparedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource).releaseSource(any());
  }

  @Test
  public void releasePreloadMediaSource_stillUsedByExternalCallers_releaseInternalSource() {
    AtomicBoolean onSourcePreparedCalled = new AtomicBoolean(false);
    PreloadMediaSource.PreloadControl preloadControl =
        new PreloadMediaSource.PreloadControl() {
          @Override
          public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
            onSourcePreparedCalled.set(true);
            return false;
          }

          @Override
          public boolean onTracksSelected(PreloadMediaSource mediaSource) {
            return false;
          }

          @Override
          public boolean onContinueLoadingRequested(
              PreloadMediaSource mediaSource, long bufferedPositionUs) {
            return false;
          }

          @Override
          public void onUsedByPlayer(PreloadMediaSource mediaSource) {}
        };
    AtomicReference<MediaSource> internalSourceReference = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaSource mockMediaSource = mock(MediaSource.class);
              internalSourceReference.set(mockMediaSource);
              doAnswer(
                      prepareSourceInvocation -> {
                        MediaSource.MediaSourceCaller caller =
                            prepareSourceInvocation.getArgument(0);
                        caller.onSourceInfoRefreshed(mockMediaSource, new FakeTimeline(1));
                        return null;
                      })
                  .when(mockMediaSource)
                  .prepareSource(any(), any(), any());
              when(mockMediaSource.createPeriod(any(), any(), anyLong()))
                  .thenReturn(mock(MediaPeriod.class));
              return mockMediaSource;
            });
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mockMediaSourceFactory,
            preloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource =
        preloadMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
                .build());
    AtomicBoolean externalCallerSourceInfoRefreshedCalled = new AtomicBoolean();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoRefreshedCalled.set(true);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releasePreloadMediaSource();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(onSourcePreparedCalled.get()).isTrue();
    assertThat(externalCallerSourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource, times(0)).releaseSource(any());
  }

  private static RendererCapabilities[] getRendererCapabilities(RenderersFactory renderersFactory) {
    Renderer[] renderers =
        renderersFactory.createRenderers(
            Util.createHandlerForCurrentLooper(),
            mock(VideoRendererEventListener.class),
            mock(AudioRendererEventListener.class),
            mock(TextOutput.class),
            mock(MetadataOutput.class));
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[renderers.length];
    for (int i = 0; i < renderers.length; i++) {
      rendererCapabilities[i] = renderers[i].getCapabilities();
    }
    return rendererCapabilities;
  }
}
