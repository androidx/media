/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.annotation.VisibleForTesting.NONE;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_OMIT_SAMPLE_DATA;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_PEEK;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_REQUIRE_FORMAT;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.SparseArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.exoplayer.source.SampleStream.ReadFlags;
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.Extractor.ReadResult;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekMap.SeekPoints;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.Mp4Extractor;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/**
 * A drop-in replacement for {@link MediaExtractor} that provides similar functionality, based on
 * the {@code media3.extractor} logic.
 */
@UnstableApi
public final class MediaExtractorCompat {

  /**
   * The seeking mode. One of {@link #SEEK_TO_PREVIOUS_SYNC}, {@link #SEEK_TO_NEXT_SYNC}, or {@link
   * #SEEK_TO_CLOSEST_SYNC}.
   */
  @IntDef({
    SEEK_TO_PREVIOUS_SYNC,
    SEEK_TO_NEXT_SYNC,
    SEEK_TO_CLOSEST_SYNC,
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface SeekMode {}

  /** See {@link MediaExtractor#SEEK_TO_PREVIOUS_SYNC}. */
  public static final int SEEK_TO_PREVIOUS_SYNC = MediaExtractor.SEEK_TO_PREVIOUS_SYNC;

  /** See {@link MediaExtractor#SEEK_TO_NEXT_SYNC}. */
  public static final int SEEK_TO_NEXT_SYNC = MediaExtractor.SEEK_TO_NEXT_SYNC;

  /** See {@link MediaExtractor#SEEK_TO_CLOSEST_SYNC}. */
  public static final int SEEK_TO_CLOSEST_SYNC = MediaExtractor.SEEK_TO_CLOSEST_SYNC;

  private static final String TAG = "MediaExtractorCompat";

  private final ExtractorsFactory extractorsFactory;
  private final DataSource.Factory dataSourceFactory;
  private final PositionHolder positionHolder;
  private final Allocator allocator;
  private final ArrayList<MediaExtractorTrack> tracks;
  private final SparseArray<MediaExtractorSampleQueue> sampleQueues;
  private final ArrayDeque<Integer> trackIndicesPerSampleInQueuedOrder;
  private final FormatHolder formatHolder;
  private final DecoderInputBuffer sampleHolder;
  private final DecoderInputBuffer noDataBuffer;
  private final Set<Integer> selectedTrackIndices;

  private boolean hasBeenPrepared;
  private long offsetInCurrentFile;
  @Nullable private Extractor currentExtractor;
  @Nullable private ExtractorInput currentExtractorInput;
  @Nullable private DataSource currentDataSource;
  @Nullable private SeekPoint pendingSeek;

  @Nullable private SeekMap seekMap;
  private boolean tracksEnded;
  private int upstreamFormatsCount;

  /** Creates a new instance. */
  public MediaExtractorCompat(Context context) {
    this(new DefaultExtractorsFactory(), new DefaultDataSource.Factory(context));
  }

  /**
   * Creates a new instance using the given {@link ExtractorsFactory extractorsFactory} to create
   * the {@link Extractor extractors} to use for obtaining media samples from a DataSource generated
   * by the given {@link DataSource.Factory dataSourceFactory}.
   */
  public MediaExtractorCompat(
      ExtractorsFactory extractorsFactory, DataSource.Factory dataSourceFactory) {
    this.extractorsFactory = extractorsFactory;
    this.dataSourceFactory = dataSourceFactory;
    positionHolder = new PositionHolder();
    allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    tracks = new ArrayList<>();
    sampleQueues = new SparseArray<>();
    trackIndicesPerSampleInQueuedOrder = new ArrayDeque<>();
    formatHolder = new FormatHolder();
    sampleHolder = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
    noDataBuffer = DecoderInputBuffer.newNoDataInstance();
    selectedTrackIndices = new HashSet<>();
  }

  /**
   * Initializes the internal state with the media stream obtained from the given {@code uri} at the
   * given {@code offset}.
   *
   * @param uri The content {@link Uri} to extract from.
   * @param offset The offset into the file where the data to be extracted starts, in bytes.
   * @throws IOException If an error occurs while extracting the media.
   * @throws UnrecognizedInputFormatException If none of the available extractors successfully
   *     sniffs the input.
   * @throws IllegalStateException If this method is called twice on the same instance.
   */
  public void setDataSource(Uri uri, long offset) throws IOException {
    // Assert that this instance is not being re-prepared, which is not currently supported.
    Assertions.checkState(!hasBeenPrepared);
    hasBeenPrepared = true;
    offsetInCurrentFile = offset;
    DataSpec dataSpec = buildDataSpec(uri, /* position= */ offsetInCurrentFile);

    currentDataSource = dataSourceFactory.createDataSource();
    long length = currentDataSource.open(dataSpec);
    ExtractorInput currentExtractorInput =
        new DefaultExtractorInput(currentDataSource, /* position= */ 0, length);
    Extractor currentExtractor = selectExtractor(currentExtractorInput);
    currentExtractor.init(new ExtractorOutputImpl());

    boolean preparing = true;
    Throwable error = null;
    while (preparing) {
      int result;
      try {
        result = currentExtractor.read(currentExtractorInput, positionHolder);
      } catch (Exception | OutOfMemoryError e) {
        // This value is ignored but initializes result to avoid static analysis errors.
        result = Extractor.RESULT_END_OF_INPUT;
        error = e;
      }
      preparing = !tracksEnded || upstreamFormatsCount < sampleQueues.size() || seekMap == null;
      if (error != null || (preparing && result == Extractor.RESULT_END_OF_INPUT)) {
        // TODO(b/178501820): Support files with incomplete track information.
        release(); // Release resources as soon as possible, in case we are low on memory.
        String message =
            error != null
                ? "Exception encountered while parsing input media."
                : "Reached end of input before preparation completed.";
        throw ParserException.createForMalformedContainer(message, /* cause= */ error);
      } else if (result == Extractor.RESULT_SEEK) {
        currentExtractorInput = reopenCurrentDataSource(positionHolder.position);
      }
    }
    this.currentExtractorInput = currentExtractorInput;
    this.currentExtractor = currentExtractor;
    // At this point, we know how many tracks we have, and their format.
  }

  /**
   * Releases any resources held by this instance.
   *
   * <p>Note: Make sure you call this when you're done to free up any resources instead of relying
   * on the garbage collector to do this for you at some point in the future.
   */
  public void release() {
    for (int i = 0; i < sampleQueues.size(); i++) {
      sampleQueues.valueAt(i).release();
    }
    sampleQueues.clear();
    if (currentExtractor != null) {
      currentExtractor.release();
      currentExtractor = null;
    }
    currentExtractorInput = null;
    pendingSeek = null;
    DataSourceUtil.closeQuietly(currentDataSource);
    currentDataSource = null;
  }

  /** Returns the number of tracks found in the data source. */
  public int getTrackCount() {
    return tracks.size();
  }

  /** Returns the track {@link MediaFormat} at the specified {@code trackIndex}. */
  public MediaFormat getTrackFormat(int trackIndex) {
    return tracks.get(trackIndex).createDownstreamMediaFormat(formatHolder, noDataBuffer);
  }

  /**
   * Selects a track at the specified {@code trackIndex}.
   *
   * <p>Subsequent calls to {@link #readSampleData}, {@link #getSampleTrackIndex} and {@link
   * #getSampleTime} only retrieve information for the subset of tracks selected.
   *
   * <p>Note: Selecting the same track multiple times has no effect, the track is only selected
   * once.
   */
  public void selectTrack(int trackIndex) {
    selectedTrackIndices.add(trackIndex);
  }

  /**
   * Unselects the track at the specified {@code trackIndex}.
   *
   * <p>Subsequent calls to {@link #readSampleData}, {@link #getSampleTrackIndex} and {@link
   * #getSampleTime} only retrieve information for the subset of tracks selected.
   */
  public void unselectTrack(int trackIndex) {
    selectedTrackIndices.remove(trackIndex);
  }

  /**
   * All selected tracks seek near the requested {@code timeUs} according to the specified {@code
   * mode}.
   */
  public void seekTo(long timeUs, @SeekMode int mode) {
    if (seekMap == null) {
      return;
    }

    SeekPoints seekPoints;
    if (selectedTrackIndices.size() == 1 && currentExtractor instanceof Mp4Extractor) {
      // Mp4Extractor supports seeking within a specific track. This helps with poorly interleaved
      // tracks. See b/223910395.
      seekPoints =
          ((Mp4Extractor) currentExtractor)
              .getSeekPoints(
                  timeUs, tracks.get(selectedTrackIndices.iterator().next()).getIdOfBackingTrack());
    } else {
      seekPoints = seekMap.getSeekPoints(timeUs);
    }
    SeekPoint seekPoint;
    switch (mode) {
      case SEEK_TO_CLOSEST_SYNC:
        seekPoint =
            Math.abs(timeUs - seekPoints.second.timeUs) < Math.abs(timeUs - seekPoints.first.timeUs)
                ? seekPoints.second
                : seekPoints.first;
        break;
      case SEEK_TO_NEXT_SYNC:
        seekPoint = seekPoints.second;
        break;
      case SEEK_TO_PREVIOUS_SYNC:
        seekPoint = seekPoints.first;
        break;
      default:
        // Should never happen.
        throw new IllegalArgumentException();
    }
    trackIndicesPerSampleInQueuedOrder.clear();
    for (int i = 0; i < sampleQueues.size(); i++) {
      sampleQueues.valueAt(i).reset();
    }
    pendingSeek = seekPoint;
  }

  /**
   * Advances to the next sample. Returns {@code false} if no more sample data is available (i.e.,
   * end of stream), or {@code true} otherwise.
   *
   * <p>Note: When extracting from a local file, the behavior of {@link #advance} and {@link
   * #readSampleData} is undefined if there are concurrent writes to the same file. This may result
   * in an unexpected end of stream being signaled.
   */
  public boolean advance() {
    // Ensure there is a sample to discard.
    if (!advanceToSampleOrEndOfInput()) {
      // The end of input has been reached.
      return false;
    }
    skipOneSample();
    return advanceToSampleOrEndOfInput();
  }

  /**
   * Retrieves the current encoded sample and stores it in the byte {@code buffer} starting at the
   * given {@code offset}.
   *
   * <p><b>Note:</b>On success, the position and limit of {@code buffer} is updated to point to the
   * data just read.
   *
   * @param buffer the destination byte buffer.
   * @param offset The offset into the byte buffer at which to write.
   * @return the sample size, or -1 if no more samples are available.
   */
  public int readSampleData(ByteBuffer buffer, int offset) {
    if (!advanceToSampleOrEndOfInput()) {
      return -1;
    }
    // The platform media extractor implementation ignores the buffer's input position and limit.
    buffer.position(offset);
    buffer.limit(buffer.capacity());
    sampleHolder.data = buffer;
    peekNextSelectedTrackSample(sampleHolder, /* omitSampleData= */ false);
    buffer.flip();
    buffer.position(offset);
    sampleHolder.data = null;
    return buffer.remaining();
  }

  /**
   * Returns the track index the current sample originates from, or -1 if no more samples are
   * available.
   */
  public int getSampleTrackIndex() {
    if (!advanceToSampleOrEndOfInput()) {
      return -1;
    }
    return trackIndicesPerSampleInQueuedOrder.peekFirst();
  }

  /**
   * Returns the current sample's presentation time in microseconds, or -1 if no more samples are
   * available.
   */
  public long getSampleTime() {
    if (!advanceToSampleOrEndOfInput()) {
      return -1;
    }
    peekNextSelectedTrackSample(noDataBuffer, /* omitSampleData= */ true);
    return noDataBuffer.timeUs;
  }

  /** Returns the current sample's flags. */
  public int getSampleFlags() {
    if (!advanceToSampleOrEndOfInput()) {
      return -1;
    }
    peekNextSelectedTrackSample(noDataBuffer, /* omitSampleData= */ true);
    int flags = 0;
    flags |= noDataBuffer.isEncrypted() ? MediaExtractor.SAMPLE_FLAG_ENCRYPTED : 0;
    flags |= noDataBuffer.isKeyFrame() ? MediaExtractor.SAMPLE_FLAG_SYNC : 0;
    return flags;
  }

  @VisibleForTesting(otherwise = NONE)
  public Allocator getAllocator() {
    return allocator;
  }

  /**
   * Peeks a sample from the front of the given {@link SampleQueue}, discarding the downstream
   * {@link Format} first, if necessary.
   *
   * @param decoderInputBuffer The buffer to populate.
   * @param omitSampleData Whether to omit the sample's data.
   * @throws IllegalStateException If a sample is not peeked as a result of calling this method.
   */
  private void peekNextSelectedTrackSample(
      DecoderInputBuffer decoderInputBuffer, boolean omitSampleData) {
    MediaExtractorTrack trackOfSample =
        tracks.get(checkNotNull(trackIndicesPerSampleInQueuedOrder.peekFirst()));
    SampleQueue sampleQueue = trackOfSample.sampleQueue;
    @ReadFlags int readFlags = FLAG_PEEK | (omitSampleData ? FLAG_OMIT_SAMPLE_DATA : 0);
    @ReadDataResult
    int result =
        sampleQueue.read(formatHolder, decoderInputBuffer, readFlags, /* loadingFinished= */ false);
    if (result == C.RESULT_FORMAT_READ) {
      // We've consumed a downstream format. Now consume the actual sample.
      result =
          sampleQueue.read(
              formatHolder, decoderInputBuffer, readFlags, /* loadingFinished= */ false);
    }
    formatHolder.clear();
    // Additional logging is added to debug b/241321832.
    if (result != C.RESULT_BUFFER_READ) {
      // This method should only be called when there is a sample available for reading.
      throw new IllegalStateException(
          Util.formatInvariant(
              "Sample read result: %s\n"
                  + "Track sample: %s\n"
                  + "TrackIndicesPerSampleInQueuedOrder: %s\n"
                  + "Tracks added: %s\n",
              result, trackOfSample, trackIndicesPerSampleInQueuedOrder, tracks));
    }
  }

  /**
   * Returns the extractor to use for extracting samples from the given {@code input}.
   *
   * @throws IOException If an error occurs while extracting the media.
   * @throws UnrecognizedInputFormatException If none of the available extractors successfully
   *     sniffs the input.
   */
  private Extractor selectExtractor(ExtractorInput input) throws IOException {
    Extractor[] extractors = extractorsFactory.createExtractors();
    Extractor result = null;
    for (Extractor extractor : extractors) {
      try {
        if (extractor.sniff(input)) {
          result = extractor;
          break;
        }
      } catch (EOFException e) {
        // We reached the end of input without recognizing the input format. Do nothing to let the
        // next extractor sniff the content.
      } finally {
        input.resetPeekPosition();
      }
    }
    if (result == null) {
      throw new UnrecognizedInputFormatException(
          "None of the available extractors ("
              + Joiner.on(", ")
                  .join(
                      Lists.transform(
                          ImmutableList.copyOf(extractors),
                          extractor ->
                              extractor.getUnderlyingImplementation().getClass().getSimpleName()))
              + ") could read the stream.",
          checkNotNull(checkNotNull(currentDataSource).getUri()),
          ImmutableList.of());
    }
    return result;
  }

  /**
   * Advances extraction until there is a queued sample from a selected track, or the end of the
   * input is found.
   *
   * <p>Handles I/O errors (for example, network connection loss) and parsing errors (for example, a
   * truncated file) in the same way as {@link MediaExtractor}, treating them as the end of input.
   *
   * @return Whether a sample from a selected track is available.
   */
  @EnsuresNonNullIf(expression = "trackIndicesPerSampleInQueuedOrder.peekFirst()", result = true)
  private boolean advanceToSampleOrEndOfInput() {
    try {
      maybeResolvePendingSeek();
    } catch (IOException e) {
      Log.w(TAG, "Treating exception as the end of input.", e);
      return false;
    }

    boolean seenEndOfInput = false;
    while (true) {
      if (!trackIndicesPerSampleInQueuedOrder.isEmpty()) {
        // By default, tracks are unselected.
        if (selectedTrackIndices.contains(trackIndicesPerSampleInQueuedOrder.peekFirst())) {
          return true;
        } else {
          // There is a queued sample, but its track is unselected. We skip the sample.
          skipOneSample();
        }
      } else if (!seenEndOfInput) {
        // There are no queued samples for the selected tracks, but we can feed more data to the
        // extractor and see if more samples are produced.
        try {
          @ReadResult
          int result =
              checkNotNull(currentExtractor)
                  .read(checkNotNull(currentExtractorInput), positionHolder);
          if (result == Extractor.RESULT_END_OF_INPUT) {
            seenEndOfInput = true;
          } else if (result == Extractor.RESULT_SEEK) {
            this.currentExtractorInput = reopenCurrentDataSource(positionHolder.position);
          }
        } catch (Exception | OutOfMemoryError e) {
          Log.w(TAG, "Treating exception as the end of input.", e);
          seenEndOfInput = true;
        }
      } else {
        // No queued samples for selected tracks, and we've parsed all the file. Nothing else to do.
        return false;
      }
    }
  }

  private void skipOneSample() {
    int trackIndex = trackIndicesPerSampleInQueuedOrder.removeFirst();
    MediaExtractorTrack track = tracks.get(trackIndex);
    if (!track.isCompatibilityTrack) {
      // We also need to skip the sample data.
      track.discardFrontSample();
    }
  }

  private ExtractorInput reopenCurrentDataSource(long newPositionInStream) throws IOException {
    DataSource currentDataSource = checkNotNull(this.currentDataSource);
    Uri currentUri = checkNotNull(currentDataSource.getUri());
    DataSourceUtil.closeQuietly(currentDataSource);
    long length =
        currentDataSource.open(
            buildDataSpec(currentUri, offsetInCurrentFile + newPositionInStream));
    if (length != C.LENGTH_UNSET) {
      length += newPositionInStream;
    }
    return new DefaultExtractorInput(currentDataSource, newPositionInStream, length);
  }

  private void onSampleQueueFormatInitialized(
      MediaExtractorSampleQueue mediaExtractorSampleQueue, Format newUpstreamFormat) {
    upstreamFormatsCount++;
    mediaExtractorSampleQueue.setMainTrackIndex(tracks.size());
    tracks.add(
        new MediaExtractorTrack(
            mediaExtractorSampleQueue,
            /* isCompatibilityTrack= */ false,
            /* compatibilityTrackMimeType= */ null));
    @Nullable
    String compatibilityTrackMimeType =
        MediaCodecUtil.getAlternativeCodecMimeType(newUpstreamFormat);
    if (compatibilityTrackMimeType != null) {
      mediaExtractorSampleQueue.setCompatibilityTrackIndex(tracks.size());
      tracks.add(
          new MediaExtractorTrack(
              mediaExtractorSampleQueue,
              /* isCompatibilityTrack= */ true,
              compatibilityTrackMimeType));
    }
  }

  private void maybeResolvePendingSeek() throws IOException {
    if (this.pendingSeek == null) {
      return; // Nothing to do.
    }
    SeekPoint pendingSeek = checkNotNull(this.pendingSeek);
    checkNotNull(currentExtractor).seek(pendingSeek.position, pendingSeek.timeUs);
    this.currentExtractorInput = reopenCurrentDataSource(pendingSeek.position);
    this.pendingSeek = null;
  }

  /**
   * Create a new {@link DataSpec} with the given data.
   *
   * <p>The created {@link DataSpec} disables caching if the content length cannot be resolved,
   * since this is indicative of a progressive live stream.
   */
  private static DataSpec buildDataSpec(Uri uri, long position) {
    return new DataSpec.Builder()
        .setUri(uri)
        .setPosition(position)
        .setFlags(
            DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN | DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
        .build();
  }

  private final class ExtractorOutputImpl implements ExtractorOutput {

    @Override
    public TrackOutput track(int id, int type) {
      MediaExtractorSampleQueue sampleQueue = sampleQueues.get(id);
      if (sampleQueue != null) {
        // This track has already been declared. We return the sample queue that corresponds to this
        // id.
        return sampleQueue;
      }
      if (tracksEnded) {
        // The id is new and the extractor has ended the tracks. Discard.
        return new DiscardingTrackOutput();
      }

      sampleQueue = new MediaExtractorSampleQueue(allocator, id);
      sampleQueues.put(id, sampleQueue);
      return sampleQueue;
    }

    @Override
    public void endTracks() {
      tracksEnded = true;
    }

    @Override
    public void seekMap(SeekMap seekMap) {
      MediaExtractorCompat.this.seekMap = seekMap;
    }
  }

  private static final class MediaExtractorTrack {

    public final MediaExtractorSampleQueue sampleQueue;
    public final boolean isCompatibilityTrack;
    @Nullable public final String compatibilityTrackMimeType;

    private MediaExtractorTrack(
        MediaExtractorSampleQueue sampleQueue,
        boolean isCompatibilityTrack,
        @Nullable String compatibilityTrackMimeType) {
      this.sampleQueue = sampleQueue;
      this.isCompatibilityTrack = isCompatibilityTrack;
      this.compatibilityTrackMimeType = compatibilityTrackMimeType;
    }

    public MediaFormat createDownstreamMediaFormat(
        FormatHolder scratchFormatHolder, DecoderInputBuffer scratchNoDataDecoderInputBuffer) {
      scratchFormatHolder.clear();
      sampleQueue.read(
          scratchFormatHolder,
          scratchNoDataDecoderInputBuffer,
          FLAG_REQUIRE_FORMAT,
          /* loadingFinished= */ false);
      Format result = checkNotNull(scratchFormatHolder.format);
      MediaFormat mediaFormatResult = MediaFormatUtil.createMediaFormatFromFormat(result);
      scratchFormatHolder.clear();
      if (compatibilityTrackMimeType != null) {
        if (Util.SDK_INT >= 29) {
          mediaFormatResult.removeKey(MediaFormat.KEY_CODECS_STRING);
        }
        mediaFormatResult.setString(MediaFormat.KEY_MIME, compatibilityTrackMimeType);
      }
      return mediaFormatResult;
    }

    public void discardFrontSample() {
      sampleQueue.skip(/* count= */ 1);
      sampleQueue.discardToRead();
    }

    public int getIdOfBackingTrack() {
      return sampleQueue.trackId;
    }

    @Override
    public String toString() {
      return String.format(
          "MediaExtractorSampleQueue: %s, isCompatibilityTrack: %s, compatibilityTrackMimeType: %s",
          sampleQueue, isCompatibilityTrack, compatibilityTrackMimeType);
    }
  }

  private final class MediaExtractorSampleQueue extends SampleQueue {

    public final int trackId;
    private int mainTrackIndex;
    private int compatibilityTrackIndex;

    public MediaExtractorSampleQueue(Allocator allocator, int trackId) {
      // We do not need the sample queue to acquire keys for encrypted samples, so we pass null
      // values for DRM-related arguments.
      super(allocator, /* drmSessionManager= */ null, /* drmEventDispatcher= */ null);
      this.trackId = trackId;
      mainTrackIndex = C.INDEX_UNSET;
      compatibilityTrackIndex = C.INDEX_UNSET;
    }

    public void setMainTrackIndex(int mainTrackIndex) {
      this.mainTrackIndex = mainTrackIndex;
    }

    public void setCompatibilityTrackIndex(int compatibilityTrackIndex) {
      this.compatibilityTrackIndex = compatibilityTrackIndex;
    }

    // SampleQueue implementation.

    @Override
    public Format getAdjustedUpstreamFormat(Format format) {
      if (getUpstreamFormat() == null) {
        onSampleQueueFormatInitialized(this, format);
      }
      return super.getAdjustedUpstreamFormat(format);
    }

    @Override
    public void sampleMetadata(
        long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
      // Disable BUFFER_FLAG_LAST_SAMPLE to prevent the sample queue from ignoring
      // FLAG_REQUIRE_FORMAT. See b/191518632.
      flags &= ~C.BUFFER_FLAG_LAST_SAMPLE;
      if (compatibilityTrackIndex != C.INDEX_UNSET) {
        trackIndicesPerSampleInQueuedOrder.addLast(compatibilityTrackIndex);
      }
      Assertions.checkState(mainTrackIndex != C.INDEX_UNSET);
      trackIndicesPerSampleInQueuedOrder.addLast(mainTrackIndex);
      super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
    }

    @Override
    public String toString() {
      return String.format(
          "trackId: %s, mainTrackIndex: %s, compatibilityTrackIndex: %s",
          trackId, mainTrackIndex, compatibilityTrackIndex);
    }
  }
}
