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

import static androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Status.STAGE_LOADED_TO_POSITION_MS;
import static androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Status.STAGE_SOURCE_PREPARED;
import static androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Status.STAGE_TRACKS_SELECTED;
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
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Unit test for {@link DefaultPreloadManager}. */
@RunWith(AndroidJUnit4.class)
public class DefaultPreloadManagerTest {

  @Mock private TargetPreloadStatusControl<Integer> mockTargetPreloadStatusControl;
  private TrackSelector trackSelector;
  private Allocator allocator;
  private BandwidthMeter bandwidthMeter;
  private RendererCapabilitiesList.Factory rendererCapabilitiesListFactory;

  @Before
  public void setUp() {
    trackSelector = new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    bandwidthMeter =
        new DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build();
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) ->
            new Renderer[] {
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  videoListener),
              new FakeAudioRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  audioListener)
            };
    rendererCapabilitiesListFactory = new DefaultRendererCapabilitiesList.Factory(renderersFactory);
    trackSelector.init(/* listener= */ () -> {}, bandwidthMeter);
  }

  @Test
  public void addByMediaItems_getCorrectCountAndSources() {
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            mockTargetPreloadStatusControl,
            new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext()),
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
        new DefaultPreloadManager(
            mockTargetPreloadStatusControl,
            new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext()),
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
        new DefaultPreloadManager(
            mockTargetPreloadStatusControl,
            new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext()),
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setMediaId("mediaId1")
            .setUri("http://exoplayer.dev/video1")
            .build();

    @Nullable MediaSource mediaSource = preloadManager.getMediaSource(mediaItem);

    assertThat(mediaSource).isNull();
  }

  @Test
  public void invalidate_withoutSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusesInOrder()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    AtomicInteger currentPlayingItemIndex = new AtomicInteger();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          if (abs(rankingData - currentPlayingItemIndex.get()) == 1) {
            return new DefaultPreloadManager.Status(STAGE_LOADED_TO_POSITION_MS, 100L);
          } else {
            return new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    runMainLooperUntil(() -> targetPreloadStatusControlCallStates.size() == 3);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1, 2).inOrder();
  }

  @Test
  public void invalidate_withSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusesInOrder()
      throws Exception {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    AtomicInteger currentPlayingItemIndex = new AtomicInteger();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          if (abs(rankingData - currentPlayingItemIndex.get()) == 1) {
            return new DefaultPreloadManager.Status(STAGE_LOADED_TO_POSITION_MS, 100L);
          } else {
            return new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
          }
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    PreloadMediaSource preloadMediaSource2 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem2);
    preloadMediaSource2.prepareSource(
        (source, timeline) -> {}, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadManager.setCurrentPlayingIndex(2);
    currentPlayingItemIndex.set(2);

    preloadManager.invalidate();
    runMainLooperUntil(() -> targetPreloadStatusControlCallStates.size() == 3);

    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1, 0).inOrder();
  }

  @Test
  public void invalidate_sourceHandedOverToPlayerDuringPreloading_continuesPreloadingNextSource() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
        };
    FakeMediaSourceFactory fakeMediaSourceFactory = new FakeMediaSourceFactory();
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            fakeMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    preloadMediaSource0.prepareSource(
        (source, timeline) -> {}, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();

    // The preload of mediaItem0 should complete and the preload manager continues to preload
    // mediaItem1, even when the preloadMediaSource0 hasn't finished preparation.
    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();
  }

  @Test
  public void invalidate_beforePreloadCompletedForLastInvalidate_preloadRespectsToLatestOrder() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
        };
    FakeMediaSourceFactory fakeMediaSourceFactory = new FakeMediaSourceFactory();
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            fakeMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    MediaSource.MediaSourceCaller externalCaller = (source, timeline) -> {};
    PreloadMediaSource preloadMediaSource0 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem0);
    preloadMediaSource0.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadManager.setCurrentPlayingIndex(0);

    preloadManager.invalidate();
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();

    targetPreloadStatusControlCallStates.clear();
    preloadMediaSource0.releaseSource(externalCaller);
    PreloadMediaSource preloadMediaSource2 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem2);
    preloadMediaSource2.prepareSource(
        externalCaller, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadManager.setCurrentPlayingIndex(2);
    preloadManager.invalidate();

    // Simulate the delay of the preparation of wrappedMediaSource0, which was triggered at the
    // first call of invalidate(). This is expected to result in nothing, as the whole flow of
    // preloading should respect the priority order triggered by the latest call of invalidate().
    wrappedMediaSource0.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1).inOrder();
    wrappedMediaSource1.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1, 0).inOrder();
  }

  @Test
  public void invalidate_provideNullTargetPreloadStatus_sourcesSkippedForPreload() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return null;
        };
    ProgressiveMediaSource.Factory mediaSourceFactory =
        new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1, 2);
  }

  @Test
  public void invalidate_clearsDeprioritizedSources() throws Exception {
    final AtomicInteger currentPlayingIndex = new AtomicInteger();
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          if (abs(rankingData - currentPlayingIndex.get()) <= 2) {
            return new DefaultPreloadManager.Status(STAGE_TRACKS_SELECTED);
          } else if (abs(rankingData - currentPlayingIndex.get()) == 3) {
            return new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
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
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mockMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    PreloadMediaSource preloadMediaSource4 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem4);
    // Simulate that preloadMediaSource4 is using by the player.
    preloadMediaSource4.prepareSource(
        (source, timeline) -> {}, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
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
  public void removeByMediaItems_correspondingHeldSourceRemovedAndReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mockMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    shadowOf(Looper.getMainLooper()).idle();

    boolean mediaItem1Removed = preloadManager.remove(mediaItem1);
    boolean mediaItem2Removed = preloadManager.remove(mediaItem2);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(mediaItem1Removed).isTrue();
    assertThat(mediaItem2Removed).isFalse();
    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1");
  }

  @Test
  public void removeByMediaSources_heldSourceRemovedAndReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    DefaultPreloadManager preloadManager =
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mockMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            rendererCapabilitiesListFactory,
            allocator,
            Util.getCurrentOrMainLooper());
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
    shadowOf(Looper.getMainLooper()).idle();
    MediaSource mediaSource1 = preloadManager.getMediaSource(mediaItem1);
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaSource mediaSource2 = defaultMediaSourceFactory.createMediaSource(mediaItem1);
    MediaSource mediaSource3 = defaultMediaSourceFactory.createMediaSource(mediaItem2);

    boolean mediaSource1Removed = preloadManager.remove(mediaSource1);
    boolean mediaSource2Removed = preloadManager.remove(mediaSource2);
    boolean mediaSource3Removed = preloadManager.remove(mediaSource3);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(mediaSource1Removed).isTrue();
    assertThat(mediaSource2Removed).isFalse();
    assertThat(mediaSource3Removed).isFalse();
    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1");
  }

  @Test
  public void reset_returnZeroCount_sourcesButNotRendererCapabilitiesListReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
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
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mockMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
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
    shadowOf(Looper.getMainLooper()).idle();

    preloadManager.reset();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1", "mediaId2");
    for (FakeRenderer renderer : underlyingRenderers) {
      assertThat(renderer.isReleased).isFalse();
    }
  }

  @Test
  public void release_returnZeroCount_sourcesAndRendererCapabilitiesListReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> new DefaultPreloadManager.Status(STAGE_SOURCE_PREPARED);
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
        new DefaultPreloadManager(
            targetPreloadStatusControl,
            mockMediaSourceFactory,
            trackSelector,
            bandwidthMeter,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory),
            allocator,
            Util.getCurrentOrMainLooper());
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
    shadowOf(Looper.getMainLooper()).idle();

    preloadManager.release();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(preloadManager.getSourceCount()).isEqualTo(0);
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1", "mediaId2");
    for (FakeRenderer renderer : underlyingRenderers) {
      assertThat(renderer.isReleased).isTrue();
    }
  }
}
