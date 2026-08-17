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
import androidx.media3.extractor.MotionPhotoDescription;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.StartOffsetExtractorInput;
import androidx.media3.extractor.StartOffsetExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.XmpMotionPhotoDescriptionParser;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;
import androidx.media3.extractor.mp4.BoxParser;
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
    STATE_READING_XMP,
  })
  private @interface State {}

  private static final int STATE_READING_ATOM_HEADER = 0;
  private static final int STATE_READING_ATOM_PAYLOAD = 1;
  private static final int STATE_SNIFFING_MOTION_PHOTO_VIDEO = 2;
  private static final int STATE_READING_MOTION_PHOTO_VIDEO = 3;
  private static final int STATE_READING_XMP = 4;
  private static final int STATE_ENDED = 5;

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
  private int mpvdHeaderSize;

  private long xmpOffset;
  private int xmpLength;
  @Nullable private ParsableByteArray iinfPayload;
  @Nullable private ParsableByteArray ilocPayload;

  /** Creates an instance. */
  public HeicMotionPhotoExtractor() {
    scratch = new ParsableByteArray(Mp4Box.LONG_HEADER_SIZE);
    mp4StartPosition = C.INDEX_UNSET;
    xmpOffset = C.INDEX_UNSET;
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
        case STATE_READING_XMP:
          if (input.getPosition() != xmpOffset) {
            seekPosition.position = xmpOffset;
            return RESULT_SEEK;
          }
          readXmpAndSetupMetadata(input);
          seekPosition.position = mp4StartPosition;
          state = STATE_SNIFFING_MOTION_PHOTO_VIDEO;
          return RESULT_SEEK;
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
      xmpOffset = C.INDEX_UNSET;
      xmpLength = 0;
      iinfPayload = null;
      ilocPayload = null;
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
      mpvdHeaderSize = atomHeaderBytesRead;
      atomHeaderBytesRead = 0;
      processIinfAndIloc();
      if (xmpOffset != C.INDEX_UNSET) {
        state = STATE_READING_XMP;
      } else {
        setupMotionPhotoMetadata(C.TIME_UNSET);
        state = STATE_SNIFFING_MOTION_PHOTO_VIDEO;
      }
    } else if (atomType == Mp4Box.TYPE_meta) {
      input.skipFully(4); // Skip version and flags
      atomHeaderBytesRead = 0;
      state = STATE_READING_ATOM_HEADER;
    } else if (atomType == Mp4Box.TYPE_iinf) {
      int payloadSize = (int) (atomSize - atomHeaderBytesRead);
      iinfPayload = new ParsableByteArray(payloadSize);
      input.readFully(iinfPayload.getData(), 0, payloadSize);
      atomHeaderBytesRead = 0;
      state = STATE_READING_ATOM_HEADER;
    } else if (atomType == Mp4Box.TYPE_iloc) {
      int payloadSize = (int) (atomSize - atomHeaderBytesRead);
      ilocPayload = new ParsableByteArray(payloadSize);
      input.readFully(ilocPayload.getData(), 0, payloadSize);
      atomHeaderBytesRead = 0;
      state = STATE_READING_ATOM_HEADER;
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

  private void setupMotionPhotoMetadata(long photoPresentationTimestampUs) {
    long boxStartPosition = mp4StartPosition - mpvdHeaderSize;
    long mpvdPayloadSize = atomSize - mpvdHeaderSize;
    motionPhotoMetadata =
        new MotionPhotoMetadata(
            /* photoStartPosition= */ 0,
            /* photoSize= */ boxStartPosition,
            photoPresentationTimestampUs,
            /* videoStartPosition= */ mp4StartPosition,
            /* videoSize= */ mpvdPayloadSize);
    outputImageTrack(motionPhotoMetadata);
  }

  private void readXmpAndSetupMetadata(ExtractorInput input) throws IOException {
    ParsableByteArray xmpData = new ParsableByteArray(xmpLength);
    input.readFully(xmpData.getData(), 0, xmpLength);
    String xmpString = xmpData.readString(xmpLength);
    long timestampUs = C.TIME_UNSET;
    try {
      MotionPhotoDescription desc = XmpMotionPhotoDescriptionParser.parse(xmpString);
      if (desc != null) {
        timestampUs = desc.photoPresentationTimestampUs;
      }
    } catch (IOException e) {
      // Ignore parser exception
    }
    setupMotionPhotoMetadata(timestampUs);
  }

  private void processIinfAndIloc() {
    ParsableByteArray iinfPayload = this.iinfPayload;
    ParsableByteArray ilocPayload = this.ilocPayload;
    if (iinfPayload == null || ilocPayload == null) {
      return;
    }
    int xmpItemId = -1;
    iinfPayload.setPosition(0);
    int version = BoxParser.parseFullBoxVersion(iinfPayload.readInt());
    int entryCount =
        version == 0
            ? iinfPayload.readUnsignedShort()
            : iinfPayload.readUnsignedIntToInt(); // entry_count
    for (int i = 0; i < entryCount; i++) {
      if (iinfPayload.bytesLeft() < 8) {
        break;
      }
      int start = iinfPayload.getPosition();
      int size = iinfPayload.readInt();
      if (size < Mp4Box.HEADER_SIZE) {
        break;
      }
      int type = iinfPayload.readInt();
      if (type == Mp4Box.TYPE_infe) {
        // ISO/IEC 14496-12:2022 8.11.6 Item Info Entry
        int infeVersion = BoxParser.parseFullBoxVersion(iinfPayload.readInt());
        int itemId; // item_ID
        if (infeVersion < 3) {
          itemId = iinfPayload.readUnsignedShort();
        } else {
          itemId = iinfPayload.readInt();
        }
        if (infeVersion >= 2) {
          iinfPayload.skipBytes(2); // item_protection_index
          int itemType = iinfPayload.readInt(); // item_type
          iinfPayload.readNullTerminatedString(); // item_name
          if (itemType == 0x6d696d65) { // 'mime'
            String contentType = iinfPayload.readNullTerminatedString(); // content_type
            if (contentType != null && contentType.equals(MimeTypes.APPLICATION_RDF_XML)) {
              xmpItemId = itemId;
              break;
            }
          }
        }
      }
      iinfPayload.setPosition(start + size);
    }

    if (xmpItemId == -1) {
      return;
    }

    // ISO/IEC 14496-12:2022 8.11.3 Item Location Box
    ilocPayload.setPosition(0);
    int ilocVersion = BoxParser.parseFullBoxVersion(ilocPayload.readInt());
    int offsetAndLengthSize = ilocPayload.readUnsignedByte(); // offset_size (4) and length_size (4)
    int offsetSize = offsetAndLengthSize >> 4;
    int lengthSize = offsetAndLengthSize & 0x0F;
    int baseOffsetSizeAndIndexSize =
        ilocPayload.readUnsignedByte(); // base_offset_size (4) and index_size (4)
    int baseOffsetSize = baseOffsetSizeAndIndexSize >> 4;
    int indexSize = baseOffsetSizeAndIndexSize & 0x0F;
    int itemCount =
        ilocVersion < 2 ? ilocPayload.readUnsignedShort() : ilocPayload.readInt(); // item_count
    for (int i = 0; i < itemCount; i++) {
      int itemId =
          ilocVersion < 2 ? ilocPayload.readUnsignedShort() : ilocPayload.readInt(); // item_ID
      if (ilocVersion == 1 || ilocVersion == 2) {
        ilocPayload.skipBytes(2); // reserved (12) and construction_method (4)
      }
      ilocPayload.skipBytes(2); // data_reference_index
      long baseOffset = 0; // base_offset
      if (baseOffsetSize == 4) {
        baseOffset = ilocPayload.readUnsignedInt();
      } else if (baseOffsetSize == 8) {
        baseOffset = ilocPayload.readUnsignedLongToLong();
      }
      int extentCount = ilocPayload.readUnsignedShort(); // extent_count
      for (int j = 0; j < extentCount; j++) {
        if (ilocVersion == 1 || ilocVersion == 2) {
          if (indexSize > 0) {
            ilocPayload.skipBytes(indexSize); // item_reference_index
          }
        }
        long extentOffset = 0; // extent_offset
        if (offsetSize == 4) {
          extentOffset = ilocPayload.readUnsignedInt();
        } else if (offsetSize == 8) {
          extentOffset = ilocPayload.readUnsignedLongToLong();
        }
        long extentLength = 0; // extent_length
        if (lengthSize == 4) {
          extentLength = ilocPayload.readUnsignedInt();
        } else if (lengthSize == 8) {
          extentLength = ilocPayload.readUnsignedLongToLong();
        }
        if (itemId == xmpItemId) {
          xmpOffset = baseOffset + extentOffset;
          xmpLength = (int) extentLength;
          return;
        }
      }
      if (itemId == xmpItemId) {
        break;
      }
    }
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
