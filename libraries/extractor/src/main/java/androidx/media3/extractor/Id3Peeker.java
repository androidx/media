/*
 * Copyright (C) 2018 The Android Open Source Project
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

package androidx.media3.extractor;

import static androidx.media3.extractor.metadata.id3.Id3Decoder.ID3_HEADER_LENGTH;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import androidx.media3.extractor.metadata.id3.Id3Decoder.FramePredicate;
import com.google.errorprone.annotations.InlineMe;
import java.io.EOFException;
import java.io.IOException;

/**
 * Peeks data from the beginning of an {@link ExtractorInput} to determine if there is any ID3 tag.
 */
@UnstableApi
public final class Id3Peeker {

  private final ParsableByteArray scratch;

  public Id3Peeker() {
    scratch = new ParsableByteArray(ID3_HEADER_LENGTH);
  }

  /**
   * @deprecated Use {@link #peekId3Data(ExtractorInput, FramePredicate, int)} instead.
   */
  @InlineMe(replacement = "this.peekId3Data(input, id3FramePredicate, /* maxTagPeekBytes= */ 0)")
  @Deprecated
  @Nullable
  public Metadata peekId3Data(
      ExtractorInput input, @Nullable Id3Decoder.FramePredicate id3FramePredicate)
      throws IOException {
    return peekId3Data(input, id3FramePredicate, /* maxTagPeekBytes= */ 0);
  }

  /**
   * Peeks ID3 data from the input and parses the first ID3 tag.
   *
   * @param input The {@link ExtractorInput} from which data should be peeked.
   * @param id3FramePredicate Determines which ID3 frames are decoded. May be null to decode all
   *     frames.
   * @param maxTagPeekBytes The maximum number of bytes to peek when searching for each ID3 tag
   *     before giving up.
   * @return The first ID3 tag decoded into a {@link Metadata} object. May be null if ID3 tag is not
   *     present in the input.
   * @throws IOException If an error occurred peeking from the input.
   */
  @Nullable
  public Metadata peekId3Data(
      ExtractorInput input,
      @Nullable Id3Decoder.FramePredicate id3FramePredicate,
      int maxTagPeekBytes)
      throws IOException {
    int peekedId3Bytes = 0;
    @Nullable Metadata metadata = null;
    while (peekId3HeaderIntoScratch(input, maxTagPeekBytes)) {
      int id3HeaderStartInScratch = scratch.getPosition();
      scratch.skipBytes(6); // Skip header, major version, minor version and flags.
      int framesLength = scratch.readSynchSafeInt();
      int tagLength = ID3_HEADER_LENGTH + framesLength;

      if (metadata == null) {
        byte[] id3Data = new byte[tagLength];
        System.arraycopy(scratch.getData(), id3HeaderStartInScratch, id3Data, 0, ID3_HEADER_LENGTH);
        input.peekFully(id3Data, ID3_HEADER_LENGTH, framesLength);

        metadata = new Id3Decoder(id3FramePredicate).decode(id3Data, tagLength);
      } else {
        input.advancePeekPosition(framesLength);
      }

      peekedId3Bytes += tagLength;
    }

    input.resetPeekPosition();
    input.advancePeekPosition(peekedId3Bytes);
    return metadata;
  }

  /**
   * Peeks through {@code input}, looking for an ID3 header.
   *
   * <p>If one is found, it is copied into {@link #scratch}, the scratch {@linkplain
   * ParsableByteArray#setPosition position} is set to the start of the header, and this method
   * returns {@code true}.
   *
   * <p>If no ID3 header is found within {@code maxTagPeekBytes}, or an MP3 header is found, this
   * method returns {@code false}.
   */
  private boolean peekId3HeaderIntoScratch(ExtractorInput input, int maxTagPeekBytes)
      throws IOException {
    int tagSearchBytes = 0;
    do {
      int headerStartIndexInScratch = tagSearchBytes % ID3_HEADER_LENGTH;
      int headerEndIndexInScratch = headerStartIndexInScratch + ID3_HEADER_LENGTH;
      if (headerStartIndexInScratch == 0 && tagSearchBytes != 0) {
        // We've reached the end of the double-length array. Move the existing data back to the
        // start.
        System.arraycopy(
            scratch.getData(), ID3_HEADER_LENGTH, scratch.getData(), 0, ID3_HEADER_LENGTH - 1);
      }
      int peekLength = tagSearchBytes == 0 ? ID3_HEADER_LENGTH : 1;
      try {
        input.peekFully(
            scratch.getData(), headerEndIndexInScratch - peekLength, /* length= */ peekLength);
      } catch (EOFException e) {
        return false;
      }
      scratch.setPosition(headerStartIndexInScratch);
      scratch.setLimit(headerEndIndexInScratch);
      if (scratch.peekUnsignedInt24() == Id3Decoder.ID3_TAG) {
        return true;
      } else if (MpegAudioUtil.getFrameSize(scratch.peekInt()) != C.LENGTH_UNSET) {
        // We've found something that looks like an MP3 header, stop searching for ID3 headers.
        return false;
      }
      // Allow reading individual bytes up to twice the header length, then copy everything back to
      // the beginning, to avoid too many repeated array allocations while also limiting the size
      // of the array to be reasonable.
      if (tagSearchBytes == 0) {
        scratch.ensureCapacity(ID3_HEADER_LENGTH * 2);
      }
      tagSearchBytes++;
    } while (tagSearchBytes <= maxTagPeekBytes);
    return false;
  }
}
