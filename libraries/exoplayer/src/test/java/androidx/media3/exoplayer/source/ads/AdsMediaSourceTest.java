/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.source.ads;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.ads.AdsLoader.EventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AdsMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class AdsMediaSourceTest {

  private static final long PREROLL_AD_DURATION_US = 10 * C.MICROS_PER_SECOND;
  private static final Timeline PREROLL_AD_TIMELINE =
      new SinglePeriodTimeline(
          PREROLL_AD_DURATION_US,
          /* isSeekable= */ true,
          /* isDynamic= */ false,
          /* useLiveConfiguration= */ false,
          /* manifest= */ null,
          MediaItem.fromUri(Uri.parse("https://google.com/empty")));
  private static final Object PREROLL_AD_PERIOD_UID =
      PREROLL_AD_TIMELINE.getUidOfPeriod(/* periodIndex= */ 0);

  private static final long CONTENT_DURATION_US = 30 * C.MICROS_PER_SECOND;
  private static final Timeline CONTENT_TIMELINE =
      new SinglePeriodTimeline(
          CONTENT_DURATION_US,
          /* isSeekable= */ true,
          /* isDynamic= */ false,
          /* useLiveConfiguration= */ false,
          /* manifest= */ null,
          MediaItem.fromUri(Uri.parse("https://google.com/empty")));
  private static final Object CONTENT_PERIOD_UID =
      CONTENT_TIMELINE.getUidOfPeriod(/* periodIndex= */ 0);

  private static final AdPlaybackState AD_PLAYBACK_STATE =
      new AdPlaybackState(/* adsId= */ new Object(), /* adGroupTimesUs...= */ 0)
          .withContentDurationUs(CONTENT_DURATION_US)
          .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
          .withAvailableAdMediaItem(
              /* adGroupIndex= */ 0,
              /* adIndexInAdGroup= */ 0,
              MediaItem.fromUri("https://google.com/ad"))
          .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
          .withAdResumePositionUs(/* adResumePositionUs= */ 0);

  private static final DataSpec TEST_ADS_DATA_SPEC = new DataSpec(Uri.EMPTY);
  private static final Object TEST_ADS_ID = new Object();

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private FakeMediaSource contentMediaSource;
  private FakeMediaSource prerollAdMediaSource;
  @Mock private MediaSourceCaller mockMediaSourceCaller;
  private AdsMediaSource adsMediaSource;

  @Before
  public void setUp() {
    // Set up content and ad media sources, passing a null timeline so tests can simulate setting it
    // later.
    contentMediaSource = new FakeMediaSource(/* timeline= */ null);
    prerollAdMediaSource = new FakeMediaSource(/* timeline= */ null);
    MediaSource.Factory adMediaSourceFactory = mock(MediaSource.Factory.class);
    when(adMediaSourceFactory.createMediaSource(any(MediaItem.class)))
        .thenReturn(prerollAdMediaSource);

    // Prepare the AdsMediaSource and capture its ads loader listener.
    AdsLoader mockAdsLoader = mock(AdsLoader.class);
    AdViewProvider mockAdViewProvider = mock(AdViewProvider.class);
    ArgumentCaptor<EventListener> eventListenerArgumentCaptor =
        ArgumentCaptor.forClass(AdsLoader.EventListener.class);
    adsMediaSource =
        new AdsMediaSource(
            contentMediaSource,
            TEST_ADS_DATA_SPEC,
            TEST_ADS_ID,
            adMediaSourceFactory,
            mockAdsLoader,
            mockAdViewProvider);
    adsMediaSource.prepareSource(
        mockMediaSourceCaller, /* mediaTransferListener= */ null, PlayerId.UNSET);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockAdsLoader)
        .start(
            eq(adsMediaSource),
            eq(TEST_ADS_DATA_SPEC),
            eq(TEST_ADS_ID),
            eq(mockAdViewProvider),
            eventListenerArgumentCaptor.capture());

    // Simulate loading a preroll ad.
    AdsLoader.EventListener adsLoaderEventListener = eventListenerArgumentCaptor.getValue();
    adsLoaderEventListener.onAdPlaybackState(AD_PLAYBACK_STATE);
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void createPeriod_preparesChildAdMediaSourceAndRefreshesSourceInfo() {
    contentMediaSource.setNewSourceInfo(CONTENT_TIMELINE);
    adsMediaSource.createPeriod(
        new MediaPeriodId(
            CONTENT_PERIOD_UID,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0),
        mock(Allocator.class),
        /* startPositionUs= */ 0);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(prerollAdMediaSource.isPrepared()).isTrue();
    verify(mockMediaSourceCaller)
        .onSourceInfoRefreshed(
            adsMediaSource, new SinglePeriodAdTimeline(CONTENT_TIMELINE, AD_PLAYBACK_STATE));
  }

  @Test
  public void createPeriod_preparesChildAdMediaSourceAndRefreshesSourceInfoWithAdMediaSourceInfo() {
    contentMediaSource.setNewSourceInfo(CONTENT_TIMELINE);
    adsMediaSource.createPeriod(
        new MediaPeriodId(
            CONTENT_PERIOD_UID,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0),
        mock(Allocator.class),
        /* startPositionUs= */ 0);
    prerollAdMediaSource.setNewSourceInfo(PREROLL_AD_TIMELINE);
    shadowOf(Looper.getMainLooper()).idle();

    verify(mockMediaSourceCaller)
        .onSourceInfoRefreshed(
            adsMediaSource,
            new SinglePeriodAdTimeline(
                CONTENT_TIMELINE,
                AD_PLAYBACK_STATE.withAdDurationsUs(new long[][] {{PREROLL_AD_DURATION_US}})));
  }

  @Test
  public void createPeriod_createsChildPrerollAdMediaPeriod() {
    contentMediaSource.setNewSourceInfo(CONTENT_TIMELINE);
    adsMediaSource.createPeriod(
        new MediaPeriodId(
            CONTENT_PERIOD_UID,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0),
        mock(Allocator.class),
        /* startPositionUs= */ 0);
    prerollAdMediaSource.setNewSourceInfo(PREROLL_AD_TIMELINE);
    shadowOf(Looper.getMainLooper()).idle();

    prerollAdMediaSource.assertMediaPeriodCreated(
        new MediaPeriodId(PREROLL_AD_PERIOD_UID, /* windowSequenceNumber= */ 0));
  }

  @Test
  public void createPeriod_createsChildContentMediaPeriod() {
    contentMediaSource.setNewSourceInfo(CONTENT_TIMELINE);
    shadowOf(Looper.getMainLooper()).idle();
    adsMediaSource.createPeriod(
        new MediaPeriodId(CONTENT_PERIOD_UID, /* windowSequenceNumber= */ 0),
        mock(Allocator.class),
        /* startPositionUs= */ 0);

    contentMediaSource.assertMediaPeriodCreated(
        new MediaPeriodId(CONTENT_PERIOD_UID, /* windowSequenceNumber= */ 0));
  }

  @Test
  public void releasePeriod_releasesChildMediaPeriodsAndSources() {
    contentMediaSource.setNewSourceInfo(CONTENT_TIMELINE);
    MediaPeriod prerollAdMediaPeriod =
        adsMediaSource.createPeriod(
            new MediaPeriodId(
                CONTENT_PERIOD_UID,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0),
            mock(Allocator.class),
            /* startPositionUs= */ 0);
    prerollAdMediaSource.setNewSourceInfo(PREROLL_AD_TIMELINE);
    shadowOf(Looper.getMainLooper()).idle();
    MediaPeriod contentMediaPeriod =
        adsMediaSource.createPeriod(
            new MediaPeriodId(CONTENT_PERIOD_UID, /* windowSequenceNumber= */ 0),
            mock(Allocator.class),
            /* startPositionUs= */ 0);
    adsMediaSource.releasePeriod(prerollAdMediaPeriod);

    prerollAdMediaSource.assertReleased();

    adsMediaSource.releasePeriod(contentMediaPeriod);
    adsMediaSource.releaseSource(mockMediaSourceCaller);
    shadowOf(Looper.getMainLooper()).idle();
    prerollAdMediaSource.assertReleased();
    contentMediaSource.assertReleased();
  }

  @Test
  public void canUpdateMediaItem_withIrrelevantFieldsChanged_returnsTrue() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.uri")
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.parse("http://ad.tag.test")).build())
            .build();
    MediaItem updatedMediaItem =
        TestUtil.buildFullyCustomizedMediaItem()
            .buildUpon()
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.parse("http://ad.tag.test")).build())
            .build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isTrue();
  }

  @Test
  public void canUpdateMediaItem_withChangedAdsConfiguration_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.uri")
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.parse("http://ad.tag.test")).build())
            .build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.uri")
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.parse("http://other.tag.test")).build())
            .build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void updateMediaItem_createsTimelineWithUpdatedItem() throws Exception {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setUri("http://test2.test").build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);
    AtomicReference<Timeline> timelineReference = new AtomicReference<>();

    mediaSource.updateMediaItem(updatedMediaItem);
    mediaSource.prepareSource(
        (source, timeline) -> timelineReference.set(timeline),
        /* mediaTransferListener= */ null,
        PlayerId.UNSET);
    RobolectricUtil.runMainLooperUntil(() -> timelineReference.get() != null);

    assertThat(
            timelineReference
                .get()
                .getWindow(/* windowIndex= */ 0, new Timeline.Window())
                .mediaItem)
        .isEqualTo(updatedMediaItem);
  }

  private static MediaSource buildMediaSource(MediaItem mediaItem) {
    FakeMediaSource fakeMediaSource = new FakeMediaSource();
    fakeMediaSource.setCanUpdateMediaItems(true);
    fakeMediaSource.updateMediaItem(mediaItem);
    AdsLoader adsLoader = mock(AdsLoader.class);
    doAnswer(
            method -> {
              ((EventListener) method.getArgument(4))
                  .onAdPlaybackState(new AdPlaybackState(TEST_ADS_ID));
              return null;
            })
        .when(adsLoader)
        .start(any(), any(), any(), any(), any());
    return new AdsMediaSource(
        fakeMediaSource,
        TEST_ADS_DATA_SPEC,
        TEST_ADS_ID,
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext()),
        adsLoader,
        /* adViewProvider= */ () -> null);
  }
}
