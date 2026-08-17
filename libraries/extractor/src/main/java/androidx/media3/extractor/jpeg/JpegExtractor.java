/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.extractor.jpeg;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.Pair;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SingleSampleExtractor;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Extracts data from the JPEG container format. */
@UnstableApi
public final class JpegExtractor implements Extractor {
  /**
   * Flags controlling the behavior of the extractor. Possible flag value is {@link
   * #FLAG_READ_IMAGE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {
        FLAG_READ_IMAGE,
      })
  public @interface Flags {}

  /** Flag to load the image track instead of the video and metadata track. */
  public static final int FLAG_READ_IMAGE = 1;

  // Specification reference: ITU-T.81 (1992) subsection B.1.1.3
  private static final int JPEG_FILE_SIGNATURE = 0xFFD8; // Start of image marker
  private static final int JPEG_FILE_SIGNATURE_LENGTH = 2;

  private final Extractor imageExtractor;
  @Nullable private final Extractor videoExtractor;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull Extractor activeExtractor;

  /** Holds a seek that arrives before {@link #activeExtractor} is assigned. */
  @Nullable private Pair<Long, Long> pendingSeek;

  /** Creates an instance reading the video and metadata track. */
  public JpegExtractor() {
    this(/* flags= */ 0);
  }

  /**
   * Creates an instance.
   *
   * @param flags The {@link JpegExtractor.Flags} to control extractor behavior.
   */
  public JpegExtractor(@JpegExtractor.Flags int flags) {
    imageExtractor =
        new SingleSampleExtractor(
            JPEG_FILE_SIGNATURE, JPEG_FILE_SIGNATURE_LENGTH, MimeTypes.IMAGE_JPEG);
    videoExtractor = (flags & FLAG_READ_IMAGE) == 0 ? new JpegMotionPhotoExtractor() : null;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    if (videoExtractor != null && videoExtractor.sniff(input)) {
      return true;
    }
    input.resetPeekPosition();
    return imageExtractor.sniff(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    this.extractorOutput = output;
    if (videoExtractor == null) {
      activeExtractor = imageExtractor;
      activeExtractor.init(output);
    }
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    if (activeExtractor == null) {
      assignActiveExtractor(input);
    }
    return activeExtractor.read(input, seekPosition);
  }

  @Override
  public void seek(long position, long timeUs) {
    if (activeExtractor != null) {
      activeExtractor.seek(position, timeUs);
    } else {
      pendingSeek = Pair.create(position, timeUs);
    }
  }

  @Override
  public void release() {
    if (videoExtractor != null) {
      videoExtractor.release();
    }
    imageExtractor.release();
  }

  @EnsuresNonNull("this.activeExtractor")
  private void assignActiveExtractor(ExtractorInput input) throws IOException {
    checkState(activeExtractor == null);
    // If activeExtractor is null it means it wasn't assigned in init(), which only happens if
    // videoExtractor is non-null (which means FLAG_READ_IMAGE wasn't set).
    activeExtractor = checkNotNull(videoExtractor).sniff(input) ? videoExtractor : imageExtractor;
    input.resetPeekPosition();
    if (pendingSeek != null) {
      activeExtractor.seek(pendingSeek.first, pendingSeek.second);
      pendingSeek = null;
    }
    activeExtractor.init(checkNotNull(extractorOutput));
  }
}
