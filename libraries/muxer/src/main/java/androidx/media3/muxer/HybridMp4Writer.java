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
package androidx.media3.muxer;

import static androidx.media3.muxer.AnnexBUtils.doesSampleContainAnnexBNalUnits;
import static androidx.media3.muxer.Av1ConfigUtil.createAv1CodecConfigurationRecord;
import static androidx.media3.muxer.Boxes.BOX_HEADER_SIZE;
import static androidx.media3.muxer.Boxes.LARGE_SIZE_BOX_HEADER_SIZE;
import static androidx.media3.muxer.Boxes.MFHD_BOX_CONTENT_SIZE;
import static androidx.media3.muxer.Boxes.TFHD_BOX_CONTENT_SIZE;
import static androidx.media3.muxer.Boxes.getTrunBoxContentSize;
import static androidx.media3.muxer.Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
import static androidx.media3.muxer.MuxerUtil.UNSIGNED_INT_MAX_VALUE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Writes media samples as fragmented MP4 during recording, then converts to a standard
 * non-fragmented MP4 on finalization.
 *
 * <p>This approach provides crash safety (each fragment is independently playable) while producing a
 * broadly compatible non-fragmented MP4 as the final output.
 *
 * <p>The file layout during recording:
 *
 * <pre>
 * [ftyp] [free (placeholder)] [moov (empty, with mvex)] [moof+mdat]* fragments
 * </pre>
 *
 * <p>After finalization:
 *
 * <pre>
 * [ftyp] [mdat (giant, absorbs placeholder + old moov + all fragments)] [moov (full sample tables)]
 * </pre>
 */
/* package */ final class HybridMp4Writer {
  private final SeekableMuxerOutput muxerOutput;
  private final MetadataCollector metadataCollector;
  private final AnnexBToAvccConverter annexBToAvccConverter;
  private final long fragmentDurationUs;
  private final boolean sampleCopyEnabled;
  private final @Mp4Muxer.LastSampleDurationBehavior int lastSampleDurationBehavior;
  private final List<Track> tracks;
  private final List<TrackAccumulator> trackAccumulators;
  private final LinearByteBufferAllocator linearByteBufferAllocator;

  private @MonotonicNonNull Track videoTrack;
  private int currentFragmentSequenceNumber;
  private boolean headerCreated;
  private long minInputPresentationTimeUs;
  private long maxTrackDurationUs;
  private int nextTrackId;

  // Position tracking for the hybrid conversion.
  private long mdatPlaceholderPosition;
  private long ftypSize;

  /**
   * Creates an instance.
   *
   * @param muxerOutput The {@link SeekableMuxerOutput} to write the data to.
   * @param metadataCollector A {@link MetadataCollector}.
   * @param annexBToAvccConverter The {@link AnnexBToAvccConverter}.
   * @param fragmentDurationMs The fragment duration (in milliseconds).
   * @param sampleCopyEnabled Whether sample copying is enabled.
   */
  public HybridMp4Writer(
      SeekableMuxerOutput muxerOutput,
      MetadataCollector metadataCollector,
      AnnexBToAvccConverter annexBToAvccConverter,
      long fragmentDurationMs,
      boolean sampleCopyEnabled) {
    this.muxerOutput = muxerOutput;
    this.metadataCollector = metadataCollector;
    this.annexBToAvccConverter = annexBToAvccConverter;
    this.fragmentDurationUs = fragmentDurationMs * 1_000;
    this.sampleCopyEnabled = sampleCopyEnabled;
    lastSampleDurationBehavior =
        LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
    tracks = new ArrayList<>();
    trackAccumulators = new ArrayList<>();
    minInputPresentationTimeUs = Long.MAX_VALUE;
    currentFragmentSequenceNumber = 1;
    linearByteBufferAllocator = new LinearByteBufferAllocator(/* initialCapacity= */ 0);
  }

  public Track addTrack(int sortKey, Format format) {
    Track track = new Track(nextTrackId++, format, sampleCopyEnabled);
    tracks.add(track);
    trackAccumulators.add(new TrackAccumulator());
    if (MimeTypes.isVideo(format.sampleMimeType)) {
      videoTrack = track;
    }
    return track;
  }

  public void writeSampleData(Track track, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws IOException {
    if (Objects.equals(track.format.sampleMimeType, MimeTypes.VIDEO_AV1)
        && track.format.initializationData.isEmpty()
        && track.parsedCsd == null) {
      track.parsedCsd = createAv1CodecConfigurationRecord(byteBuffer.duplicate());
    }
    if (!headerCreated) {
      createHeader();
      headerCreated = true;
    }
    if (shouldFlushPendingSamples(track, bufferInfo)) {
      createFragment();
    }
    track.writeSampleData(byteBuffer, bufferInfo);
    BufferInfo firstPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekFirst());
    BufferInfo lastPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekLast());
    minInputPresentationTimeUs =
        min(minInputPresentationTimeUs, firstPendingSample.presentationTimeUs);
    maxTrackDurationUs =
        max(
            maxTrackDurationUs,
            lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs);
  }

  public void close() throws IOException {
    try {
      // Flush any remaining samples as a final fragment.
      createFragment();
      // Convert from fragmented to non-fragmented.
      finalizeAsNonFragmented();
    } finally {
      muxerOutput.close();
    }
  }

  private void createHeader() throws IOException {
    // Write ftyp.
    ByteBuffer ftypBox = Boxes.ftyp();
    ftypSize = ftypBox.remaining();
    muxerOutput.setPosition(0L);
    muxerOutput.write(ftypBox);

    // Write a free box placeholder. After finalization this region (plus the old moov and all
    // fragments) will be rewritten as a single mdat box.
    mdatPlaceholderPosition = muxerOutput.getPosition();
    // Write an 8-byte placeholder (will become the 64-bit mdat header).
    ByteBuffer placeholder = ByteBuffer.allocate(LARGE_SIZE_BOX_HEADER_SIZE);
    placeholder.putInt(1); // indicates 64-bit length follows
    placeholder.put(Util.getUtf8Bytes("free"));
    placeholder.putLong(LARGE_SIZE_BOX_HEADER_SIZE); // self-referencing size for now
    placeholder.flip();
    muxerOutput.write(placeholder);

    // Write the fragmented moov (with mvex) for crash-safe playback.
    muxerOutput.write(
        Boxes.moov(
            tracks, metadataCollector, /* isFragmentedMp4= */ true, lastSampleDurationBehavior));
  }

  private boolean shouldFlushPendingSamples(Track track, BufferInfo nextSampleBufferInfo) {
    if (videoTrack != null) {
      if (track.equals(videoTrack)
          && track.hadKeyframe
          && ((nextSampleBufferInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0)) {
        BufferInfo firstPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekFirst());
        BufferInfo lastPendingSample = checkNotNull(track.pendingSamplesBufferInfo.peekLast());
        return lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs
            >= fragmentDurationUs;
      }
      return false;
    } else {
      return maxTrackDurationUs >= fragmentDurationUs;
    }
  }

  private void createFragment() throws IOException {
    ImmutableList<ProcessedTrackInfo> trackInfos = processAllTracks();
    long moofBoxStartPosition = muxerOutput.getSize();
    ImmutableList<ByteBuffer> trafBoxes = createTrafBoxes(trackInfos, moofBoxStartPosition);
    if (trafBoxes.isEmpty()) {
      return;
    }
    muxerOutput.setPosition(moofBoxStartPosition);
    muxerOutput.write(Boxes.moof(Boxes.mfhd(currentFragmentSequenceNumber), trafBoxes));

    // Track the data offset for each track's samples in this fragment.
    long mdatDataStart = muxerOutput.getPosition() + 8; // after mdat header
    writeMdatBox(trackInfos);

    // Accumulate sample metadata for the final non-fragmented moov.
    accumulateFragmentSamples(trackInfos, mdatDataStart);

    currentFragmentSequenceNumber++;
    maxTrackDurationUs = 0;
  }

  private void accumulateFragmentSamples(
      List<ProcessedTrackInfo> trackInfos, long mdatDataStart) {
    long currentOffset = mdatDataStart;
    for (int i = 0; i < trackInfos.size(); i++) {
      ProcessedTrackInfo info = trackInfos.get(i);
      int trackIndex = info.trackId - 1;
      TrackAccumulator accumulator = trackAccumulators.get(trackIndex);

      // Record a chunk for this track in this fragment.
      accumulator.chunkOffsets.add(currentOffset);
      accumulator.chunkSampleCounts.add(info.pendingSamplesMetadata.size());

      for (int j = 0; j < info.pendingSamplesMetadata.size(); j++) {
        BufferInfo bufferInfo = info.pendingSamplesBufferInfo.get(j);
        accumulator.writtenSamples.add(bufferInfo);
      }
      currentOffset += info.totalSamplesSize;
    }
  }

  private void finalizeAsNonFragmented() throws IOException {
    if (!headerCreated) {
      return;
    }

    // Transfer accumulated sample metadata into the Track objects so that Boxes.moov() can
    // build complete sample tables (stco, stsz, stts, stss, ctts).
    for (int i = 0; i < tracks.size(); i++) {
      Track track = tracks.get(i);
      TrackAccumulator accumulator = trackAccumulators.get(i);
      track.writtenSamples.addAll(accumulator.writtenSamples);
      track.writtenChunkOffsets.addAll(accumulator.chunkOffsets);
      track.writtenChunkSampleCounts.addAll(accumulator.chunkSampleCounts);
    }

    // Write the full non-fragmented moov at the end of the file.
    long moovPosition = muxerOutput.getSize();
    muxerOutput.setPosition(moovPosition);
    ByteBuffer moovBox =
        Boxes.moov(
            tracks, metadataCollector, /* isFragmentedMp4= */ false, lastSampleDurationBehavior);
    muxerOutput.write(moovBox);

    // Now overwrite the placeholder to turn the region [placeholder .. end-of-fragments] into a
    // single mdat box. The mdat absorbs the old fragmented moov and all moof+mdat fragments.
    long mdatSize = moovPosition - mdatPlaceholderPosition;
    muxerOutput.setPosition(mdatPlaceholderPosition);
    ByteBuffer mdatHeader = ByteBuffer.allocate(LARGE_SIZE_BOX_HEADER_SIZE);
    mdatHeader.putInt(1); // indicates 64-bit length
    mdatHeader.put(Util.getUtf8Bytes("mdat"));
    mdatHeader.putLong(mdatSize);
    mdatHeader.flip();
    muxerOutput.write(mdatHeader);
  }

  // Fragment writing helpers — mirrored from FragmentedMp4Writer.

  private static ImmutableList<ByteBuffer> createTrafBoxes(
      List<ProcessedTrackInfo> trackInfos, long moofBoxStartPosition) {
    ImmutableList.Builder<ByteBuffer> trafBoxes = new ImmutableList.Builder<>();
    int moofBoxSize = calculateMoofBoxSize(trackInfos);
    int mdatBoxHeaderSize = BOX_HEADER_SIZE;
    int dataOffset = moofBoxSize + mdatBoxHeaderSize;
    for (int i = 0; i < trackInfos.size(); i++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(i);
      trafBoxes.add(
          Boxes.traf(
              Boxes.tfhd(currentTrackInfo.trackId, /* baseDataOffset= */ moofBoxStartPosition),
              Boxes.trun(
                  currentTrackInfo.trackFormat,
                  currentTrackInfo.pendingSamplesMetadata,
                  dataOffset,
                  currentTrackInfo.hasBFrame)));
      dataOffset += currentTrackInfo.totalSamplesSize;
    }
    return trafBoxes.build();
  }

  private static int calculateMoofBoxSize(List<ProcessedTrackInfo> trackInfos) {
    int moofBoxHeaderSize = BOX_HEADER_SIZE;
    int mfhdBoxSize = BOX_HEADER_SIZE + MFHD_BOX_CONTENT_SIZE;
    int trafBoxHeaderSize = BOX_HEADER_SIZE;
    int tfhdBoxSize = BOX_HEADER_SIZE + TFHD_BOX_CONTENT_SIZE;
    int trunBoxHeaderFixedSize = BOX_HEADER_SIZE;
    int trafBoxesSize = 0;
    for (int i = 0; i < trackInfos.size(); i++) {
      ProcessedTrackInfo trackInfo = trackInfos.get(i);
      int trunBoxSize =
          trunBoxHeaderFixedSize
              + getTrunBoxContentSize(trackInfo.pendingSamplesMetadata.size(), trackInfo.hasBFrame);
      trafBoxesSize += trafBoxHeaderSize + tfhdBoxSize + trunBoxSize;
    }
    return moofBoxHeaderSize + mfhdBoxSize + trafBoxesSize;
  }

  private void writeMdatBox(List<ProcessedTrackInfo> trackInfos) throws IOException {
    long totalNumBytesSamples = 0;
    for (int trackInfoIndex = 0; trackInfoIndex < trackInfos.size(); trackInfoIndex++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(trackInfoIndex);
      for (int sampleIndex = 0;
          sampleIndex < currentTrackInfo.pendingSamplesByteBuffer.size();
          sampleIndex++) {
        totalNumBytesSamples +=
            currentTrackInfo.pendingSamplesByteBuffer.get(sampleIndex).remaining();
      }
    }

    int mdatHeaderSize = 8;
    ByteBuffer header = ByteBuffer.allocate(mdatHeaderSize);
    long totalMdatSize = mdatHeaderSize + totalNumBytesSamples;

    checkArgument(
        totalMdatSize <= UNSIGNED_INT_MAX_VALUE,
        "Only 32-bit long mdat size supported in the fragmented MP4");
    header.putInt((int) totalMdatSize);
    header.put(Util.getUtf8Bytes("mdat"));
    header.flip();
    muxerOutput.write(header);

    for (int trackInfoIndex = 0; trackInfoIndex < trackInfos.size(); trackInfoIndex++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(trackInfoIndex);
      for (int sampleIndex = 0;
          sampleIndex < currentTrackInfo.pendingSamplesByteBuffer.size();
          sampleIndex++) {
        muxerOutput.write(currentTrackInfo.pendingSamplesByteBuffer.get(sampleIndex));
      }
    }
    linearByteBufferAllocator.reset();
  }

  private ImmutableList<ProcessedTrackInfo> processAllTracks() {
    ImmutableList.Builder<ProcessedTrackInfo> trackInfos = new ImmutableList.Builder<>();
    for (int i = 0; i < tracks.size(); i++) {
      if (!tracks.get(i).pendingSamplesBufferInfo.isEmpty()) {
        trackInfos.add(processTrack(/* trackId= */ i + 1, tracks.get(i)));
      }
    }
    return trackInfos.build();
  }

  private ProcessedTrackInfo processTrack(int trackId, Track track) {
    checkState(track.pendingSamplesByteBuffer.size() == track.pendingSamplesBufferInfo.size());

    ImmutableList.Builder<ByteBuffer> pendingSamplesByteBuffer = new ImmutableList.Builder<>();
    ImmutableList.Builder<BufferInfo> pendingSamplesBufferInfoBuilder =
        new ImmutableList.Builder<>();
    if (doesSampleContainAnnexBNalUnits(track.format)) {
      while (!track.pendingSamplesByteBuffer.isEmpty()) {
        ByteBuffer currentSampleByteBuffer = track.pendingSamplesByteBuffer.removeFirst();
        currentSampleByteBuffer =
            annexBToAvccConverter.process(currentSampleByteBuffer, linearByteBufferAllocator);
        pendingSamplesByteBuffer.add(currentSampleByteBuffer);
        BufferInfo currentSampleBufferInfo = track.pendingSamplesBufferInfo.removeFirst();
        currentSampleBufferInfo =
            new BufferInfo(
                currentSampleBufferInfo.presentationTimeUs,
                currentSampleByteBuffer.remaining(),
                currentSampleBufferInfo.flags);
        pendingSamplesBufferInfoBuilder.add(currentSampleBufferInfo);
      }
    } else {
      pendingSamplesByteBuffer.addAll(track.pendingSamplesByteBuffer);
      track.pendingSamplesByteBuffer.clear();
      pendingSamplesBufferInfoBuilder.addAll(track.pendingSamplesBufferInfo);
      track.pendingSamplesBufferInfo.clear();
    }

    boolean hasBFrame = false;
    ImmutableList<BufferInfo> pendingSamplesBufferInfo = pendingSamplesBufferInfoBuilder.build();
    List<Integer> sampleDurations =
        Boxes.convertPresentationTimestampsToDurationsVu(
            pendingSamplesBufferInfo,
            track.videoUnitTimebase(),
            LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS,
            track.endOfStreamTimestampUs);

    List<Integer> sampleCompositionTimeOffsets =
        Boxes.calculateSampleCompositionTimeOffsets(
            pendingSamplesBufferInfo, sampleDurations, track.videoUnitTimebase());
    if (!sampleCompositionTimeOffsets.isEmpty()) {
      hasBFrame = true;
    }

    ImmutableList.Builder<FragmentedMp4Writer.SampleMetadata> pendingSamplesMetadata =
        new ImmutableList.Builder<>();
    int totalSamplesSize = 0;
    for (int i = 0; i < pendingSamplesBufferInfo.size(); i++) {
      totalSamplesSize += pendingSamplesBufferInfo.get(i).size;
      pendingSamplesMetadata.add(
          new FragmentedMp4Writer.SampleMetadata(
              sampleDurations.get(i),
              pendingSamplesBufferInfo.get(i).size,
              pendingSamplesBufferInfo.get(i).flags,
              hasBFrame ? sampleCompositionTimeOffsets.get(i) : 0));
    }

    return new ProcessedTrackInfo(
        trackId,
        track.format,
        totalSamplesSize,
        hasBFrame,
        pendingSamplesByteBuffer.build(),
        pendingSamplesBufferInfo,
        pendingSamplesMetadata.build());
  }

  /** Accumulates sample metadata across fragments for the final non-fragmented moov. */
  private static final class TrackAccumulator {
    final List<BufferInfo> writtenSamples = new ArrayList<>();
    final List<Long> chunkOffsets = new ArrayList<>();
    final List<Integer> chunkSampleCounts = new ArrayList<>();
  }

  private static class ProcessedTrackInfo {
    public final int trackId;
    public final Format trackFormat;
    public final int totalSamplesSize;
    public final boolean hasBFrame;
    public final ImmutableList<ByteBuffer> pendingSamplesByteBuffer;
    public final ImmutableList<BufferInfo> pendingSamplesBufferInfo;
    public final ImmutableList<FragmentedMp4Writer.SampleMetadata> pendingSamplesMetadata;

    public ProcessedTrackInfo(
        int trackId,
        Format trackFormat,
        int totalSamplesSize,
        boolean hasBFrame,
        ImmutableList<ByteBuffer> pendingSamplesByteBuffer,
        ImmutableList<BufferInfo> pendingSamplesBufferInfo,
        ImmutableList<FragmentedMp4Writer.SampleMetadata> pendingSamplesMetadata) {
      this.trackId = trackId;
      this.trackFormat = trackFormat;
      this.totalSamplesSize = totalSamplesSize;
      this.hasBFrame = hasBFrame;
      this.pendingSamplesByteBuffer = pendingSamplesByteBuffer;
      this.pendingSamplesBufferInfo = pendingSamplesBufferInfo;
      this.pendingSamplesMetadata = pendingSamplesMetadata;
    }
  }
}
