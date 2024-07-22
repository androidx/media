/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.ChunkHolder;
import androidx.media3.exoplayer.source.chunk.ChunkSource;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.SingleSampleMediaChunk;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.test.utils.FakeDataSet.FakeData.Segment;
import java.util.List;

/** Fake {@link ChunkSource} with adaptive media chunks of a given duration. */
@UnstableApi
public class FakeChunkSource implements ChunkSource {

  /** Factory for a {@link FakeChunkSource}. */
  public static class Factory {

    protected final FakeAdaptiveDataSet.Factory dataSetFactory;
    protected final FakeDataSource.Factory dataSourceFactory;

    public Factory(
        FakeAdaptiveDataSet.Factory dataSetFactory, FakeDataSource.Factory dataSourceFactory) {
      this.dataSetFactory = dataSetFactory;
      this.dataSourceFactory = dataSourceFactory;
    }

    public FakeChunkSource createChunkSource(
        ExoTrackSelection trackSelection,
        long durationUs,
        @Nullable TransferListener transferListener) {
      FakeAdaptiveDataSet dataSet =
          dataSetFactory.createDataSet(trackSelection.getTrackGroup(), durationUs);
      dataSourceFactory.setFakeDataSet(dataSet);
      FakeDataSource dataSource = dataSourceFactory.createDataSource();
      if (transferListener != null) {
        dataSource.addTransferListener(transferListener);
      }
      return new FakeChunkSource(trackSelection, dataSource, dataSet);
    }
  }

  private final ExoTrackSelection trackSelection;
  private final DataSource dataSource;
  private final FakeAdaptiveDataSet dataSet;

  public FakeChunkSource(
      ExoTrackSelection trackSelection, DataSource dataSource, FakeAdaptiveDataSet dataSet) {
    this.trackSelection = trackSelection;
    this.dataSource = dataSource;
    this.dataSet = dataSet;
  }

  @Override
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    int chunkIndex = dataSet.getChunkIndexByPosition(positionUs);
    long firstSyncUs = dataSet.getStartTime(chunkIndex);
    long secondSyncUs =
        firstSyncUs < positionUs && chunkIndex < dataSet.getChunkCount() - 1
            ? dataSet.getStartTime(chunkIndex + 1)
            : firstSyncUs;
    return seekParameters.resolveSeekPositionUs(positionUs, firstSyncUs, secondSyncUs);
  }

  @Override
  public void maybeThrowError() {
    // Do nothing.
  }

  @Override
  public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
  }

  @Override
  public boolean shouldCancelLoad(
      long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
    return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue);
  }

  @Override
  public void getNextChunk(
      LoadingInfo loadingInfo,
      long loadPositionUs,
      List<? extends MediaChunk> queue,
      ChunkHolder out) {
    long playbackPositionUs = loadingInfo.playbackPositionUs;
    long bufferedDurationUs = loadPositionUs - playbackPositionUs;
    int chunkIndex =
        queue.isEmpty()
            ? dataSet.getChunkIndexByPosition(playbackPositionUs)
            : (int) queue.get(queue.size() - 1).getNextChunkIndex();
    MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
    for (int i = 0; i < chunkIterators.length; i++) {
      int trackGroupIndex = trackSelection.getIndexInTrackGroup(i);
      chunkIterators[i] = new FakeAdaptiveDataSet.Iterator(dataSet, trackGroupIndex, chunkIndex);
    }
    trackSelection.updateSelectedTrack(
        playbackPositionUs, bufferedDurationUs, C.TIME_UNSET, queue, chunkIterators);
    if (chunkIndex >= dataSet.getChunkCount()) {
      out.endOfStream = true;
    } else {
      Format selectedFormat = trackSelection.getSelectedFormat();
      long startTimeUs = dataSet.getStartTime(chunkIndex);
      long endTimeUs = startTimeUs + dataSet.getChunkDuration(chunkIndex);
      int trackGroupIndex = trackSelection.getIndexInTrackGroup(trackSelection.getSelectedIndex());
      String uri = dataSet.getUri(trackGroupIndex);
      Segment fakeDataChunk =
          Assertions.checkStateNotNull(dataSet.getData(uri)).getSegments().get(chunkIndex);
      DataSpec dataSpec =
          new DataSpec(Uri.parse(uri), fakeDataChunk.byteOffset, fakeDataChunk.length);
      int trackType = MimeTypes.getTrackType(selectedFormat.sampleMimeType);
      out.chunk =
          new SingleSampleMediaChunk(
              dataSource,
              dataSpec,
              selectedFormat,
              trackSelection.getSelectionReason(),
              trackSelection.getSelectionData(),
              startTimeUs,
              endTimeUs,
              chunkIndex,
              trackType,
              selectedFormat);
    }
  }

  @Override
  public void onChunkLoadCompleted(Chunk chunk) {
    // Do nothing.
  }

  @Override
  public boolean onChunkLoadError(
      Chunk chunk,
      boolean cancelable,
      LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
    return false;
  }

  @Override
  public void release() {
    // Do nothing.
  }
}
