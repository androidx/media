/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts JPEG frames from Motion JPEG streams.
 *
 * <p>Both raw concatenated JPEG streams and HTTP {@code multipart/x-mixed-replace} bodies are
 * supported. Multipart bodies may declare each frame size with {@code Content-Length}. If they do
 * not, the JPEG end-of-image marker is used to find the end of each frame. Optional {@code
 * X-Timestamp} part headers are used as presentation timestamps; otherwise frame arrival times are
 * used. Raw streams use a default frame rate of 25 frames per second.
 */
@UnstableApi
public final class MjpegExtractor implements Extractor {

  private static final int STATE_DETECTING_STREAM_TYPE = 0;
  private static final int STATE_READING_BOUNDARY = 1;
  private static final int STATE_READING_HEADERS = 2;
  private static final int STATE_SCANNING_SAMPLE = 3;
  private static final int STATE_READING_SAMPLE = 4;

  private static final int STREAM_TYPE_UNSET = 0;
  private static final int STREAM_TYPE_MULTIPART = 1;
  private static final int STREAM_TYPE_RAW = 2;

  private static final int DEFAULT_RAW_FRAME_RATE = 25;
  private static final int MAX_HEADER_LINE_LENGTH = 4 * 1024;
  private static final int MAX_HEADER_COUNT = 100;
  private static final int MAX_SAMPLE_READ_LENGTH = 16 * 1024;
  private static final int MAX_SNIFF_BYTES = 4 * 1024 * 1024;
  private static final int JPEG_START_OF_IMAGE = 0xFFD8;
  private static final int JPEG_END_OF_IMAGE = 0xFFD9;

  private final Clock clock;
  private final byte[] scratch;
  private final byte[] sampleScanBuffer;
  private final StringBuilder lineBuffer;

  private int state;
  private int streamType;
  private int sampleSize;
  private int sampleBytesRemaining;
  private int sampleBytesPeeked;
  private boolean previousPeekByteWasFF;
  private boolean outputFormatSet;
  private long firstSampleRealtimeMs;
  private long firstPartTimestampUs;
  private long partTimestampUs;
  private long lastSampleTimeUs;
  private long sampleIndex;
  private @Nullable String boundary;
  private @MonotonicNonNull TrackOutput trackOutput;

  /** Creates an instance. */
  public MjpegExtractor() {
    this(Clock.DEFAULT);
  }

  @VisibleForTesting
  /* package */ MjpegExtractor(Clock clock) {
    this.clock = clock;
    scratch = new byte[2];
    sampleScanBuffer = new byte[MAX_SAMPLE_READ_LENGTH];
    lineBuffer = new StringBuilder();
    resetState();
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    try {
      if (!input.peekFully(
          scratch, /* offset= */ 0, /* length= */ 2, /* allowEndOfInput= */ true)) {
        return false;
      }
      int signature = ((scratch[0] & 0xFF) << 8) | (scratch[1] & 0xFF);
      input.resetPeekPosition();
      if (signature == JPEG_START_OF_IMAGE) {
        return sniffRawStream(input);
      }
      if (scratch[0] == '-' && scratch[1] == '-') {
        return sniffMultipartStream(input);
      }
      return false;
    } catch (EOFException e) {
      return false;
    }
  }

  @Override
  public void init(ExtractorOutput output) {
    trackOutput = output.track(/* id= */ 0, C.TRACK_TYPE_IMAGE);
    output.endTracks();
    output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    switch (state) {
      case STATE_DETECTING_STREAM_TYPE:
        return detectStreamType(input);
      case STATE_READING_BOUNDARY:
        return readBoundary(input);
      case STATE_READING_HEADERS:
        return readHeader(input);
      case STATE_SCANNING_SAMPLE:
        return scanSample(input);
      case STATE_READING_SAMPLE:
        return readSample(input);
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    if (position == 0) {
      resetState();
    }
  }

  @Override
  public void release() {
    // Do nothing.
  }

  private @ReadResult int detectStreamType(ExtractorInput input) throws IOException {
    if (!input.peekFully(
        scratch, /* offset= */ 0, /* length= */ 2, /* allowEndOfInput= */ true)) {
      return RESULT_END_OF_INPUT;
    }
    input.resetPeekPosition();
    int signature = ((scratch[0] & 0xFF) << 8) | (scratch[1] & 0xFF);
    if (signature == JPEG_START_OF_IMAGE) {
      streamType = STREAM_TYPE_RAW;
      outputFormat(MimeTypes.VIDEO_MJPEG);
      startScanningSample();
    } else if (scratch[0] == '-' && scratch[1] == '-') {
      streamType = STREAM_TYPE_MULTIPART;
      outputFormat(MimeTypes.MULTIPART_MJPEG);
      state = STATE_READING_BOUNDARY;
    } else {
      throw malformedStream("Input is not a raw or multipart MJPEG stream.");
    }
    return RESULT_CONTINUE;
  }

  private @ReadResult int readBoundary(ExtractorInput input) throws IOException {
    @Nullable String line;
    do {
      line = readLine(input);
      if (line == null) {
        return RESULT_END_OF_INPUT;
      }
    } while (line.isEmpty());

    if (boundary == null) {
      if (!isBoundary(line)) {
        throw malformedStream("Invalid initial MJPEG boundary.");
      }
      boundary = line;
    } else if (line.equals(boundary + "--")) {
      return RESULT_END_OF_INPUT;
    } else if (!line.equals(boundary)) {
      throw malformedStream("Unexpected MJPEG boundary.");
    }
    sampleSize = C.LENGTH_UNSET;
    partTimestampUs = C.TIME_UNSET;
    state = STATE_READING_HEADERS;
    return RESULT_CONTINUE;
  }

  private @ReadResult int readHeader(ExtractorInput input) throws IOException {
    @Nullable String line = readLine(input);
    if (line == null) {
      return RESULT_END_OF_INPUT;
    }
    if (line.isEmpty()) {
      if (sampleSize > 0) {
        sampleBytesRemaining = sampleSize;
        state = STATE_READING_SAMPLE;
      } else {
        startScanningSample();
      }
      return RESULT_CONTINUE;
    }

    int colonIndex = line.indexOf(':');
    if (colonIndex == -1) {
      return RESULT_CONTINUE;
    }
    String headerName = line.substring(0, colonIndex).trim();
    String headerValue = line.substring(colonIndex + 1).trim();
    if (headerName.equalsIgnoreCase("Content-Length")) {
      try {
        sampleSize = parseContentLength(headerValue);
      } catch (NumberFormatException e) {
        throw malformedStream("Invalid MJPEG Content-Length header.", e);
      }
    } else if (headerName.equalsIgnoreCase("X-Timestamp")) {
      try {
        partTimestampUs = parseTimestampUs(headerValue);
      } catch (NumberFormatException e) {
        // Ignore malformed optional timestamps and fall back to frame arrival times.
        partTimestampUs = C.TIME_UNSET;
      }
    }
    return RESULT_CONTINUE;
  }

  private @ReadResult int scanSample(ExtractorInput input) throws IOException {
    int bytesPeeked = input.peek(sampleScanBuffer, /* offset= */ 0, sampleScanBuffer.length);
    if (bytesPeeked == C.RESULT_END_OF_INPUT) {
      input.resetPeekPosition();
      return RESULT_END_OF_INPUT;
    }
    for (int i = 0; i < bytesPeeked; i++) {
      int value = sampleScanBuffer[i] & 0xFF;
      if (previousPeekByteWasFF && value == (JPEG_END_OF_IMAGE & 0xFF)) {
        sampleSize = sampleBytesPeeked + i + 1;
        sampleBytesRemaining = sampleSize;
        input.resetPeekPosition();
        state = STATE_READING_SAMPLE;
        return RESULT_CONTINUE;
      }
      previousPeekByteWasFF = value == 0xFF;
    }
    if (bytesPeeked > Integer.MAX_VALUE - sampleBytesPeeked) {
      throw malformedStream("MJPEG frame exceeds the supported sample size.");
    }
    sampleBytesPeeked += bytesPeeked;
    return RESULT_CONTINUE;
  }

  private @ReadResult int readSample(ExtractorInput input) throws IOException {
    int bytesRead =
        checkNotNull(trackOutput)
            .sampleData(
                input,
                Math.min(sampleBytesRemaining, MAX_SAMPLE_READ_LENGTH),
                /* allowEndOfInput= */ true);
    if (bytesRead == C.RESULT_END_OF_INPUT) {
      return RESULT_END_OF_INPUT;
    }
    sampleBytesRemaining -= bytesRead;
    if (sampleBytesRemaining == 0) {
      long sampleTimeUs = getSampleTimeUs();
      trackOutput.sampleMetadata(
          sampleTimeUs,
          BUFFER_FLAG_KEY_FRAME,
          sampleSize,
          /* offset= */ 0,
          /* cryptoData= */ null);
      lastSampleTimeUs = sampleTimeUs;
      sampleIndex++;
      if (streamType == STREAM_TYPE_RAW) {
        startScanningSample();
      } else {
        state = STATE_READING_BOUNDARY;
      }
    }
    return RESULT_CONTINUE;
  }

  private long getSampleTimeUs() {
    if (streamType == STREAM_TYPE_RAW) {
      return (sampleIndex * C.MICROS_PER_SECOND) / DEFAULT_RAW_FRAME_RATE;
    }
    long candidateTimeUs;
    if (partTimestampUs != C.TIME_UNSET) {
      if (firstPartTimestampUs == C.TIME_UNSET) {
        firstPartTimestampUs = partTimestampUs;
      }
      candidateTimeUs = partTimestampUs - firstPartTimestampUs;
    } else {
      long nowMs = clock.elapsedRealtime();
      if (firstSampleRealtimeMs == C.TIME_UNSET) {
        firstSampleRealtimeMs = nowMs;
      }
      candidateTimeUs = (nowMs - firstSampleRealtimeMs) * 1000;
    }
    return lastSampleTimeUs == C.TIME_UNSET
        ? 0
        : Math.max(lastSampleTimeUs + 1, candidateTimeUs);
  }

  private void outputFormat(String containerMimeType) {
    if (outputFormatSet) {
      return;
    }
    Format.Builder formatBuilder =
        new Format.Builder()
            .setContainerMimeType(containerMimeType)
            .setSampleMimeType(MimeTypes.IMAGE_JPEG);
    if (streamType == STREAM_TYPE_RAW) {
      formatBuilder.setFrameRate(DEFAULT_RAW_FRAME_RATE);
    }
    checkNotNull(trackOutput).format(formatBuilder.build());
    outputFormatSet = true;
  }

  private void startScanningSample() {
    sampleSize = C.LENGTH_UNSET;
    sampleBytesRemaining = 0;
    sampleBytesPeeked = 0;
    previousPeekByteWasFF = false;
    state = STATE_SCANNING_SAMPLE;
  }

  private @Nullable String readLine(ExtractorInput input) throws IOException {
    while (true) {
      int result = input.read(scratch, /* offset= */ 0, /* length= */ 1);
      if (result == C.RESULT_END_OF_INPUT) {
        if (lineBuffer.length() == 0) {
          return null;
        }
        throw malformedStream("Truncated MJPEG header line.");
      }
      int value = scratch[0] & 0xFF;
      if (value == '\n') {
        String line = lineBuffer.toString();
        lineBuffer.setLength(0);
        return line;
      }
      if (value != '\r') {
        if (lineBuffer.length() == MAX_HEADER_LINE_LENGTH) {
          throw malformedStream("MJPEG header line is too long.");
        }
        lineBuffer.append((char) value);
      }
    }
  }

  private static boolean sniffMultipartStream(ExtractorInput input) throws IOException {
    @Nullable String firstLine = peekLine(input);
    if (firstLine == null || !isBoundary(firstLine)) {
      return false;
    }
    for (int i = 0; i < MAX_HEADER_COUNT; i++) {
      @Nullable String line = peekLine(input);
      if (line == null) {
        return false;
      }
      if (line.isEmpty()) {
        byte[] signature = new byte[2];
        input.peekFully(signature, /* offset= */ 0, /* length= */ 2);
        return (((signature[0] & 0xFF) << 8) | (signature[1] & 0xFF))
            == JPEG_START_OF_IMAGE;
      }
    }
    return false;
  }

  private static boolean sniffRawStream(ExtractorInput input) throws IOException {
    byte[] value = new byte[1];
    boolean previousByteWasFF = false;
    for (int i = 0; i < MAX_SNIFF_BYTES; i++) {
      if (!input.peekFully(value, /* offset= */ 0, /* length= */ 1, /* allowEndOfInput= */ true)) {
        return false;
      }
      int unsignedValue = value[0] & 0xFF;
      if (previousByteWasFF && unsignedValue == (JPEG_END_OF_IMAGE & 0xFF)) {
        return peekNextJpegStart(input);
      }
      previousByteWasFF = unsignedValue == 0xFF;
    }
    return false;
  }

  private static boolean peekNextJpegStart(ExtractorInput input) throws IOException {
    byte[] value = new byte[1];
    if (!input.peekFully(value, /* offset= */ 0, /* length= */ 1, /* allowEndOfInput= */ true)
        || (value[0] & 0xFF) != 0xFF) {
      return false;
    }
    input.peekFully(value, /* offset= */ 0, /* length= */ 1);
    return (value[0] & 0xFF) == 0xD8;
  }

  private static @Nullable String peekLine(ExtractorInput input) throws IOException {
    StringBuilder line = new StringBuilder();
    byte[] value = new byte[1];
    while (true) {
      if (!input.peekFully(value, /* offset= */ 0, /* length= */ 1, /* allowEndOfInput= */ true)) {
        return line.length() == 0 ? null : line.toString();
      }
      int unsignedValue = value[0] & 0xFF;
      if (unsignedValue == '\n') {
        return line.toString();
      }
      if (unsignedValue != '\r') {
        if (line.length() == MAX_HEADER_LINE_LENGTH) {
          return null;
        }
        line.append((char) unsignedValue);
      }
    }
  }

  private static boolean isBoundary(String line) {
    return line.startsWith("--") && line.length() > 2;
  }

  private static int parseContentLength(String value) throws NumberFormatException {
    long contentLength = Long.parseLong(value.trim());
    if (contentLength <= 0 || contentLength > Integer.MAX_VALUE) {
      throw new NumberFormatException("Content-Length is outside the supported sample size.");
    }
    return (int) contentLength;
  }

  private static long parseTimestampUs(String value) throws NumberFormatException {
    int decimalPointIndex = value.indexOf('.');
    String secondsString = decimalPointIndex == -1 ? value : value.substring(0, decimalPointIndex);
    String fractionString = decimalPointIndex == -1 ? "" : value.substring(decimalPointIndex + 1);
    if (fractionString.length() > 6) {
      fractionString = fractionString.substring(0, 6);
    }
    if (fractionString.length() < 6) {
      fractionString = (fractionString + "000000").substring(0, 6);
    }
    long seconds = Long.parseLong(secondsString);
    long fractionUs = fractionString.isEmpty() ? 0 : Long.parseLong(fractionString);
    try {
      return Math.addExact(Math.multiplyExact(seconds, C.MICROS_PER_SECOND), fractionUs);
    } catch (ArithmeticException e) {
      throw new NumberFormatException("X-Timestamp is outside the supported range.");
    }
  }

  private static ParserException malformedStream(String message) {
    return malformedStream(message, /* cause= */ null);
  }

  private static ParserException malformedStream(String message, @Nullable Throwable cause) {
    return ParserException.createForMalformedContainer(message, cause);
  }

  private void resetState() {
    state = STATE_DETECTING_STREAM_TYPE;
    streamType = STREAM_TYPE_UNSET;
    sampleSize = C.LENGTH_UNSET;
    sampleBytesRemaining = 0;
    sampleBytesPeeked = 0;
    previousPeekByteWasFF = false;
    firstSampleRealtimeMs = C.TIME_UNSET;
    firstPartTimestampUs = C.TIME_UNSET;
    partTimestampUs = C.TIME_UNSET;
    lastSampleTimeUs = C.TIME_UNSET;
    sampleIndex = 0;
    boundary = null;
    lineBuffer.setLength(0);
  }
}
