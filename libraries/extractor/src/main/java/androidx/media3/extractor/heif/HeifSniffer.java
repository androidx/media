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

import static androidx.media3.extractor.mp4.Sniffer.BRAND_HEIC;

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.Mp4Box;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/**
 * Provides a method to peek data from an {@link ExtractorInput} and determine if the input is a
 * supported HEIF/HEIC file.
 */
/* package */ final class HeifSniffer {

  /**
   * Peeks into the input stream to determine if it is a supported HEIC file.
   *
   * <p>This method checks for an {@code ftyp} box with a major brand of {@code heic}. If {@code
   * sniffMotionPhoto} is true, it also scans for a top-level {@code mpvd} atom to identify a motion
   * photo.
   *
   * @param input The {@link ExtractorInput} from which to peek data. The peek position will be
   *     modified.
   * @param sniffMotionPhoto If {@code true}, the method will also check for the presence of an
   *     {@code mpvd} atom to identify a motion photo.
   * @return {@code true} if the input is identified as a supported HEIC file (and contains an
   *     {@code mpvd} atom if requested).
   * @throws IOException If an I/O error occurs while peeking from the input.
   */
  public static boolean sniff(ExtractorInput input, boolean sniffMotionPhoto) throws IOException {
    ParsableByteArray buffer = new ParsableByteArray(Mp4Box.LONG_HEADER_SIZE);
    boolean firstAtom = true;

    while (true) {
      int headerSize = Mp4Box.HEADER_SIZE;
      buffer.reset(headerSize);
      if (!input.peekFully(
          buffer.getData(),
          /* offset= */ 0,
          /* length= */ headerSize,
          /* allowEndOfInput= */ true)) {
        return false;
      }

      long atomSize = buffer.readUnsignedInt();
      int atomType = buffer.readInt();

      if (atomSize == Mp4Box.DEFINES_LARGE_SIZE) {
        headerSize = Mp4Box.LONG_HEADER_SIZE;
        if (!input.peekFully(
            buffer.getData(),
            /* offset= */ Mp4Box.HEADER_SIZE,
            /* length= */ Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE,
            /* allowEndOfInput= */ true)) {
          return false;
        }
        atomSize = buffer.readUnsignedLongToLong();
      }

      if (atomSize < headerSize) {
        return false;
      }

      int atomDataSize = (int) (atomSize - headerSize);
      if (firstAtom) {
        if (atomType != Mp4Box.TYPE_ftyp || atomDataSize < 8) {
          return false;
        }

        buffer.reset(4);
        input.peekFully(buffer.getData(), /* offset= */ 0, /* length= */ 4);
        if (buffer.readInt() != BRAND_HEIC) {
          return false;
        }
        if (!sniffMotionPhoto) {
          return true;
        }
        input.advancePeekPosition(atomDataSize - 4);
        firstAtom = false;
      } else if (atomType == Mp4Box.TYPE_mpvd) {
        return true;
      } else if (atomDataSize != 0) {
        input.advancePeekPosition(atomDataSize);
      }
    }
  }

  private HeifSniffer() {}
}
