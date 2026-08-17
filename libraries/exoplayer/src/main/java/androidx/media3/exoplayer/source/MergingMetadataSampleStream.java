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

import static androidx.media3.common.C.RESULT_BUFFER_READ;
import static androidx.media3.common.C.RESULT_FORMAT_READ;
import static androidx.media3.common.C.RESULT_NOTHING_READ;

import android.util.LongSparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link SampleStream} wrapper that merges a sparse metadata {@link SampleStream} into the
 * supplemental data of a video {@link SampleStream}.
 *
 * <p>The metadata stream is read into a PTS-keyed cache. For each video sample read, the wrapper
 * attaches the most recently active metadata payload (with PTS less than or equal to the video
 * sample's PTS) to the video buffer's {@link DecoderInputBuffer#supplementalData}.
 *
 * <p>This is designed for out-of-band metadata tracks such as HAGC (ST 2094-50), where a single
 * metadata sample may apply to multiple consecutive video frames (sparse 1:N mapping).
 */
/* package */ final class MergingMetadataSampleStream implements SampleStream {

  /**
   * The default maximum number of reorder samples used when the format doesn't specify one.
   *
   * <p>This value is 16 because it represents the maximum Decoded Picture Buffer (DPB) size allowed
   * by standard video codecs such as H.264/AVC (ITU-T Rec. H.264 Annex A) and H.265/HEVC (ITU-T
   * Rec. H.265 Annex A).
   */
  private static final int DEFAULT_MAX_NUM_REORDER_SAMPLES = 16;

  private final SampleStream videoSampleStream;
  private final SampleStream metadataSampleStream;
  private final FormatHolder metadataFormatHolder;
  private final DecoderInputBuffer metadataBuffer;
  private final LongSparseArray<byte[]> metadataCache;
  private final int maxCacheSize;

  @Nullable private byte[] activeMetadata;
  private long lastMetadataPtsUs;
  private boolean metadataTrackInvalid;
  private boolean metadataStreamEnded;

  /**
   * Creates a new instance.
   *
   * @param videoSampleStream The video {@link SampleStream} to wrap.
   * @param metadataSampleStream The metadata {@link SampleStream} to merge into the video stream's
   *     supplemental data.
   * @param videoFormat The {@link Format} of the video track, used to determine the cache eviction
   *     bound from {@link Format#maxNumReorderSamples}. Note that video format changes mid-stream
   *     are not currently well-supported by this implementation.
   */
  /* package */ MergingMetadataSampleStream(
      SampleStream videoSampleStream, SampleStream metadataSampleStream, Format videoFormat) {
    this.videoSampleStream = videoSampleStream;
    this.metadataSampleStream = metadataSampleStream;
    this.metadataFormatHolder = new FormatHolder();
    this.metadataBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    this.metadataCache = new LongSparseArray<>();
    this.maxCacheSize = getMaxCacheSize(videoFormat);
    this.lastMetadataPtsUs = C.TIME_UNSET;
  }

  /** Returns the primary video {@link SampleStream} that this wrapper delegates to. */
  /* package */ SampleStream getPrimaryStream() {
    return videoSampleStream;
  }

  /** Returns the metadata {@link SampleStream} that is merged into the video stream. */
  /* package */ SampleStream getMetadataStream() {
    return metadataSampleStream;
  }

  @Override
  public boolean isReady() {
    return videoSampleStream.isReady();
  }

  @Override
  public void maybeThrowError() throws IOException {
    videoSampleStream.maybeThrowError();
    if (!metadataTrackInvalid) {
      metadataSampleStream.maybeThrowError();
    }
  }

  @Override
  public @ReadDataResult int readData(
      FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
    @ReadDataResult int result = videoSampleStream.readData(formatHolder, buffer, readFlags);
    if (result == RESULT_BUFFER_READ
        && !buffer.isEndOfStream()
        && (readFlags & FLAG_OMIT_SAMPLE_DATA) == 0
        && (readFlags & FLAG_PEEK) == 0
        && !metadataTrackInvalid) {
      populateMetadataCache(buffer.timeUs);
      @Nullable byte[] metadata = findActiveMetadata(buffer.timeUs);
      if (metadata != null) {
        attachSupplementalData(buffer, metadata);
      }
    }
    return result;
  }

  @Override
  public int skipData(long positionUs) {
    return videoSampleStream.skipData(positionUs);
  }

  @Override
  public @Flags int getFlags() {
    return videoSampleStream.getFlags();
  }

  /**
   * Clears the internal metadata cache and resets the stream state. This should be called when the
   * playback position changes discontinuously (e.g. on seek or flush).
   */
  /* package */ void reset() {
    metadataCache.clear();
    activeMetadata = null;
    lastMetadataPtsUs = C.TIME_UNSET;
    metadataTrackInvalid = false;
    metadataStreamEnded = false;
    metadataBuffer.clear();
  }

  /**
   * Reads from the metadata stream into the cache until a metadata sample with a PTS greater than
   * {@code videoPtsUs} is encountered, or the metadata stream has no more data.
   */
  private void populateMetadataCache(long videoPtsUs) {
    if (metadataStreamEnded) {
      return;
    }
    while (true) {
      metadataBuffer.clear();
      @ReadDataResult
      int result =
          metadataSampleStream.readData(metadataFormatHolder, metadataBuffer, /* readFlags= */ 0);
      if (result == RESULT_NOTHING_READ) {
        break;
      }
      if (result == RESULT_FORMAT_READ) {
        // Format change in metadata stream; continue reading.
        continue;
      }
      // RESULT_BUFFER_READ
      if (metadataBuffer.isEndOfStream()) {
        metadataStreamEnded = true;
        break;
      }
      long metadataPtsUs = metadataBuffer.timeUs;
      // Detect out-of-order metadata samples (e.g., after a backward seek).
      if (lastMetadataPtsUs != C.TIME_UNSET && metadataPtsUs < lastMetadataPtsUs) {
        metadataTrackInvalid = true;
        metadataCache.clear();
        activeMetadata = null;
      }
      lastMetadataPtsUs = metadataPtsUs;
      ByteBuffer data = metadataBuffer.data;
      if (!metadataTrackInvalid && data != null) {
        metadataBuffer.flip();
        Format format = metadataFormatHolder.format;
        int initializationDataSize = 0;
        if (format != null) {
          for (int i = 0; i < format.initializationData.size(); i++) {
            initializationDataSize += format.initializationData.get(i).length;
          }
        }
        byte[] payload = new byte[initializationDataSize + data.remaining()];
        int offset = 0;
        if (format != null) {
          for (int i = 0; i < format.initializationData.size(); i++) {
            byte[] initData = format.initializationData.get(i);
            System.arraycopy(initData, 0, payload, offset, initData.length);
            offset += initData.length;
          }
        }
        data.get(payload, offset, data.remaining());
        metadataCache.put(metadataPtsUs, payload);
        evictCacheIfNeeded();
      }
      if (metadataPtsUs > videoPtsUs) {
        // We've read past the current video frame's PTS.
        break;
      }
    }
  }

  /**
   * Returns the active metadata payload for the given video PTS, or {@code null} if no metadata is
   * available.
   *
   * <p>The active metadata is the one with the largest PTS that is less than or equal to {@code
   * videoPtsUs}. Once found, it becomes the active metadata and is used for subsequent frames until
   * a newer metadata sample supersedes it.
   */
  @Nullable
  private byte[] findActiveMetadata(long videoPtsUs) {
    // Search the cache for the closest metadata sample with PTS <= videoPtsUs.
    @Nullable byte[] bestMatch = null;
    for (int i = metadataCache.size() - 1; i >= 0; i--) {
      if (metadataCache.keyAt(i) <= videoPtsUs) {
        bestMatch = metadataCache.valueAt(i);
        break;
      }
    }
    if (bestMatch != null) {
      activeMetadata = bestMatch;
    }
    return activeMetadata;
  }

  /** Attaches the metadata payload as supplemental data on the video buffer. */
  private static void attachSupplementalData(DecoderInputBuffer buffer, byte[] metadata) {
    buffer.resetSupplementalData(metadata.length);
    buffer.supplementalData.put(metadata);
    buffer.addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
  }

  /** Evicts the oldest cache entries if the cache exceeds the maximum size. */
  private void evictCacheIfNeeded() {
    while (metadataCache.size() > maxCacheSize) {
      metadataCache.removeAt(0);
    }
  }

  /**
   * Returns the maximum cache size based on the video format's {@link Format#maxNumReorderSamples}.
   */
  private static int getMaxCacheSize(Format videoFormat) {
    // We need maxNumReorderSamples to account for B-frame reordering, +1 for the currently active
    // frame, and +1 to read ahead to the next metadata sample to know when the active one expires.
    if (videoFormat.maxNumReorderSamples != Format.NO_VALUE) {
      return videoFormat.maxNumReorderSamples + 2;
    }
    return DEFAULT_MAX_NUM_REORDER_SAMPLES + 2;
  }
}
