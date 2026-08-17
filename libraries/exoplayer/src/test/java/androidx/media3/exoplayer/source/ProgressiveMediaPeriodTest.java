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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.NullableType;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.png.PngExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeTrackSelection;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

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
  @Config(minSdk = 30) // MediaParser is only available on API 30+.
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

    // Select only track 1 (audio). Track 0 (video) is left unselected.
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);
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

    // Read from stream 1 (selected audio) to check we successfully read the format and samples.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Read from stream 0 (unselected video) to check we get no samples (or at most in-flight
    // samples from before track selection).
    assertThat(readProgressiveStreamUntilEndOfStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isAtMost(1);
    mediaPeriod.release();
  }

  @Test
  public void readData_unselectedTextTrack_returnsSamples() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_with_vobsub.mp4"));
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    assertThat(trackGroups.length).isEqualTo(3);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).type).isEqualTo(C.TRACK_TYPE_TEXT);
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    assertThat(streams[0]).isNull();
    assertThat(streams[1]).isNotNull();
    assertThat(streams[2]).isNull();
    boolean unusedResult =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);

    // Audio track (selected) returns samples.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Text track (unselected) returns samples because audio, text, and metadata tracks are always
    // loaded.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 2, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 2, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Video track (unselected) returns no samples (or at most in-flight samples from before track
    // selection).
    assertThat(readProgressiveStreamUntilEndOfStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isAtMost(1);
    mediaPeriod.release();
  }

  @Test
  public void readData_unselectedAudioAndTextTracks_returnsSamples() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_with_vobsub.mp4"));
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    assertThat(trackGroups.length).isEqualTo(3);
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).type).isEqualTo(C.TRACK_TYPE_TEXT);
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    assertThat(streams[0]).isNotNull();
    assertThat(streams[1]).isNull();
    assertThat(streams[2]).isNull();
    boolean unusedResult =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);

    // Video track (selected) returns samples.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Audio track (unselected) returns samples because audio tracks are always loaded.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    // Text track (unselected) returns samples because text tracks are always loaded.
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 2, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 2, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    mediaPeriod.release();
  }

  @Test
  public void selectTracks_disablingAllTracksAfterFatalError_clearsFatalError() throws Exception {
    FatalErrorDataSource dataSource =
        new FatalErrorDataSource(new AssetDataSource(ApplicationProvider.getApplicationContext()));
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            dataSource,
            new DefaultLoadErrorHandlingPolicy());

    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    // Select a track so continueLoading starts sample loading.
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    // Wait until initial loading is complete before testing fatal error behavior.
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    // Reset track selection when idle so continueLoading can start a fresh load.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    dataSource.setThrowErrorAfterPrepare(true);
    unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          try {
            mediaPeriod.maybeThrowPrepareError();
            return false;
          } catch (IOException e) {
            return true;
          }
        });
    assertThrows(ParserException.class, mediaPeriod::maybeThrowPrepareError);
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();

    // Disabling all tracks clears the fatal error state on the loader.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    mediaPeriod.maybeThrowPrepareError();
    dataSource.setThrowErrorAfterPrepare(false);
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
    mediaPeriod.release();
  }

  @Test
  public void seekToUs_afterFatalError_clearsFatalError() throws Exception {
    FatalErrorDataSource dataSource =
        new FatalErrorDataSource(new AssetDataSource(ApplicationProvider.getApplicationContext()));
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            dataSource,
            new DefaultLoadErrorHandlingPolicy());

    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    // Select a track so continueLoading starts sample loading.
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    // Wait until initial loading is complete before testing fatal error behavior.
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    // Reset track selection when idle so continueLoading can start a fresh load.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    dataSource.setThrowErrorAfterPrepare(true);
    unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          try {
            mediaPeriod.maybeThrowPrepareError();
            return false;
          } catch (IOException e) {
            return true;
          }
        });
    assertThrows(ParserException.class, mediaPeriod::maybeThrowPrepareError);
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();

    // Seeking outside the buffer clears the fatal error state on the loader.
    long unusedSeek = mediaPeriod.seekToUs(1000);
    mediaPeriod.maybeThrowPrepareError();
    dataSource.setThrowErrorAfterPrepare(false);

    assertThat(
            mediaPeriod.continueLoading(
                new LoadingInfo.Builder().setPlaybackPositionUs(1000).build()))
        .isTrue();
    mediaPeriod.release();
  }

  @Test
  public void selectTracks_disablingAllTracksWhenIdle_resetsLoadingFinished() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    // Select a track so continueLoading starts sample loading.
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    // When idle (loading finished), continueLoading returns false.
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();

    // Disabling all tracks when idle resets loadingFinished.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
    mediaPeriod.release();
  }

  @Test
  public void selectTracks_disablingAllTracksWhenLoading_cancelsLoading() throws Exception {
    ConditionVariable blockLoadingCondition = new ConditionVariable();
    ConditionVariable loadingStartedCondition = new ConditionVariable();
    blockLoadingCondition.open();
    AssetDataSource assetDataSource =
        new AssetDataSource(ApplicationProvider.getApplicationContext());
    DataSource dataSource =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {
            assetDataSource.addTransferListener(transferListener);
          }

          @Override
          public long open(DataSpec dataSpec) throws IOException {
            return assetDataSource.open(dataSpec);
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            loadingStartedCondition.open();
            blockLoadingCondition.blockUninterruptible();
            return assetDataSource.read(buffer, offset, length);
          }

          @Nullable
          @Override
          public Uri getUri() {
            return assetDataSource.getUri();
          }

          @Override
          public Map<String, List<String>> getResponseHeaders() {
            return assetDataSource.getResponseHeaders();
          }

          @Override
          public void close() throws IOException {
            assetDataSource.close();
          }
        };
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            dataSource,
            new DefaultLoadErrorHandlingPolicy());
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    blockLoadingCondition.close();
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
    loadingStartedCondition.block();
    assertThat(mediaPeriod.isLoading()).isTrue();

    // Disabling all tracks while loading starts canceling.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();

    blockLoadingCondition.open();
    runMainLooperUntil(() -> !mediaPeriod.isLoading());
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
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

  @Test
  public void selectTracks_joiningStreamWithPrerollSamples_doesNotIncurExtraDiscontinuity()
      throws Exception {
    ProgressiveMediaExtractor extractor =
        new ProgressiveMediaExtractor() {
          @Override
          public void init(
              DataReader dataReader,
              Uri uri,
              Map<String, List<String>> responseHeaders,
              long position,
              long length,
              ExtractorOutput output) {
            // Provide custom formats that have pre-roll samples.
            output
                .track(0, C.TRACK_TYPE_AUDIO)
                .format(
                    new Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setHasPrerollSamples(true)
                        .build());
            output
                .track(1, C.TRACK_TYPE_VIDEO)
                .format(
                    new Format.Builder()
                        .setSampleMimeType(MimeTypes.VIDEO_H264)
                        .setHasPrerollSamples(true)
                        .build());
            output.endTracks();
            output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
          }

          @Override
          public void release() {}

          @Override
          public void disableSeekingOnMp3Streams() {}

          @Override
          public long getCurrentInputPosition() {
            return 0;
          }

          @Override
          public void seek(long position, long timeUs) {}

          @Override
          public int read(PositionHolder positionHolder) {
            return Extractor.RESULT_END_OF_INPUT;
          }
        };

    // Create the period using the custom extractor.
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            extractor,
            /* imageDurationUs= */ C.TIME_UNSET,
            /* executor= */ null,
            /* executorReleased= */ null);

    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];

    // 1. Initially select only the audio track (track 0).
    selections[0] = new FakeTrackSelection(trackGroups.get(0), 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            /* mayRetainStreamFlags= */ new boolean[2],
            streams,
            /* streamResetFlags= */ new boolean[2],
            /* positionUs= */ 0);

    // Verify initial discontinuity for first track selection.
    assertThat(mediaPeriod.readDiscontinuity()).isEqualTo(0);

    // 2. Simulate seeking to a non-zero position.
    long seekPositionUs = 5 * C.MICROS_PER_SECOND;
    unused = mediaPeriod.seekToUs(seekPositionUs);

    // 3. Join the video stream (track 1) at the non-zero position.
    selections[1] = new FakeTrackSelection(trackGroups.get(1), 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            /* mayRetainStreamFlags= */ new boolean[2],
            streams,
            /* streamResetFlags= */ new boolean[2],
            /* positionUs= */ seekPositionUs);

    // Verify no extra discontinuity when selecting the second stream.
    assertThat(mediaPeriod.readDiscontinuity()).isEqualTo(C.TIME_UNSET);

    mediaPeriod.release();
  }

  @Test
  public void selectTracks_withHagcTrack_createsMergingMetadataSampleStream() throws Exception {
    assumeTrue("Skipping HAGC test on SDK < 37", SDK_INT >= 37);
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_with_it35_track.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    SampleStream[] streams = new SampleStream[trackGroups.length];
    // Select the video track (which is track 0 in sample_with_it35_track.mp4)
    selections[0] = new FakeTrackSelection(trackGroups.get(0), 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            new boolean[trackGroups.length],
            /* positionUs= */ 0);

    assertThat(streams[0]).isInstanceOf(MergingMetadataSampleStream.class);
    mediaPeriod.release();
  }

  @Test
  public void selectTracks_switchingToTrackOfSameType_retainsBuffer() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_ac3_aac.mp4"));
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    assertThat(trackGroups.length).isEqualTo(2);
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedResult =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);

    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 0, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();

    selections[0] = null;
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[] {true, false},
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(readProgressiveStream(mediaPeriod, /* trackIndex= */ 1, buffer))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isFalse();
    mediaPeriod.release();
  }

  private static @SampleStream.ReadDataResult int readProgressiveStream(
      ProgressiveMediaPeriod mediaPeriod, int trackIndex, DecoderInputBuffer buffer) {
    return mediaPeriod.readData(trackIndex, new FormatHolder(), buffer, /* readFlags= */ 0);
  }

  private static int readProgressiveStreamUntilEndOfStream(
      ProgressiveMediaPeriod mediaPeriod, int trackIndex, DecoderInputBuffer buffer) {
    int readResult;
    int sampleCount = 0;
    do {
      readResult = readProgressiveStream(mediaPeriod, trackIndex, buffer);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        sampleCount++;
      }
    } while (readResult != C.RESULT_BUFFER_READ || !buffer.isEndOfStream());
    return sampleCount;
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
        new AssetDataSource(ApplicationProvider.getApplicationContext()),
        new DefaultLoadErrorHandlingPolicy());
  }

  private static ProgressiveMediaPeriod createMediaPeriod(
      Uri uri,
      ProgressiveMediaExtractor extractor,
      long imageDurationUs,
      @Nullable Executor executor,
      @Nullable Consumer<Executor> executorReleased)
      throws TimeoutException {
    return createMediaPeriod(
        uri,
        new AssetDataSource(ApplicationProvider.getApplicationContext()),
        new DefaultLoadErrorHandlingPolicy(),
        extractor,
        imageDurationUs,
        executor,
        executorReleased);
  }

  private static ProgressiveMediaPeriod createMediaPeriod(
      Uri uri, DataSource dataSource, LoadErrorHandlingPolicy loadErrorHandlingPolicy)
      throws TimeoutException {
    return createMediaPeriod(
        uri,
        dataSource,
        loadErrorHandlingPolicy,
        new BundledExtractorsAdapter(new DefaultExtractorsFactory()),
        /* imageDurationUs= */ C.TIME_UNSET,
        /* executor= */ null,
        /* executorReleased= */ null);
  }

  private static ProgressiveMediaPeriod createMediaPeriod(
      Uri uri,
      DataSource dataSource,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
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
            dataSource,
            extractor,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            loadErrorHandlingPolicy,
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

  @Test
  public void setEndPositionUs_beforePreparation_setsEndPositionAndReturnsEarly() throws Exception {
    MediaPeriodId mediaPeriodId = new MediaPeriodId(/* periodUid= */ new Object());
    ProgressiveMediaPeriod mediaPeriod =
        new ProgressiveMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            new AssetDataSource(ApplicationProvider.getApplicationContext()),
            new BundledExtractorsAdapter(new DefaultExtractorsFactory()),
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            new DefaultLoadErrorHandlingPolicy(),
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            /* listener= */ null,
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            /* customCacheKey= */ null,
            ProgressiveMediaSource.DEFAULT_LOADING_CHECK_INTERVAL_BYTES,
            /* loadOnlySelectedTracks= */ true,
            /* singleTrackId= */ 0,
            /* singleTrackFormat= */ null,
            /* singleSampleDurationUs= */ C.TIME_UNSET,
            /* downloadExecutor= */ null);

    long resultEndPositionUs = mediaPeriod.setEndPositionUs(500_000);

    assertThat(resultEndPositionUs).isEqualTo(500_000);
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_loweredMidPlayback_discardsUpstreamBuffers() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    long unusedEndPosition = mediaPeriod.setEndPositionUs(300_000);

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_loweredWhileLoading_defersUpstreamDiscardUntilLoadCanceled()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    assertThat(mediaPeriod.isLoading()).isTrue();

    long unusedEndPosition = mediaPeriod.setEndPositionUs(300_000);
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    assertThat(mediaPeriod.isLoading()).isFalse();
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_beforeTrackSelection_doesNotPreventLoadingWhenTracksSelectedLater()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];

    // Set end position when prepared but enabledTrackCount == 0.
    long unusedEndPosition = mediaPeriod.setEndPositionUs(300_000);

    // Later select a track and start loading.
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isTrue();
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_afterFatalError_doesNotClearFatalError() throws Exception {
    FatalErrorDataSource dataSource =
        new FatalErrorDataSource(new AssetDataSource(ApplicationProvider.getApplicationContext()));
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            dataSource,
            new DefaultLoadErrorHandlingPolicy());

    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    // Reset track selection when idle so continueLoading can start a fresh load.
    Arrays.fill(selections, null);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    dataSource.setThrowErrorAfterPrepare(true);
    unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          try {
            mediaPeriod.maybeThrowPrepareError();
            return false;
          } catch (IOException e) {
            return true;
          }
        });
    assertThrows(ParserException.class, mediaPeriod::maybeThrowPrepareError);
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();

    // Setting end position while in fatal error should NOT transition to CLIPPED_FINISHED.
    long unusedEnd = mediaPeriod.setEndPositionUs(300_000);
    assertThat(
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build()))
        .isFalse();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_onlyNonAvTrackSelected_loadsUntilEndPosition() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample_with_vobsub.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    // Verify sample_with_vobsub.mp4 has Video, Audio, and Text tracks.
    assertThat(trackGroups.get(0).type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(1).type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).type).isEqualTo(C.TRACK_TYPE_TEXT);

    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    // Select ONLY the text track (index 2).
    selections[2] =
        new FakeTrackSelection(trackGroups.get(2), new int[] {0}, /* selectedIndex= */ 0);

    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    assertThat(streams[2]).isNotNull();

    // Clip at 300ms. Since only a non-AV track is selected, it must NOT immediately assume the
    // end position is reached, and should load until 300ms.
    long unusedEnd = mediaPeriod.setEndPositionUs(300_000);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return !mediaPeriod.isLoading();
        });

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[2].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[2].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_reducedTwice_discardsUpstreamBuffersToLatestEndPosition()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    // Reduce end position first to 600ms, then further to 300ms.
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(600_000);
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(300_000);

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_extendedAfterFinished_calculatesMinAvResumePositionAndResumes()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    // Extend end position after loading finished at 300_000us.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(600_000);
    boolean unusedLoad2 =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(lastReadTimeUs).isAtMost(600_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_extendedWhileCancelingForClipping_resumesLoadingToNewEnd()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    assertThat(mediaPeriod.isLoading()).isTrue();

    // Lower end position while loading to trigger cancellation for clipping.
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    // Extend end position before cancellation completes.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(600_000);

    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(lastReadTimeUs).isAtMost(600_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void
      setEndPositionUs_extendedAfterFinished_loadErrorDuringResumption_retriesFromFailurePosition()
          throws Exception {
    AtomicInteger openCount = new AtomicInteger();
    DataSource underlyingDataSource =
        new AssetDataSource(ApplicationProvider.getApplicationContext());
    DataSource dataSource =
        new DataSource() {
          @Override
          public void addTransferListener(TransferListener transferListener) {
            underlyingDataSource.addTransferListener(transferListener);
          }

          @Override
          public long open(DataSpec dataSpec) throws IOException {
            if (openCount.incrementAndGet() == 2) {
              throw new IOException("Simulated transient network error on resumption");
            }
            return underlyingDataSource.open(dataSpec);
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            return underlyingDataSource.read(buffer, offset, length);
          }

          @Override
          public Uri getUri() {
            return underlyingDataSource.getUri();
          }

          @Override
          public void close() throws IOException {
            underlyingDataSource.close();
          }
        };

    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(
            Uri.parse("asset://android_asset/media/mp4/sample.mp4"),
            dataSource,
            new DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 3) {
              @Override
              public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
                return 0; // Retry immediately
              }
            });
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(openCount.get()).isEqualTo(1);

    // Extend end position after loading finished at 300_000us.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(600_000);
    boolean unusedLoad2 =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return openCount.get() >= 3
              && mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    // Verified that openCount reached at least 3 (1 initial, 2 simulated failure, 3 retry success).
    assertThat(openCount.get()).isAtLeast(3);

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(lastReadTimeUs).isAtMost(600_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_extendedToEndOfSourceAfterFinished_resumesLoadingToEnd()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    // Extend end position to C.TIME_END_OF_SOURCE after loading finished at 300_000us.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(C.TIME_END_OF_SOURCE);
    boolean unusedLoad2 =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE
              && !mediaPeriod.isLoading();
        });
    shadowOf(Looper.getMainLooper()).idle();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_extendedToEndOfSourceWhileCancelingForClipping_resumesLoadingToEnd()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    assertThat(mediaPeriod.isLoading()).isTrue();

    // Lower end position while loading to trigger cancellation for clipping.
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    // Extend end position to C.TIME_END_OF_SOURCE before cancellation completes.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(C.TIME_END_OF_SOURCE);

    runMainLooperUntil(() -> !mediaPeriod.isLoading());

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void seekToUs_beyondEndPositionUs_signalsEndOfStream() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    // Initial load up to 300ms.
    long unusedEndPosition = mediaPeriod.setEndPositionUs(300_000);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    // Seek to 500ms (beyond clip end of 300ms).
    long seekTimeUs = mediaPeriod.seekToUs(500_000);
    assertThat(seekTimeUs).isEqualTo(500_000);
    runMainLooperUntil(
        () -> {
          boolean unused2 =
              mediaPeriod.continueLoading(
                  new LoadingInfo.Builder().setPlaybackPositionUs(500_000).build());
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 500_000);
          return !mediaPeriod.isLoading();
        });
    shadowOf(Looper.getMainLooper()).idle();
    assertThat(mediaPeriod.isLoading()).isFalse();

    // Verify stream signals EOS without stalling.
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    if (readResult == C.RESULT_FORMAT_READ) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    }
    while (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    }
    assertThat(readResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_reducedThenExtendedThenReduced_discardsToFinalEndPosition()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    assertThat(mediaPeriod.isLoading()).isTrue();

    // 1. Lower end position to 300ms while loading.
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);
    // 2. Extend end position to 600ms before cancellation finishes.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(600_000);
    // 3. Reduce end position again to 450ms.
    long unusedEndPosition3 = mediaPeriod.setEndPositionUs(450_000);

    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return !mediaPeriod.isLoading();
        });
    shadowOf(Looper.getMainLooper()).idle();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isAtMost(450_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void setEndPositionUs_extendedAfterNaturalEofReached_resumesLoading() throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[0] =
        new FakeTrackSelection(trackGroups.get(0), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    // Initial load until entire file reaches natural EOF (STATE_FINISHED).
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);
    shadowOf(Looper.getMainLooper()).idle();

    // Clip the stream at 300ms. State transitions FINISHED -> CLIPPED_FINISHED and discards
    // upstream buffers.
    long unusedEndPosition1 = mediaPeriod.setEndPositionUs(300_000);

    // Later extend the clip to 600ms. State transitions CLIPPED_FINISHED -> IDLE and loading
    // resumes.
    long unusedEndPosition2 = mediaPeriod.setEndPositionUs(600_000);
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return !mediaPeriod.isLoading();
        });
    shadowOf(Looper.getMainLooper()).idle();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(readResult).isEqualTo(C.RESULT_FORMAT_READ);
    long lastReadTimeUs = C.TIME_UNSET;
    while (true) {
      buffer.clear();
      readResult = streams[0].readData(formatHolder, buffer, /* readFlags= */ 0);
      if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        lastReadTimeUs = buffer.timeUs;
      } else {
        break;
      }
    }
    assertThat(lastReadTimeUs).isGreaterThan(300_000);
    assertThat(lastReadTimeUs).isAtMost(600_000);
    assertThat(buffer.isEndOfStream()).isTrue();
    mediaPeriod.release();
  }

  @Test
  public void seekToUs_toStreamStartWithPositiveFirstSampleTimestamp_seeksInsideBuffer()
      throws Exception {
    ProgressiveMediaPeriod mediaPeriod =
        createMediaPeriod(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    TrackGroupArray trackGroups = mediaPeriod.getTrackGroups();
    @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[trackGroups.length];
    @NullableType SampleStream[] streams = new SampleStream[trackGroups.length];
    boolean[] streamResetFlags = new boolean[trackGroups.length];
    selections[1] =
        new FakeTrackSelection(trackGroups.get(1), new int[] {0}, /* selectedIndex= */ 0);
    long unused =
        mediaPeriod.selectTracks(
            selections,
            new boolean[trackGroups.length],
            streams,
            streamResetFlags,
            /* positionUs= */ 0);

    // Initial load until buffered.
    boolean unusedLoad =
        mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    runMainLooperUntil(
        () -> {
          mediaPeriod.reevaluateBuffer(/* positionUs= */ 0);
          return mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE;
        });
    shadowOf(Looper.getMainLooper()).idle();

    // Read first sample to advance read index past 0.
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int readResult = streams[1].readData(formatHolder, buffer, /* readFlags= */ 0);
    if (readResult == C.RESULT_FORMAT_READ) {
      buffer.clear();
      readResult = streams[1].readData(formatHolder, buffer, /* readFlags= */ 0);
    }
    assertThat(readResult).isEqualTo(C.RESULT_BUFFER_READ);
    long firstSampleTimeUs = buffer.timeUs;
    assertThat(firstSampleTimeUs).isGreaterThan(0);

    // Seek back to position 0 (same as last seek position).
    long seekTimeUs = mediaPeriod.seekToUs(0);
    assertThat(seekTimeUs).isEqualTo(0);

    // Verify in-buffer seek was successful and loading is not restarted.
    assertThat(mediaPeriod.isLoading()).isFalse();

    // Verify reading starts again from the first sample.
    buffer.clear();
    readResult = streams[1].readData(formatHolder, buffer, /* readFlags= */ 0);
    if (readResult == C.RESULT_FORMAT_READ) {
      buffer.clear();
      readResult = streams[1].readData(formatHolder, buffer, /* readFlags= */ 0);
    }
    assertThat(readResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.timeUs).isEqualTo(firstSampleTimeUs);

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

  private static final class FatalErrorDataSource implements DataSource {
    private final DataSource delegate;
    private boolean throwErrorAfterPrepare;

    public FatalErrorDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    public void setThrowErrorAfterPrepare(boolean throwErrorAfterPrepare) {
      this.throwErrorAfterPrepare = throwErrorAfterPrepare;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
      delegate.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
      if (throwErrorAfterPrepare) {
        throw ParserException.createForMalformedContainer("Fatal error", /* cause= */ null);
      }
      return delegate.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (throwErrorAfterPrepare) {
        throw ParserException.createForMalformedContainer("Fatal error", /* cause= */ null);
      }
      return delegate.read(buffer, offset, length);
    }

    @Override
    @Nullable
    public Uri getUri() {
      return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
      return delegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
