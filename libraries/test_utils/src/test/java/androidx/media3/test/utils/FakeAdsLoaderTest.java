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
package androidx.media3.test.utils;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.net.Uri;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FakeAdsLoader}. */
@RunWith(AndroidJUnit4.class)
public final class FakeAdsLoaderTest {

  private static final Object ADS_ID = "adsId";

  @Test
  public void start_withRegisteredInitialAdPlaybackState_publishesStateToEventListener() {
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource = createAdsMediaSource(fakeAdsLoader);
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);

    fakeAdsLoader.start(
        adsMediaSource,
        new DataSpec(Uri.EMPTY),
        ADS_ID,
        /* adViewProvider= */ () -> null,
        mockEventListener);

    verify(mockEventListener).onAdPlaybackState(initialState);
  }

  @Test
  public void start_withoutRegisteredInitialAdPlaybackState_doesNotPublishStateToEventListener() {
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource = createAdsMediaSource(fakeAdsLoader);
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);

    fakeAdsLoader.start(
        adsMediaSource,
        new DataSpec(Uri.EMPTY),
        ADS_ID,
        /* adViewProvider= */ () -> null,
        mockEventListener);

    verify(mockEventListener, never()).onAdPlaybackState(any());
  }

  @Test
  public void setInitialAdPlaybackState_withMismatchedAdsId_throwsIllegalArgumentException() {
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdPlaybackState mismatchedState = new AdPlaybackState("otherAdsId");

    assertThrows(
        IllegalArgumentException.class,
        () -> fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, mismatchedState));
  }

  private static AdsMediaSource createAdsMediaSource(AdsLoader adsLoader) {
    return new AdsMediaSource(
        new FakeMediaSource(),
        new DataSpec(Uri.EMPTY),
        ADS_ID,
        new FakeMediaSourceFactory(),
        adsLoader,
        /* adViewProvider= */ () -> null,
        /* useLazyContentSourcePreparation= */ true,
        /* useAdMediaSourceClipping= */ true);
  }
}
