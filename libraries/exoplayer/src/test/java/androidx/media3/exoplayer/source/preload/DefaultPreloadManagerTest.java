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
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
  public void
      invalidate_withoutSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusesInOrder() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return new DefaultPreloadManager.Status(
              DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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

    preloadManager.invalidate();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0);
    wrappedMediaSource0.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1).inOrder();
  }

  @Test
  public void invalidate_withSettingCurrentPlayingIndex_sourcesPreloadedToTargetStatusesInOrder() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return new DefaultPreloadManager.Status(
              DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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
    PreloadMediaSource preloadMediaSource2 =
        (PreloadMediaSource) preloadManager.getMediaSource(mediaItem2);
    preloadMediaSource2.prepareSource(
        (source, timeline) -> {}, bandwidthMeter.getTransferListener(), PlayerId.UNSET);
    preloadManager.setCurrentPlayingIndex(2);

    preloadManager.invalidate();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1);
    wrappedMediaSource1.setAllowPreparation(true);
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(targetPreloadStatusControlCallStates).containsExactly(2, 1, 0).inOrder();
  }

  @Test
  public void invalidate_sourceHandedOverToPlayerDuringPreloading_continuesPreloadingNextSource() {
    ArrayList<Integer> targetPreloadStatusControlCallStates = new ArrayList<>();
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData -> {
          targetPreloadStatusControlCallStates.add(rankingData);
          return new DefaultPreloadManager.Status(
              DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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
          return new DefaultPreloadManager.Status(
              DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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

    preloadManager.invalidate();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(targetPreloadStatusControlCallStates).containsExactly(0, 1, 2);
  }

  @Test
  public void removeByMediaItems_correspondingHeldSourceRemovedAndReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData ->
            new DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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
        rankingData ->
            new DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
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
  public void release_returnZeroCountAndNullSources_sourcesReleased() {
    TargetPreloadStatusControl<Integer> targetPreloadStatusControl =
        rankingData ->
            new DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_TIMELINE_REFRESHED);
    MediaSource.Factory mockMediaSourceFactory = mock(MediaSource.Factory.class);
    AtomicReference<List<FakeRenderer>> underlyingRenderersReference = new AtomicReference<>();
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) -> {
          FakeRenderer[] createdRenderers =
              new FakeRenderer[] {
                new FakeVideoRenderer(
                    SystemClock.DEFAULT.createHandler(
                        eventHandler.getLooper(), /* callback= */ null),
                    videoRendererEventListener),
                new FakeAudioRenderer(
                    SystemClock.DEFAULT.createHandler(
                        eventHandler.getLooper(), /* callback= */ null),
                    audioRendererEventListener)
              };
          underlyingRenderersReference.set(ImmutableList.copyOf(createdRenderers));
          return createdRenderers;
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
    assertThat(preloadManager.getMediaSource(mediaItem1)).isNull();
    assertThat(preloadManager.getMediaSource(mediaItem2)).isNull();
    assertThat(internalSourceToReleaseReferenceByMediaId).containsExactly("mediaId1", "mediaId2");
    List<FakeRenderer> underlyingRenderers = checkNotNull(underlyingRenderersReference.get());
    for (FakeRenderer renderer : underlyingRenderers) {
      assertThat(renderer.isReleased).isTrue();
    }
  }
}
