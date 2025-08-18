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
package androidx.media3.common.util;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import com.google.common.primitives.UnsignedBytes;
import com.google.common.primitives.UnsignedInts;
import com.google.errorprone.annotations.CheckReturnValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a byte array, providing a set of methods for parsing data from it. Numerical values are
 * parsed with the assumption that their constituent bytes are in big endian order.
 */
@UnstableApi
@CheckReturnValue
public final class ParsableByteArray {

  /** A value that is outside the valid range of unicode code points. */
  public static final int INVALID_CODE_POINT = 0x11_0000;

  private static final char[] CR_AND_LF = {'\r', '\n'};
  private static final char[] LF = {'\n'};
  private static final ImmutableSet<Charset> SUPPORTED_CHARSETS_FOR_READLINE =
      ImmutableSet.of(
          StandardCharsets.US_ASCII,
          StandardCharsets.UTF_8,
          StandardCharsets.UTF_16,
          StandardCharsets.UTF_16BE,
          StandardCharsets.UTF_16LE);

  // TODO: b/147657250 - Flip this to true
  private static final AtomicBoolean shouldEnforceLimitOnLegacyMethods = new AtomicBoolean();

  private byte[] data;
  private int position;
  // TODO(internal b/147657250): Enforce this limit on all read methods.
  private int limit;

  /** Creates a new instance that initially has no backing data. */
  public ParsableByteArray() {
    data = Util.EMPTY_BYTE_ARRAY;
  }

  /**
   * Creates a new instance with {@code limit} bytes and sets the limit.
   *
   * @param limit The limit to set.
   */
  public ParsableByteArray(int limit) {
    this.data = new byte[limit];
    this.limit = limit;
  }

  /**
   * Creates a new instance wrapping {@code data}, and sets the limit to {@code data.length}.
   *
   * @param data The array to wrap.
   */
  public ParsableByteArray(byte[] data) {
    this.data = data;
    limit = data.length;
  }

  /**
   * Creates a new instance that wraps an existing array.
   *
   * @param data The data to wrap.
   * @param limit The limit to set.
   */
  public ParsableByteArray(byte[] data, int limit) {
    this.data = data;
    this.limit = limit;
  }

  /**
   * Resets the position to zero and the limit to the specified value. This might replace or wipe
   * the {@link #getData() underlying array}, potentially invalidating any local references.
   *
   * @param limit The limit to set.
   */
  public void reset(int limit) {
    reset(capacity() < limit ? new byte[limit] : data, limit);
  }

  /**
   * Updates the instance to wrap {@code data}, and resets the position to zero and the limit to
   * {@code data.length}.
   *
   * @param data The array to wrap.
   */
  public void reset(byte[] data) {
    reset(data, data.length);
  }

  /**
   * Updates the instance to wrap {@code data}, and resets the position to zero.
   *
   * @param data The array to wrap.
   * @param limit The limit to set.
   */
  public void reset(byte[] data, int limit) {
    this.data = data;
    this.limit = limit;
    position = 0;
  }

  /**
   * Ensures the backing array is at least {@code requiredCapacity} long.
   *
   * <p>{@link #getPosition() position}, {@link #limit() limit}, and all data in the underlying
   * array (including that beyond {@link #limit()}) are preserved.
   *
   * <p>This might replace or wipe the {@link #getData() underlying array}, potentially invalidating
   * any local references.
   */
  public void ensureCapacity(int requiredCapacity) {
    if (requiredCapacity > capacity()) {
      data = Arrays.copyOf(data, requiredCapacity);
    }
  }

  /** Returns the number of bytes yet to be read. */
  public int bytesLeft() {
    return Math.max(limit - position, 0);
  }

  /** Returns the limit. */
  public int limit() {
    return limit;
  }

  /**
   * Sets the limit.
   *
   * @param limit The limit to set.
   */
  public void setLimit(int limit) {
    checkArgument(limit >= 0 && limit <= data.length);
    this.limit = limit;
  }

  /** Returns the current offset in the array, in bytes. */
  public int getPosition() {
    return position;
  }

  /**
   * Sets the reading offset in the array.
   *
   * @param position Byte offset in the array from which to read.
   * @throws IllegalArgumentException Thrown if the new position is neither in nor at the end of the
   *     array.
   */
  public void setPosition(int position) {
    // It is fine for position to be at the end of the array.
    checkArgument(position >= 0 && position <= limit);
    this.position = position;
  }

  /**
   * Returns the underlying array.
   *
   * <p>Changes to this array are reflected in the results of the {@code read...()} methods.
   *
   * <p>This reference must be assumed to become invalid when {@link #reset} or {@link
   * #ensureCapacity} are called (because the array might get reallocated).
   */
  public byte[] getData() {
    return data;
  }

  /** Returns the capacity of the array, which may be larger than the limit. */
  public int capacity() {
    return data.length;
  }

  /**
   * Moves the reading offset by {@code bytes}.
   *
   * @param bytes The number of bytes to skip.
   * @throws IllegalArgumentException Thrown if the new position is neither in nor at the end of the
   *     array.
   */
  public void skipBytes(int bytes) {
    setPosition(position + bytes);
  }

  /**
   * Reads the next {@code length} bytes into {@code bitArray}, and resets the position of {@code
   * bitArray} to zero.
   *
   * @param bitArray The {@link ParsableBitArray} into which the bytes should be read.
   * @param length The number of bytes to write.
   */
  public void readBytes(ParsableBitArray bitArray, int length) {
    readBytes(bitArray.data, 0, length);
    bitArray.setPosition(0);
  }

  /**
   * Reads the next {@code length} bytes into {@code buffer} at {@code offset}.
   *
   * @see System#arraycopy(Object, int, Object, int, int)
   * @param buffer The array into which the read data should be written.
   * @param offset The offset in {@code buffer} at which the read data should be written.
   * @param length The number of bytes to read.
   */
  public void readBytes(byte[] buffer, int offset, int length) {
    maybeAssertAtLeastBytesLeftForLegacyMethod(length);
    System.arraycopy(data, position, buffer, offset, length);
    position += length;
  }

  /**
   * Reads the next {@code length} bytes into {@code buffer}.
   *
   * @see ByteBuffer#put(byte[], int, int)
   * @param buffer The {@link ByteBuffer} into which the read data should be written.
   * @param length The number of bytes to read.
   */
  public void readBytes(ByteBuffer buffer, int length) {
    maybeAssertAtLeastBytesLeftForLegacyMethod(length);
    buffer.put(data, position, length);
    position += length;
  }

  /** Peeks at the next byte as an unsigned value. */
  public int peekUnsignedByte() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(1);
    return (data[position] & 0xFF);
  }

  /** Peeks at the next two bytes and interprets them as a big-endian char. */
  public char peekChar() {
    return peekChar(BIG_ENDIAN, /* offset= */ 0);
  }

  /**
   * @deprecated Either use {@link #peekChar()} to peek the next two bytes (big-endian) or {@link
   *     #peekCodePoint(Charset)} to peek in a {@link Charset}-aware way.
   */
  @Deprecated
  public char peekChar(Charset charset) {
    checkArgument(
        SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset);
    if (bytesLeft() == 0) {
      return 0;
    }
    if (charset.equals(StandardCharsets.US_ASCII)) {
      return (char) peekUnsignedByte();
    } else if (charset.equals(StandardCharsets.UTF_8)) {
      return (data[position] & 0x80) == 0 ? (char) peekUnsignedByte() : 0;
    } else {
      // UTF-16
      if (bytesLeft() < 2) {
        return 0;
      }
      ByteOrder byteOrder = charset.equals(StandardCharsets.UTF_16LE) ? LITTLE_ENDIAN : BIG_ENDIAN;
      return peekChar(byteOrder, /* offset= */ 0);
    }
  }

  /** Peek the UTF-16 char at {@link #position}{@code + offset}. */
  private char peekChar(ByteOrder byteOrder, int offset) {
    maybeAssertAtLeastBytesLeftForLegacyMethod(2);
    return byteOrder == BIG_ENDIAN
        ? Chars.fromBytes(data[position + offset], data[position + offset + 1])
        : Chars.fromBytes(data[position + offset + 1], data[position + offset]);
  }

  /**
   * Peeks at the code point starting at {@link #getPosition()} as interpreted by {@code charset}.
   *
   * <p>The exact behaviour depends on {@code charset}:
   *
   * <ul>
   *   <li>US_ASCII: Returns the byte at {@link #getPosition()} if it's valid ASCII (less than
   *       {@code 0x80}), otherwise returns {@link #INVALID_CODE_POINT}.
   *   <li>UTF-8: If {@link #getPosition()} is the start of a UTF-8 code unit the whole unit is
   *       decoded and returned. Otherwise {@link #INVALID_CODE_POINT} is returned.
   *   <li>UTF-16 (all endian-nesses):
   *       <ul>
   *         <li>If {@link #getPosition()} is at the start of a {@linkplain
   *             Character#isHighSurrogate(char) high surrogate} code unit and the following two
   *             bytes are a {@linkplain Character#isLowSurrogate(char)} low surrogate} code unit,
   *             the {@linkplain Character#toCodePoint(char, char) combined code point} is returned.
   *         <li>Otherwise the single code unit starting at {@link #getPosition()} is returned
   *             directly.
   *         <li>UTF-16 has no support for byte-level synchronization, so if {@link #getPosition()}
   *             is not aligned with the start of a UTF-16 code unit then the result is undefined.
   *       </ul>
   * </ul>
   *
   * @throws IllegalArgumentException if charset is not supported. Only US_ASCII, UTF-8, UTF-16,
   *     UTF-16BE, and UTF-16LE are supported.
   * @throws IndexOutOfBoundsException if {@link #bytesLeft()} doesn't allow reading the smallest
   *     code unit in {@code charset} (1 byte for ASCII and UTF-8, 2 bytes for UTF-16).
   */
  public int peekCodePoint(Charset charset) {
    int codePointAndSize = peekCodePointAndSize(charset);
    return codePointAndSize != 0 ? Ints.checkedCast(codePointAndSize >>> 8) : INVALID_CODE_POINT;
  }

  /** Peeks the next three bytes as an unsigned value. */
  public int peekUnsignedInt24() {
    if (bytesLeft() < 3) {
      throw new IndexOutOfBoundsException("position=" + position + ", limit=" + limit);
    }
    int result = readUnsignedInt24();
    position -= 3;
    return result;
  }

  /** Peeks the next four bytes as a signed value. */
  public int peekInt() {
    if (bytesLeft() < 4) {
      throw new IndexOutOfBoundsException("position=" + position + ", limit=" + limit);
    }
    int result = readInt();
    position -= 4;
    return result;
  }

  /** Reads the next byte as an unsigned value. */
  public int readUnsignedByte() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(1);
    return (data[position++] & 0xFF);
  }

  /** Reads the next two bytes as an unsigned value. */
  public int readUnsignedShort() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(2);
    return (data[position++] & 0xFF) << 8 | (data[position++] & 0xFF);
  }

  /** Reads the next two bytes as an unsigned value. */
  public int readLittleEndianUnsignedShort() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(2);
    return (data[position++] & 0xFF) | (data[position++] & 0xFF) << 8;
  }

  /** Reads the next two bytes as a signed value. */
  public short readShort() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(2);
    return (short) ((data[position++] & 0xFF) << 8 | (data[position++] & 0xFF));
  }

  /** Reads the next two bytes as a signed value. */
  public short readLittleEndianShort() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(2);
    return (short) ((data[position++] & 0xFF) | (data[position++] & 0xFF) << 8);
  }

  /** Reads the next three bytes as an unsigned value. */
  public int readUnsignedInt24() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(3);
    return (data[position++] & 0xFF) << 16
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF);
  }

  /** Reads the next three bytes as a signed value. */
  public int readInt24() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(3);
    return ((data[position++] & 0xFF) << 24) >> 8
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF);
  }

  /** Reads the next three bytes as a signed value in little endian order. */
  public int readLittleEndianInt24() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(3);
    return (data[position++] & 0xFF)
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF) << 16;
  }

  /** Reads the next three bytes as an unsigned value in little endian order. */
  public int readLittleEndianUnsignedInt24() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(3);
    return (data[position++] & 0xFF)
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF) << 16;
  }

  /** Reads the next four bytes as an unsigned value. */
  public long readUnsignedInt() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(4);
    return (data[position++] & 0xFFL) << 24
        | (data[position++] & 0xFFL) << 16
        | (data[position++] & 0xFFL) << 8
        | (data[position++] & 0xFFL);
  }

  /** Reads the next four bytes as an unsigned value in little endian order. */
  public long readLittleEndianUnsignedInt() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(4);
    return (data[position++] & 0xFFL)
        | (data[position++] & 0xFFL) << 8
        | (data[position++] & 0xFFL) << 16
        | (data[position++] & 0xFFL) << 24;
  }

  /** Reads the next four bytes as a signed value */
  public int readInt() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(4);
    return (data[position++] & 0xFF) << 24
        | (data[position++] & 0xFF) << 16
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF);
  }

  /** Reads the next four bytes as a signed value in little endian order. */
  public int readLittleEndianInt() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(4);
    return (data[position++] & 0xFF)
        | (data[position++] & 0xFF) << 8
        | (data[position++] & 0xFF) << 16
        | (data[position++] & 0xFF) << 24;
  }

  /** Reads the next eight bytes as a signed value. */
  public long readLong() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(8);
    return (data[position++] & 0xFFL) << 56
        | (data[position++] & 0xFFL) << 48
        | (data[position++] & 0xFFL) << 40
        | (data[position++] & 0xFFL) << 32
        | (data[position++] & 0xFFL) << 24
        | (data[position++] & 0xFFL) << 16
        | (data[position++] & 0xFFL) << 8
        | (data[position++] & 0xFFL);
  }

  /** Reads the next eight bytes as a signed value in little endian order. */
  public long readLittleEndianLong() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(8);
    return (data[position++] & 0xFFL)
        | (data[position++] & 0xFFL) << 8
        | (data[position++] & 0xFFL) << 16
        | (data[position++] & 0xFFL) << 24
        | (data[position++] & 0xFFL) << 32
        | (data[position++] & 0xFFL) << 40
        | (data[position++] & 0xFFL) << 48
        | (data[position++] & 0xFFL) << 56;
  }

  /** Reads the next four bytes, returning the integer portion of the fixed point 16.16 integer. */
  public int readUnsignedFixedPoint1616() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(4);
    int result = (data[position++] & 0xFF) << 8 | (data[position++] & 0xFF);
    position += 2; // Skip the non-integer portion.
    return result;
  }

  /**
   * Reads a Synchsafe integer.
   *
   * <p>Synchsafe integers keep the highest bit of every byte zeroed. A 32 bit synchsafe integer can
   * store 28 bits of information.
   *
   * @return The parsed value.
   */
  public int readSynchSafeInt() {
    int b1 = readUnsignedByte();
    int b2 = readUnsignedByte();
    int b3 = readUnsignedByte();
    int b4 = readUnsignedByte();
    return (b1 << 21) | (b2 << 14) | (b3 << 7) | b4;
  }

  /**
   * Reads the next four bytes as an unsigned integer into an integer, if the top bit is a zero.
   *
   * @throws IllegalStateException Thrown if the top bit of the input data is set.
   */
  public int readUnsignedIntToInt() {
    int result = readInt();
    if (result < 0) {
      throw new IllegalStateException("Top bit not zero: " + result);
    }
    return result;
  }

  /**
   * Reads the next four bytes as a little endian unsigned integer into an integer, if the top bit
   * is a zero.
   *
   * @throws IllegalStateException Thrown if the top bit of the input data is set.
   */
  public int readLittleEndianUnsignedIntToInt() {
    int result = readLittleEndianInt();
    if (result < 0) {
      throw new IllegalStateException("Top bit not zero: " + result);
    }
    return result;
  }

  /**
   * Reads the next eight bytes as an unsigned long into a long, if the top bit is a zero.
   *
   * @throws IllegalStateException Thrown if the top bit of the input data is set.
   */
  public long readUnsignedLongToLong() {
    long result = readLong();
    if (result < 0) {
      throw new IllegalStateException("Top bit not zero: " + result);
    }
    return result;
  }

  /** Reads the next four bytes as a 32-bit floating point value. */
  public float readFloat() {
    return Float.intBitsToFloat(readInt());
  }

  /** Reads the next eight bytes as a 64-bit floating point value. */
  public double readDouble() {
    return Double.longBitsToDouble(readLong());
  }

  /**
   * Reads the next {@code length} bytes as UTF-8 characters.
   *
   * @param length The number of bytes to read.
   * @return The string encoded by the bytes.
   */
  public String readString(int length) {
    return readString(length, StandardCharsets.UTF_8);
  }

  /**
   * Reads the next {@code length} bytes as characters in the specified {@link Charset}.
   *
   * @param length The number of bytes to read.
   * @param charset The character set of the encoded characters.
   * @return The string encoded by the bytes in the specified character set.
   */
  public String readString(int length, Charset charset) {
    maybeAssertAtLeastBytesLeftForLegacyMethod(length);
    String result = new String(data, position, length, charset);
    position += length;
    return result;
  }

  /**
   * Reads the next {@code length} bytes as UTF-8 characters. A terminating NUL byte is discarded,
   * if present.
   *
   * @param length The number of bytes to read.
   * @return The string, not including any terminating NUL byte.
   */
  public String readNullTerminatedString(int length) {
    maybeAssertAtLeastBytesLeftForLegacyMethod(length);
    if (length == 0) {
      return "";
    }
    int stringLength = length;
    int lastIndex = position + length - 1;
    if (lastIndex < limit && data[lastIndex] == 0) {
      stringLength--;
    }
    String result = Util.fromUtf8Bytes(data, position, stringLength);
    position += length;
    return result;
  }

  /**
   * Reads up to the next NUL byte (or the limit) as UTF-8 characters.
   *
   * @return The string not including any terminating NUL byte, or null if the end of the data has
   *     already been reached.
   */
  @Nullable
  public String readNullTerminatedString() {
    return readDelimiterTerminatedString('\0');
  }

  /**
   * Reads up to the next delimiter byte (or the limit) as UTF-8 characters.
   *
   * @return The string not including any terminating delimiter byte, or null if the end of the data
   *     has already been reached.
   */
  @Nullable
  public String readDelimiterTerminatedString(char delimiter) {
    if (bytesLeft() == 0) {
      return null;
    }
    int stringLimit = position;
    while (stringLimit < limit && data[stringLimit] != delimiter) {
      stringLimit++;
    }
    String string = Util.fromUtf8Bytes(data, position, stringLimit - position);
    position = stringLimit;
    if (position < limit) {
      position++;
    }
    return string;
  }

  /**
   * Reads a line of text in UTF-8.
   *
   * <p>Equivalent to passing {@link StandardCharsets#UTF_8} to {@link #readLine(Charset)}.
   */
  @Nullable
  public String readLine() {
    return readLine(StandardCharsets.UTF_8);
  }

  /**
   * Reads a line of text in {@code charset}.
   *
   * <p>A line is considered to be terminated by any one of a carriage return ('\r'), a line feed
   * ('\n'), or a carriage return followed immediately by a line feed ('\r\n'). This method discards
   * leading UTF byte order marks (BOM), if present.
   *
   * <p>The {@linkplain #getPosition() position} is advanced to start of the next line (i.e. any
   * line terminators are skipped).
   *
   * @param charset The charset used to interpret the bytes as a {@link String}.
   * @return The line not including any line-termination characters, or null if the end of the data
   *     has already been reached.
   * @throws IllegalArgumentException if charset is not supported. Only US_ASCII, UTF-8, UTF-16,
   *     UTF-16BE, and UTF-16LE are supported.
   */
  @Nullable
  public String readLine(Charset charset) {
    checkArgument(
        SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset);
    if (bytesLeft() == 0) {
      return null;
    }
    if (!charset.equals(StandardCharsets.US_ASCII)) {
      Charset unused = readUtfCharsetFromBom(); // Skip BOM if present
    }
    int lineLimit = findNextLineTerminator(charset);
    String line = readString(lineLimit - position, charset);
    if (position == limit) {
      return line;
    }
    skipLineTerminator(charset);
    return line;
  }

  /**
   * Reads a long value encoded by UTF-8 encoding
   *
   * @throws NumberFormatException if there is a problem with decoding
   * @return Decoded long value
   */
  public long readUtf8EncodedLong() {
    maybeAssertAtLeastBytesLeftForLegacyMethod(1);
    int length = 0;
    long value = data[position];
    // find the high most 0 bit
    for (int j = 7; j >= 0; j--) {
      if ((value & (1 << j)) == 0) {
        if (j < 6) {
          value &= (1 << j) - 1;
          length = 7 - j;
        } else if (j == 7) {
          length = 1;
        }
        break;
      }
    }
    if (length == 0) {
      throw new NumberFormatException("Invalid UTF-8 sequence first byte: " + value);
    }
    maybeAssertAtLeastBytesLeftForLegacyMethod(length);
    for (int i = 1; i < length; i++) {
      int x = data[position + i];
      if ((x & 0xC0) != 0x80) { // if the high most 0 bit not 7th
        throw new NumberFormatException("Invalid UTF-8 sequence continuation byte: " + value);
      }
      value = (value << 6) | (x & 0x3F);
    }
    position += length;
    return value;
  }

  /**
   * Reads an unsigned variable-length <a href="https://en.wikipedia.org/wiki/LEB128">LEB128</a>
   * value into a long.
   *
   * @throws IllegalStateException if the byte to be read is over the limit of the parsable byte
   *     array
   * @return long value
   */
  public long readUnsignedLeb128ToLong() {
    long value = 0;
    // At most, 63 bits of unsigned data can be stored in a long, which corresponds to 63/7=9 bytes
    // in LEB128.
    for (int i = 0; i < 9; i++) {
      if (this.position == limit) {
        throw new IllegalStateException("Attempting to read a byte over the limit.");
      }
      long currentByte = this.readUnsignedByte();
      value |= (currentByte & 0x7F) << (i * 7);
      if ((currentByte & 0x80) == 0) {
        break;
      }
    }
    return value;
  }

  /**
   * Reads an unsigned variable-length <a href="https://en.wikipedia.org/wiki/LEB128">LEB128</a>
   * value into an int.
   *
   * @throws IllegalArgumentException if the read value is greater than {@link Integer#MAX_VALUE} or
   *     less than {@link Integer#MIN_VALUE}
   * @return integer value
   */
  public int readUnsignedLeb128ToInt() {
    return Ints.checkedCast(readUnsignedLeb128ToLong());
  }

  /** Skips a variable-length <a href="https://en.wikipedia.org/wiki/LEB128">LEB128</a> value. */
  public void skipLeb128() {
    while ((readUnsignedByte() & 0x80) != 0) {}
  }

  /**
   * Reads a UTF byte order mark (BOM) and returns the UTF {@link Charset} it represents. Returns
   * {@code null} without advancing {@link #getPosition() position} if no BOM is found.
   */
  @Nullable
  public Charset readUtfCharsetFromBom() {
    if (bytesLeft() >= 3
        && data[position] == (byte) 0xEF
        && data[position + 1] == (byte) 0xBB
        && data[position + 2] == (byte) 0xBF) {
      position += 3;
      return StandardCharsets.UTF_8;
    } else if (bytesLeft() >= 2) {
      if (data[position] == (byte) 0xFE && data[position + 1] == (byte) 0xFF) {
        position += 2;
        return StandardCharsets.UTF_16BE;
      } else if (data[position] == (byte) 0xFF && data[position + 1] == (byte) 0xFE) {
        position += 2;
        return StandardCharsets.UTF_16LE;
      }
    }
    return null;
  }

  /**
   * Sets whether all read/peek methods should enforce that {@link #getPosition()} never exceeds
   * {@link #limit()}.
   *
   * <p>Setting this to {@code true} in tests can help catch cases of accidentally reading beyond
   * {@link #limit()} but still within the bounds of the underlying {@link #getData()}.
   *
   * <p>Some (newer) methods will always enforce the invariant, even when this is set to {@code
   * false}.
   *
   * <p>Defaults to false (this may change in a later release).
   */
  @VisibleForTesting
  public static void setShouldEnforceLimitOnLegacyMethods(boolean enforceLimit) {
    ParsableByteArray.shouldEnforceLimitOnLegacyMethods.set(enforceLimit);
  }

  /**
   * Returns the index of the next occurrence of '\n' or '\r', or {@link #limit} if none is found.
   */
  private int findNextLineTerminator(Charset charset) {
    int stride;
    if (charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)) {
      stride = 1;
    } else if (charset.equals(StandardCharsets.UTF_16)
        || charset.equals(StandardCharsets.UTF_16LE)
        || charset.equals(StandardCharsets.UTF_16BE)) {
      stride = 2;
    } else {
      throw new IllegalArgumentException("Unsupported charset: " + charset);
    }
    for (int i = position; i < limit - (stride - 1); i += stride) {
      if ((charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII))
          && Util.isLinebreak(data[i])) {
        return i;
      } else if ((charset.equals(StandardCharsets.UTF_16)
              || charset.equals(StandardCharsets.UTF_16BE))
          && data[i] == 0x00
          && Util.isLinebreak(data[i + 1])) {
        return i;
      } else if (charset.equals(StandardCharsets.UTF_16LE)
          && data[i + 1] == 0x00
          && Util.isLinebreak(data[i])) {
        return i;
      }
    }
    return limit;
  }

  private void skipLineTerminator(Charset charset) {
    if (readCharacterIfInList(charset, CR_AND_LF) == '\r') {
      char unused = readCharacterIfInList(charset, LF);
    }
  }

  /**
   * Peeks at the character at {@link #position} (as decoded by {@code charset}), returns it and
   * advances {@link #position} past it if it's in {@code chars}, otherwise returns {@code 0}
   * without advancing {@link #position}. Returns {@code 0} if {@link #bytesLeft()} doesn't allow
   * reading a whole character in {@code charset}.
   *
   * <p>Only supports characters in {@code chars} that are in the Basic Multilingual Plane (occupy a
   * single char).
   */
  private char readCharacterIfInList(Charset charset, char[] chars) {
    if (bytesLeft() < getSmallestCodeUnitSize(charset)) {
      return 0;
    }
    int codePointAndSize = peekCodePointAndSize(charset);
    if (codePointAndSize == 0) {
      return 0;
    }

    int codePoint = UnsignedInts.checkedCast(codePointAndSize >>> 8);
    if (Character.isSupplementaryCodePoint(codePoint)) {
      return 0;
    }
    char c = Chars.checkedCast(codePoint);
    if (Chars.contains(chars, c)) {
      position += Ints.checkedCast(codePointAndSize & 0xFF);
      return c;
    } else {
      return 0;
    }
  }

  /**
   * Peeks at the code unit at {@link #position} (as decoded by {@code charset}), and the number of
   * bytes it occupies within {@link #data}.
   *
   * <p>See {@link #peekCodePoint(Charset)} for detailed per-charset behaviour & edge cases.
   *
   * @return The code point in the upper 24 bits, and the size in bytes in the lower 8 bits. Or zero
   *     if no valid code unit starts at {@link #position} and fits within {@link #bytesLeft()}.
   * @throws IndexOutOfBoundsException if {@link #bytesLeft()} doesn't allow reading the smallest
   *     code unit in {@code charset} (1 byte for ASCII and UTF-8, 2 bytes for UTF-16).
   * @throws IllegalArgumentException if charset is not supported. Only US_ASCII, UTF-8, UTF-16,
   *     UTF-16BE, and UTF-16LE are supported.
   */
  private int peekCodePointAndSize(Charset charset) {
    checkArgument(
        SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset);
    if (bytesLeft() < getSmallestCodeUnitSize(charset)) {
      throw new IndexOutOfBoundsException("position=" + position + ", limit=" + limit);
    }
    int codePoint;
    byte codePointSize;
    if (charset.equals(StandardCharsets.US_ASCII)) {
      if ((data[position] & 0x80) != 0) {
        return 0;
      }
      codePoint = UnsignedBytes.toInt(data[position]);
      codePointSize = 1;
    } else if (charset.equals(StandardCharsets.UTF_8)) {
      codePointSize = peekUtf8CodeUnitSize();
      switch (codePointSize) {
        case 1:
          codePoint = UnsignedBytes.toInt(data[position]);
          break;
        case 2:
          codePoint = decodeUtf8CodeUnit(0, 0, data[position], data[position + 1]);
          break;
        case 3:
          int firstByteWithoutStartCode = data[position] & 0xF;
          codePoint =
              decodeUtf8CodeUnit(
                  0, firstByteWithoutStartCode, data[position + 1], data[position + 2]);
          break;
        case 4:
          codePoint =
              decodeUtf8CodeUnit(
                  data[position], data[position + 1], data[position + 2], data[position + 3]);
          break;
        case 0:
        default:
          return 0;
      }
    } else {
      // UTF-16
      ByteOrder byteOrder = charset.equals(StandardCharsets.UTF_16LE) ? LITTLE_ENDIAN : BIG_ENDIAN;
      char c = peekChar(byteOrder, /* offset= */ 0);
      if (Character.isHighSurrogate(c) && bytesLeft() >= 4) {
        char lowSurrogate = peekChar(byteOrder, /* offset= */ 2);
        codePoint = Character.toCodePoint(c, lowSurrogate);
        codePointSize = 4;
      } else {
        // This is either a BMP code point, an unpaired surrogate, or position is in the middle of
        // a matching surrogate pair.
        codePoint = c;
        codePointSize = 2;
      }
    }
    return (codePoint << 8) | codePointSize;
  }

  private static int getSmallestCodeUnitSize(Charset charset) {
    checkArgument(
        SUPPORTED_CHARSETS_FOR_READLINE.contains(charset), "Unsupported charset: %s", charset);
    return charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)
        ? 1
        : 2;
  }

  /**
   * Returns the size (in bytes) of the UTF-8 code unit starting at {@link #position}. Returns zero
   * if no full UTF-8 code unit seems to start at {@link #position}.
   */
  private byte peekUtf8CodeUnitSize() {
    if ((data[position] & 0x80) == 0) {
      return 1;
    } else if ((data[position] & 0xE0) == 0xC0
        && bytesLeft() >= 2
        && isUtf8ContinuationByte(data[position + 1])) {
      return 2;
    } else if ((data[position] & 0xF0) == 0xE0
        && bytesLeft() >= 3
        && isUtf8ContinuationByte(data[position + 1])
        && isUtf8ContinuationByte(data[position + 2])) {
      return 3;
    } else if ((data[position] & 0xF8) == 0xF0
        && bytesLeft() >= 4
        && isUtf8ContinuationByte(data[position + 1])
        && isUtf8ContinuationByte(data[position + 2])
        && isUtf8ContinuationByte(data[position + 3])) {
      return 4;
    } else {
      // We found a pattern that doesn't seem to be valid UTF-8.
      return 0;
    }
  }

  /**
   * Enforces that {@link #bytesLeft()} is at least {@code bytesNeeded} if {@link
   * #shouldEnforceLimitOnLegacyMethods} is set to {@code true}.
   *
   * <p>This should only be called from methods that previously didn't enforce the limit. All new
   * methods added to this class should unconditionally enforce the limit.
   */
  private void maybeAssertAtLeastBytesLeftForLegacyMethod(int bytesNeeded) {
    if (shouldEnforceLimitOnLegacyMethods.get()) {
      if (bytesLeft() < bytesNeeded) {
        throw new IndexOutOfBoundsException(
            "bytesNeeded= " + bytesNeeded + ", bytesLeft=" + bytesLeft());
      }
    }
  }

  private static boolean isUtf8ContinuationByte(byte b) {
    return (b & 0xC0) == 0x80;
  }

  private static int decodeUtf8CodeUnit(int b1, int b2, int b3, int b4) {
    return Ints.fromBytes(
        (byte) 0,
        UnsignedBytes.checkedCast(((b1 & 0x7) << 2) | (b2 & 0b0011_0000) >> 4),
        UnsignedBytes.checkedCast(((byte) b2 & 0xF) << 4 | ((byte) b3 & 0b0011_1100) >> 2),
        UnsignedBytes.checkedCast(((byte) b3 & 0x3) << 6 | ((byte) b4 & 0x3F)));
  }
}
