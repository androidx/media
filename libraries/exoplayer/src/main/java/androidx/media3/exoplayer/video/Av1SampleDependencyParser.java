/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.video;

import static androidx.media3.container.ObuParser.OBU_FRAME;
import static androidx.media3.container.ObuParser.OBU_FRAME_HEADER;
import static androidx.media3.container.ObuParser.OBU_PADDING;
import static androidx.media3.container.ObuParser.OBU_SEQUENCE_HEADER;
import static androidx.media3.container.ObuParser.OBU_TEMPORAL_DELIMITER;
import static androidx.media3.container.ObuParser.split;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.ObuParser;
import androidx.media3.container.ObuParser.FrameHeader;
import androidx.media3.container.ObuParser.SequenceHeader;
import java.nio.ByteBuffer;
import java.util.List;

/** An AV1 bitstream parser that identifies frames that are not depended on. */
@UnstableApi
public final class Av1SampleDependencyParser {
  /**
   * When {@link #sampleLimitAfterSkippingNonReferenceFrame(ByteBuffer, boolean)} partially skips a
   * temporal unit, the decoder input buffer is left with extra reference frames that need to be
   * decoded.
   *
   * <p>The AV1 spec defines {@code NUM_REF_FRAMES = 8} - delaying more than 8 reference frames will
   * overwrite the same output slots.
   */
  private static final int MAX_OBU_COUNT_FOR_PARTIAL_SKIP = 8;

  /**
   * Number of bytes at the beginning of a keyframe input buffer that are guaranteed to hold the
   * full sequence header OBU.
   *
   * <p>Key frame packets start with an optional temporal delimiter, and an optional sequence
   * header. The largest possible temporal delimiter is 10 bytes, and the largest possible sequence
   * header is 393 bytes.
   *
   * <p>See <a href=https://aomediacodec.github.io/av1-spec/#ordering-of-obus>Ordering of OBUs</a>.
   */
  private static final int MAX_BYTES_FROM_KEYFRAME_TO_READ = 500;

  /**
   * Buffer that holds truncated sample data passed to {@link #queueInputBuffer}.
   *
   * <p>Use this buffer to delay expensive parsing of data until it's needed, in {@link
   * #sampleLimitAfterSkippingNonReferenceFrame}.
   */
  private final ByteBuffer delayedKeyFrameTruncatedSample;

  @Nullable private SequenceHeader sequenceHeader;

  public Av1SampleDependencyParser() {
    delayedKeyFrameTruncatedSample = ByteBuffer.allocateDirect(MAX_BYTES_FROM_KEYFRAME_TO_READ);
  }

  /**
   * Returns the new sample {@linkplain ByteBuffer#limit() limit} after deleting any frames that are
   * not used as reference.
   *
   * <p>Each AV1 temporal unit must have exactly one shown frame. Other frames in the temporal unit
   * that aren't shown are used as reference, but the shown frame may not be used as reference.
   * Frequently, the shown frame is the last frame in the temporal unit.
   *
   * <p>If the last frame in the temporal unit is a non-reference {@link ObuParser#OBU_FRAME} or
   * {@link ObuParser#OBU_FRAME_HEADER}, this method returns a new {@link ByteBuffer#limit()} value
   * that would leave only the frames used as reference in the input {@code sample}.
   *
   * <p>See <a href=https://aomediacodec.github.io/av1-spec/#ordering-of-obus>Ordering of OBUs</a>.
   *
   * @param sample The sample data for one AV1 temporal unit.
   * @param skipFrameHeaders Whether to skip {@link ObuParser#OBU_FRAME_HEADER}.
   */
  public int sampleLimitAfterSkippingNonReferenceFrame(
      ByteBuffer sample, boolean skipFrameHeaders) {
    if (delayedKeyFrameTruncatedSample.hasRemaining()) {
      updateSequenceHeaders(split(delayedKeyFrameTruncatedSample));
      emptyDelayedKeyFrameTruncatedSample();
    }
    List<ObuParser.Obu> obuList = split(sample);
    updateSequenceHeaders(obuList);
    int skippedFramesCount = 0;
    int last = obuList.size() - 1;
    while (last >= 0 && canSkipObu(obuList.get(last), skipFrameHeaders)) {
      if (obuList.get(last).type == OBU_FRAME || obuList.get(last).type == OBU_FRAME_HEADER) {
        skippedFramesCount++;
      }
      last--;
    }
    if (skippedFramesCount > 1 || last + 1 >= MAX_OBU_COUNT_FOR_PARTIAL_SKIP) {
      return sample.limit();
    }
    if (last >= 0) {
      return obuList.get(last).payload.limit();
    }
    return sample.position();
  }

  /**
   * Updates the parser state with the next sample data from a random access temporal unit.
   *
   * <p>In order to identify non-reference frames, the parser needs a sequence header. The relevant
   * sequence headers fields cannot change within a coded video sequence. And a new coded video
   * sequence is defined to begin with a temporal unit where:
   *
   * <ul>
   *   <li>A sequence header OBU appears before the first frame header.
   *   <li>The first frame header has frame_type equal to KEY_FRAME.
   * </ul>
   *
   * <p>These requirements are the same as the requirements for random access points. See <a
   * href=https://aomediacodec.github.io/av1-spec/#ordering-of-obus>Ordering of OBUs</a>
   */
  public void queueInputBuffer(ByteBuffer sample) {
    // Copy a prefix of sample data into delayedKeyFrameTruncatedSample, preserving the sample
    // position and limit.
    int samplePosition = sample.position();
    int sampleLimit = sample.limit();
    sample.limit(min(sampleLimit, samplePosition + MAX_BYTES_FROM_KEYFRAME_TO_READ));

    delayedKeyFrameTruncatedSample.clear();
    delayedKeyFrameTruncatedSample.put(sample);
    delayedKeyFrameTruncatedSample.flip();

    sample.position(samplePosition);
    sample.limit(sampleLimit);
  }

  /** Resets the parser state. */
  public void reset() {
    sequenceHeader = null;
    emptyDelayedKeyFrameTruncatedSample();
  }

  private boolean canSkipObu(ObuParser.Obu obu, boolean skipFrameHeaders) {
    if (obu.type == OBU_TEMPORAL_DELIMITER || obu.type == OBU_PADDING) {
      return true;
    }
    if (obu.type == OBU_FRAME_HEADER && !skipFrameHeaders) {
      return false;
    }
    if ((obu.type == OBU_FRAME || obu.type == OBU_FRAME_HEADER) && sequenceHeader != null) {
      FrameHeader frameHeader = FrameHeader.parse(sequenceHeader, obu);
      return frameHeader != null && !frameHeader.isDependedOn();
    }
    return false;
  }

  private void updateSequenceHeaders(List<ObuParser.Obu> obuList) {
    for (int i = 0; i < obuList.size(); ++i) {
      if (obuList.get(i).type == OBU_SEQUENCE_HEADER) {
        sequenceHeader = SequenceHeader.parse(obuList.get(i));
      }
    }
  }

  /**
   * Modify the {@code delayedKeyFrameSample} position to ensure {@link ByteBuffer#hasRemaining()}
   * returns false.
   */
  private void emptyDelayedKeyFrameTruncatedSample() {
    delayedKeyFrameTruncatedSample.position(delayedKeyFrameTruncatedSample.limit());
  }
}
