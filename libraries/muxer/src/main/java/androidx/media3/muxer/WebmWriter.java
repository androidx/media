/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static androidx.media3.muxer.WebmConstants.MAX_META_SEEK_SIZE;
import static androidx.media3.muxer.WebmConstants.MKV_UNKNOWN_LENGTH;
import static androidx.media3.muxer.WebmElements.createCuePointElement;
import static androidx.media3.muxer.WebmElements.createEbmlHeaderElement;
import static androidx.media3.muxer.WebmElements.createInfoElement;
import static androidx.media3.muxer.WebmElements.createSeekHeadElement;
import static androidx.media3.muxer.WebmElements.createSimpleBlockElement;
import static androidx.media3.muxer.WebmElements.createTrackElements;
import static androidx.media3.muxer.WebmElements.createUnsignedIntElement;
import static androidx.media3.muxer.WebmElements.createVoidElement;
import static androidx.media3.muxer.WebmElements.uintToMinimumLengthByteBuffer;
import static androidx.media3.muxer.WebmElements.wrapIntoElement;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.muxer.WebmConstants.MkvEbmlElement;
import androidx.media3.muxer.WebmConstants.TrackNumber;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A writer for creating a WebM container file.
 *
 * <p><a href="https://datatracker.ietf.org/doc/rfc9559/">WebM Specification</a>.
 *
 * <p>File Layout of a WebM file:
 *
 * <pre>
 * EBML
 * Segment
 * ├── SeekHead
 * │   ├── Seek
 * │   ├── Seek
 * │   ├── Seek
 * │   └── Seek
 * ├── Void
 * ├── Info
 * ├── Tracks
 * │   └── TrackEntry
 * │       └── Video
 * │           └── Colour
 * │               ├── MasteringMetadata
 * │               ├── MatrixCoefficients
 * │               ├── ...
 * │   └── TrackEntry
 * │       └── Audio
 * │           ├── SamplingFrequency
 * │           ├── Channels
 * │           ├── BitDepth
 * │           ├── ...
 * ├── Cluster
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   └── SimpleBlock
 * ├── Cluster
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   ├── SimpleBlock
 * │   └── SimpleBlock
 *   ...
 * ├── Cues
 * │   ├── CuePoint
 * │   │   └── CueTrackPositions
 * │   ├── CuePoint
 * │   │   └── CueTrackPositions
 * │   ├── CuePoint
 * │   │   └── CueTrackPositions
 * ...
 * </pre>
 */
/* package */ final class WebmWriter {

  // As per the default TIMESTAMP_SCALE of 1,000,000, one Segment Tick represents 1,000,000
  // nanoseconds.
  private static final int TIMESTAMP_SCALE = 1_000_000;
  private static final int MAX_CLUSTER_DURATION_US = 2_000_000;

  private final SeekableMuxerOutput muxerOutput;
  private final boolean sampleCopyEnabled;
  private final List<Track> addedTracks;
  private final List<ByteBuffer> cuePoints;

  private boolean writtenSegmentHeader;
  private long trackElementStart;
  private long infoElementStart;
  private long segmentDataStart;
  private long firstSampleTimestampUs;
  private long lastSampleTimestampUs;

  /**
   * Creates a new WebmWriter.
   *
   * @param muxerOutput The {@link SeekableMuxerOutput} to write the media data to.
   * @param sampleCopyEnabled Whether to enable the sample copy.
   */
  WebmWriter(SeekableMuxerOutput muxerOutput, boolean sampleCopyEnabled) {
    this.muxerOutput = muxerOutput;
    this.sampleCopyEnabled = sampleCopyEnabled;
    addedTracks = new ArrayList<>();
    cuePoints = new ArrayList<>();
    firstSampleTimestampUs = C.TIME_UNSET;
    lastSampleTimestampUs = C.TIME_UNSET;
  }

  /**
   * Adds a track of the given {@link Format}.
   *
   * @param trackId The track id for the track.
   * @param format The {@link Format} for the track.
   * @return A unique {@link Track}. It should be used in {@link #writeSampleData}.
   */
  public Track addTrack(int trackId, Format format) {
    // Tracks can only be added before writing any samples.
    checkArgument(!writtenSegmentHeader);

    // Sort key is redundant for WebM, hardcoding it to 1.
    Track track = new Track(trackId, format, /* sortKey= */ 1, sampleCopyEnabled);
    addedTracks.add(track);
    return track;
  }

  /**
   * Writes encoded sample data.
   *
   * @param track The {@link Track} for which this sample is being written.
   * @param byteBuffer The encoded sample.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws IOException If there is any error while writing data to the {@link SeekableMuxerOutput
   *     muxer output}.
   */
  public void writeSampleData(Track track, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws IOException {
    if (!writtenSegmentHeader) {
      writeSegmentHeader();
      writtenSegmentHeader = true;
    }
    // Create a new cluster if the next sample is a key frame or if the duration of the cluster
    // exceeds the maximum duration.
    if (shouldCreateCluster(track, bufferInfo)) {
      createCluster();
    }

    track.writeSampleData(byteBuffer, bufferInfo);
    firstSampleTimestampUs =
        firstSampleTimestampUs == C.TIME_UNSET
            ? bufferInfo.presentationTimeUs
            : min(firstSampleTimestampUs, bufferInfo.presentationTimeUs);
    lastSampleTimestampUs = max(lastSampleTimestampUs, bufferInfo.presentationTimeUs);
  }

  /**
   * Writes the segment header to the output file.
   *
   * <p>This includes the EBML header and the top-level Segment element with placeholders for the
   * SeekHead ane Info elements.
   */
  private void writeSegmentHeader() throws IOException {
    muxerOutput.write(createEbmlHeaderElement());

    // Write Segment element with unknown size for now.
    muxerOutput.write(uintToMinimumLengthByteBuffer(MkvEbmlElement.SEGMENT));
    muxerOutput.write(uintToMinimumLengthByteBuffer(MKV_UNKNOWN_LENGTH));

    // Reserve space for SeekHead.
    segmentDataStart = muxerOutput.getPosition();
    // Write Empty void element for SeekHead with estimated size.
    muxerOutput.write(createVoidElement(MAX_META_SEEK_SIZE));
    infoElementStart = muxerOutput.getPosition();
    // Write Info element with segment duration set to 0 for now. This will be updated in {@link
    // #close()}.
    muxerOutput.write(createInfoElement(/* segmentDuration= */ 0));
    trackElementStart = muxerOutput.getPosition();

    // Write Tracks elements.
    muxerOutput.write(createTrackElements(addedTracks));
  }

  private boolean shouldCreateCluster(Track track, BufferInfo nextSampleInfo) {
    if (track.pendingSamplesBufferInfo.isEmpty()) {
      return false;
    }

    if (MimeTypes.isVideo(track.format.sampleMimeType)) {
      return (nextSampleInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0;
    } else { // Audio track.
      long firstSampleTimestampUs = track.pendingSamplesBufferInfo.getFirst().presentationTimeUs;
      return (nextSampleInfo.presentationTimeUs - firstSampleTimestampUs) > MAX_CLUSTER_DURATION_US;
    }
  }

  /**
   * Creates a cluster from any pending samples, writing them to the output file in presentation
   * order.
   *
   * @throws IOException If an error occurs while writing to the output file.
   */
  private void createCluster() throws IOException {
    boolean isVideoKeyFramePresent = false;
    // Add all pending samples of all tracks to frames in presentation order.
    PriorityQueue<WebmFrame> frames = new PriorityQueue<>();
    for (int i = 0; i < addedTracks.size(); i++) {
      Track polledTrack = addedTracks.get(i);
      while (!polledTrack.pendingSamplesByteBuffer.isEmpty()) {
        boolean isAudioFrame = MimeTypes.isAudio(polledTrack.format.sampleMimeType);
        WebmFrame frame =
            new WebmFrame(
                isAudioFrame ? TrackNumber.AUDIO : TrackNumber.VIDEO,
                polledTrack.pendingSamplesByteBuffer.removeFirst(),
                polledTrack.pendingSamplesBufferInfo.removeFirst(),
                isAudioFrame);
        frames.add(frame);
        // For video tracks, need to ensure that a key frame is present in the cluster.
        if (!frame.isAudioFrame && (frame.bufferInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0) {
          isVideoKeyFramePresent = true;
        }
      }
    }

    if (frames.isEmpty()) {
      return;
    }

    WebmFrame firstFrame = checkNotNull(frames.peek());
    long clusterTimestampUs = firstFrame.bufferInfo.presentationTimeUs;

    List<ByteBuffer> simpleBlockCluster = new ArrayList<>();
    // Cluster timestamps are relative to the first minimum sample timestamp.
    long clusterTimestampTicks = usToSegmentTicks(clusterTimestampUs - firstSampleTimestampUs);
    simpleBlockCluster.add(
        createUnsignedIntElement(MkvEbmlElement.TIMESTAMP, clusterTimestampTicks));

    while (!frames.isEmpty()) {
      WebmFrame frame = checkNotNull(frames.poll());
      simpleBlockCluster.add(
          createSimpleBlockElement(
              frame.trackNumber,
              // SimpleBlock timestamp is relative to the cluster timestamp.
              usToSegmentTicks(frame.bufferInfo.presentationTimeUs - clusterTimestampUs),
              (frame.bufferInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0,
              frame.data));
    }

    // The offset of the current cluster in the segment data. This is used to calculate the
    // cluster position in the Cues element.
    long currentClusterOffset = muxerOutput.getPosition() - segmentDataStart;
    muxerOutput.write(wrapIntoElement(MkvEbmlElement.CLUSTER, simpleBlockCluster));

    // Prefer to use the video track number for the CuePoint element if a video key frame is
    // present in the cluster.
    int cueTrackNumber = isVideoKeyFramePresent ? TrackNumber.VIDEO : firstFrame.trackNumber;
    // Add a CuePoint element for the cluster to make output file seekable.
    cuePoints.add(
        createCuePointElement(
            /* timestampTicks= */ clusterTimestampTicks,
            /* trackNumber= */ cueTrackNumber,
            /* clusterOffset= */ currentClusterOffset));
  }

  /**
   * Finalizes the output and closes WebmWriter.
   *
   * <p>The WebmWriter cannot be used anymore once this method returns.
   *
   * @throws IOException If the WebmWriter fails to finish writing the output.
   */
  public void close() throws IOException {
    // Write the last cluster.
    createCluster();
    // Write Cues master element.
    long cuesElementStart = muxerOutput.getPosition();
    ByteBuffer cuesElement = wrapIntoElement(MkvEbmlElement.CUES, cuePoints);
    muxerOutput.write(cuesElement);

    long segmentDataEnd = muxerOutput.getPosition();
    long segmentSize = segmentDataEnd - segmentDataStart;
    // Write segment size. The size of segment size is 8 bytes.
    muxerOutput.setPosition(segmentDataStart - 8);
    muxerOutput.write(EbmlUtils.encodeVIntWithWidth(segmentSize, /* width= */ 8));

    muxerOutput.setPosition(infoElementStart);
    // TODO(b/447591371): Update the segment duration by adding the last sample duration.
    long durationUs = lastSampleTimestampUs - firstSampleTimestampUs;
    // Info element is of fixed size and is not expected to change.
    muxerOutput.write(
        createInfoElement(/* segmentDuration= */ (float) usToSegmentTicks(durationUs)));
    long infoElementEnd = muxerOutput.getPosition();
    // Info element should be ended at the start of the track elements.
    checkState(infoElementEnd == trackElementStart);

    // Update SeekHead element.
    muxerOutput.setPosition(segmentDataStart);
    // The SeekHead element contains the offset of other elements, relative to the start of
    // the segment data.
    ByteBuffer seekHead =
        createSeekHeadElement(
            infoElementStart - segmentDataStart,
            trackElementStart - segmentDataStart,
            cuesElementStart - segmentDataStart);
    muxerOutput.write(seekHead);
    long seekHeadEndPosition = muxerOutput.getPosition();
    int totalFreeSize = (int) (infoElementStart - seekHeadEndPosition);
    ByteBuffer voidElement = createVoidElement(totalFreeSize);
    muxerOutput.write(voidElement);
  }

  /** Represents a single frame of WebM media. */
  private static final class WebmFrame implements Comparable<WebmFrame> {
    /** The track number of the frame. */
    private final @TrackNumber int trackNumber;

    /** The data of the frame. */
    private final ByteBuffer data;

    /** The {@link BufferInfo} of the frame. */
    private final BufferInfo bufferInfo;

    /** Whether the frame is of an audio track. */
    private final Boolean isAudioFrame;

    /**
     * @param trackNumber The track number of the frame.
     * @param data The data of the frame.
     * @param bufferInfo The {@link BufferInfo} of the frame.
     * @param isAudioFrame Whether the frame is of an audio track.
     */
    WebmFrame(
        @TrackNumber int trackNumber,
        ByteBuffer data,
        BufferInfo bufferInfo,
        boolean isAudioFrame) {
      this.trackNumber = trackNumber;
      this.data = data;
      this.bufferInfo = bufferInfo;
      this.isAudioFrame = isAudioFrame;
    }

    @Override
    public int compareTo(WebmFrame other) {
      int timeComparison =
          Long.compare(bufferInfo.presentationTimeUs, other.bufferInfo.presentationTimeUs);
      if (timeComparison != 0) {
        return timeComparison;
      } else {
        // If presentation times are equal, prioritize audio frames.
        return Boolean.compare(isAudioFrame, other.isAudioFrame);
      }
    }
  }

  private long usToSegmentTicks(long timestampUs) {
    // The TimestampScale is in nanoseconds. When using the default TIMESTAMP_SCALE of 1,000,000,
    // one Segment Tick represents 1,000,000 nanoseconds. To convert a timestamp from microseconds
    // (`timestampUs`) to Segment Ticks, first convert the microseconds to nanoseconds by
    // multiplying by 1000, and then divide by the TIMESTAMP_SCALE.
    return Util.scaleLargeTimestamp(timestampUs, /* multiplier= */ 1000, TIMESTAMP_SCALE);
  }
}
