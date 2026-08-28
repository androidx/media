/*
 * Copyright 2022 The Android Open Source Project
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

import static androidx.media3.common.util.Util.constrainValue;
import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.muxer.Mp4Muxer.TrackReferenceType;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Represents a single track (audio, video, metadata etc.). */
/* package */ final class Track {
  public final int id;
  public final Format format;
  public final int sortKey;
  public final List<BufferInfo> writtenSamples;
  public final List<Long> writtenChunkOffsets;
  public final List<Integer> writtenChunkSampleCounts;
  public final List<Boxes.TfraEntry> tfraEntries;
  public final Deque<BufferInfo> pendingSamplesBufferInfo;
  public final Deque<ByteBuffer> pendingSamplesByteBuffer;
  // Map from the reference type (e.g. "cdsc") to the list of referenced track ids.
  public final Map<Integer, List<Integer>> trackReferences;
  public boolean hadKeyframe;
  @Nullable public byte[] parsedCsd;
  public long endOfStreamTimestampUs;

  private final boolean sampleCopyEnabled;

  /** Creates an instance with {@code sortKey} set to 1. */
  public Track(int trackId, Format format, boolean sampleCopyEnabled) {
    this(trackId, format, /* sortKey= */ 1, sampleCopyEnabled);
  }

  /**
   * Creates an instance.
   *
   * @param trackId A unique id for the track.
   * @param format The {@link Format} for the track.
   * @param sortKey The key used for sorting the track list.
   * @param sampleCopyEnabled Whether sample copying is enabled.
   */
  public Track(int trackId, Format format, int sortKey, boolean sampleCopyEnabled) {
    id = trackId;
    this.format = format;
    this.sortKey = sortKey;
    this.sampleCopyEnabled = sampleCopyEnabled;
    writtenSamples = new ArrayList<>();
    writtenChunkOffsets = new ArrayList<>();
    writtenChunkSampleCounts = new ArrayList<>();
    tfraEntries = new ArrayList<>();
    pendingSamplesBufferInfo = new ArrayDeque<>();
    pendingSamplesByteBuffer = new ArrayDeque<>();
    trackReferences = new HashMap<>();
    endOfStreamTimestampUs = C.TIME_UNSET;
  }

  public void writeSampleData(ByteBuffer byteBuffer, BufferInfo bufferInfo) {
    checkArgument(
        endOfStreamTimestampUs == C.TIME_UNSET,
        "Samples can not be written after writing a sample with"
            + " MediaCodec.BUFFER_FLAG_END_OF_STREAM flag");
    //  Skip empty samples.
    if (bufferInfo.size == 0 || byteBuffer.remaining() == 0) {
      if ((bufferInfo.flags & C.BUFFER_FLAG_END_OF_STREAM) != 0) {
        endOfStreamTimestampUs = bufferInfo.presentationTimeUs;
      }
      return;
    }

    if ((bufferInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0) {
      hadKeyframe = true;
    }

    // The video track must start with a key frame.
    if (!hadKeyframe && MimeTypes.isVideo(format.sampleMimeType)) {
      return;
    }

    ByteBuffer byteBufferToAdd = byteBuffer;
    if (sampleCopyEnabled) {
      // Copy sample data and release the original buffer.
      byteBufferToAdd = ByteBuffer.allocateDirect(byteBuffer.remaining());
      byteBufferToAdd.put(byteBuffer);
      byteBufferToAdd.rewind();
    }

    // Always copy the buffer info as it is retained until the track is finalized.
    BufferInfo bufferInfoToAdd =
        new BufferInfo(
            bufferInfo.presentationTimeUs, byteBufferToAdd.remaining(), bufferInfo.flags);

    pendingSamplesBufferInfo.addLast(bufferInfoToAdd);
    pendingSamplesByteBuffer.addLast(byteBufferToAdd);
  }

  public void addTrackReference(
      @TrackReferenceType int referenceType, List<Integer> referencedTrackIds) {
    trackReferences.put(referenceType, referencedTrackIds);
  }

  public int videoUnitTimebase() {
    if (MimeTypes.isAudio(format.sampleMimeType)) {
      return format.sampleRate != Format.NO_VALUE ? format.sampleRate : 48_000;
    }
    // 1. Primary: Calculate timescale from frame rate (see b/270583563#comment6).
    if (format.frameRate != Format.NO_VALUE && format.frameRate >= 1.0f) {
      int roundedFrameRate = Math.round(format.frameRate);
      return constrainValue(roundedFrameRate * 1000, 1_000, 1_000_000);
    }
    // 2. First fallback: Use track timescale if present in format metadata.
    if (format.metadata != null) {
      for (int i = 0; i < format.metadata.length(); i++) {
        Metadata.Entry entry = format.metadata.get(i);
        if (entry instanceof Mp4TimestampData) {
          long sourceTimescale = ((Mp4TimestampData) entry).timescale;
          if (sourceTimescale > 0 && sourceTimescale <= Integer.MAX_VALUE) {
            return (int) sourceTimescale;
          }
        }
      }
    }
    // 3. Second fallback: Default to 90 kHz video timescale.
    return 90_000;
  }
}
