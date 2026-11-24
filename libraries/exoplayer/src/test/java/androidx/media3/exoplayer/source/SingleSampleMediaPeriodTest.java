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
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
              MediaSource.@Nullable MediaPeriodId mediaPeriodId,
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
              MediaSource.@Nullable MediaPeriodId mediaPeriodId,
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

  @Test
  public void setEndPositionUs_positive_returnsEndPositionAndReadsSample() throws Exception {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();
    SingleSampleMediaPeriod mediaPeriod = createLoadedMediaPeriod(format, new byte[] {1, 2, 3, 4});
    SampleStream stream = selectSingleTrack(mediaPeriod);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(/* endPositionUs= */ 1_000_000L);
    int formatResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    int sampleResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    long sampleTimeUs = buffer.timeUs;
    boolean sampleIsEndOfStream = buffer.isEndOfStream();
    buffer.clear();
    int eosResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    boolean eosFlagSet = buffer.isEndOfStream();
    mediaPeriod.release();

    assertThat(actualEndPositionUs).isEqualTo(1_000_000L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format).isEqualTo(format);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_zero_returnsZeroAndReadsSample() throws Exception {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();
    SingleSampleMediaPeriod mediaPeriod = createLoadedMediaPeriod(format, new byte[] {1, 2, 3, 4});
    SampleStream stream = selectSingleTrack(mediaPeriod);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(/* endPositionUs= */ 0L);
    int formatResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    int sampleResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    long sampleTimeUs = buffer.timeUs;
    boolean sampleIsEndOfStream = buffer.isEndOfStream();
    buffer.clear();
    int eosResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    boolean eosFlagSet = buffer.isEndOfStream();
    mediaPeriod.release();

    assertThat(actualEndPositionUs).isEqualTo(0L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_endOfSource_returnsEndOfSource() throws Exception {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();
    SingleSampleMediaPeriod mediaPeriod = createLoadedMediaPeriod(format, new byte[] {1, 2, 3, 4});

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(C.TIME_END_OF_SOURCE);
    mediaPeriod.release();

    assertThat(actualEndPositionUs).isEqualTo(C.TIME_END_OF_SOURCE);
  }

  private static SingleSampleMediaPeriod createLoadedMediaPeriod(Format format, byte[] sampleBytes)
      throws Exception {
    SingleSampleMediaPeriod mediaPeriod =
        new SingleSampleMediaPeriod(
            new DataSpec(Uri.parse("http://example.com/subtitle.vtt")),
            () -> new ByteArrayDataSource(sampleBytes),
            /* transferListener= */ null,
            format,
            /* durationUs= */ 10_000_000L,
            new DefaultLoadErrorHandlingPolicy(),
            new EventDispatcher(),
            /* treatLoadErrorsAsEndOfStream= */ false,
            /* downloadExecutor= */ null);
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
    runMainLooperUntil(() -> mediaPeriod.loadingFinished);
    return mediaPeriod;
  }

  private static SampleStream selectSingleTrack(SingleSampleMediaPeriod mediaPeriod) {
    SampleStream[] streams = new SampleStream[1];
    assertThat(
            mediaPeriod.selectTracks(
                new ExoTrackSelection[] {
                  new FixedTrackSelection(mediaPeriod.getTrackGroups().get(0), /* track= */ 0)
                },
                /* mayRetainStreamFlags= */ new boolean[] {false},
                streams,
                /* streamResetFlags= */ new boolean[] {false},
                /* positionUs= */ 0))
        .isEqualTo(0L);
    return streams[0];
  }
}
