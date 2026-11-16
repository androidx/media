/*
 * Copyright 2026 The Android Open Source Project
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

import android.net.Uri;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem;
import androidx.media3.exoplayer.source.WrappingMediaSource;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import org.mockito.InOrder;

/** Test utilities for {@link AdPlaybackTracker} unit tests. */
/* package */ final class AdPlaybackTrackerTestUtil {

  public static final Object ADS_ID = "adsId";
  public static final Object SECOND_ADS_ID = "secondAdsId";
  public static final long CONTENT_DURATION_US = 60 * C.MICROS_PER_SECOND;
  public static final long AD_DURATION_US = 10 * C.MICROS_PER_SECOND;
  public static final MediaItem AD_MEDIA_ITEM_0 = MediaItem.fromUri("https://example.com/ad_0.mp4");
  public static final MediaItem AD_MEDIA_ITEM_1 = MediaItem.fromUri("https://example.com/ad_1.mp4");

  private static final Uri AD_TAG_URI = Uri.parse("https://example.com/ad_tag");

  /**
   * Returns a content {@link MediaItem} configured with a {@link MediaItem.AdsConfiguration} whose
   * {@link MediaItem.AdsConfiguration#adsId} is {@code adsId}.
   */
  public static MediaItem createContentMediaItem(Object adsId) {
    return new MediaItem.Builder()
        .setUri("https://example.com/content.mp4")
        .setAdsConfiguration(
            new MediaItem.AdsConfiguration.Builder(AD_TAG_URI).setAdsId(adsId).build())
        .build();
  }

  /**
   * Verifies that {@code mockSdkBridge} received the standard sequence of ad group and ad playback
   * events in order for the specified ad range within an ad group.
   *
   * @param inOrder The {@link InOrder} verifier to check callback ordering.
   * @param mockSdkBridge The mock {@link AdPlaybackTracker.SdkBridge} to verify against.
   * @param adsId The opaque ads identifier for the ad playback session.
   * @param adGroupIndex The index of the ad group.
   * @param startAdIndexInAdGroup The index of the first played ad in the ad group (inclusive).
   * @param endAdIndexInAdGroup The index of the last played ad in the ad group (inclusive).
   */
  public static void verifyStandardAdGroupPlaybackEvents(
      InOrder inOrder,
      AdPlaybackTracker.SdkBridge mockSdkBridge,
      Object adsId,
      int adGroupIndex,
      int startAdIndexInAdGroup,
      int endAdIndexInAdGroup) {
    inOrder.verify(mockSdkBridge).onAdGroupStarted(adsId, adGroupIndex);
    for (int adIndexInAdGroup = startAdIndexInAdGroup;
        adIndexInAdGroup <= endAdIndexInAdGroup;
        adIndexInAdGroup++) {
      inOrder.verify(mockSdkBridge).onAdStarted(adsId, adGroupIndex, adIndexInAdGroup);
      inOrder.verify(mockSdkBridge).onAdCompleted(adsId, adGroupIndex, adIndexInAdGroup);
    }
    inOrder.verify(mockSdkBridge).onAdGroupEnded(adsId, adGroupIndex);
  }

  /**
   * Creates a single-window VOD content {@link Timeline} with duration {@link
   * #CONTENT_DURATION_US}.
   */
  public static Timeline createVodContentTimeline() {
    return new FakeTimeline(
        new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .build());
  }

  /**
   * Creates a default {@link MediaSource.Factory} for ad media sources with duration {@link
   * #AD_DURATION_US}.
   */
  public static MediaSource.Factory createDefaultAdMediaSourceFactory() {
    return new FakeMediaSourceFactory(
        new TimelineWindowDefinition.Builder().setDurationUs(AD_DURATION_US));
  }

  /**
   * Creates an {@link AdsMediaSource} wrapping a {@link FakeMediaSource} built from {@code
   * contentTimeline}.
   *
   * @param contentTimeline The content {@link Timeline}.
   * @param adMediaSourceFactory The factory for creating ad {@link MediaSource} instances.
   * @param adsLoader The {@link AdsLoader} for the media source.
   * @param adViewProvider The {@link AdViewProvider} for the media source.
   * @param adsId The opaque ads identifier for the media source.
   * @return The configured {@link AdsMediaSource}.
   */
  public static AdsMediaSource createAdsMediaSource(
      Timeline contentTimeline,
      MediaSource.Factory adMediaSourceFactory,
      AdsLoader adsLoader,
      AdViewProvider adViewProvider,
      Object adsId) {
    return createAdsMediaSource(
        new FakeMediaSource(contentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
        adMediaSourceFactory,
        adsLoader,
        adViewProvider,
        adsId);
  }

  /**
   * Creates an {@link AdsMediaSource} wrapping {@code contentMediaSource}.
   *
   * @param contentMediaSource The content {@link MediaSource}.
   * @param adMediaSourceFactory The factory for creating ad {@link MediaSource} instances.
   * @param adsLoader The {@link AdsLoader} for the media source.
   * @param adViewProvider The {@link AdViewProvider} for the media source.
   * @param adsId The opaque ads identifier for the media source.
   * @return The configured {@link AdsMediaSource}.
   */
  public static AdsMediaSource createAdsMediaSource(
      MediaSource contentMediaSource,
      MediaSource.Factory adMediaSourceFactory,
      AdsLoader adsLoader,
      AdViewProvider adViewProvider,
      Object adsId) {
    MediaItem contentMediaItemWithAdsId =
        contentMediaSource
            .getMediaItem()
            .buildUpon()
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(AD_TAG_URI).setAdsId(adsId).build())
            .build();
    MediaSource wrappedContentMediaSource =
        new WrappingMediaSource(contentMediaSource) {
          @Override
          public MediaItem getMediaItem() {
            return contentMediaItemWithAdsId;
          }

          @Override
          protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
            refreshSourceInfo(
                TimelineWithUpdatedMediaItem.create(newTimeline, contentMediaItemWithAdsId));
          }
        };
    return new AdsMediaSource(
        wrappedContentMediaSource,
        /* adTagDataSpec= */ new DataSpec(AD_TAG_URI),
        adsId,
        adMediaSourceFactory,
        adsLoader,
        adViewProvider,
        /* useLazyContentSourcePreparation= */ true,
        /* useAdMediaSourceClipping= */ true);
  }

  private AdPlaybackTrackerTestUtil() {}
}
