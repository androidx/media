/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source.chunk;

import static androidx.media3.common.util.Util.createHandlerForCurrentLooper;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SequenceableLoader;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.FakeAdaptiveDataSet;
import androidx.media3.test.utils.FakeChunkSource;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.FakeTrackSelection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ChunkSampleStream}. */
@RunWith(AndroidJUnit4.class)
public final class ChunkSampleStreamTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private ChunkSource mockChunkSource;
  @Mock private SequenceableLoader.Callback<ChunkSampleStream<ChunkSource>> mockCallback;

  private DrmSessionEventListener.EventDispatcher drmEventDispatcher;
  private MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher;
  private ChunkSampleStream<ChunkSource> chunkSampleStream;
  private DefaultAllocator allocator;

  @Before
  public void setUp() {
    drmEventDispatcher = new DrmSessionEventListener.EventDispatcher();
    mediaSourceEventDispatcher = new MediaSourceEventListener.EventDispatcher();
    allocator =
        new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 65536);
    chunkSampleStream =
        new ChunkSampleStream<>(
            /* primaryTrackType= */ C.TRACK_TYPE_VIDEO,
            /* embeddedTrackTypes= */ null,
            /* embeddedTrackFormats= */ null,
            mockChunkSource,
            mockCallback,
            allocator,
            /* positionUs= */ 0,
            DrmSessionManager.DRM_UNSUPPORTED,
            drmEventDispatcher,
            new DefaultLoadErrorHandlingPolicy(),
            mediaSourceEventDispatcher,
            /* handleInitialDiscontinuity= */ false,
            /* firstChunkStartTimeUs= */ C.TIME_UNSET,
            /* downloadExecutor= */ null);
  }

  @Test
  public void seekToUs_intoCanceledChunk_fallsBackToReset() throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_MP4)
            .setAverageBitrate(1000000)
            .build();
    TrackGroup trackGroup = new TrackGroup(format);
    FakeAdaptiveDataSet.Factory dataSetFactory =
        new FakeAdaptiveDataSet.Factory(
            /* chunkDurationUs= */ 500000, /* bitratePercentStdDev= */ 0.0, new Random(0));
    FakeAdaptiveDataSet dataSet =
        dataSetFactory.createDataSet(trackGroup, /* mediaDurationUs= */ 1000000);
    FakeDataSource.Factory dataSourceFactory = new FakeDataSource.Factory();
    dataSourceFactory.setFakeDataSet(dataSet);
    FakeDataSource fakeDataSource = dataSourceFactory.createDataSource();
    FakeTrackSelection trackSelection =
        new FakeTrackSelection(trackGroup) {
          @Override
          public void updateSelectedTrack(
              long playbackPositionUs,
              long bufferedDurationUs,
              long availableDurationUs,
              List<? extends MediaChunk> queue,
              MediaChunkIterator[] mediaChunkIterators) {
            // Do nothing.
          }

          @Override
          public boolean shouldCancelChunkLoad(
              long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
            // Always return true to simulate the cancellation of the first chunk.
            return true;
          }
        };
    final BaseMediaChunkOutput[] capturedOutput = new BaseMediaChunkOutput[/* size= */ 1];
    BaseMediaChunk testChunk =
        new BaseMediaChunk(
            fakeDataSource,
            new DataSpec(Uri.parse(dataSet.getUri(0))),
            format,
            /* trackSelectionReason= */ C.SELECTION_REASON_UNKNOWN,
            /* trackSelectionData= */ null,
            /* startTimeUs= */ 0,
            /* endTimeUs= */ 1000000,
            /* clippedStartTimeUs= */ C.TIME_UNSET,
            /* clippedEndTimeUs= */ C.TIME_UNSET,
            /* chunkIndex= */ 0) {
          @Override
          public void init(BaseMediaChunkOutput output) {
            super.init(output);
            capturedOutput[/* index= */ 0] = output;
          }

          @Override
          public void cancelLoad() {}

          @Override
          public void load() throws IOException {}

          @Override
          public boolean isLoadCompleted() {
            return false;
          }
        };
    FakeChunkSource fakeChunkSource =
        new FakeChunkSource(trackSelection, fakeDataSource, dataSet) {
          @Override
          protected MediaChunk createMediaChunk(
              DataSource dataSource,
              DataSpec dataSpec,
              Format trackFormat,
              @C.SelectionReason int trackSelectionReason,
              @Nullable Object trackSelectionData,
              long startTimeUs,
              long endTimeUs,
              long chunkIndex,
              @C.TrackType int trackType,
              Format sampleFormat) {
            return testChunk;
          }
        };
    chunkSampleStream =
        new ChunkSampleStream<>(
            /* primaryTrackType= */ C.TRACK_TYPE_VIDEO,
            /* embeddedTrackTypes= */ null,
            /* embeddedTrackFormats= */ null,
            fakeChunkSource,
            mockCallback,
            allocator,
            /* positionUs= */ 0,
            DrmSessionManager.DRM_UNSUPPORTED,
            drmEventDispatcher,
            new DefaultLoadErrorHandlingPolicy(),
            mediaSourceEventDispatcher,
            /* handleInitialDiscontinuity= */ false,
            /* firstChunkStartTimeUs= */ C.TIME_UNSET,
            /* downloadExecutor= */ null);
    LoadingInfo loadingInfo =
        new LoadingInfo.Builder().setPlaybackPositionUs(/* playbackPositionUs= */ 0).build();
    assertThat(chunkSampleStream.continueLoading(loadingInfo)).isTrue();
    TrackOutput trackOutput =
        capturedOutput[/* index= */ 0].track(/* id= */ 0, /* type= */ C.TRACK_TYPE_VIDEO);
    trackOutput.format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MP4).build());
    byte[] sampleBytes = new byte[/* size= */ 10];
    ParsableByteArray data = new ParsableByteArray(sampleBytes);
    trackOutput.sampleData(data, /* length= */ 10);
    trackOutput.sampleMetadata(
        /* timeUs= */ 0,
        /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ 10,
        /* offset= */ 0,
        /* cryptoData= */ null);
    data.setPosition(/* position= */ 0);
    trackOutput.sampleData(data, /* length= */ 10);
    trackOutput.sampleMetadata(
        /* timeUs= */ 500000,
        /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ 10,
        /* offset= */ 0,
        /* cryptoData= */ null);

    // Simulate cancellation, seek into the canceled range, and delivery of cancellation event.
    chunkSampleStream.reevaluateBuffer(/* positionUs= */ 0);
    chunkSampleStream.seekToUs(/* positionUs= */ 500000);
    chunkSampleStream.onLoadCanceled(
        testChunk, /* elapsedRealtimeMs= */ 0, /* loadDurationMs= */ 0, /* released= */ false);

    // Verify that the seek fell back to reset instead of succeeding inside the buffer.
    assertThat(chunkSampleStream.isPendingReset()).isTrue();
  }

  @Test
  public void getFlags_beforeChunksLoaded_returnsFlagMaybeHasPreroll() {
    chunkSampleStream = createChunkSampleStreamHandlingInitialDiscontinuity();

    @SampleStream.Flags int flags = chunkSampleStream.getFlags();

    assertThat(flags).isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);
  }

  @Test
  public void getFlags_loadingFinishedWithoutMediaChunks_returnsZero() {
    chunkSampleStream = createChunkSampleStreamHandlingInitialDiscontinuity();
    doAnswer(
            invocation -> {
              ChunkHolder chunkHolder = invocation.getArgument(3);
              chunkHolder.endOfStream = true;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), any(), any());

    assertThat(
            chunkSampleStream.continueLoading(
                new LoadingInfo.Builder()
                    .setPlaybackPositionUs(/* playbackPositionUs= */ 0)
                    .build()))
        .isTrue();

    // No media chunk will ever be loaded, so the initial discontinuity can never be evaluated and
    // the stream must not stay stuck on FLAG_MAYBE_HAS_PREROLL.
    assertThat(chunkSampleStream.getFlags()).isEqualTo(0);
  }

  @Test
  public void onLoadError_whenRetriesExhausted_returnsDontRetryFatalAndReportsCanceled() {
    List<Boolean> wasCanceledEvents = new ArrayList<>();
    mediaSourceEventDispatcher.addEventListener(
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
    Chunk testChunk =
        new Chunk(
            new FakeDataSource(),
            new DataSpec(Uri.parse("http://example.com/chunk")),
            C.DATA_TYPE_MEDIA,
            new Format.Builder().build(),
            C.SELECTION_REASON_UNKNOWN,
            /* trackSelectionData= */ null,
            /* startTimeUs= */ 0,
            /* endTimeUs= */ 1_000_000,
            /* steeredPathwayId= */ null) {
          @Override
          public void cancelLoad() {}

          @Override
          public void load() {}
        };

    LoadErrorAction actionWithinRetryLimit =
        chunkSampleStream.onLoadError(
            testChunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 3);
    LoadErrorAction actionRetriesExhausted =
        chunkSampleStream.onLoadError(
            testChunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 4);

    assertThat(actionWithinRetryLimit.isRetry()).isTrue();
    assertThat(actionRetriesExhausted).isEqualTo(Loader.DONT_RETRY_FATAL);
    assertThat(wasCanceledEvents).containsExactly(false, true).inOrder();
  }

  @Test
  public void mayHaveInitialDiscontinuity_whenChunkSourceThrowsError_returnsFalse()
      throws Exception {
    ChunkSampleStream<ChunkSource> streamWithDiscontinuity =
        createChunkSampleStreamHandlingInitialDiscontinuity();

    boolean initialDiscontinuityBeforeError = streamWithDiscontinuity.mayHaveInitialDiscontinuity();
    int flagsBeforeError = streamWithDiscontinuity.getFlags();
    doThrow(new IOException("Manifest refresh failed")).when(mockChunkSource).maybeThrowError();
    boolean initialDiscontinuityAfterError = streamWithDiscontinuity.mayHaveInitialDiscontinuity();
    int flagsAfterError = streamWithDiscontinuity.getFlags();

    assertThat(initialDiscontinuityBeforeError).isTrue();
    assertThat(flagsBeforeError).isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);
    assertThat(initialDiscontinuityAfterError).isFalse();
    assertThat(flagsAfterError).isEqualTo(0);
  }

  private ChunkSampleStream<ChunkSource> createChunkSampleStreamHandlingInitialDiscontinuity() {
    return new ChunkSampleStream<>(
        /* primaryTrackType= */ C.TRACK_TYPE_VIDEO,
        /* embeddedTrackTypes= */ null,
        /* embeddedTrackFormats= */ null,
        mockChunkSource,
        mockCallback,
        allocator,
        /* positionUs= */ 0,
        DrmSessionManager.DRM_UNSUPPORTED,
        drmEventDispatcher,
        new DefaultLoadErrorHandlingPolicy(),
        mediaSourceEventDispatcher,
        /* handleInitialDiscontinuity= */ true,
        /* firstChunkStartTimeUs= */ C.TIME_UNSET,
        /* downloadExecutor= */ null);
  }
}
