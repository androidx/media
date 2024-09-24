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
package androidx.media3.extractor.amr;

import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static java.lang.Math.abs;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Extracts data from the AMR containers format (either AMR or AMR-WB). This follows RFC-4867,
 * section 5.
 *
 * <p>This extractor only supports single-channel AMR container formats.
 */
@UnstableApi
public final class AmrExtractor implements Extractor {

  /** Factory for {@link AmrExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new AmrExtractor()};

  /**
   * Flags controlling the behavior of the extractor. Possible flag values are {@link
   * #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING}, {@link #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS}
   * and {@link #FLAG_ENABLE_INDEX_SEEKING}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {
        FLAG_ENABLE_CONSTANT_BITRATE_SEEKING,
        FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS,
        FLAG_ENABLE_INDEX_SEEKING,
      })
  public @interface Flags {}

  /**
   * Flag to force enable seeking using a constant bitrate assumption in cases where seeking would
   * otherwise not be possible.
   *
   * <p>This flag is ignored if {@link #FLAG_ENABLE_INDEX_SEEKING} is set.
   */
  public static final int FLAG_ENABLE_CONSTANT_BITRATE_SEEKING = 1;

  /**
   * Like {@link #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING}, except that seeking is also enabled in
   * cases where the content length (and hence the duration of the media) is unknown. Application
   * code should ensure that requested seek positions are valid when using this flag, or be ready to
   * handle playback failures reported through {@link Player.Listener#onPlayerError} with {@link
   * PlaybackException#errorCode} set to {@link
   * PlaybackException#ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE}.
   *
   * <p>If this flag is set, then the behavior enabled by {@link
   * #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING} is implicitly enabled as well.
   *
   * <p>This flag is ignored if {@link #FLAG_ENABLE_INDEX_SEEKING} is set.
   */
  public static final int FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS = 1 << 1;

  /**
   * Flag to force index seeking, in which a time-to-byte mapping is built as the file is read.
   *
   * <p>This seeker may require to scan a significant portion of the file to compute a seek point.
   * Therefore, it should only be used if one of the following is true:
   *
   * <ul>
   *   <li>The file is small.
   *   <li>The bitrate is variable (or it's unknown whether it's variable).
   *   <li>The file contains silence frames in a constant bitrate stream.
   * </ul>
   */
  public static final int FLAG_ENABLE_INDEX_SEEKING = 1 << 2;

  /**
   * The frame size in bytes, including header (1 byte), for each of the 16 frame types for AMR
   * narrow band.
   */
  private static final int[] frameSizeBytesByTypeNb = {
    13,
    14,
    16,
    18,
    20,
    21,
    27,
    32,
    6, // AMR SID
    7, // GSM-EFR SID
    6, // TDMA-EFR SID
    6, // PDC-EFR SID
    1, // Future use
    1, // Future use
    1, // Future use
    1 // No data
  };

  /**
   * The frame size in bytes, including header (1 byte), for each of the 16 frame types for AMR wide
   * band.
   */
  private static final int[] frameSizeBytesByTypeWb = {
    18,
    24,
    33,
    37,
    41,
    47,
    51,
    59,
    61,
    6, // AMR-WB SID
    1, // Future use
    1, // Future use
    1, // Future use
    1, // Future use
    1, // speech lost
    1 // No data
  };

  private static final byte[] amrSignatureNb = Util.getUtf8Bytes("#!AMR\n");
  private static final byte[] amrSignatureWb = Util.getUtf8Bytes("#!AMR-WB\n");

  /**
   * The required number of samples in the stream with same sample size to classify the stream as a
   * constant-bitrate-stream.
   */
  private static final int NUM_SAME_SIZE_CONSTANT_BIT_RATE_THRESHOLD = 20;

  private static final int SAMPLE_RATE_WB = 16_000;
  private static final int SAMPLE_RATE_NB = 8_000;
  private static final int SAMPLE_TIME_PER_FRAME_US = 20_000;

  private final byte[] scratch;
  private final @Flags int flags;
  private final TrackOutput skippingTrackOutput;

  private boolean isWideBand;
  private long currentSampleTimeUs;
  private int currentSampleSize;
  private int currentSampleBytesRemaining;
  private long firstSamplePosition;
  private int firstSampleSize;
  private int numSamplesWithSameSize;
  private long timeOffsetUs;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput realTrackOutput;
  private TrackOutput currentTrackOutput; // skippingTrackOutput or realTrackOutput.
  private @MonotonicNonNull SeekMap seekMap;
  private boolean isSeekInProgress;
  private long seekTimeUs;
  private boolean hasOutputFormat;

  public AmrExtractor() {
    this(/* flags= */ 0);
  }

  /**
   * @param flags Flags that control the extractor's behavior.
   */
  public AmrExtractor(@Flags int flags) {
    if ((flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0) {
      flags |= FLAG_ENABLE_CONSTANT_BITRATE_SEEKING;
    }
    this.flags = flags;
    scratch = new byte[1];
    firstSampleSize = C.LENGTH_UNSET;
    skippingTrackOutput = new DiscardingTrackOutput();
    currentTrackOutput = skippingTrackOutput;
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return readAmrHeader(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    realTrackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
    currentTrackOutput = realTrackOutput;
    output.endTracks();
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    assertInitialized();
    if (input.getPosition() == 0) {
      if (!readAmrHeader(input)) {
        throw ParserException.createForMalformedContainer(
            "Could not find AMR header.", /* cause= */ null);
      }
    }
    maybeOutputFormat();
    int sampleReadResult = readSample(input);
    maybeOutputSeekMap(input.getLength(), sampleReadResult);
    if (sampleReadResult == RESULT_END_OF_INPUT && seekMap instanceof IndexSeekMap) {
      // Set exact duration when end of input is reached.
      long durationUs = timeOffsetUs + currentSampleTimeUs;
      ((IndexSeekMap) seekMap).setDurationUs(durationUs);
      extractorOutput.seekMap(seekMap);
    }
    return sampleReadResult;
  }

  @Override
  public void seek(long position, long timeUs) {
    currentSampleTimeUs = 0;
    currentSampleSize = 0;
    currentSampleBytesRemaining = 0;
    seekTimeUs = timeUs;
    if (seekMap instanceof IndexSeekMap) {
      timeOffsetUs = ((IndexSeekMap) seekMap).getTimeUs(position);
      if (!isSeekTimeUsWithinRange(timeOffsetUs, seekTimeUs)) {
        isSeekInProgress = true;
        currentTrackOutput = skippingTrackOutput;
      }
    } else if (position != 0 && seekMap instanceof ConstantBitrateSeekMap) {
      timeOffsetUs = ((ConstantBitrateSeekMap) seekMap).getTimeUsAtPosition(position);
    } else {
      timeOffsetUs = 0;
    }
  }

  @Override
  public void release() {
    // Do nothing
  }

  /* package */ static int frameSizeBytesByTypeNb(int frameType) {
    return frameSizeBytesByTypeNb[frameType];
  }

  /* package */ static int frameSizeBytesByTypeWb(int frameType) {
    return frameSizeBytesByTypeWb[frameType];
  }

  /* package */ static byte[] amrSignatureNb() {
    return Arrays.copyOf(amrSignatureNb, amrSignatureNb.length);
  }

  /* package */ static byte[] amrSignatureWb() {
    return Arrays.copyOf(amrSignatureWb, amrSignatureWb.length);
  }

  // Internal methods.

  /**
   * Peeks the AMR header from the beginning of the input, and consumes it if it exists.
   *
   * @param input The {@link ExtractorInput} from which data should be peeked/read.
   * @return Whether the AMR header has been read.
   */
  private boolean readAmrHeader(ExtractorInput input) throws IOException {
    if (peekAmrSignature(input, amrSignatureNb)) {
      isWideBand = false;
      input.skipFully(amrSignatureNb.length);
      return true;
    } else if (peekAmrSignature(input, amrSignatureWb)) {
      isWideBand = true;
      input.skipFully(amrSignatureWb.length);
      return true;
    }
    return false;
  }

  /** Peeks from the beginning of the input to see if the given AMR signature exists. */
  private static boolean peekAmrSignature(ExtractorInput input, byte[] amrSignature)
      throws IOException {
    input.resetPeekPosition();
    byte[] header = new byte[amrSignature.length];
    input.peekFully(header, 0, amrSignature.length);
    return Arrays.equals(header, amrSignature);
  }

  @RequiresNonNull("realTrackOutput")
  private void maybeOutputFormat() {
    if (!hasOutputFormat) {
      hasOutputFormat = true;
      String mimeType = isWideBand ? MimeTypes.AUDIO_AMR_WB : MimeTypes.AUDIO_AMR_NB;
      int sampleRate = isWideBand ? SAMPLE_RATE_WB : SAMPLE_RATE_NB;
      // Theoretical maximum frame size for a AMR frame.
      int maxInputSize = isWideBand ? frameSizeBytesByTypeWb[8] : frameSizeBytesByTypeNb[7];
      currentTrackOutput.format(
          new Format.Builder()
              .setSampleMimeType(mimeType)
              .setMaxInputSize(maxInputSize)
              .setChannelCount(1)
              .setSampleRate(sampleRate)
              .build());
    }
  }

  @RequiresNonNull("realTrackOutput")
  private int readSample(ExtractorInput extractorInput) throws IOException {
    if (currentSampleBytesRemaining == 0) {
      try {
        currentSampleSize = peekNextSampleSize(extractorInput);
      } catch (EOFException e) {
        return RESULT_END_OF_INPUT;
      }
      currentSampleBytesRemaining = currentSampleSize;
      if (firstSampleSize == C.LENGTH_UNSET) {
        firstSamplePosition = extractorInput.getPosition();
        firstSampleSize = currentSampleSize;
      }
      if (firstSampleSize == currentSampleSize) {
        numSamplesWithSameSize++;
      }
      if (seekMap instanceof IndexSeekMap) {
        IndexSeekMap indexSeekMap = (IndexSeekMap) seekMap;
        // Add seek point corresponding to the next frame instead of the current one to be able to
        // start writing to the realTrackOutput on time when a seek is in progress.
        long nextSampleTimeUs = timeOffsetUs + currentSampleTimeUs + SAMPLE_TIME_PER_FRAME_US;
        long nextSamplePosition = extractorInput.getPosition() + currentSampleSize;
        if (!indexSeekMap.isTimeUsInIndex(
            nextSampleTimeUs, /* minTimeBetweenPointsUs= */ C.MICROS_PER_SECOND / 10)) {
          indexSeekMap.addSeekPoint(nextSampleTimeUs, nextSamplePosition);
        }
        if (isSeekInProgress && isSeekTimeUsWithinRange(nextSampleTimeUs, seekTimeUs)) {
          isSeekInProgress = false;
          currentTrackOutput = realTrackOutput;
        }
      }
    }

    int bytesAppended =
        currentTrackOutput.sampleData(
            extractorInput, currentSampleBytesRemaining, /* allowEndOfInput= */ true);
    if (bytesAppended == C.RESULT_END_OF_INPUT) {
      return RESULT_END_OF_INPUT;
    }
    currentSampleBytesRemaining -= bytesAppended;
    if (currentSampleBytesRemaining > 0) {
      return RESULT_CONTINUE;
    }

    currentTrackOutput.sampleMetadata(
        timeOffsetUs + currentSampleTimeUs,
        C.BUFFER_FLAG_KEY_FRAME,
        currentSampleSize,
        /* offset= */ 0,
        /* cryptoData= */ null);
    currentSampleTimeUs += SAMPLE_TIME_PER_FRAME_US;
    return RESULT_CONTINUE;
  }

  private int peekNextSampleSize(ExtractorInput extractorInput) throws IOException {
    extractorInput.resetPeekPosition();
    extractorInput.peekFully(scratch, /* offset= */ 0, /* length= */ 1);

    byte frameHeader = scratch[0];
    if ((frameHeader & 0x83) > 0) {
      // The padding bits are at bit-1 positions in the following pattern: 1000 0011
      // Padding bits must be 0.
      throw ParserException.createForMalformedContainer(
          "Invalid padding bits for frame header " + frameHeader, /* cause= */ null);
    }

    int frameType = (frameHeader >> 3) & 0x0f;
    return getFrameSizeInBytes(frameType);
  }

  private int getFrameSizeInBytes(int frameType) throws ParserException {
    if (!isValidFrameType(frameType)) {
      throw ParserException.createForMalformedContainer(
          "Illegal AMR " + (isWideBand ? "WB" : "NB") + " frame type " + frameType,
          /* cause= */ null);
    }

    return isWideBand ? frameSizeBytesByTypeWb[frameType] : frameSizeBytesByTypeNb[frameType];
  }

  private boolean isValidFrameType(int frameType) {
    return frameType >= 0
        && frameType <= 15
        && (isWideBandValidFrameType(frameType) || isNarrowBandValidFrameType(frameType));
  }

  private boolean isWideBandValidFrameType(int frameType) {
    // For wide band, type 10-13 are for future use.
    return isWideBand && (frameType < 10 || frameType > 13);
  }

  private boolean isNarrowBandValidFrameType(int frameType) {
    // For narrow band, type 12-14 are for future use.
    return !isWideBand && (frameType < 12 || frameType > 14);
  }

  @RequiresNonNull("extractorOutput")
  private void maybeOutputSeekMap(long inputLength, int sampleReadResult) {
    if (seekMap != null) {
      return;
    }

    if ((flags & FLAG_ENABLE_INDEX_SEEKING) != 0) {
      seekMap =
          new IndexSeekMap(
              /* positions= */ new long[] {firstSamplePosition},
              /* timesUs= */ new long[] {0L},
              /* durationUs= */ C.TIME_UNSET);
    } else if ((flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) == 0
        || (firstSampleSize != C.LENGTH_UNSET && firstSampleSize != currentSampleSize)) {
      seekMap = new SeekMap.Unseekable(C.TIME_UNSET);
    } else if (numSamplesWithSameSize >= NUM_SAME_SIZE_CONSTANT_BIT_RATE_THRESHOLD
        || sampleReadResult == RESULT_END_OF_INPUT) {
      seekMap =
          getConstantBitrateSeekMap(
              inputLength, (flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0);
    }

    if (seekMap != null) {
      extractorOutput.seekMap(seekMap);
    }
  }

  private SeekMap getConstantBitrateSeekMap(long inputLength, boolean allowSeeksIfLengthUnknown) {
    int bitrate = getBitrateFromFrameSize(firstSampleSize, SAMPLE_TIME_PER_FRAME_US);
    return new ConstantBitrateSeekMap(
        inputLength, firstSamplePosition, bitrate, firstSampleSize, allowSeeksIfLengthUnknown);
  }

  @EnsuresNonNull({"extractorOutput", "realTrackOutput"})
  private void assertInitialized() {
    checkStateNotNull(realTrackOutput);
    Util.castNonNull(extractorOutput);
  }

  /**
   * Checks if a given {@code timeUs} is within the acceptable range for seeking operations in the
   * context of index-based seeking.
   *
   * @param timeUs The time in microseconds to check.
   * @param seekTimeUs The target seek time in microseconds.
   */
  private boolean isSeekTimeUsWithinRange(long timeUs, long seekTimeUs) {
    return abs(seekTimeUs - timeUs) < SAMPLE_TIME_PER_FRAME_US;
  }

  /**
   * Returns the stream bitrate, given a frame size and the duration of that frame in microseconds.
   *
   * @param frameSize The size of each frame in the stream.
   * @param durationUsPerFrame The duration of the given frame in microseconds.
   * @return The stream bitrate.
   */
  private static int getBitrateFromFrameSize(int frameSize, long durationUsPerFrame) {
    return (int)
        ((frameSize * ((long) C.BITS_PER_BYTE) * C.MICROS_PER_SECOND) / durationUsPerFrame);
  }
}
