/*
 * Copyright (C) 2016 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;

/** A {@link BaseMediaChunk} that uses an {@link Extractor} to decode sample data. */
@UnstableApi
public class ContainerMediaChunk extends BaseMediaChunk {

  private final int chunkCount;
  private final long sampleOffsetUs;
  private final ChunkExtractor chunkExtractor;

  private long nextLoadPosition;
  private volatile boolean loadCanceled;
  private boolean loadCompleted;

  /**
   * @param dataSource The source from which the data should be loaded.
   * @param dataSpec Defines the data to be loaded.
   * @param trackFormat See {@link #trackFormat}.
   * @param trackSelectionReason See {@link #trackSelectionReason}.
   * @param trackSelectionData See {@link #trackSelectionData}.
   * @param startTimeUs The start time of the media contained by the chunk, in microseconds.
   * @param endTimeUs The end time of the media contained by the chunk, in microseconds.
   * @param clippedStartTimeUs The time in the chunk from which output will begin, or {@link
   *     C#TIME_UNSET} to output from the start of the chunk.
   * @param clippedEndTimeUs The time in the chunk from which output will end, or {@link
   *     C#TIME_UNSET} to output to the end of the chunk.
   * @param chunkIndex The index of the chunk, or {@link C#INDEX_UNSET} if it is not known.
   * @param chunkCount The number of chunks in the underlying media that are spanned by this
   *     instance. Normally equal to one, but may be larger if multiple chunks as defined by the
   *     underlying media are being merged into a single load.
   * @param sampleOffsetUs An offset to add to the sample timestamps parsed by the extractor.
   * @param chunkExtractor A wrapped extractor to use for parsing the data.
   */
  public ContainerMediaChunk(
      DataSource dataSource,
      DataSpec dataSpec,
      Format trackFormat,
      @C.SelectionReason int trackSelectionReason,
      @Nullable Object trackSelectionData,
      long startTimeUs,
      long endTimeUs,
      long clippedStartTimeUs,
      long clippedEndTimeUs,
      long chunkIndex,
      int chunkCount,
      long sampleOffsetUs,
      ChunkExtractor chunkExtractor) {
    super(
        dataSource,
        dataSpec,
        trackFormat,
        trackSelectionReason,
        trackSelectionData,
        startTimeUs,
        endTimeUs,
        clippedStartTimeUs,
        clippedEndTimeUs,
        chunkIndex);
    this.chunkCount = chunkCount;
    this.sampleOffsetUs = sampleOffsetUs;
    this.chunkExtractor = chunkExtractor;
  }

  @Override
  public long getNextChunkIndex() {
    return chunkIndex + chunkCount;
  }

  @Override
  public boolean isLoadCompleted() {
    return loadCompleted;
  }

  // Loadable implementation.

  @Override
  public final void cancelLoad() {
    loadCanceled = true;
  }

  @SuppressWarnings("NonAtomicVolatileUpdate")
  @Override
  public final void load() throws IOException {
    BaseMediaChunkOutput output = getOutput();
    if (nextLoadPosition == 0) {
      // Configure the output and set it as the target for the extractor wrapper.
      output.setSampleOffsetUs(sampleOffsetUs);
      chunkExtractor.init(
          getTrackOutputProvider(output),
          clippedStartTimeUs == C.TIME_UNSET ? C.TIME_UNSET : (clippedStartTimeUs - sampleOffsetUs),
          clippedEndTimeUs == C.TIME_UNSET ? C.TIME_UNSET : (clippedEndTimeUs - sampleOffsetUs));
    }
    try {
      // Create and open the input.
      DataSpec loadDataSpec = dataSpec.subrange(nextLoadPosition);
      ExtractorInput input =
          new DefaultExtractorInput(
              dataSource, loadDataSpec.position, dataSource.open(loadDataSpec));
      // Load and decode the sample data.
      try {
        while (!loadCanceled && chunkExtractor.read(input)) {}
        maybeWriteEmptySamples(output);
      } finally {
        nextLoadPosition = input.getPosition() - dataSpec.position;
      }
    } finally {
      DataSourceUtil.closeQuietly(dataSource);
    }
    loadCompleted = !loadCanceled;
  }

  /**
   * Returns the {@link TrackOutputProvider} to be used by the wrapped extractor.
   *
   * @param baseMediaChunkOutput The {@link BaseMediaChunkOutput} most recently passed to {@link
   *     #init(BaseMediaChunkOutput)}.
   * @return A {@link TrackOutputProvider} to be used by the wrapped extractor.
   */
  protected TrackOutputProvider getTrackOutputProvider(BaseMediaChunkOutput baseMediaChunkOutput) {
    return baseMediaChunkOutput;
  }

  private void maybeWriteEmptySamples(BaseMediaChunkOutput output) {
    if (!MimeTypes.isImage(trackFormat.containerMimeType)) {
      return;
    }
    if ((trackFormat.tileCountHorizontal <= 1 && trackFormat.tileCountVertical <= 1)
        || trackFormat.tileCountHorizontal == Format.NO_VALUE
        || trackFormat.tileCountVertical == Format.NO_VALUE) {
      return;
    }

    TrackOutput trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_IMAGE);
    int tileCount = trackFormat.tileCountHorizontal * trackFormat.tileCountVertical;
    long tileDurationUs = (endTimeUs - startTimeUs) / tileCount;

    for (int i = 1; i < tileCount; ++i) {
      long tileStartTimeUs = i * tileDurationUs;
      trackOutput.sampleData(new ParsableByteArray(), /* length= */ 0);
      trackOutput.sampleMetadata(
          tileStartTimeUs, /* flags= */ 0, /* size= */ 0, /* offset= */ 0, /* cryptoData= */ null);
    }
  }
}
