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
package androidx.media3.extractor.mp3;

import androidx.media3.common.C;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.SeekMap.SeekPoints;
import androidx.media3.extractor.SeekPoint;

/**
 * MP3 seeker that doesn't rely on metadata and seeks assuming the source has a constant bitrate.
 */
/* package */ final class ConstantBitrateSeeker extends ConstantBitrateSeekMap implements Seeker {

  private final long firstFramePosition;
  private final int bitrate;
  private final int frameSize;
  private final boolean allowSeeksIfLengthUnknown;
  private final long durationUs;
  private final long dataEndPosition;

  /**
   * Constructs an instance.
   *
   * <p>The duration exposed from {@link #getDurationUs()} is computed from {@code inputLength} and
   * the frame bitrate, or is {@link C#TIME_UNSET} if {@code inputLength} is unknown.
   *
   * @param inputLength The length of the stream in bytes, or {@link C#LENGTH_UNSET} if unknown.
   * @param firstFramePosition The position of the first frame in the stream.
   * @param mpegAudioHeader The MPEG audio header associated with the first frame.
   * @param allowSeeksIfLengthUnknown Whether to allow seeking even if the length of the content is
   *     unknown.
   */
  public ConstantBitrateSeeker(
      long inputLength,
      long firstFramePosition,
      MpegAudioUtil.Header mpegAudioHeader,
      boolean allowSeeksIfLengthUnknown) {
    // Set the seeker frame size to the size of the first frame (even though some constant bitrate
    // streams have variable frame sizes due to padding) to avoid the need to re-synchronize for
    // constant frame size streams.
    this(
        inputLength,
        firstFramePosition,
        mpegAudioHeader.bitrate,
        mpegAudioHeader.frameSize,
        allowSeeksIfLengthUnknown,
        /* durationUs= */ C.TIME_UNSET);
  }

  /**
   * See {@link ConstantBitrateSeekMap#ConstantBitrateSeekMap(long, long, int, int, boolean)}. Uses
   * {@code durationUs} as the duration exposed from {@link #getDurationUs()}, or computes the
   * duration from {@code inputLength} and {@code bitrate} if {@code durationUs} is {@link
   * C#TIME_UNSET}.
   */
  public ConstantBitrateSeeker(
      long inputLength,
      long firstFramePosition,
      int bitrate,
      int frameSize,
      boolean allowSeeksIfLengthUnknown,
      long durationUs) {
    this(
        inputLength,
        firstFramePosition,
        bitrate,
        frameSize,
        allowSeeksIfLengthUnknown,
        /* isEstimated= */ true,
        durationUs);
  }

  private ConstantBitrateSeeker(
      long inputLength,
      long firstFramePosition,
      int bitrate,
      int frameSize,
      boolean allowSeeksIfLengthUnknown,
      boolean isEstimated,
      long durationUs) {
    super(
        inputLength,
        firstFramePosition,
        bitrate,
        frameSize,
        allowSeeksIfLengthUnknown,
        isEstimated);
    this.firstFramePosition = firstFramePosition;
    this.bitrate = bitrate;
    this.frameSize = frameSize == C.LENGTH_UNSET ? 1 : frameSize;
    this.allowSeeksIfLengthUnknown = allowSeeksIfLengthUnknown;
    this.durationUs = durationUs;
    dataEndPosition = inputLength != C.LENGTH_UNSET ? inputLength : C.INDEX_UNSET;
  }

  @Override
  public long getTimeUs(long position) {
    return getTimeUsAtPosition(position);
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (durationUs != C.TIME_UNSET && timeUs >= durationUs && dataEndPosition != C.INDEX_UNSET) {
      long finalFramePosition = Math.max(firstFramePosition, dataEndPosition - frameSize);
      long frameDurationUs = getTimeUsAtPosition(firstFramePosition + frameSize);
      return new SeekPoints(
          new SeekPoint(Math.max(0, durationUs - frameDurationUs), finalFramePosition));
    }
    return super.getSeekPoints(timeUs);
  }

  @Override
  public long getDataStartPosition() {
    return firstFramePosition;
  }

  @Override
  public long getDataEndPosition() {
    return dataEndPosition;
  }

  @Override
  public long getDurationUs() {
    return durationUs != C.TIME_UNSET ? durationUs : super.getDurationUs();
  }

  @Override
  public int getAverageBitrate() {
    return bitrate;
  }

  public ConstantBitrateSeeker copyWithNewDataEndPosition(long dataEndPosition) {
    return new ConstantBitrateSeeker(
        /* inputLength= */ dataEndPosition,
        firstFramePosition,
        bitrate,
        frameSize,
        allowSeeksIfLengthUnknown,
        /* isEstimated= */ false,
        durationUs);
  }
}
