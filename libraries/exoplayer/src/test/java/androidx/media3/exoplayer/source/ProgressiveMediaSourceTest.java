/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.DEFAULT_TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.test.utils.MediaSourceTestRunner;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ProgressiveMediaSource}. */
@RunWith(AndroidJUnit4.class)
public class ProgressiveMediaSourceTest {

  @Test
  public void canUpdateMediaItem_withIrrelevantFieldsChanged_returnsTrue() {
    MediaItem initialMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setCustomCacheKey("cache").build();
    MediaItem updatedMediaItem =
        TestUtil.buildFullyCustomizedMediaItem()
            .buildUpon()
            .setUri("http://test.test")
            .setCustomCacheKey("cache")
            .build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isTrue();
  }

  @Test
  public void canUpdateMediaItem_withNullLocalConfiguration_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setMediaId("id").build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedUri_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setUri("http://test2.test").build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedCustomCacheKey_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setCustomCacheKey("old").build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setCustomCacheKey("new").build();
    MediaSource mediaSource = buildMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void updateMediaItem_createsTimelineWithUpdatedItem() throws Exception {
    MediaItem initialMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag1").build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag2").build();
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

  @Test
  public void maybeThrowPrepareError_withSuppressPrepareError_doesNotThrow() throws Exception {
    ProgressiveMediaSource mediaSource =
        new ProgressiveMediaSource.Factory(
                new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
            // Disable retries, so the first error is marked fatal.
            .setLoadErrorHandlingPolicy(
                new DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 0))
            .setSuppressPrepareError(true)
            .createMediaSource(MediaItem.fromUri("file:///not/found"));
    MediaSourceTestRunner mediaSourceTestRunner = new MediaSourceTestRunner(mediaSource);

    Timeline timeline = mediaSourceTestRunner.prepareSource();
    CountDownLatch loadErrorReported = new CountDownLatch(1);
    mediaSourceTestRunner.runOnPlaybackThread(
        () ->
            mediaSource.addEventListener(
                Util.createHandlerForCurrentLooper(),
                new MediaSourceEventListener() {
                  @Override
                  public void onLoadError(
                      int windowIndex,
                      @Nullable MediaSource.MediaPeriodId mediaPeriodId,
                      LoadEventInfo loadEventInfo,
                      MediaLoadData mediaLoadData,
                      IOException error,
                      boolean wasCanceled) {
                    loadErrorReported.countDown();
                  }
                }));
    MediaPeriod mediaPeriod =
        mediaSourceTestRunner.createPeriod(
            new MediaSource.MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    CountDownLatch preparedLatch =
        mediaSourceTestRunner.preparePeriod(mediaPeriod, /* positionUs= */ 0);
    assertThat(loadErrorReported.await(DEFAULT_TIMEOUT_MS, MILLISECONDS)).isTrue();
    // Call maybeThrowPrepareError() in a loop until preparation completes (preparation is not
    // unblocked until the error is caught and suppressed inside maybeThrowPrepareError()). This
    // mimics the behaviour of ExoPlayerImplInternal which calls maybeThrowPrepareError() on
    // un-prepared MediaPeriods on every doSomeWork() iteration.
    long startTime = SystemClock.elapsedRealtime();
    do {
      AtomicReference<Throwable> prepareError = new AtomicReference<>();
      mediaSourceTestRunner.runOnPlaybackThread(
          () -> {
            try {
              mediaPeriod.maybeThrowPrepareError();
            } catch (Throwable e) {
              prepareError.set(e);
            }
          });
      assertThat(prepareError.get()).isNull();
    } while (preparedLatch.getCount() > 0
        && (SystemClock.elapsedRealtime() - startTime) < DEFAULT_TIMEOUT_MS);
    assertThat(preparedLatch.await(DEFAULT_TIMEOUT_MS, MILLISECONDS)).isTrue();

    mediaSourceTestRunner.releasePeriod(mediaPeriod);
    mediaSourceTestRunner.releaseSource();
    mediaSourceTestRunner.release();
  }

  private static MediaSource buildMediaSource(MediaItem mediaItem) {
    return new ProgressiveMediaSource.Factory(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()))
        .createMediaSource(mediaItem);
  }
}
