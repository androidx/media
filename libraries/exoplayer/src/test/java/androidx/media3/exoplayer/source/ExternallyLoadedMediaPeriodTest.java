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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExternallyLoadedMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class ExternallyLoadedMediaPeriodTest {

  private static final Uri TEST_URI = Uri.parse("http://example.com/image.jpg");

  @Test
  public void setEndPositionUs_positive_returnsEndPositionAndReadsSample() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();
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
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(1_000_000L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format.sampleMimeType)
        .isEqualTo(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_zero_returnsZeroAndReadsSample() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();
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
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(0L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_endOfSource_returnsEndOfSource() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(C.TIME_END_OF_SOURCE);
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(C.TIME_END_OF_SOURCE);
  }

  @Test
  public void isLoading_beforePrepare_returnsFalse() {
    ExternallyLoadedMediaPeriod mediaPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI,
            MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE,
            loadRequest -> SettableFuture.create());

    boolean isLoading = mediaPeriod.isLoading();
    mediaPeriod.releasePeriod();

    assertThat(isLoading).isFalse();
  }

  // The load must already be reported via isLoading() when the player handles onPrepared, as the
  // player evaluates Player.isLoading() for this period based on isLoading() alone.
  @Test
  public void prepare_startsLoadingBeforeOnPrepared() {
    SettableFuture<Object> loadFuture = SettableFuture.create();
    AtomicBoolean isLoadingInOnPrepared = new AtomicBoolean();
    ExternallyLoadedMediaPeriod mediaPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI, MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE, loadRequest -> loadFuture);

    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod period) {
            isLoadingInOnPrepared.set(period.isLoading());
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        },
        /* positionUs= */ 0L);
    loadFuture.set(new Object());
    boolean isLoadingAfterCompletion = mediaPeriod.isLoading();
    mediaPeriod.releasePeriod();

    assertThat(isLoadingInOnPrepared.get()).isTrue();
    assertThat(isLoadingAfterCompletion).isFalse();
  }

  @Test
  public void isLoading_afterLoadFailure_returnsFalseAndStreamThrowsError() {
    RuntimeException loadException = new RuntimeException("Load failed");
    SettableFuture<Object> loadFuture = SettableFuture.create();
    ExternallyLoadedMediaPeriod mediaPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI, MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE, loadRequest -> loadFuture);
    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod period) {}

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        },
        /* positionUs= */ 0L);
    SampleStream stream = selectSingleTrack(mediaPeriod);

    boolean isLoadingWhilePending = mediaPeriod.isLoading();
    loadFuture.setException(loadException);
    boolean isLoadingAfterFailure = mediaPeriod.isLoading();
    IOException thrown = assertThrows(IOException.class, stream::maybeThrowError);
    mediaPeriod.releasePeriod();

    assertThat(isLoadingWhilePending).isTrue();
    assertThat(isLoadingAfterFailure).isFalse();
    assertThat(thrown).hasCauseThat().isSameInstanceAs(loadException);
  }

  @Test
  public void continueLoading_inMergingMediaPeriodWithPendingAndFailedFuture_doesNotLoop() {
    SettableFuture<Object> loadFuture = SettableFuture.create();
    ExternallyLoadedMediaPeriod externallyLoadedPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI, MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE, loadRequest -> loadFuture);
    FakeMediaPeriod siblingPeriod =
        new FakeMediaPeriod(
            new TrackGroupArray(
                new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())),
            new DefaultAllocator(/* trimOnReset= */ false, /* individualAllocationSize= */ 1024),
            /* singleSampleTimeUs= */ 0L,
            new EventDispatcher()
                .withParameters(/* windowIndex= */ 0, new MediaPeriodId(new Object()))) {
          @Override
          public long getNextLoadPositionUs() {
            return 1_000_000L;
          }

          @Override
          public boolean continueLoading(LoadingInfo loadingInfo) {
            return false;
          }
        };
    MergingMediaPeriod mergingMediaPeriod =
        new MergingMediaPeriod(
            new DefaultCompositeSequenceableLoaderFactory(),
            /* periodTimeOffsetsUs= */ new long[] {0, 0},
            externallyLoadedPeriod,
            siblingPeriod);
    mergingMediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod period) {}

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        },
        /* positionUs= */ 0L);
    SampleStream[] streams = new SampleStream[2];
    assertThat(
            mergingMediaPeriod.selectTracks(
                new ExoTrackSelection[] {
                  new FixedTrackSelection(
                      mergingMediaPeriod.getTrackGroups().get(0), /* track= */ 0),
                  new FixedTrackSelection(
                      mergingMediaPeriod.getTrackGroups().get(1), /* track= */ 0)
                },
                /* mayRetainStreamFlags= */ new boolean[] {false, false},
                streams,
                /* streamResetFlags= */ new boolean[] {false, false},
                /* positionUs= */ 0L))
        .isEqualTo(0L);
    LoadingInfo loadingInfo = new LoadingInfo.Builder().setPlaybackPositionUs(0L).build();

    boolean directProgressWhilePending = externallyLoadedPeriod.continueLoading(loadingInfo);
    boolean mergedProgressWhilePending = mergingMediaPeriod.continueLoading(loadingInfo);
    long nextLoadPositionWhilePending = externallyLoadedPeriod.getNextLoadPositionUs();
    long mergedNextLoadPositionWhilePending = mergingMediaPeriod.getNextLoadPositionUs();
    boolean mergedIsLoadingWhilePending = mergingMediaPeriod.isLoading();
    loadFuture.setException(new RuntimeException("Load failed"));
    boolean directProgressAfterFailure = externallyLoadedPeriod.continueLoading(loadingInfo);
    boolean mergedProgressAfterFailure = mergingMediaPeriod.continueLoading(loadingInfo);
    boolean mergedIsLoadingAfterFailure = mergingMediaPeriod.isLoading();
    siblingPeriod.release();
    externallyLoadedPeriod.releasePeriod();

    assertThat(directProgressWhilePending).isFalse();
    assertThat(mergedProgressWhilePending).isFalse();
    assertThat(nextLoadPositionWhilePending).isEqualTo(C.TIME_END_OF_SOURCE);
    assertThat(mergedNextLoadPositionWhilePending).isEqualTo(1_000_000L);
    assertThat(mergedIsLoadingWhilePending).isTrue();
    assertThat(directProgressAfterFailure).isFalse();
    assertThat(mergedProgressAfterFailure).isFalse();
    assertThat(mergedIsLoadingAfterFailure).isFalse();
  }

  private static ExternallyLoadedMediaPeriod createPreparedLoadedMediaPeriod() {
    ExternallyLoadedMediaPeriod mediaPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI,
            MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE,
            loadRequest -> Futures.immediateVoidFuture());
    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod period) {}

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        },
        /* positionUs= */ 0L);
    return mediaPeriod;
  }

  private static SampleStream selectSingleTrack(ExternallyLoadedMediaPeriod mediaPeriod) {
    SampleStream[] streams = new SampleStream[1];
    assertThat(
            mediaPeriod.selectTracks(
                new ExoTrackSelection[] {
                  new FixedTrackSelection(mediaPeriod.getTrackGroups().get(0), /* track= */ 0)
                },
                /* mayRetainStreamFlags= */ new boolean[] {false},
                streams,
                /* streamResetFlags= */ new boolean[] {false},
                /* positionUs= */ 0L))
        .isEqualTo(0L);
    return streams[0];
  }
}
