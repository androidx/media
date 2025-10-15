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
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
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
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTrackSelector;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test for {@link PreloadMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class PreloadMediaSourceTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final int LOADING_CHECK_INTERVAL_BYTES = 32;
  private static final int TARGET_PRELOAD_DURATION_US = 10000;
  private static final int LARGE_TARGET_BUFFER_BYTES_FOR_PRELOAD = Integer.MAX_VALUE;
  private static final int SMALL_TARGET_BUFFER_BYTES_FOR_PRELOAD = 1024;

  private LoadControl loadControl;
  private BandwidthMeter bandwidthMeter;
  private RenderersFactory renderersFactory;
  private MediaItem mediaItem;
  @Mock private PreloadMediaSource.PreloadControl mockPreloadControl;

  @Before
  public void setUp() {
    loadControl =
        mock(
            LoadControl.class,
            delegatesTo(
                new DefaultLoadControl.Builder()
                    .setPlayerTargetBufferBytes(
                        PlayerId.PRELOAD.name, LARGE_TARGET_BUFFER_BYTES_FOR_PRELOAD)
                    .build()));
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
    mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse("asset://android_asset/media/mp4/long_1080p_lowbitrate.mp4"))
            .build();
    when(mockPreloadControl.onSourcePrepared(any())).thenReturn(true);
    when(mockPreloadControl.onTracksSelected(any())).thenReturn(true);
    when(mockPreloadControl.onContinueLoadingRequested(any(), anyLong())).thenReturn(true);
  }

  @Test
  public void preload_loadPeriodToTargetPreloadPosition() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onContinueLoadingRequested(any(), anyLong()))
        .thenAnswer(
            invocation -> {
              long bufferedDurationUs = invocation.getArgument(1);
              if (bufferedDurationUs >= TARGET_PRELOAD_DURATION_US) {
                preloadTerminated.set(true);
                return false;
              }
              return true;
            });
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_stopWhenTracksSelectedByPreloadControl() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onTracksSelected(any()))
        .thenAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return false;
            });
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_stopWhenSourcePreparedByPreloadControl() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onSourcePrepared(any()))
        .thenAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return false;
            });
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_whileSourceIsAccessedByExternalCaller_notProceedWithPreloading() {
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            new FakeMediaSourceFactory(),
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    MediaSource.MediaSourceCaller externalCaller = mock(MediaSource.MediaSourceCaller.class);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();

    verify(loadControl, never()).onPrepared(PlayerId.PRELOAD);
    verify(externalCaller).onSourceInfoRefreshed(eq(preloadMediaSource), any());
    verify(mockPreloadControl, never()).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce()).onUsedByPlayer(eq(preloadMediaSource));
  }

  @Test
  public void preload_loadToTheEndOfSource() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return null;
            })
        .when(mockPreloadControl)
        .onLoadedToTheEndOfSource(any());
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    // In fact, PreloadControl.onContinueLoadingRequested is not necessarily to be called if the
    // LOADING_CHECK_INTERVAL_BYTES set for the ProgressiveMediaSource.Factory is large
    // enough to have the media load to the end in one round. However, since we explicitly
    // set with a small value below, we will still expect this method to be called for at
    // least once.
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl).onLoadedToTheEndOfSource(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_withSmallTargetBufferBytesThreshold_onLoadingUnableToContinueCalled() {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return null;
            })
        .when(mockPreloadControl)
        .onLoadedToTheEndOfSource(any());
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    mediaSourceFactory.setContinueLoadingCheckIntervalBytes(LOADING_CHECK_INTERVAL_BYTES);
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    LoadControl loadControl =
        mock(
            LoadControl.class,
            delegatesTo(
                new DefaultLoadControl.Builder()
                    .setPlayerTargetBufferBytes(
                        PlayerId.PRELOAD.name, SMALL_TARGET_BUFFER_BYTES_FOR_PRELOAD)
                    .build()));
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);

    assertThrows(TimeoutException.class, () -> runMainLooperUntil(preloadTerminated::get));
    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    // In fact, PreloadControl.onContinueLoadingRequested is not necessarily to be called if the
    // LOADING_CHECK_INTERVAL_BYTES set for the ProgressiveMediaSource.Factory is large
    // enough to have the media load to the end in one round. However, since we explicitly
    // set with a small value below, we will still expect this method to be called for at
    // least once.
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_sourceInfoRefreshErrorThrows_onPreloadErrorCalled() throws TimeoutException {
    AtomicReference<PreloadException> preloadExceptionReference = new AtomicReference<>();
    IOException causeException = new IOException("Failed to refresh source info");
    doAnswer(
            invocation -> {
              PreloadException preloadException = invocation.getArgument(0);
              preloadExceptionReference.set(preloadException);
              return null;
            })
        .when(mockPreloadControl)
        .onPreloadError(any(), any());
    MediaSource.Factory mediaSourceFactory =
        new MediaSource.Factory() {
          @Override
          public MediaSource.Factory setDrmSessionManagerProvider(
              DrmSessionManagerProvider drmSessionManagerProvider) {
            return this;
          }

          @Override
          public MediaSource.Factory setLoadErrorHandlingPolicy(
              LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            return this;
          }

          @Override
          public @C.ContentType int[] getSupportedTypes() {
            return new int[0];
          }

          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            return new FakeMediaSource(/* timeline= */ null) {
              @Override
              public void maybeThrowSourceInfoRefreshError() throws IOException {
                throw causeException;
              }
            };
          }
        };
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(() -> preloadExceptionReference.get() != null);

    assertThat(preloadExceptionReference.get()).hasCauseThat().isEqualTo(causeException);
    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onPreloadError(any(), eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
  }

  @Test
  public void preload_periodPrepareErrorThrows_onPreloadErrorCalled() throws TimeoutException {
    AtomicReference<PreloadException> preloadExceptionReference = new AtomicReference<>();
    IOException causeException = new IOException("Failed to prepare the period");
    doAnswer(
            invocation -> {
              PreloadException preloadException = invocation.getArgument(0);
              preloadExceptionReference.set(preloadException);
              return null;
            })
        .when(mockPreloadControl)
        .onPreloadError(any(), any());
    MediaSource.Factory mediaSourceFactory =
        new MediaSource.Factory() {
          @Override
          public MediaSource.Factory setDrmSessionManagerProvider(
              DrmSessionManagerProvider drmSessionManagerProvider) {
            return this;
          }

          @Override
          public MediaSource.Factory setLoadErrorHandlingPolicy(
              LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            return this;
          }

          @Override
          public @C.ContentType int[] getSupportedTypes() {
            return new int[0];
          }

          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            return new FakeMediaSource() {
              @Override
              public MediaPeriod createPeriod(
                  MediaPeriodId id, Allocator allocator, long startPositionUs) {
                return new FakeMediaPeriod(
                    TrackGroupArray.EMPTY,
                    allocator,
                    startPositionUs,
                    new MediaSourceEventListener.EventDispatcher()) {
                  @Override
                  public void prepare(Callback callback, long positionUs) {
                    // Do nothing to simulate that something wrong happens and onPrepared will not
                    // be called.
                  }

                  @Override
                  public void maybeThrowPrepareError() throws IOException {
                    throw causeException;
                  }
                };
              }
            };
          }
        };
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(() -> preloadExceptionReference.get() != null);

    assertThat(preloadExceptionReference.get()).hasCauseThat().isEqualTo(causeException);
    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onPreloadError(any(), eq(preloadMediaSource));
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
  }

  @Test
  public void preload_sampleStreamErrorThrows_onPreloadErrorCalled() throws TimeoutException {
    AtomicReference<PreloadException> preloadExceptionReference = new AtomicReference<>();
    IOException causeException = new IOException("Failed to read the data");
    doAnswer(
            invocation -> {
              PreloadException preloadException = invocation.getArgument(0);
              preloadExceptionReference.set(preloadException);
              return null;
            })
        .when(mockPreloadControl)
        .onPreloadError(any(), any());
    MediaSource.Factory mediaSourceFactory =
        new MediaSource.Factory() {
          @Override
          public MediaSource.Factory setDrmSessionManagerProvider(
              DrmSessionManagerProvider drmSessionManagerProvider) {
            return this;
          }

          @Override
          public MediaSource.Factory setLoadErrorHandlingPolicy(
              LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            return this;
          }

          @Override
          public @C.ContentType int[] getSupportedTypes() {
            return new int[0];
          }

          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            Format videoFormat =
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setAverageBitrate(800_000)
                    .setWidth(1280)
                    .setHeight(720)
                    .build();
            return new FakeMediaSource(new FakeTimeline(), videoFormat) {
              @Override
              public MediaPeriod createMediaPeriod(
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
                    /* trackDataFactory= */ (format, mediaPeriodId) -> ImmutableList.of(),
                    mediaSourceEventDispatcher,
                    drmSessionManager,
                    drmEventDispatcher,
                    /* deferOnPrepared= */ false) {
                  @Override
                  protected FakeSampleStream createSampleStream(
                      Allocator allocator,
                      @Nullable MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
                      DrmSessionManager drmSessionManager,
                      DrmSessionEventListener.EventDispatcher drmEventDispatcher,
                      Format initialFormat,
                      List<FakeSampleStream.FakeSampleStreamItem> fakeSampleStreamItems) {
                    return new FakeSampleStream(
                        allocator,
                        mediaSourceEventDispatcher,
                        drmSessionManager,
                        drmEventDispatcher,
                        initialFormat,
                        fakeSampleStreamItems) {
                      @Override
                      public void maybeThrowError() throws IOException {
                        throw causeException;
                      }
                    };
                  }
                };
              }
            };
          }
        };
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(() -> preloadExceptionReference.get() != null);

    assertThat(preloadExceptionReference.get()).hasCauseThat().isEqualTo(causeException);
    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onPreloadError(any(), eq(preloadMediaSource));
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
  }

  @Test
  public void prepareSourceNotOnPreloadThread_beforePreloadCalled_throwsIllegalStateException() {
    HandlerThread preloadThread = new HandlerThread("preload");
    preloadThread.start();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            new FakeMediaSourceFactory(),
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            preloadThread.getLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    assertThrows(
        IllegalStateException.class,
        () ->
            preloadMediaSource.prepareSource(
                (source, timeline) -> {}, bandwidthMeter.getTransferListener(), PlayerId.UNSET));

    preloadThread.quit();
  }

  @Test
  public void prepareSourceNotOnPreloadThread_afterPreloadCalled_throwsIllegalStateException() {
    HandlerThread preloadThread = new HandlerThread("preload");
    preloadThread.start();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            new FakeMediaSourceFactory(),
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            preloadThread.getLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    preloadMediaSource.preload(/* startPositionUs= */ C.TIME_UNSET);
    shadowOf(preloadThread.getLooper()).idle();

    assertThrows(
        IllegalStateException.class,
        () ->
            preloadMediaSource.prepareSource(
                mock(MediaSource.MediaSourceCaller.class),
                bandwidthMeter.getTransferListener(),
                PlayerId.UNSET));

    preloadThread.quit();
  }

  @Test
  public void
      prepareSource_beforeSourceInfoRefreshedForPreloading_onlyInvokeExternalCallerOnSourceInfoRefreshed() {
    FakeMediaSourceFactory mediaSourceFactory = new FakeMediaSourceFactory();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    FakeMediaSource wrappedMediaSource = mediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource.setAllowPreparation(false);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    MediaSource.MediaSourceCaller externalCaller = mock(MediaSource.MediaSourceCaller.class);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    wrappedMediaSource.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();

    verify(externalCaller).onSourceInfoRefreshed(eq(preloadMediaSource), any());
    verify(mockPreloadControl, never()).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl).onUsedByPlayer(eq(preloadMediaSource));
  }

  @Test
  public void prepareSource_afterPreload_immediatelyInvokeExternalCallerOnSourceInfoRefreshed() {
    FakeMediaSourceFactory mediaSourceFactory = new FakeMediaSourceFactory();
    TrackSelector trackSelector = new FakeTrackSelector();
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    MediaSource.MediaSourceCaller externalCaller = mock(MediaSource.MediaSourceCaller.class);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);

    verify(externalCaller).onSourceInfoRefreshed(eq(preloadMediaSource), any());
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl).onUsedByPlayer(eq(preloadMediaSource));
  }

  @Test
  public void createPeriodWithSameMediaPeriodIdAndStartPosition_returnExistingPeriod()
      throws Exception {
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
            mockPreloadControl,
            mockTrackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

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
    preloadMediaSource.createPeriod(
        mediaPeriodId, loadControl.getAllocator(PlayerId.UNSET), periodPosition.second);

    verify(internalSourceReference.get()).createPeriod(any(), any(), anyLong());
  }

  @Test
  public void createPeriodWithSameMediaPeriodIdAndDifferentStartPosition_returnNewPeriod()
      throws Exception {
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
            mockPreloadControl,
            mockTrackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

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
    preloadMediaSource.createPeriod(
        mediaPeriodId, loadControl.getAllocator(PlayerId.UNSET), periodPosition.second);

    verify(internalSourceReference.get(), times(2)).createPeriod(any(), any(), anyLong());
  }

  @Test
  public void clear_preloadingPeriodReleased() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();

    preloadMediaSource.clear();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(preloadingMediaPeriodReleased.get()).isTrue();
  }

  @Test
  public void releaseSourceByAllExternalCallers_preloadNotCalledBefore_releaseInternalSource() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    AtomicBoolean externalCallerSourceInfoRefreshedCalled = new AtomicBoolean();
    MediaSource.MediaSourceCaller externalCaller =
        (source, timeline) -> externalCallerSourceInfoRefreshedCalled.set(true);
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releaseSource(externalCaller);

    verify(loadControl, never()).onPrepared(PlayerId.PRELOAD);
    verify(loadControl, never()).onReleased(PlayerId.PRELOAD);
    assertThat(externalCallerSourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource).releaseSource(any());
  }

  @Test
  public void releaseSourceByAllExternalCallers_stillPreloading_notReleaseInternalSource() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    MediaSource.MediaSourceCaller externalCaller = mock(MediaSource.MediaSourceCaller.class);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadMediaSource.releaseSource(externalCaller);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(loadControl, never()).onReleased(PlayerId.PRELOAD);
    verify(externalCaller).onSourceInfoRefreshed(eq(preloadMediaSource), any());
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource, never()).releaseSource(any());
  }

  @Test
  public void
      releaseSourceNotByAllExternalCallers_preloadNotCalledBefore_notReleaseInternalSource() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
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

    verify(loadControl, never()).onPrepared(PlayerId.PRELOAD);
    verify(loadControl, never()).onReleased(PlayerId.PRELOAD);
    assertThat(externalCaller1SourceInfoRefreshedCalled.get()).isTrue();
    assertThat(externalCaller2SourceInfoRefreshedCalled.get()).isTrue();
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource, never()).releaseSource(any());
  }

  @Test
  public void releasePreloadMediaSource_notUsedByExternalCallers_releaseInternalSource() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releasePreloadMediaSource();
    shadowOf(Looper.getMainLooper()).idle();

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(loadControl).onReleased(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    MediaSource internalSource = internalSourceReference.get();
    assertThat(internalSource).isNotNull();
    verify(internalSource).releaseSource(any());
  }

  @Test
  public void releasePreloadMediaSource_stillUsedByExternalCallers_releaseInternalSource() {
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
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);
    MediaSource.MediaSourceCaller externalCaller = mock(MediaSource.MediaSourceCaller.class);
    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    preloadMediaSource.releasePreloadMediaSource();
    shadowOf(Looper.getMainLooper()).idle();

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(loadControl).onReleased(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(externalCaller).onSourceInfoRefreshed(eq(preloadMediaSource), any());
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
