/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.NullableType;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.BaseTrackSelection;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.png.PngExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ProgressiveMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class ProgressiveMediaPeriodTest {

  @Test
  public void prepareUsingBundledExtractors_updatesSourceInfoBeforeOnPreparedCallback()
      throws TimeoutException {
    testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
        new BundledExtractorsAdapter(Mp4Extractor.FACTORY), C.TIME_UNSET);
  }

  @Test
  public void
      prepareUsingBundledExtractor_withImageExtractor_updatesSourceInfoBeforeOnPreparedCallback()
          throws TimeoutException {
    ExtractorsFactory pngExtractorFactory = () -> new Extractor[] {new PngExtractor()};
    testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
        new BundledExtractorsAdapter(pngExtractorFactory), 5 * C.MICROS_PER_SECOND);
  }

  @Test
  public void prepareUsingMediaParser_updatesSourceInfoBeforeOnPreparedCallback()
      throws TimeoutException {
    MediaParserExtractorAdapter extractor =
        new MediaParserExtractorAdapter.Factory().createProgressiveMediaExtractor(PlayerId.UNSET);
    testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(extractor, C.TIME_UNSET);
  }

  @Test
  public void supplyingCustomDownloadExecutor_downloadsOnCustomThread() throws TimeoutException {
    AtomicBoolean hasThreadRun = new AtomicBoolean(false);
    AtomicBoolean hasReleaseCallbackRun = new AtomicBoolean(false);
    Executor executor =
        Executors.newSingleThreadExecutor(r -> new ExecutionTrackingThread(r, hasThreadRun));

    testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
        new BundledExtractorsAdapter(Mp4Extractor.newFactory(SubtitleParser.Factory.UNSUPPORTED)),
        C.TIME_UNSET,
        executor,
        e -> hasReleaseCallbackRun.set(true));

    assertThat(hasThreadRun.get()).isTrue();
    assertThat(hasReleaseCallbackRun.get()).isTrue();
  }

  @Test
  public void readData_forUnselectedTrack_returnsFormatAndNothingElse() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            new BundledExtractorsAdapter(
                Mp4Extractor.newFactory(SubtitleParser.Factory.UNSUPPORTED)),
            /* imageDurationUs= */ C.TIME_UNSET,
            /* executor= */ null,
            /* executorReleased= */ null);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    // media/mp4/sample.mp4 has 2 tracks.
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    assertThat(trackGroups.length).isAtLeast(2);
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];

    // Select only track 0.
    selections[0] = new FakeTrackSelection(trackGroups.get(0), 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    // Run loader until source has finished loading.
    boolean unusedResult =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);

    // Read from stream 0 (selected) to check we successfully read the format and samples.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Read from stream 1 (unselected) to check we get no samples.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isTrue();

    mediaPeriod.release();
  }

  @Test
  public void getTrackGroups_multipleTracks_setsExpectedPrimaryTrackGroupId() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();

    assertThat(trackGroups.length).isEqualTo(2);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(0).getFormat(0).primaryTrackGroupId).isNull();
    assertThat(trackGroups.get(1).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(0).id);

    mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/h265_with_metadata_track.mp4"));
    trackGroups = mediaPeriod.getTrackGroups();

    assertThat(trackGroups.length).isEqualTo(3);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(0).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(2).id);
    assertThat(trackGroups.get(1).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(2).id);
    assertThat(trackGroups.get(2).getFormat(0).primaryTrackGroupId).isNull();

    mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_with_vobsub.mp4"));
    trackGroups = mediaPeriod.getTrackGroups();

    assertThat(trackGroups.length).isEqualTo(3);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).type).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(trackGroups.get(0).getFormat(0).primaryTrackGroupId).isNull();
    assertThat(trackGroups.get(1).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(0).id);
    assertThat(trackGroups.get(2).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(0).id);

    mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/ts/sample_with_id3.adts"));
    trackGroups = mediaPeriod.getTrackGroups();

    assertThat(trackGroups.length).isEqualTo(2);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(trackGroups.get(0).getFormat(0).primaryTrackGroupId).isNull();
    assertThat(trackGroups.get(1).getFormat(0).primaryTrackGroupId)
        .isEqualTo(trackGroups.get(0).id);
  }

  private static @SampleStream.ReadDataResult int readProgressiveStream(
      ProgressiveMediaPeriod mediaPeriod, int trackIndex, DecoderInputBuffer buffer) {
    return mediaPeriod.readData(trackIndex, new FormatHolder(), buffer, /* readFlags= */ 0);
  }

  private static ProgressiveMediaPeriod createMediaPeriod(
      ProgressiveMediaExtractor extractor,
      long imageDurationUs,
      @Nullable Executor executor,
      @Nullable Consumer<Executor> executorReleased)
      throws TimeoutException {
    return createMediaPeriod(
        Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
        extractor,
        imageDurationUs,
        executor,
        executorReleased);
  }

  private static ProgressiveMediaPeriod createMediaPeriod(Uri uri) throws TimeoutException {
    return createMediaPeriod(
        uri,
        new BundledExtractorsAdapter(new DefaultExtractorsFactory()),
        /* imageDurationUs= */ C.TIME_UNSET,
        /* executor= */ null,
        /* executorReleased= */ null);
  }

  private static ProgressiveMediaPeriod createMediaPeriod(
      Uri uri,
      ProgressiveMediaExtractor extractor,
      long imageDurationUs,
      @Nullable Executor executor,
      @Nullable Consumer<Executor> executorReleased)
      throws TimeoutException {
    AtomicBoolean sourceInfoRefreshCalled = new AtomicBoolean(false);
    ProgressiveMediaPeriod.Listener sourceInfoRefreshListener =
        (durationUs, seekMap, isLive) -> sourceInfoRefreshCalled.set(true);
    MediaPeriodId mediaPeriodId = new MediaPeriodId(/* periodUid= */ new Object());
    ProgressiveMediaPeriod mediaPeriod =
        new ProgressiveMediaPeriod(
            uri,
            new AssetDataSource(ApplicationProvider.getApplicationContext()),
            extractor,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            new DefaultLoadErrorHandlingPolicy(),
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            sourceInfoRefreshListener,
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            /* customCacheKey= */ null,
            ProgressiveMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES,
            /* loadOnlySelectedTracks= */ true,
            /* singleTrackId= */ 0,
            /* singleTrackFormat= */ null,
            imageDurationUs,
            executor != null ? ReleasableExecutor.from(executor, executorReleased) : null);

    AtomicBoolean prepareCallbackCalled = new AtomicBoolean(false);
    AtomicBoolean sourceInfoRefreshCalledBeforeOnPrepared = new AtomicBoolean(false);
    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            sourceInfoRefreshCalledBeforeOnPrepared.set(sourceInfoRefreshCalled.get());
            prepareCallbackCalled.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            source.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
          }
        },
        /* positionUs= */ 0);
    runMainLooperUntil(prepareCallbackCalled::get);

    assertThat(sourceInfoRefreshCalledBeforeOnPrepared.get()).isTrue();
    return mediaPeriod;
  }

  private static void testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
      ProgressiveMediaExtractor extractor, long imageDurationUs) throws TimeoutException {
    testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
        extractor, imageDurationUs, /* executor= */ null, /* executorReleased= */ null);
  }

  private static void testExtractorsUpdatesSourceInfoBeforeOnPreparedCallback(
      ProgressiveMediaExtractor extractor,
      long imageDurationUs,
      @Nullable Executor executor,
      @Nullable Consumer<Executor> executorReleased)
      throws TimeoutException {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(extractor, imageDurationUs, executor, executorReleased);
    mediaPeriod.release();
  }

  private static final class ExecutionTrackingThread extends Thread {
    private final AtomicBoolean hasRun;

    public ExecutionTrackingThread(Runnable runnable, AtomicBoolean hasRun) {
      super(runnable, "TestExecutionTrackingThread");
      this.hasRun = hasRun;
    }

    @Override
    public void run() {
      hasRun.set(true);
      super.run();
    }
  }

  public static final class FakeTrackSelection extends BaseTrackSelection {

    public FakeTrackSelection(TrackGroup group, int... tracks) {
      super(group, tracks);
    }

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
    public int getSelectedIndex() {
      return 0;
    }

    @Override
    public int getSelectionReason() {
      return C.SELECTION_REASON_UNKNOWN;
    }

    @Nullable
    @Override
    public Object getSelectionData() {
      return null;
    }
  }
}
