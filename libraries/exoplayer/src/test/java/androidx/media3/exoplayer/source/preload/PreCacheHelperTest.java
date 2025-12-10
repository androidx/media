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
package androidx.media3.exoplayer.source.preload;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloaderFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.FakeDownloader;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link PreCacheHelper}. */
@RunWith(AndroidJUnit4.class)
public class PreCacheHelperTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final long TIMEOUT_MS = 10_000;
  private File testDir;
  private Cache downloadCache;
  private HandlerThread preCacheThread;
  private Looper preCacheLooper;
  @Mock private PreCacheHelper.Listener mockPreCacheHelperListener;
  private MediaItem testMediaItem;

  @Before
  public void setUp() throws Exception {
    testDir =
        Util.createTempFile(ApplicationProvider.getApplicationContext(), "PreCacheHelperTest");
    assertThat(testDir.delete()).isTrue();
    assertThat(testDir.mkdirs()).isTrue();
    downloadCache =
        new SimpleCache(testDir, new NoOpCacheEvictor(), TestUtil.getInMemoryDatabaseProvider());
    preCacheThread = new HandlerThread("preCache");
    preCacheThread.start();
    preCacheLooper = preCacheThread.getLooper();
    testMediaItem = MediaItem.fromUri("http://test.uri");
  }

  @After
  public void tearDown() {
    downloadCache.release();
    Util.recursiveDelete(testDir);
    preCacheThread.quit();
  }

  @Test
  public void preCache_succeeds() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4");
    AtomicBoolean preCacheTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheTerminated.set(true);
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onPreCacheProgress(any(), anyLong(), anyLong(), eq(100f));
    PreCacheHelper preCacheHelper =
        new PreCacheHelper.Factory(
                ApplicationProvider.getApplicationContext(), downloadCache, preCacheLooper)
            .setListener(mockPreCacheHelperListener)
            .create(mediaItem);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 2000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheTerminated.get());
    shadowOf(Looper.getMainLooper()).idle();

    verify(mockPreCacheHelperListener).onPrepared(eq(mediaItem), any());
    verify(mockPreCacheHelperListener, never()).onPrepareError(eq(mediaItem), any());
    verify(mockPreCacheHelperListener, never()).onDownloadError(eq(mediaItem), any());

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCache_sourceInfoRefreshErrorThrownWhenPreparing_prepareErrorReported()
      throws Exception {
    IOException fakeException =
        ParserException.createForMalformedContainer("Fake preparation error", new IOException());
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource =
                  new FakeMediaSource() {
                    @Override
                    public void maybeThrowSourceInfoRefreshError() throws IOException {
                      throw fakeException;
                    }
                  };
              fakeMediaSource.setAllowPreparation(false);
              return fakeMediaSource;
            });
    AtomicBoolean preCacheTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheTerminated.set(true);
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onPrepareError(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            new FakeDownloaderFactory(),
            preCacheLooper,
            mockPreCacheHelperListener);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheTerminated.get());

    verify(mockPreCacheHelperListener, never()).onPrepared(eq(testMediaItem), any());
    verify(mockPreCacheHelperListener, never())
        .onPreCacheProgress(eq(testMediaItem), anyLong(), anyLong(), anyFloat());
    verify(mockPreCacheHelperListener).onPrepareError(eq(testMediaItem), eq(fakeException));
    verify(mockPreCacheHelperListener, never()).onDownloadError(eq(testMediaItem), any());

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCache_errorThrownWhenDownloading_downloadErrorReported() throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(invocation -> new FakeMediaSource());
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    AtomicBoolean preCacheTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheTerminated.set(true);
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onDownloadError(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    // Try to fail the download for five times to create a final exception.
    for (int i = 0; i <= PreCacheHelper.DEFAULT_MIN_RETRY_COUNT; i++) {
      downloader.assertDownloadStarted();
      downloader.fail();
    }
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheTerminated.get());

    verify(mockPreCacheHelperListener).onPrepared(eq(testMediaItem), any());
    verify(mockPreCacheHelperListener, never())
        .onPreCacheProgress(eq(testMediaItem), anyLong(), anyLong(), anyFloat());
    verify(mockPreCacheHelperListener, never()).onPrepareError(eq(testMediaItem), any());
    verify(mockPreCacheHelperListener).onDownloadError(eq(testMediaItem), any());

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void
      preCacheAgainWithSameTimeRange_whilePreparationOngoing_reuseTheOngoingPreCacheRequest() {
    ArrayList<FakeMediaSource> createdMediaSources = new ArrayList<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              fakeMediaSource.setAllowPreparation(false);
              createdMediaSources.add(fakeMediaSource);
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            new FakeDownloaderFactory(),
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    // Each time we internally initiate a DownloadCallback, we create a new DownloadHelper, and in
    // turn we create a MediaSource for preparation. The size of createdMediaSources is equal to 1
    // means that we only initiated DownloadCallback once, which means that the ongoing pre-cache
    // request was reused.
    assertThat(createdMediaSources).hasSize(1);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCacheAgainWithSameTimeRange_whileDownloadOngoing_reuseTheOngoingPreCacheRequest()
      throws Exception {
    ArrayList<FakeMediaSource> createdMediaSources = new ArrayList<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              createdMediaSources.add(fakeMediaSource);
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    // Each time we internally initiate a DownloadCallback, we create a new DownloadHelper, and in
    // turn we create a MediaSource for preparation. The size of createdMediaSources is equal to 1
    // means that we only initiated DownloadCallback once, which means that the ongoing pre-cache
    // request was reused.
    assertThat(createdMediaSources).hasSize(1);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCacheAgainWithSameTimeRange_afterPreparationFailed_startNewPreCacheRequest()
      throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    CountDownLatch createMediaSourceLatch = new CountDownLatch(2);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource =
                  new FakeMediaSource() {
                    @Override
                    public void maybeThrowSourceInfoRefreshError() throws IOException {
                      // After the first source created for preparation, we throw an IOException
                      // for it.
                      throw new IOException();
                    }
                  };
              fakeMediaSource.setAllowPreparation(false);
              createMediaSourceLatch.countDown();
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    AtomicBoolean preCacheTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheTerminated.set(true);
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onPrepareError(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            new FakeDownloaderFactory(),
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheTerminated.get());

    // While the preparation for the first pre-cache call failed, trigger the second call with the
    // same time range.
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    assertThat(createMediaSourceLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCacheAgainWithSameTimeRange_afterDownloadCompleted_startNewPreCacheRequest()
      throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    CountDownLatch createMediaSourceLatch = new CountDownLatch(2);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              createMediaSourceLatch.countDown();
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();
    downloader.finish();
    shadowOf(preCacheLooper).idle();

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    assertThat(createMediaSourceLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCacheAgainWithSameTimeRange_afterDownloadFailed_startNewPreCacheRequest()
      throws Exception {
    CountDownLatch createMediaSourceLatch = new CountDownLatch(2);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              createMediaSourceLatch.countDown();
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    AtomicBoolean preCacheTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheTerminated.set(true);
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onDownloadError(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    // Try to fail the download for five times to create a final exception.
    for (int i = 0; i <= PreCacheHelper.DEFAULT_MIN_RETRY_COUNT; i++) {
      downloader.assertDownloadStarted();
      downloader.fail();
    }
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheTerminated.get());

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    assertThat(createMediaSourceLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCacheAgainWithSameTimeRange_afterStopped_startNewPreCacheRequest()
      throws Exception {
    CountDownLatch createMediaSourceLatch = new CountDownLatch(2);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              createMediaSourceLatch.countDown();
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            new FakeDownloaderFactory(),
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();
    preCacheHelper.stop();
    shadowOf(preCacheLooper).idle();
    shadowOf(Looper.getMainLooper());

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 1000L);
    shadowOf(preCacheLooper).idle();

    assertThat(createMediaSourceLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void
      preCacheAgainWithDifferentTimeRange_whilePreparationOngoing_onlyNotifyOnPreparedAndStartDownloadForTheNewPreCacheRequest()
          throws Exception {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    Format videoFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(200_000)
            .build();
    Format audioFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("en").build();

    TrackGroup trackGroupVideo = new TrackGroup(videoFormat);
    TrackGroup trackGroupAudio = new TrackGroup(audioFormat);
    TrackGroupArray trackGroupArray = new TrackGroupArray(trackGroupVideo, trackGroupAudio);
    RenderersFactory renderersFactory =
        new RenderersFactory() {
          private int createRenderersCount = 0;

          @Override
          public Renderer[] createRenderers(
              Handler eventHandler,
              VideoRendererEventListener videoRendererEventListener,
              AudioRendererEventListener audioRendererEventListener,
              TextOutput textRendererOutput,
              MetadataOutput metadataRendererOutput) {
            Renderer[] renderers;
            if (createRenderersCount == 0) {
              // When prepare the media for the first time, we enable both video and audio.
              renderers =
                  new Renderer[] {
                    new FakeRenderer(C.TRACK_TYPE_VIDEO), new FakeRenderer(C.TRACK_TYPE_AUDIO)
                  };
            } else {
              // When prepare the media for the following times, we enable video only.
              renderers = new Renderer[] {new FakeRenderer(C.TRACK_TYPE_VIDEO)};
            }
            createRenderersCount++;
            return renderers;
          }
        };
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory()
            .setDataSourceFactory(cacheDataSourceFactory)
            .setRenderersFactory(renderersFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    ArrayList<FakeMediaSource> createdMediaSources = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource =
                  new FakeMediaSource() {
                    @Override
                    public MediaPeriod createPeriod(
                        MediaPeriodId id, Allocator allocator, long startPositionUs) {
                      return new FakeMediaPeriod(
                          trackGroupArray,
                          allocator,
                          getTimeline().getWindow(0, new Timeline.Window()).positionInFirstPeriodUs,
                          new MediaSourceEventListener.EventDispatcher()
                              .withParameters(/* windowIndex= */ 0, id)) {
                        @Override
                        public List<StreamKey> getStreamKeys(
                            List<ExoTrackSelection> trackSelections) {
                          List<StreamKey> result = new ArrayList<>();
                          for (ExoTrackSelection trackSelection : trackSelections) {
                            int groupIndex =
                                trackGroupArray.indexOf(trackSelection.getTrackGroup());
                            for (int i = 0; i < trackSelection.length(); i++) {
                              result.add(
                                  new StreamKey(
                                      0, groupIndex, trackSelection.getIndexInTrackGroup(i)));
                            }
                          }
                          return result;
                        }
                      };
                    }

                    @Override
                    public void releasePeriod(MediaPeriod mediaPeriod) {
                      // Do nothing.
                    }
                  };
              fakeMediaSource.setAllowPreparation(false);
              createdMediaSources.add(fakeMediaSource);
              return fakeMediaSource;
            });
    ArrayList<MediaItem> updatedMediaItemsAfterPreparation = new ArrayList<>();
    CountDownLatch onPreparedCalledLatch = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              updatedMediaItemsAfterPreparation.add(invocation.getArgument(1));
              onPreparedCalledLatch.countDown();
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onPrepared(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 100L);
    shadowOf(preCacheLooper).idle();
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 200L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> createdMediaSources.size() == 2);
    FakeMediaSource preparingMediaSourceAtFirstPreCache = createdMediaSources.get(0);
    FakeMediaSource preparingMediaSourceAtSecondPreCache = createdMediaSources.get(1);
    preparingMediaSourceAtFirstPreCache.setAllowPreparation(true);
    preparingMediaSourceAtSecondPreCache.setAllowPreparation(true);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    shadowOf(Looper.getMainLooper()).idle();

    // The onPreparedCalledLatch doesn't count down twice within the timeout, which means that we
    // got onPrepared called for 0 time or once after waiting long enough. In the later assertions
    // we will verify that we got onPrepared called exactly once.
    assertThat(onPreparedCalledLatch.await(TIMEOUT_MS, MILLISECONDS)).isFalse();
    // The updated MediaItem that the PreCacheHelper.Listener received should just be the one
    // resulting from the second `preCache` call, and we can verify that it only contains the
    // StreamKey for the video track.
    assertThat(updatedMediaItemsAfterPreparation).hasSize(1);
    MediaItem updatedMediaItem = updatedMediaItemsAfterPreparation.get(0);
    List<StreamKey> streamKeys = checkNotNull(updatedMediaItem.localConfiguration).streamKeys;
    assertThat(streamKeys)
        .containsExactly(new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0));
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertStreamKeys(new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0));

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void
      preCacheAgainWithDifferentTimeRange_whileDownloadOngoing_cancelOngoingDownloadAndStartDownloadForTheNewPreCacheRequest()
          throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(invocation -> new FakeMediaSource());
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    CountDownLatch onPreparedCalledLatch = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              onPreparedCalledLatch.countDown();
              return null;
            })
        .when(mockPreCacheHelperListener)
        .onPrepared(any(), any());
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 100L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    shadowOf(Looper.getMainLooper()).idle();
    FakeDownloader downloader1 = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader1.assertDownloadStarted();
    downloader1.incrementBytesDownloaded();

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 200L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> fakeDownloaderFactory.createdDownloaders.size() == 2);
    shadowOf(Looper.getMainLooper()).idle();

    downloader1.assertCanceled(true);
    FakeDownloader downloader2 = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(1));
    downloader2.assertDownloadStarted();
    downloader2.incrementBytesDownloaded();
    assertThat(onPreparedCalledLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    verify(mockPreCacheHelperListener, never()).onPrepareError(eq(testMediaItem), any());

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void stop_whilePreparationOngoing_doNotNotifyOnPrepared() throws Exception {
    AtomicReference<FakeMediaSource> createdMediaSource = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              fakeMediaSource.setAllowPreparation(false);
              createdMediaSource.set(fakeMediaSource);
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> createdMediaSource.get() != null);
    shadowOf(Looper.getMainLooper());

    preCacheHelper.stop();
    FakeMediaSource fakeMediaSource = checkNotNull(createdMediaSource.get());
    fakeMediaSource.setAllowPreparation(true);
    shadowOf(preCacheLooper).idle();

    verifyNoInteractions(mockPreCacheHelperListener);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void stop_whileDownloadOngoing_downloaderCanceled() throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(invocation -> new FakeMediaSource());
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();

    preCacheHelper.stop();
    shadowOf(preCacheLooper).idle();

    downloader.assertCanceled(true);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void releaseWithoutRemovingCachedContent_whilePreparationOngoing_doNotNotifyOnPrepared()
      throws Exception {
    AtomicReference<FakeMediaSource> createdMediaSource = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              fakeMediaSource.setAllowPreparation(false);
              createdMediaSource.set(fakeMediaSource);
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> createdMediaSource.get() != null);
    shadowOf(Looper.getMainLooper());

    preCacheHelper.release(/* removeCachedContent= */ false);
    FakeMediaSource fakeMediaSource = checkNotNull(createdMediaSource.get());
    fakeMediaSource.setAllowPreparation(true);
    shadowOf(preCacheLooper).idle();

    verifyNoInteractions(mockPreCacheHelperListener);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void
      releaseWithoutRemovingCachedContent_whileDownloadOngoing_downloaderCanceledButDoNotRemove()
          throws Exception {
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(invocation -> new FakeMediaSource());
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();

    preCacheHelper.release(/* removeCachedContent= */ false);
    shadowOf(preCacheLooper).idle();

    downloader.assertCanceled(true);
    downloader.assertRemoveStarted(false);

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void releaseWithRemovingCachedContent_whilePreparationOngoing_doNotNotifyOnPrepared()
      throws Exception {
    AtomicReference<FakeMediaSource> createdMediaSource = new AtomicReference<>();
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              FakeMediaSource fakeMediaSource = new FakeMediaSource();
              fakeMediaSource.setAllowPreparation(false);
              createdMediaSource.set(fakeMediaSource);
              return fakeMediaSource;
            });
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            testMediaItem,
            mockMediaSourceFactory,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> createdMediaSource.get() != null);
    shadowOf(Looper.getMainLooper()).idle();

    preCacheHelper.release(/* removeCachedContent= */ true);
    FakeMediaSource fakeMediaSource = checkNotNull(createdMediaSource.get());
    fakeMediaSource.setAllowPreparation(true);
    shadowOf(preCacheLooper).idle();

    verifyNoInteractions(mockPreCacheHelperListener);
  }

  @Test
  public void releaseWithRemovingCachedContent_whileDownloadOngoing_downloaderCanceledAndRemove()
      throws Exception {
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4");
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            mediaItem,
            /* testMediaSourceFactory= */ null,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();

    downloader.assertCanceled(true);
    downloader.assertRemoveStarted(true);
  }

  @Test
  public void releaseWithRemovingCachedContent_whileRemoveOngoing_doNotCancelDownloaderForRemoving()
      throws Exception {
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4");
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setCache(downloadCache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory().setDataSourceFactory(cacheDataSourceFactory);
    FakeDownloaderFactory fakeDownloaderFactory = new FakeDownloaderFactory();
    PreCacheHelper preCacheHelper =
        new PreCacheHelper(
            mediaItem,
            /* testMediaSourceFactory= */ null,
            downloadHelperFactory,
            fakeDownloaderFactory,
            preCacheLooper,
            mockPreCacheHelperListener);
    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> !fakeDownloaderFactory.createdDownloaders.isEmpty());
    FakeDownloader downloader = checkNotNull(fakeDownloaderFactory.createdDownloaders.get(0));
    downloader.assertDownloadStarted();
    downloader.incrementBytesDownloaded();
    // Call release() with `removeCachedContent == true`, then the downloader should trigger the
    // remove().
    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
    downloader.assertCanceled(true);
    downloader.assertRemoveStarted(true);

    // Call release() with `removeCachedContent == true` again, then the downloader for removing
    // should not be canceled
    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();

    downloader.assertCanceled(false);
    downloader.assertRemoveStarted(false);
  }

  private static class FakeDownloaderFactory implements DownloaderFactory {

    public final ArrayList<FakeDownloader> createdDownloaders;

    private FakeDownloaderFactory() {
      this.createdDownloaders = new ArrayList<>();
    }

    @Override
    public FakeDownloader createDownloader(DownloadRequest request) {
      FakeDownloader fakeDownloader = new FakeDownloader(request);
      createdDownloaders.add(fakeDownloader);
      return fakeDownloader;
    }
  }
}
