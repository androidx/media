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
package androidx.media3.exoplayer.source;

import static androidx.media3.common.util.Util.createHandlerForCurrentLooper;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link SingleSampleMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class SingleSampleMediaPeriodTest {

  @Test
  public void onLoadError_treatLoadErrorsAsEndOfStreamTrue_stopsRetryingAfterMinimumRetryCount() {
    EventDispatcher eventDispatcher = new EventDispatcher();
    List<Boolean> wasCanceledEvents = new ArrayList<>();
    eventDispatcher.addEventListener(
        createHandlerForCurrentLooper(),
        new MediaSourceEventListener() {
          @Override
          public void onLoadError(
              int windowIndex,
              @Nullable MediaSource.MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            wasCanceledEvents.add(wasCanceled);
          }
        });
    DataSpec dataSpec = new DataSpec(Uri.parse("http://example.com/subtitle.vtt"));
    FakeDataSource fakeDataSource = new FakeDataSource();
    SingleSampleMediaPeriod mediaPeriod =
        new SingleSampleMediaPeriod(
            dataSpec,
            () -> fakeDataSource,
            /* transferListener= */ null,
            new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            /* durationUs= */ 10_000_000L,
            new DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 3),
            eventDispatcher,
            /* treatLoadErrorsAsEndOfStream= */ true,
            /* downloadExecutor= */ null);
    SingleSampleMediaPeriod.SourceLoadable loadable =
        new SingleSampleMediaPeriod.SourceLoadable(dataSpec, fakeDataSource);

    // At errorCount == 3 (minimumLoadableRetryCount), it should still retry.
    Loader.LoadErrorAction actionAtMinRetryCount =
        mediaPeriod.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 3);
    boolean loadingFinishedAtMinRetry = mediaPeriod.loadingFinished;
    // At errorCount == 4 (> minimumLoadableRetryCount), it should treat as end of stream.
    Loader.LoadErrorAction actionExhausted =
        mediaPeriod.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 4);
    boolean loadingFinishedAfterExhausted = mediaPeriod.loadingFinished;
    mediaPeriod.release();

    assertThat(actionAtMinRetryCount.isRetry()).isTrue();
    assertThat(loadingFinishedAtMinRetry).isFalse();
    assertThat(actionExhausted).isEqualTo(Loader.DONT_RETRY);
    assertThat(loadingFinishedAfterExhausted).isTrue();
    assertThat(wasCanceledEvents).containsExactly(false, true).inOrder();
  }

  @Test
  public void
      onLoadError_treatLoadErrorsAsEndOfStreamFalse_returnsDontRetryFatalAfterMinimumRetryCount() {
    EventDispatcher eventDispatcher = new EventDispatcher();
    List<Boolean> wasCanceledEvents = new ArrayList<>();
    eventDispatcher.addEventListener(
        createHandlerForCurrentLooper(),
        new MediaSourceEventListener() {
          @Override
          public void onLoadError(
              int windowIndex,
              @Nullable MediaSource.MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            wasCanceledEvents.add(wasCanceled);
          }
        });
    DataSpec dataSpec = new DataSpec(Uri.parse("http://example.com/subtitle.vtt"));
    FakeDataSource fakeDataSource = new FakeDataSource();
    SingleSampleMediaPeriod mediaPeriod =
        new SingleSampleMediaPeriod(
            dataSpec,
            () -> fakeDataSource,
            /* transferListener= */ null,
            new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            /* durationUs= */ 10_000_000L,
            new DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 3),
            eventDispatcher,
            /* treatLoadErrorsAsEndOfStream= */ false,
            /* downloadExecutor= */ null);
    SingleSampleMediaPeriod.SourceLoadable loadable =
        new SingleSampleMediaPeriod.SourceLoadable(dataSpec, fakeDataSource);

    // At errorCount == 3 (minimumLoadableRetryCount), it should still retry.
    Loader.LoadErrorAction actionAtMinRetryCount =
        mediaPeriod.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 3);
    boolean loadingFinishedAtMinRetry = mediaPeriod.loadingFinished;
    // At errorCount == 4 (> minimumLoadableRetryCount), it should return DONT_RETRY_FATAL.
    Loader.LoadErrorAction actionExhausted =
        mediaPeriod.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 4);
    boolean loadingFinishedAfterExhausted = mediaPeriod.loadingFinished;
    mediaPeriod.release();

    assertThat(actionAtMinRetryCount.isRetry()).isTrue();
    assertThat(loadingFinishedAtMinRetry).isFalse();
    assertThat(actionExhausted).isEqualTo(Loader.DONT_RETRY_FATAL);
    assertThat(loadingFinishedAfterExhausted).isFalse();
    assertThat(wasCanceledEvents).containsExactly(false, true).inOrder();
  }
}
