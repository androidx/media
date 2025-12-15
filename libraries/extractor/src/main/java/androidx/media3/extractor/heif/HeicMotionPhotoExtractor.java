/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.extractor.heif;

import static androidx.media3.extractor.SingleSampleExtractor.IMAGE_TRACK_ID;
import static androidx.media3.extractor.mp4.Mp4Extractor.FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.Mp4Box;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.StartOffsetExtractorInput;
import androidx.media3.extractor.StartOffsetExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts HEIC motion photos following the <a
 * href="https://developer.android.com/media/platform/motion-photo-format">Android Motion Photo
 * format 1.0</a>.
 */
/* package */ final class HeicMotionPhotoExtractor implements Extractor {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_READING_ATOM_HEADER,
    STATE_READING_ATOM_PAYLOAD,
    STATE_SNIFFING_MOTION_PHOTO_VIDEO,
    STATE_READING_MOTION_PHOTO_VIDEO,
    STATE_ENDED,
  })
  private @interface State {}

  private static final int STATE_READING_ATOM_HEADER = 0;
  private static final int STATE_READING_ATOM_PAYLOAD = 1;
  private static final int STATE_SNIFFING_MOTION_PHOTO_VIDEO = 2;
  private static final int STATE_READING_MOTION_PHOTO_VIDEO = 3;
  private static final int STATE_ENDED = 4;

  private final ParsableByteArray scratch;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull MotionPhotoMetadata motionPhotoMetadata;
  private @MonotonicNonNull ExtractorInput lastExtractorInput;
  private @MonotonicNonNull StartOffsetExtractorInput mp4ExtractorStartOffsetExtractorInput;
  @Nullable private Mp4Extractor mp4Extractor;

  private @State int state;
  private int atomType;
  private long atomSize;
  private int atomHeaderBytesRead;
  private long mp4StartPosition;

  /** Creates an instance. */
  public HeicMotionPhotoExtractor() {
    scratch = new ParsableByteArray(Mp4Box.LONG_HEADER_SIZE);
    mp4StartPosition = C.INDEX_UNSET;
    state = STATE_READING_ATOM_HEADER;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return HeifSniffer.sniff(input, /* sniffMotionPhoto= */ true);
  }

  @Override
  public void init(ExtractorOutput output) {
    this.extractorOutput = output;
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    while (true) {
      switch (state) {
        case STATE_READING_ATOM_HEADER:
          if (!readAtomHeader(input)) {
            endReading();
            return RESULT_END_OF_INPUT;
          }
          break;
        case STATE_READING_ATOM_PAYLOAD:
          readAtomPayload(input);
          break;
        case STATE_SNIFFING_MOTION_PHOTO_VIDEO:
          sniffMotionPhotoVideo(input);
          break;
        case STATE_READING_MOTION_PHOTO_VIDEO:
          return readMotionPhotoVideo(input, seekPosition);
        case STATE_ENDED:
          return RESULT_END_OF_INPUT;
        default:
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    if (position == 0) {
      state = STATE_READING_ATOM_HEADER;
      atomHeaderBytesRead = 0;
      mp4StartPosition = C.INDEX_UNSET;
      if (mp4Extractor != null) {
        mp4Extractor.release();
        mp4Extractor = null;
      }
    } else if (state == STATE_READING_MOTION_PHOTO_VIDEO) {
      checkNotNull(mp4Extractor).seek(position, timeUs);
    }
  }

  @Override
  public void release() {
    if (mp4Extractor != null) {
      mp4Extractor.release();
      mp4Extractor = null;
    }
  }

  private boolean readAtomHeader(ExtractorInput input) throws IOException {
    if (atomHeaderBytesRead == 0) {
      if (!input.readFully(
          scratch.getData(), /* offset= */ 0, Mp4Box.HEADER_SIZE, /* allowEndOfInput= */ true)) {
        return false;
      }
      atomHeaderBytesRead = Mp4Box.HEADER_SIZE;
      scratch.setPosition(0);
      atomSize = scratch.readUnsignedInt();
      atomType = scratch.readInt();
    }

    if (atomSize == Mp4Box.DEFINES_LARGE_SIZE) {
      int headerBytesRemaining = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE;
      input.readFully(scratch.getData(), Mp4Box.HEADER_SIZE, headerBytesRemaining);
      atomHeaderBytesRead += headerBytesRemaining;
      atomSize = scratch.readUnsignedLongToLong();
    }

    if (atomType == Mp4Box.TYPE_mpvd) {
      mp4StartPosition = input.getPosition();
      long boxStartPosition = mp4StartPosition - atomHeaderBytesRead;
      motionPhotoMetadata =
          new MotionPhotoMetadata(
              /* photoStartPosition= */ 0,
              /* photoSize= */ boxStartPosition,
              /* photoPresentationTimestampUs= */ C.TIME_UNSET,
              /* videoStartPosition= */ mp4StartPosition,
              /* videoSize= */ atomSize - atomHeaderBytesRead);
      outputImageTrack(motionPhotoMetadata);
      state = STATE_SNIFFING_MOTION_PHOTO_VIDEO;
    } else {
      state = STATE_READING_ATOM_PAYLOAD;
    }
    return true;
  }

  private void readAtomPayload(ExtractorInput input) throws IOException {
    long atomPayloadSize = atomSize - atomHeaderBytesRead;
    input.skipFully((int) atomPayloadSize);
    atomHeaderBytesRead = 0;
    state = STATE_READING_ATOM_HEADER;
  }

  private void sniffMotionPhotoVideo(ExtractorInput input) throws IOException {
    if (mp4Extractor == null) {
      mp4Extractor =
          new Mp4Extractor(
              SubtitleParser.Factory.UNSUPPORTED, FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE);
    }
    mp4ExtractorStartOffsetExtractorInput = new StartOffsetExtractorInput(input, mp4StartPosition);
    if (mp4Extractor.sniff(mp4ExtractorStartOffsetExtractorInput)) {
      mp4Extractor.init(
          new StartOffsetExtractorOutput(mp4StartPosition, checkNotNull(extractorOutput)));
      state = STATE_READING_MOTION_PHOTO_VIDEO;
    } else {
      endReading();
    }
  }

  private int readMotionPhotoVideo(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    if (mp4ExtractorStartOffsetExtractorInput == null || input != lastExtractorInput) {
      lastExtractorInput = input;
      mp4ExtractorStartOffsetExtractorInput =
          new StartOffsetExtractorInput(input, mp4StartPosition);
    }
    @ReadResult
    int readResult =
        checkNotNull(mp4Extractor).read(mp4ExtractorStartOffsetExtractorInput, seekPosition);
    if (readResult == RESULT_SEEK) {
      seekPosition.position += mp4StartPosition;
    }
    return readResult;
  }

  private void outputImageTrack(MotionPhotoMetadata motionPhotoMetadata) {
    TrackOutput imageTrackOutput =
        checkNotNull(extractorOutput).track(IMAGE_TRACK_ID, C.TRACK_TYPE_IMAGE);
    imageTrackOutput.format(
        new Format.Builder()
            .setContainerMimeType(MimeTypes.IMAGE_HEIC)
            .setMetadata(new Metadata(motionPhotoMetadata))
            .build());
  }

  private void endReading() {
    checkNotNull(extractorOutput).endTracks();
    extractorOutput.seekMap(new SeekMap.Unseekable(/* durationUs= */ C.TIME_UNSET));
    state = STATE_ENDED;
  }
}
