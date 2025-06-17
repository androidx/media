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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.test.utils.FakeMediaSourceFactory.DEFAULT_WINDOW_UID;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.abs;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Unit test for {@link DefaultPreloadManager}. */
@RunWith(AndroidJUnit4.class)
public class DefaultPreloadManagerTest {

  private static final int SMALL_LOADING_CHECK_INTERVAL_BYTES = 32;

  private Context context;

  @Mock
  private TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
      mockTargetPreloadStatusControl;

  private LoadControl loadControl;
  private RenderersFactory renderersFactory;
  private HandlerThread preloadThread;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
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
    loadControl = new DefaultLoadControl();
    preloadThread = new HandlerThread("DefaultPreloadManagerTest");
    preloadThread.start();
  }

  @After
  public void tearDown() {
    preloadThread.quit();
  }

  @Test
  public void addByMediaItems_getCorrectCountAndSources() {
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, mockTargetPreloadStatusControl)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();

    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);

    assertThat(preloadManager.getSourceCount()).isEqualTo(2);
    assertThat(preloadManager.getMediaSource(mediaItem1).getMediaItem()).isEqualTo(mediaItem1);
    assertThat(preloadManager.getMediaSource(mediaItem2).getMediaItem()).isEqualTo(mediaItem2);
  }

  @Test
  public void addByMediaSources_getCorrectCountAndSources() {
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, mockTargetPreloadStatusControl)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaSource mediaSourceToAdd1 = defaultMediaSourceFactory.createMediaSource(mediaItem1);
    MediaSource mediaSourceToAdd2 = defaultMediaSourceFactory.createMediaSource(mediaItem2);

    preloadManager.add(mediaSourceToAdd1, /* rankingData= */ 1);
    preloadManager.add(mediaSourceToAdd2, /* rankingData= */ 2);

    assertThat(preloadManager.getSourceCount()).isEqualTo(2);
    assertThat(preloadManager.getMediaSource(mediaItem1).getMediaItem()).isEqualTo(mediaItem1);
    assertThat(preloadManager.getMediaSource(mediaItem2).getMediaItem()).isEqualTo(mediaItem2);
  }

  @Test
  public void getMediaSourceForMediaItemNotAdded() {
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, mockTargetPreloadStatusControl)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setMediaId("mediaId1")
            .setUri("http://exoplayer.dev/video1")
            .build();

    @Nullable MediaSource mediaSource = preloadManager.getMediaSource(mediaItem);

    assertThat(mediaSource).isNull();
  }

  @Test
  public void invalidate_withoutSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusInOrder()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    AtomicInteger currentPlayingItemIndex = new AtomicInteger();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              if (abs(rankingData - currentPlayingItemIndex.get()) == 1) {
                return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                    /* durationMs= */ 100L);
              } else {
                return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
              }
            };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder
            .setMediaId("mediaId0")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem1 =
        mediaItemBuilder
            .setMediaId("mediaId1")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem2 =
        mediaItemBuilder
            .setMediaId("mediaId2")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);

    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 3);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1, 2).inOrder();
    assertThat(preloadManagerListener.onCompletedMediaItemRecords)
        .containsExactly(mediaItem0, mediaItem1, mediaItem2)
        .inOrder();
  }

  @Test
  public void invalidate_withSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusInOrder()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    AtomicInteger currentPlayingItemIndex = new AtomicInteger();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              if (abs(rankingData - currentPlayingItemIndex.get()) == 1) {
                return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                    /* durationMs= */ 100L);
              } else {
                return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
              }
            };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder
            .setMediaId("mediaId0")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem1 =
        mediaItemBuilder
            .setMediaId("mediaId1")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem2 =
        mediaItemBuilder
            .setMediaId("mediaId2")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);
    preloadManager.setCurrentPlayingIndex(2);
    currentPlayingItemIndex.set(2);

    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 3);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1, 0).inOrder();
    assertThat(preloadManagerListener.onCompletedMediaItemRecords)
        .containsExactly(mediaItem2, mediaItem1, mediaItem0)
        .inOrder();
  }

  @Test
  public void invalidate_sourceHandedOverToPlayerDuringPreloading_continuesPreloadingNextSource()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
            };
    FakeMediaSourceFactory fakeMediaSourceFactory = new FakeMediaSourceFactory();
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(fakeMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder.setMediaId("mediaId0").setUri("http://exoplayer.dev/video0").build();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    FakeMediaSource wrappedMediaSource0 = fakeMediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource0.setAllowPreparation(false);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    FakeMediaSource wrappedMediaSource1 = fakeMediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource1.setAllowPreparation(false);
    preloadManager.invalidate();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(0);

    PreloadMediaSource preloadMediaSource0 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem0);
    Handler playbackHandler = new Handler(preloadThread.getLooper());
    playbackHandler.post(
        () ->
            checkNotNull(preloadMediaSource0)
                .prepareSource(
                    (source, timeline) -> {},
                    DefaultBandwidthMeter.getSingletonInstance(context).getTransferListener(),
                    PlayerId.UNSET));
    wrappedMediaSource0.setAllowPreparation(true);
    wrappedMediaSource1.setAllowPreparation(true);
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 1);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();
    assertThat(preloadManagerListener.onCompletedMediaItemRecords).containsExactly(mediaItem1);
  }

  @Test
  public void invalidate_beforePreloadCompletedForLastInvalidate_preloadRespectsToLatestOrder()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
            };
    FakeMediaSourceFactory fakeMediaSourceFactory = new FakeMediaSourceFactory();
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(fakeMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder.setMediaId("mediaId0").setUri("http://exoplayer.dev/video0").build();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    FakeMediaSource wrappedMediaSource0 = fakeMediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource0.setAllowPreparation(false);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    FakeMediaSource wrappedMediaSource1 = fakeMediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource1.setAllowPreparation(false);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);
    FakeMediaSource wrappedMediaSource2 = fakeMediaSourceFactory.getLastCreatedSource();
    wrappedMediaSource2.setAllowPreparation(false);
    preloadManager.setCurrentPlayingIndex(0);

    preloadManager.invalidate();
    wrappedMediaSource0.setAllowPreparation(true);
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 1);
    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();
    assertThat(preloadManagerListener.onCompletedMediaItemRecords).containsExactly(mediaItem0);

    targetPreloadStatusControlCallStates.clear();
    preloadManagerListener.reset();
    preloadManager.setCurrentPlayingIndex(2);
    preloadManager.invalidate();

    // Simulate the delay of the preparation of wrappedMediaSource1, which was triggered at the
    // first call of invalidate(). This is expected to result in nothing, as the whole flow of
    // preloading should respect the priority order triggered by the latest call of invalidate(),
    // which will be verified by the order of items in
    // preloadManagerListener.onCompletedMediaItemRecords.
    wrappedMediaSource1.setAllowPreparation(true);
    wrappedMediaSource2.setAllowPreparation(true);
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 3);
    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1, 0).inOrder();
    assertThat(preloadManagerListener.onCompletedMediaItemRecords)
        .containsExactly(mediaItem2, mediaItem1, mediaItem0)
        .inOrder();
  }

  @Test
  public void invalidate_provideNullTargetPreloadStatus_sourcesSkippedForPreload()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              return (rankingData == 0)
                  ? null
                  : DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
            };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder
            .setMediaId("mediaId0")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem1 =
        mediaItemBuilder
            .setMediaId("mediaId1")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);

    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 1);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1);
    assertThat(preloadManagerListener.onCompletedMediaItemRecords).containsExactly(mediaItem1);
  }

  @Test
  public void invalidate_sourceHasPreloadException_continuesPreloadingNextSource()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
            };
    IOException causeException = new IOException("Failed to refresh source info");
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder.setMediaId("mediaId0").setUri("http://exoplayer.dev/video0").build();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
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
            FakeMediaSource mediaSource =
                new FakeMediaSource() {
                  @Override
                  public MediaItem getMediaItem() {
                    return mediaItem;
                  }
                };
            if (mediaItem.equals(mediaItem0)) {
              mediaSource =
                  new FakeMediaSource() {
                    @Override
                    public void maybeThrowSourceInfoRefreshError() throws IOException {
                      throw causeException;
                    }

                    @Override
                    public MediaItem getMediaItem() {
                      return mediaItem;
                    }
                  };
              mediaSource.setAllowPreparation(false);
            }
            return mediaSource;
          }
        };
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);

    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 1);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();
    assertThat(Iterables.getOnlyElement(preloadManagerListener.onErrorPreloadExceptionRecords))
        .hasCauseThat()
        .isEqualTo(causeException);
    assertThat(preloadManagerListener.onCompletedMediaItemRecords).containsExactly(mediaItem1);
  }

  @Test
  public void invalidate_clearsDeprioritizedSources() throws Exception {
    final AtomicInteger currentPlayingIndex = new AtomicInteger();
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              if (abs(rankingData - currentPlayingIndex.get()) <= 2) {
                return DefaultPreloadManager.PreloadStatus.TRACKS_SELECTED;
              } else if (abs(rankingData - currentPlayingIndex.get()) == 3) {
                return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
              }
              return null;
            };
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    ArrayList<String> releasedPreloadingPeriodMediaIds = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              FakeTimeline.TimelineWindowDefinition timelineWindowDefinition =
                  new FakeTimeline.TimelineWindowDefinition(
                      /* periodCount= */ 1,
                      /* id= */ DEFAULT_WINDOW_UID,
                      /* isSeekable= */ true,
                      /* isDynamic= */ false,
                      /* isLive= */ false,
                      /* isPlaceholder= */ false,
                      /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                      /* defaultPositionUs= */ 2 * C.MICROS_PER_SECOND,
                      /* windowOffsetInFirstPeriodUs= */ Util.msToUs(123456789),
                      ImmutableList.of(AdPlaybackState.NONE),
                      mediaItem);
              return new FakeMediaSource(new FakeTimeline(timelineWindowDefinition)) {
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
                      FakeTimeline.TimelineWindowDefinition
                          .DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
                      mediaSourceEventDispatcher) {
                    @Override
                    public void release() {
                      releasedPreloadingPeriodMediaIds.add(mediaItem.mediaId);
                    }
                  };
                }
              };
            });
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem0 =
        mediaItemBuilder
            .setMediaId("mediaId0")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem1 =
        mediaItemBuilder
            .setMediaId("mediaId1")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem2 =
        mediaItemBuilder
            .setMediaId("mediaId2")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem3 =
        mediaItemBuilder
            .setMediaId("mediaId3")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    MediaItem mediaItem4 =
        mediaItemBuilder
            .setMediaId("mediaId4")
            .setUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"))
            .build();
    preloadManager.add(mediaItem0, /* rankingData= */ 0);
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);
    preloadManager.add(mediaItem3, /* rankingData= */ 3);
    preloadManager.add(mediaItem4, /* rankingData= */ 4);
    currentPlayingIndex.set(C.INDEX_UNSET);

    preloadManager.invalidate();
    runMainLooperUntil(() -> targetPreloadStatusControlCallStates.size() == 5);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1, 2, 3, 4).inOrder();
    assertThat(releasedPreloadingPeriodMediaIds).isEmpty();

    targetPreloadStatusControlCallStates.clear();
    // Simulate that preloadMediaSource4 is using by the player.
    PreloadMediaSource preloadMediaSource4 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem4);
    Handler playbackHandler = new Handler(preloadThread.getLooper());
    playbackHandler.post(
        () ->
            checkNotNull(preloadMediaSource4)
                .prepareSource(
                    (source, timeline) -> {},
                    DefaultBandwidthMeter.getSingletonInstance(context).getTransferListener(),
                    PlayerId.UNSET));
    shadowOf(preloadThread.getLooper()).idle();
    currentPlayingIndex.set(4);
    preloadManager.setCurrentPlayingIndex(4);

    preloadManager.invalidate();
    runMainLooperUntil(() -> releasedPreloadingPeriodMediaIds.size() == 2);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(4, 3, 2, 1, 0).inOrder();
    // The sources for mediaItem4, mediaItem3 and mediaItem2 either got used by the player or
    // preload more after the second invalidate() call because their priorities increased. Thus the
    // sources got cleared are the ones for mediaItem1 and mediaItem0 due to their decreased
    // priorities.
    assertThat(releasedPreloadingPeriodMediaIds).containsExactly("mediaId1", "mediaId0");
  }

  @Test
  public void invalidate_setsNonDefaultStartPosition_sourcesPreloadedFromStartPosition()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    // Set a large value for start position and a small value for duration.
    long startPositionMs = 1_000L;
    long durationMs = 1L;
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> {
              targetPreloadStatusControlCallStates.add(rankingData);
              return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                  startPositionMs, durationMs);
            };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            .setContinueLoadingCheckIntervalBytes(SMALL_LOADING_CHECK_INTERVAL_BYTES);
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .setLoadControl(loadControl)
            .build();
    TestPreloadManagerListener preloadManagerListener = new TestPreloadManagerListener();
    preloadManager.addListener(preloadManagerListener);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem =
        mediaItemBuilder
            .setMediaId("mediaId0")
            .setUri(Uri.parse("asset://android_asset/media/mp4/long_1080p_lowbitrate.mp4"))
            .build();
    preloadManager.add(mediaItem, /* rankingData= */ 0);

    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    runMainLooperUntil(() -> preloadManagerListener.onCompletedMediaItemRecords.size() == 1);
    MediaSource preloadedMediaSource = preloadManager.getMediaSource(mediaItem);
    AtomicReference<MediaPeriod> preloadedMediaPeriodReference = new AtomicReference<>();
    simulatePlayerPrepareSource(
        preloadedMediaSource, startPositionMs, preloadedMediaPeriodReference);
    runMainLooperUntil(() -> preloadedMediaPeriodReference.get() != null);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0);
    assertThat(preloadManagerListener.onCompletedMediaItemRecords).containsExactly(mediaItem);
    // The getBufferedPositionUs() for the preloaded period should reach at least
    // startPositionMs + durationMs, which mostly results from the large startPositionMs value.
    assertThat(preloadedMediaPeriodReference.get().getBufferedPositionUs())
        .isAtLeast(Util.msToUs(1001L));
  }

  @Test
  public void invalidateThenRelease_doNotPreloadNextSource() {
    AtomicReference<DefaultPreloadManager> preloadManagerReference = new AtomicReference<>();
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            new TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>() {
              @Nullable
              @Override
              public DefaultPreloadManager.PreloadStatus getTargetPreloadStatus(
                  Integer rankingData) {
                if (rankingData == 2) {
                  // When DefaultPreloadManager is about to preload the second item, release it.
                  checkNotNull(preloadManagerReference.get()).release();
                  shadowOf(preloadThread.getLooper()).idle();
                }
                return DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
              }
            };
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    ArrayList<String> preparedMediaId = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              return new FakeMediaSource() {
                @Override
                public synchronized void prepareSourceInternal(
                    @Nullable TransferListener mediaTransferListener) {
                  preparedMediaId.add(mediaItem.mediaId);
                  super.prepareSourceInternal(mediaTransferListener);
                }

                @Override
                public MediaItem getMediaItem() {
                  return mediaItem;
                }
              };
            });
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    preloadManagerReference.set(preloadManager);
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);

    // Call `invalidate()` and then call `release()` in the `TargetPreloadStatusControl` to
    // simulate the situation that the preload manager is released during the transition of the
    // sources.
    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    shadowOf(Util.getCurrentOrMainLooper()).idle();

    // The preload of second media source shouldn't start as the preload manager has already been
    // released.
    assertThat(preparedMediaId).containsExactly("mediaId1");
  }

  @Test
  public void removeByMediaItems_correspondingHeldSourceRemovedAndReleased() {
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    ArrayList<String> internalSourceToReleaseReferenceByMediaId = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              return new FakeMediaSource() {
                @Override
                public MediaItem getMediaItem() {
                  return mediaItem;
                }

                @Override
                protected void releaseSourceInternal() {
                  internalSourceToReleaseReferenceByMediaId.add(mediaItem.mediaId);
                  super.releaseSourceInternal();
                }
              };
            });
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();

    boolean mediaItem1Removed = preloadManager.remove(mediaItem1);
    boolean mediaItem2Removed = preloadManager.remove(mediaItem2);
    shadowOf(preloadThread.getLooper()).idle();

    assertThat(mediaItem1Removed).isTrue();
    assertThat(mediaItem2Removed).isFalse();
    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1");
  }

  @Test
  public void removeByMediaSources_heldSourceRemovedAndReleased() {
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    ArrayList<String> internalSourceToReleaseReferenceByMediaId = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              return new FakeMediaSource() {
                @Override
                public MediaItem getMediaItem() {
                  return mediaItem;
                }

                @Override
                protected void releaseSourceInternal() {
                  internalSourceToReleaseReferenceByMediaId.add(mediaItem.mediaId);
                  super.releaseSourceInternal();
                }
              };
            });
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    MediaSource mediaSource1 = preloadManager.getMediaSource(mediaItem1);
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaSource mediaSource2 = defaultMediaSourceFactory.createMediaSource(mediaItem1);
    MediaSource mediaSource3 = defaultMediaSourceFactory.createMediaSource(mediaItem2);

    boolean mediaSource1Removed = preloadManager.remove(mediaSource1);
    boolean mediaSource2Removed = preloadManager.remove(mediaSource2);
    boolean mediaSource3Removed = preloadManager.remove(mediaSource3);
    shadowOf(preloadThread.getLooper()).idle();

    assertThat(mediaSource1Removed).isTrue();
    assertThat(mediaSource2Removed).isFalse();
    assertThat(mediaSource3Removed).isFalse();
    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1");
  }

  @Test
  public void reset_returnZeroCount_sourcesButNotRendererCapabilitiesListReleased() {
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    List<FakeRenderer> underlyingRenderers = new ArrayList<>();
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) -> {
          FakeRenderer fakeVideoRenderer =
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(eventHandler.getLooper(), /* callback= */ null),
                  videoRendererEventListener);
          underlyingRenderers.add(fakeVideoRenderer);
          FakeRenderer fakeAudioRenderer =
              new FakeAudioRenderer(
                  SystemClock.DEFAULT.createHandler(eventHandler.getLooper(), /* callback= */ null),
                  audioRendererEventListener);
          underlyingRenderers.add(fakeAudioRenderer);
          return underlyingRenderers.toArray(new Renderer[2]);
        };
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    ArrayList<String> internalSourceToReleaseReferenceByMediaId = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              return new FakeMediaSource() {
                @Override
                public MediaItem getMediaItem() {
                  return mediaItem;
                }

                @Override
                protected void releaseSourceInternal() {
                  internalSourceToReleaseReferenceByMediaId.add(mediaItem.mediaId);
                  super.releaseSourceInternal();
                }
              };
            });
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);
    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    shadowOf(Util.getCurrentOrMainLooper()).idle();

    preloadManager.reset();
    shadowOf(preloadThread.getLooper()).idle();

    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1", "mediaId2");
    for (FakeRenderer renderer : underlyingRenderers) {
      assertThat(renderer.isReleased).isFalse();
    }
  }

  @Test
  public void release_returnZeroCount_sourcesAndRendererCapabilitiesListReleased() {
    TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus>
        targetPreloadStatusControl =
            rankingData -> DefaultPreloadManager.PreloadStatus.SOURCE_PREPARED;
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    List<FakeRenderer> underlyingRenderers = new ArrayList<>();
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) -> {
          FakeRenderer fakeVideoRenderer =
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(eventHandler.getLooper(), /* callback= */ null),
                  videoRendererEventListener);
          underlyingRenderers.add(fakeVideoRenderer);
          FakeRenderer fakeAudioRenderer =
              new FakeAudioRenderer(
                  SystemClock.DEFAULT.createHandler(eventHandler.getLooper(), /* callback= */ null),
                  audioRendererEventListener);
          underlyingRenderers.add(fakeAudioRenderer);
          return underlyingRenderers.toArray(new Renderer[2]);
        };
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
            .setMediaSourceFactory(mockMediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setPreloadLooper(preloadThread.getLooper())
            .build();
    MediaItem.Builder mediaItemBuilder = new MediaItem.Builder();
    MediaItem mediaItem1 =
        mediaItemBuilder.setMediaId("mediaId1").setUri("http://exoplayer.dev/video1").build();
    MediaItem mediaItem2 =
        mediaItemBuilder.setMediaId("mediaId2").setUri("http://exoplayer.dev/video2").build();
    ArrayList<String> internalSourceToReleaseReferenceByMediaId = new ArrayList<>();
    when(mockMediaSourceFactory.createMediaSource(any()))
        .thenAnswer(
            invocation -> {
              MediaItem mediaItem = invocation.getArgument(0);
              return new FakeMediaSource() {
                @Override
                public MediaItem getMediaItem() {
                  return mediaItem;
                }

                @Override
                protected void releaseSourceInternal() {
                  internalSourceToReleaseReferenceByMediaId.add(mediaItem.mediaId);
                  super.releaseSourceInternal();
                }
              };
            });
    preloadManager.add(mediaItem1, /* rankingData= */ 1);
    preloadManager.add(mediaItem2, /* rankingData= */ 2);
    preloadManager.invalidate();
    shadowOf(preloadThread.getLooper()).idle();
    shadowOf(Util.getCurrentOrMainLooper()).idle();

    preloadManager.release();
    shadowOf(preloadThread.getLooper()).idle();

    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1", "mediaId2");
    for (FakeRenderer renderer : underlyingRenderers) {
      assertThat(renderer.isReleased).isTrue();
    }
  }

  private void simulatePlayerPrepareSource(
      MediaSource mediaSource,
      long startPositionMs,
      AtomicReference<MediaPeriod> preloadedMediaPeriodReference) {
    MediaPeriod.Callback mediaPeriodCallback =
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            preloadedMediaPeriodReference.set(mediaPeriod);
            shadowOf(preloadThread.getLooper()).idle();
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        };
    MediaSource.MediaSourceCaller mediaSourceCaller =
        (source, timeline) -> {
          Pair<Object, Long> periodPosition =
              timeline.getPeriodPositionUs(
                  new Timeline.Window(),
                  new Timeline.Period(),
                  /* windowIndex= */ 0,
                  /* windowPositionUs= */ Util.msToUs(startPositionMs));
          MediaPeriod mediaPeriod =
              source.createPeriod(
                  new MediaSource.MediaPeriodId(periodPosition.first),
                  loadControl.getAllocator(),
                  periodPosition.second);
          mediaPeriod.prepare(mediaPeriodCallback, periodPosition.second);
          shadowOf(preloadThread.getLooper()).idle();
        };
    Handler preloadHandler = Util.createHandler(preloadThread.getLooper(), /* callback= */ null);
    preloadHandler.post(() -> mediaSource.prepareSource(mediaSourceCaller, null, PlayerId.UNSET));
    shadowOf(preloadThread.getLooper()).idle();
  }

  private static class TestPreloadManagerListener implements PreloadManagerListener {

    public final List<MediaItem> onCompletedMediaItemRecords;
    public final List<PreloadException> onErrorPreloadExceptionRecords;

    public TestPreloadManagerListener() {
      onCompletedMediaItemRecords = new ArrayList<>();
      onErrorPreloadExceptionRecords = new ArrayList<>();
    }

    @Override
    public void onCompleted(MediaItem mediaItem) {
      onCompletedMediaItemRecords.add(mediaItem);
    }

    @Override
    public void onError(PreloadException exception) {
      onErrorPreloadExceptionRecords.add(exception);
    }

    public void reset() {
      onCompletedMediaItemRecords.clear();
      onErrorPreloadExceptionRecords.clear();
    }
  }
}
