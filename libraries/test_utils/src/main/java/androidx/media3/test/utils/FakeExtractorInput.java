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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.util.SparseBooleanArray;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.EOFException;
import java.io.IOException;

/**
 * A fake {@link ExtractorInput} capable of simulating various scenarios.
 *
 * <p>Read, skip and peek errors can be simulated using {@link Builder#setSimulateIOErrors}. When
 * enabled each read and skip will throw a {@link SimulatedIOException} unless one has already been
 * thrown from the current position. Each peek will throw {@link SimulatedIOException} unless one
 * has already been thrown from the current peek position. When a {@link SimulatedIOException} is
 * thrown the read position is left unchanged and the peek position is reset back to the read
 * position.
 *
 * <p>Partial reads and skips can be simulated using {@link Builder#setSimulatePartialReads}. When
 * enabled, {@link #read(byte[], int, int)} and {@link #skip(int)} calls will only read or skip a
 * single byte unless a partial read or skip has already been performed that had the same target
 * position. For example, a first read request for 10 bytes will be partially satisfied by reading a
 * single byte and advancing the position to 1. If the following read request attempts to read 9
 * bytes then it will be fully satisfied, since it has the same target position of 10.
 *
 * <p>Unknown data length can be simulated using {@link Builder#setSimulateUnknownLength}. When
 * enabled {@link #getLength()} will return {@link C#LENGTH_UNSET} rather than the length of the
 * data.
 */
@UnstableApi
public final class FakeExtractorInput implements ExtractorInput {

  /** Thrown when simulating an {@link IOException}. */
  public static final class SimulatedIOException extends IOException {

    public SimulatedIOException(String message) {
      super(message);
    }
  }

  private final byte[] data;
  private final boolean simulateUnknownLength;
  private final boolean simulatePartialReads;
  private final boolean simulateIOErrors;
  private final int peekLimit;

  private int readPosition;
  private int peekPosition;
  private int maxPeekLimit;

  private final SparseBooleanArray partiallySatisfiedTargetReadPositions;
  private final SparseBooleanArray partiallySatisfiedTargetPeekPositions;
  private final SparseBooleanArray failedReadPositions;
  private final SparseBooleanArray failedPeekPositions;

  private FakeExtractorInput(
      byte[] data,
      boolean simulateUnknownLength,
      boolean simulatePartialReads,
      boolean simulateIOErrors,
      int peekLimit) {
    this.data = data;
    this.simulateUnknownLength = simulateUnknownLength;
    this.simulatePartialReads = simulatePartialReads;
    this.simulateIOErrors = simulateIOErrors;
    this.peekLimit = peekLimit;
    partiallySatisfiedTargetReadPositions = new SparseBooleanArray();
    partiallySatisfiedTargetPeekPositions = new SparseBooleanArray();
    failedReadPositions = new SparseBooleanArray();
    failedPeekPositions = new SparseBooleanArray();
  }

  /** Resets the input to its initial state. */
  public void reset() {
    readPosition = 0;
    peekPosition = 0;
    partiallySatisfiedTargetReadPositions.clear();
    partiallySatisfiedTargetPeekPositions.clear();
    failedReadPositions.clear();
    failedPeekPositions.clear();
  }

  /**
   * Sets the read and peek positions.
   *
   * @param position The position to set.
   */
  public void setPosition(int position) {
    assertThat(position).isAtLeast(0);
    assertThat(position).isAtMost(data.length);
    readPosition = position;
    peekPosition = position;
  }

  public int getMaxPeekLimit() {
    return maxPeekLimit;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    checkIOException(readPosition, failedReadPositions);
    length = getLengthToRead(readPosition, length, partiallySatisfiedTargetReadPositions);
    return readFullyInternal(buffer, offset, length, true) ? length : C.RESULT_END_OF_INPUT;
  }

  @Override
  public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
      throws IOException {
    checkIOException(readPosition, failedReadPositions);
    return readFullyInternal(target, offset, length, allowEndOfInput);
  }

  @Override
  public void readFully(byte[] target, int offset, int length) throws IOException {
    readFully(target, offset, length, false);
  }

  @Override
  public int skip(int length) throws IOException {
    checkIOException(readPosition, failedReadPositions);
    length = getLengthToRead(readPosition, length, partiallySatisfiedTargetReadPositions);
    return skipFullyInternal(length, true) ? length : C.RESULT_END_OF_INPUT;
  }

  @Override
  public boolean skipFully(int length, boolean allowEndOfInput) throws IOException {
    checkIOException(readPosition, failedReadPositions);
    return skipFullyInternal(length, allowEndOfInput);
  }

  @Override
  public void skipFully(int length) throws IOException {
    skipFully(length, false);
  }

  @Override
  public int peek(byte[] target, int offset, int length) throws IOException {
    checkIOException(peekPosition, failedPeekPositions);
    length = getLengthToRead(peekPosition, length, partiallySatisfiedTargetPeekPositions);
    return peekFullyInternal(target, offset, length, true) ? length : C.RESULT_END_OF_INPUT;
  }

  @Override
  public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput)
      throws IOException {
    checkIOException(peekPosition, failedPeekPositions);
    return peekFullyInternal(target, offset, length, allowEndOfInput);
  }

  @Override
  public void peekFully(byte[] target, int offset, int length) throws IOException {
    peekFully(target, offset, length, false);
  }

  @Override
  public boolean advancePeekPosition(int length, boolean allowEndOfInput) throws IOException {
    checkIOException(peekPosition, failedPeekPositions);
    if (!checkXFully(allowEndOfInput, peekPosition, length)) {
      return false;
    }
    checkPeekLimit(length);
    peekPosition += length;
    return true;
  }

  @Override
  public void advancePeekPosition(int length) throws IOException {
    advancePeekPosition(length, false);
  }

  @Override
  public void resetPeekPosition() {
    peekPosition = readPosition;
  }

  @Override
  public long getPeekPosition() {
    return peekPosition;
  }

  @Override
  public long getPosition() {
    return readPosition;
  }

  @Override
  public long getLength() {
    return simulateUnknownLength ? C.LENGTH_UNSET : data.length;
  }

  @Override
  public <E extends Throwable> void setRetryPosition(long position, E e) throws E {
    assertThat(position >= 0).isTrue();
    readPosition = (int) position;
    throw e;
  }

  private void checkIOException(int position, SparseBooleanArray failedPositions)
      throws SimulatedIOException {
    if (simulateIOErrors && !failedPositions.get(position)) {
      failedPositions.put(position, true);
      peekPosition = readPosition;
      throw new SimulatedIOException("Simulated IO error at position: " + position);
    }
  }

  private boolean checkXFully(boolean allowEndOfInput, int position, int length)
      throws EOFException {
    if (length > 0 && position == data.length) {
      if (allowEndOfInput) {
        return false;
      }
      throw new EOFException();
    }
    if (position + length > data.length) {
      throw new EOFException(
          "Attempted to move past end of data: ("
              + position
              + " + "
              + length
              + ") > "
              + data.length);
    }
    return true;
  }

  private int getLengthToRead(
      int position, int requestedLength, SparseBooleanArray partiallySatisfiedTargetPositions) {
    if (position == data.length) {
      // If the requested length is non-zero, the end of the input will be read.
      return requestedLength == 0 ? 0 : Integer.MAX_VALUE;
    }
    int targetPosition = position + requestedLength;
    if (simulatePartialReads
        && requestedLength > 1
        && !partiallySatisfiedTargetPositions.get(targetPosition)) {
      partiallySatisfiedTargetPositions.put(targetPosition, true);
      return 1;
    }
    return min(requestedLength, data.length - position);
  }

  private boolean readFullyInternal(byte[] target, int offset, int length, boolean allowEndOfInput)
      throws EOFException {
    if (!checkXFully(allowEndOfInput, readPosition, length)) {
      return false;
    }
    System.arraycopy(data, readPosition, target, offset, length);
    readPosition += length;
    peekPosition = readPosition;
    return true;
  }

  private boolean skipFullyInternal(int length, boolean allowEndOfInput) throws EOFException {
    if (!checkXFully(allowEndOfInput, readPosition, length)) {
      return false;
    }
    readPosition += length;
    peekPosition = readPosition;
    return true;
  }

  private boolean peekFullyInternal(byte[] target, int offset, int length, boolean allowEndOfInput)
      throws EOFException {
    if (!checkXFully(allowEndOfInput, peekPosition, length)) {
      return false;
    }
    checkPeekLimit(length);
    System.arraycopy(data, peekPosition, target, offset, length);
    peekPosition += length;
    return true;
  }

  private void checkPeekLimit(int length) {
    int targetPeekOffset = peekPosition + length - readPosition;
    maxPeekLimit = max(maxPeekLimit, targetPeekOffset);
    if (peekLimit != C.LENGTH_UNSET && targetPeekOffset > peekLimit) {
      throw new IllegalStateException(
          "Peeking "
              + length
              + " bytes would increase the peek offset to "
              + targetPeekOffset
              + " and exceed the peek limit of "
              + peekLimit
              + " (readPosition="
              + readPosition
              + ", peekPosition="
              + peekPosition
              + ")");
    }
  }

  /** Builder of {@link FakeExtractorInput} instances. */
  public static final class Builder {

    private byte[] data;
    private boolean simulateUnknownLength;
    private boolean simulatePartialReads;
    private boolean simulateIOErrors;
    private int peekLimit;

    public Builder() {
      data = Util.EMPTY_BYTE_ARRAY;
      peekLimit = C.LENGTH_UNSET;
    }

    @CanIgnoreReturnValue
    public Builder setData(byte[] data) {
      this.data = data;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSimulateUnknownLength(boolean simulateUnknownLength) {
      this.simulateUnknownLength = simulateUnknownLength;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSimulatePartialReads(boolean simulatePartialReads) {
      this.simulatePartialReads = simulatePartialReads;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSimulateIOErrors(boolean simulateIOErrors) {
      this.simulateIOErrors = simulateIOErrors;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPeekLimit(int peekLimit) {
      checkArgument(peekLimit == C.LENGTH_UNSET || peekLimit >= 0);
      this.peekLimit = peekLimit;
      return this;
    }

    public FakeExtractorInput build() {
      return new FakeExtractorInput(
          data, simulateUnknownLength, simulatePartialReads, simulateIOErrors, peekLimit);
    }
  }
}
