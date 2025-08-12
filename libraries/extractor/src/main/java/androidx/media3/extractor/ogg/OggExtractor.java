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
package androidx.media3.extractor.ogg;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Extracts data from the Ogg container format. */
@UnstableApi
public class OggExtractor implements Extractor {

  /** Factory for {@link OggExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new OggExtractor()};

  private static final int MAX_VERIFICATION_BYTES = 8;

  private @MonotonicNonNull ExtractorOutput output;
  private @MonotonicNonNull StreamReader streamReader;
  private boolean streamReaderInitialized;

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    try {
      return sniffInternal(input);
    } catch (ParserException e) {
      return false;
    }
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
  }

  @Override
  public void seek(long position, long timeUs) {
    if (streamReader != null) {
      streamReader.seek(position, timeUs);
    }
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    // Check that init has been called.
    checkNotNull(output);
    if (streamReader == null) {
      if (!sniffInternal(input)) {
        throw ParserException.createForMalformedContainer(
            "Failed to determine bitstream type", /* cause= */ null);
      }
      input.resetPeekPosition();
    }
    if (!streamReaderInitialized) {
      TrackOutput trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
      output.endTracks();
      streamReader.init(output, trackOutput);
      streamReaderInitialized = true;
    }
    return streamReader.read(input, seekPosition);
  }

  @EnsuresNonNullIf(expression = "streamReader", result = true)
  private boolean sniffInternal(ExtractorInput input) throws IOException {
    OggPageHeader header = new OggPageHeader();
    if (!header.populate(input, true) || (header.type & 0x02) != 0x02) {
      return false;
    }

    int length = min(header.bodySize, MAX_VERIFICATION_BYTES);
    ParsableByteArray scratch = new ParsableByteArray(length);
    input.peekFully(scratch.getData(), 0, length);

    if (FlacReader.verifyBitstreamType(resetPosition(scratch))) {
      streamReader = new FlacReader();
    } else if (VorbisReader.verifyBitstreamType(resetPosition(scratch))) {
      streamReader = new VorbisReader();
    } else if (OpusReader.verifyBitstreamType(resetPosition(scratch))) {
      streamReader = new OpusReader();
    } else {
      return false;
    }
    return true;
  }

  private static ParsableByteArray resetPosition(ParsableByteArray scratch) {
    scratch.setPosition(0);
    return scratch;
  }
}
