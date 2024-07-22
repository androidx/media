/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.container.NalUnitUtil.NAL_START_CODE;
import static androidx.media3.container.NalUnitUtil.NAL_UNIT_TYPE_PREFIX;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.metadata.mp4.SlowMotionData;
import androidx.media3.extractor.metadata.mp4.SmtaMetadataEntry;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Sample transformer that flattens SEF slow motion videos in H.264/AVC and H.265/HEVC format using
 * temporal layers.
 *
 * <p>If the input is not an SEF slow motion video, samples will be unchanged.
 */

/* package */ final class SefSlowMotionFlattener {

  /**
   * The frame rate of SEF slow motion videos, in fps.
   *
   * <p>This frame rate is constant and is not equal to the capture frame rate. It is set to a lower
   * value so that the video is entirely played in slow motion on players that do not support SEF
   * slow motion.
   */
  @VisibleForTesting /* package */ static final int INPUT_FRAME_RATE = 30;

  /**
   * The target frame rate of the flattened output, in fps.
   *
   * <p>The output frame rate might be slightly different and might not be constant.
   */
  private static final int TARGET_OUTPUT_FRAME_RATE = 30;

  private static final int NAL_START_CODE_LENGTH = NAL_START_CODE.length;

  private final byte[] scratch;

  /** The SEF slow motion configuration of the input. */
  @Nullable private final SlowMotionData slowMotionData;

  /** The MIME type of video data stored in input buffers. */
  private final String mimeType;

  /**
   * An iterator iterating over the slow motion segments, pointing at the segment following {@code
   * nextSegmentInfo}, if any.
   */
  private final Iterator<SlowMotionData.Segment> segmentIterator;

  /** The frame rate at which the input has been captured, in fps. */
  private final float captureFrameRate;

  /** The maximum SVC temporal layer present in the input. */
  private final int inputMaxLayer;

  /**
   * The maximum SVC temporal layer value of the frames that should be kept in the input (or a part
   * of it) so that it is played at normal speed.
   */
  private final int normalSpeedMaxLayer;

  /**
   * The {@link SegmentInfo} describing the current slow motion segment, or null if the current
   * frame is not in such a segment.
   */
  @Nullable private SegmentInfo currentSegmentInfo;

  /**
   * The {@link SegmentInfo} describing the slow motion segment following (not including) the
   * current frame, or null if there is no such segment.
   */
  @Nullable private SegmentInfo nextSegmentInfo;

  /**
   * The time delta to be added to the output timestamps before scaling to take the slow motion
   * segments into account, in microseconds.
   */
  private long frameTimeDeltaUs;

  /**
   * The presentation time for the last {@linkplain #dropOrTransformSample(ByteBuffer, long)
   * processed sample}.
   */
  private long lastSamplePresentationTimeUs;

  public SefSlowMotionFlattener(Format format) {
    scratch = new byte[NAL_START_CODE_LENGTH];
    lastSamplePresentationTimeUs = C.TIME_UNSET;
    MetadataInfo metadataInfo = getMetadataInfo(format.metadata);
    slowMotionData = metadataInfo.slowMotionData;
    mimeType = checkNotNull(format.sampleMimeType);
    if (slowMotionData != null) {
      checkArgument(
          mimeType.equals(MimeTypes.VIDEO_H264) || mimeType.equals(MimeTypes.VIDEO_H265),
          "Unsupported MIME type for SEF slow motion video track: " + mimeType);
    }
    List<SlowMotionData.Segment> segments =
        slowMotionData != null ? slowMotionData.segments : ImmutableList.of();
    segmentIterator = segments.iterator();
    captureFrameRate = metadataInfo.captureFrameRate;
    inputMaxLayer = metadataInfo.inputMaxLayer;
    normalSpeedMaxLayer = metadataInfo.normalSpeedMaxLayer;
    nextSegmentInfo =
        segmentIterator.hasNext()
            ? new SegmentInfo(segmentIterator.next(), inputMaxLayer, normalSpeedMaxLayer)
            : null;
  }

  /**
   * Applies slow motion flattening by either indicating that the buffer's data should be dropped or
   * transforming it in place.
   *
   * <p>After calling this method, call {@link #getSamplePresentationTimeUs()} to get the new
   * presentation time for the sample (whether it was dropped or not).
   *
   * @return Whether the buffer should be dropped.
   */
  public boolean dropOrTransformSample(ByteBuffer buffer, long bufferTimeUs) {
    if (slowMotionData == null) {
      // The input is not an SEF slow motion video.
      lastSamplePresentationTimeUs = bufferTimeUs;
      return false;
    }

    int originalPosition = buffer.position();
    buffer.position(originalPosition + NAL_START_CODE_LENGTH);
    buffer.get(scratch, 0, 4);
    int layer;
    if (mimeType.equals(MimeTypes.VIDEO_H264)) {
      int nalUnitType = scratch[0] & 0x1F;
      boolean svcExtensionFlag = ((scratch[1] & 0xFF) >> 7) == 1;
      checkState(
          nalUnitType == NAL_UNIT_TYPE_PREFIX && svcExtensionFlag,
          "Missing SVC extension prefix NAL unit.");
      layer = (scratch[3] & 0xFF) >> 5; // temporal_id
    } else if (mimeType.equals(MimeTypes.VIDEO_H265)) {
      layer = (scratch[1] & 0x07) - 1; // nuh_temporal_id_plus1
    } else {
      throw new IllegalStateException();
    }

    boolean shouldKeepFrame = processCurrentFrame(layer, bufferTimeUs);
    // Update the timestamp regardless of whether the buffer is dropped as the timestamp may be
    // reused for the empty end-of-stream buffer.
    lastSamplePresentationTimeUs = getCurrentFrameOutputTimeUs(bufferTimeUs);
    if (shouldKeepFrame) {
      buffer.position(originalPosition);
      return false;
    }
    return true;
  }

  /**
   * Returns the new presentation time for the last sample handled via {@link
   * #dropOrTransformSample(ByteBuffer, long)}.
   */
  public long getSamplePresentationTimeUs() {
    checkState(lastSamplePresentationTimeUs != C.TIME_UNSET);
    return lastSamplePresentationTimeUs;
  }

  /**
   * Processes the current frame and returns whether it should be kept.
   *
   * @param layer The frame temporal SVC layer.
   * @param timeUs The frame presentation time, in microseconds.
   * @return Whether to keep the current frame.
   */
  @VisibleForTesting
  /* package */ boolean processCurrentFrame(int layer, long timeUs) {
    // Skip segments in the unlikely case that they do not contain any frame start time.
    while (nextSegmentInfo != null && timeUs >= nextSegmentInfo.endTimeUs) {
      enterNextSegment();
    }

    if (nextSegmentInfo != null && timeUs >= nextSegmentInfo.startTimeUs) {
      enterNextSegment();
    } else if (currentSegmentInfo != null && timeUs >= currentSegmentInfo.endTimeUs) {
      leaveCurrentSegment();
    }

    int maxLayer = currentSegmentInfo != null ? currentSegmentInfo.maxLayer : normalSpeedMaxLayer;
    return layer <= maxLayer || shouldKeepFrameForOutputValidity(layer, timeUs);
  }

  /** Updates the segments information so that the next segment becomes the current segment. */
  private void enterNextSegment() {
    if (currentSegmentInfo != null) {
      leaveCurrentSegment();
    }
    currentSegmentInfo = nextSegmentInfo;
    nextSegmentInfo =
        segmentIterator.hasNext()
            ? new SegmentInfo(segmentIterator.next(), inputMaxLayer, normalSpeedMaxLayer)
            : null;
  }

  /**
   * Updates the segments information so that there is no current segment. The next segment is
   * unchanged.
   */
  @RequiresNonNull("currentSegmentInfo")
  private void leaveCurrentSegment() {
    frameTimeDeltaUs +=
        (currentSegmentInfo.endTimeUs - currentSegmentInfo.startTimeUs)
            * (currentSegmentInfo.speedDivisor - 1);
    currentSegmentInfo = null;
  }

  /**
   * Returns whether the frames of the next segment are based on the current frame. In this case,
   * the current frame should be kept in order for the output to be valid.
   *
   * @param layer The frame temporal SVC layer.
   * @param timeUs The frame presentation time, in microseconds.
   * @return Whether to keep the current frame.
   */
  private boolean shouldKeepFrameForOutputValidity(int layer, long timeUs) {
    if (nextSegmentInfo == null || layer >= nextSegmentInfo.maxLayer) {
      return false;
    }

    long frameOffsetToSegmentEstimate =
        (nextSegmentInfo.startTimeUs - timeUs) * INPUT_FRAME_RATE / C.MICROS_PER_SECOND;
    float allowedError = 0.45f;
    float baseMaxFrameOffsetToSegment =
        -(1 << (inputMaxLayer - nextSegmentInfo.maxLayer)) + allowedError;
    for (int i = 1; i < nextSegmentInfo.maxLayer; i++) {
      if (frameOffsetToSegmentEstimate < (1 << (inputMaxLayer - i)) + baseMaxFrameOffsetToSegment) {
        if (layer <= i) {
          return true;
        }
      } else {
        return false;
      }
    }
    return false;
  }

  /**
   * Returns the time of the current frame in the output, in microseconds.
   *
   * <p>This time is computed so that segments start and end at the correct times. As a result, the
   * output frame rate might be variable.
   *
   * <p>This method can only be called if all the frames until the current one (included) have been
   * {@linkplain #processCurrentFrame(int, long) processed} in order, and if the next frames have
   * not been processed yet.
   */
  @VisibleForTesting
  /* package */ long getCurrentFrameOutputTimeUs(long inputTimeUs) {
    long outputTimeUs = inputTimeUs + frameTimeDeltaUs;
    if (currentSegmentInfo != null) {
      outputTimeUs +=
          (inputTimeUs - currentSegmentInfo.startTimeUs) * (currentSegmentInfo.speedDivisor - 1);
    }
    return Math.round(outputTimeUs * INPUT_FRAME_RATE / captureFrameRate);
  }

  /** Returns the {@link MetadataInfo} derived from the {@link Metadata} provided. */
  private static MetadataInfo getMetadataInfo(@Nullable Metadata metadata) {
    MetadataInfo metadataInfo = new MetadataInfo();
    if (metadata == null) {
      return metadataInfo;
    }

    for (int i = 0; i < metadata.length(); i++) {
      Metadata.Entry entry = metadata.get(i);
      if (entry instanceof SmtaMetadataEntry) {
        SmtaMetadataEntry smtaMetadataEntry = (SmtaMetadataEntry) entry;
        metadataInfo.captureFrameRate = smtaMetadataEntry.captureFrameRate;
        metadataInfo.inputMaxLayer = smtaMetadataEntry.svcTemporalLayerCount - 1;
      } else if (entry instanceof SlowMotionData) {
        metadataInfo.slowMotionData = (SlowMotionData) entry;
      }
    }

    if (metadataInfo.slowMotionData == null) {
      return metadataInfo;
    }

    checkState(metadataInfo.inputMaxLayer != C.INDEX_UNSET, "SVC temporal layer count not found.");
    checkState(metadataInfo.captureFrameRate != C.RATE_UNSET, "Capture frame rate not found.");
    checkState(
        metadataInfo.captureFrameRate % 1 == 0
            && metadataInfo.captureFrameRate % TARGET_OUTPUT_FRAME_RATE == 0,
        "Invalid capture frame rate: " + metadataInfo.captureFrameRate);

    int frameCountDivisor = (int) metadataInfo.captureFrameRate / TARGET_OUTPUT_FRAME_RATE;
    int normalSpeedMaxLayer = metadataInfo.inputMaxLayer;
    while (normalSpeedMaxLayer >= 0) {
      if ((frameCountDivisor & 1) == 1) {
        // Set normalSpeedMaxLayer only if captureFrameRate / TARGET_OUTPUT_FRAME_RATE is a power of
        // 2. Otherwise, the target output frame rate cannot be reached because removing a layer
        // divides the number of frames by 2.
        checkState(
            frameCountDivisor >> 1 == 0,
            "Could not compute normal speed max SVC layer for capture frame rate  "
                + metadataInfo.captureFrameRate);
        metadataInfo.normalSpeedMaxLayer = normalSpeedMaxLayer;
        break;
      }
      frameCountDivisor >>= 1;
      normalSpeedMaxLayer--;
    }
    return metadataInfo;
  }

  /** Metadata of an SEF slow motion input. */
  private static final class MetadataInfo {
    /**
     * The frame rate at which the slow motion video has been captured in fps, or {@link
     * C#RATE_UNSET} if it is unknown or invalid.
     */
    public float captureFrameRate;

    /**
     * The maximum SVC layer value of the input frames, or {@link C#INDEX_UNSET} if it is unknown.
     */
    public int inputMaxLayer;

    /**
     * The maximum SVC layer value of the frames to keep in order to play the video at normal speed
     * at {@link #TARGET_OUTPUT_FRAME_RATE}, or {@link C#INDEX_UNSET} if it is unknown.
     */
    public int normalSpeedMaxLayer;

    /** The input {@link SlowMotionData}. */
    @Nullable public SlowMotionData slowMotionData;

    public MetadataInfo() {
      captureFrameRate = C.RATE_UNSET;
      inputMaxLayer = C.INDEX_UNSET;
      normalSpeedMaxLayer = C.INDEX_UNSET;
    }
  }

  /** Information about a slow motion segment. */
  private static final class SegmentInfo {
    /** The segment start time, in microseconds. */
    public final long startTimeUs;

    /** The segment end time, in microseconds. */
    public final long endTimeUs;

    /**
     * The segment speedDivisor.
     *
     * @see SlowMotionData.Segment#speedDivisor
     */
    public final int speedDivisor;

    /**
     * The maximum SVC layer value of the frames to keep in the segment in order to slow down the
     * segment by {@code speedDivisor}.
     */
    public final int maxLayer;

    public SegmentInfo(SlowMotionData.Segment segment, int inputMaxLayer, int normalSpeedLayer) {
      this.startTimeUs = Util.msToUs(segment.startTimeMs);
      this.endTimeUs = Util.msToUs(segment.endTimeMs);
      this.speedDivisor = segment.speedDivisor;
      this.maxLayer = getSlowMotionMaxLayer(speedDivisor, inputMaxLayer, normalSpeedLayer);
    }

    private static int getSlowMotionMaxLayer(
        int speedDivisor, int inputMaxLayer, int normalSpeedMaxLayer) {
      int maxLayer = normalSpeedMaxLayer;
      // Increase the maximum layer to increase the number of frames in the segment. For every layer
      // increment, the number of frames is doubled.
      int shiftedSpeedDivisor = speedDivisor;
      while (shiftedSpeedDivisor > 0) {
        if ((shiftedSpeedDivisor & 1) == 1) {
          checkState(shiftedSpeedDivisor >> 1 == 0, "Invalid speed divisor: " + speedDivisor);
          break;
        }
        maxLayer++;
        shiftedSpeedDivisor >>= 1;
      }

      // The optimal segment max layer can be larger than the input max layer. In this case, it is
      // not possible to have speedDivisor times more frames in the segment than outside the
      // segments. The desired speed must therefore be reached by keeping all the frames and by
      // decreasing the frame rate in the segment.
      return min(maxLayer, inputMaxLayer);
    }
  }
}
