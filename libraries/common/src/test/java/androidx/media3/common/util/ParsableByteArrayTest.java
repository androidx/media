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

import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ParsableByteArray}. */
@RunWith(AndroidJUnit4.class)
public final class ParsableByteArrayTest {

  private static final byte[] TEST_DATA =
      new byte[] {0x0F, (byte) 0xFF, (byte) 0x42, (byte) 0x0F, 0x00, 0x00, 0x00, 0x00};

  private static ParsableByteArray getTestDataArray() {
    ParsableByteArray testArray = new ParsableByteArray(TEST_DATA.length);
    System.arraycopy(TEST_DATA, 0, testArray.getData(), 0, TEST_DATA.length);
    return testArray;
  }

  @Test
  public void ensureCapacity_doesntReallocateNeedlesslyAndPreservesPositionAndLimit() {
    ParsableByteArray array = getTestDataArray();
    byte[] dataBefore = array.getData();
    byte[] copyOfDataBefore = dataBefore.clone();

    array.setPosition(3);
    array.setLimit(4);
    array.ensureCapacity(array.capacity() - 1);

    assertThat(array.getData()).isSameInstanceAs(dataBefore);
    assertThat(array.getData()).isEqualTo(copyOfDataBefore);
    assertThat(array.getPosition()).isEqualTo(3);
    assertThat(array.limit()).isEqualTo(4);
  }

  @Test
  public void ensureCapacity_preservesDataPositionAndLimitWhenReallocating() {
    ParsableByteArray array = getTestDataArray();
    byte[] copyOfDataBefore = array.getData().clone();

    array.setPosition(3);
    array.setLimit(4);
    array.ensureCapacity(array.capacity() + 1);

    assertThat(array.getData()).isEqualTo(Bytes.concat(copyOfDataBefore, new byte[] {0}));
    assertThat(array.getPosition()).isEqualTo(3);
    assertThat(array.limit()).isEqualTo(4);
  }

  @Test
  public void bytesLeft() {
    ParsableByteArray array = getTestDataArray();
    assertThat(array.bytesLeft()).isEqualTo(TEST_DATA.length);

    array.setPosition(1);
    array.setLimit(2);
    assertThat(array.bytesLeft()).isEqualTo(1);
  }

  @Test
  public void bytesLeft_positionExceedsLimit_returnsZero() {
    ParsableByteArray array = getTestDataArray();
    array.setLimit(1);
    // readInt advances position without checking limit (see b/147657250)
    int unused = array.readInt();

    assertThat(array.bytesLeft()).isEqualTo(0);
  }

  @Test
  public void readShort() {
    testReadShort((short) -1);
    testReadShort((short) 0);
    testReadShort((short) 1);
    testReadShort(Short.MIN_VALUE);
    testReadShort(Short.MAX_VALUE);
  }

  private static void testReadShort(short testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(ByteBuffer.allocate(4).putShort(testValue).array());
    int readValue = testArray.readShort();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(2);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-2);
    readValue = testArray.readShort();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(2);
  }

  @Test
  public void readInt() {
    testReadInt(0);
    testReadInt(1);
    testReadInt(-1);
    testReadInt(Integer.MIN_VALUE);
    testReadInt(Integer.MAX_VALUE);
  }

  private static void testReadInt(int testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(ByteBuffer.allocate(4).putInt(testValue).array());
    int readValue = testArray.readInt();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(4);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-4);
    readValue = testArray.readInt();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readUnsignedInt() {
    testReadUnsignedInt(0);
    testReadUnsignedInt(1);
    testReadUnsignedInt(Integer.MAX_VALUE);
    testReadUnsignedInt(Integer.MAX_VALUE + 1L);
    testReadUnsignedInt(0xFFFFFFFFL);
  }

  private static void testReadUnsignedInt(long testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(
            Arrays.copyOfRange(ByteBuffer.allocate(8).putLong(testValue).array(), 4, 8));
    long readValue = testArray.readUnsignedInt();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(4);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-4);
    readValue = testArray.readUnsignedInt();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readUnsignedIntToInt() {
    testReadUnsignedIntToInt(0);
    testReadUnsignedIntToInt(1);
    testReadUnsignedIntToInt(Integer.MAX_VALUE);
    try {
      testReadUnsignedIntToInt(-1);
      fail();
    } catch (IllegalStateException e) {
      // Expected.
    }
    try {
      testReadUnsignedIntToInt(Integer.MIN_VALUE);
      fail();
    } catch (IllegalStateException e) {
      // Expected.
    }
  }

  private static void testReadUnsignedIntToInt(int testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(ByteBuffer.allocate(4).putInt(testValue).array());
    int readValue = testArray.readUnsignedIntToInt();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(4);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-4);
    readValue = testArray.readUnsignedIntToInt();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readUnsignedLongToLong() {
    testReadUnsignedLongToLong(0);
    testReadUnsignedLongToLong(1);
    testReadUnsignedLongToLong(Long.MAX_VALUE);
    try {
      testReadUnsignedLongToLong(-1);
      fail();
    } catch (IllegalStateException e) {
      // Expected.
    }
    try {
      testReadUnsignedLongToLong(Long.MIN_VALUE);
      fail();
    } catch (IllegalStateException e) {
      // Expected.
    }
  }

  private static void testReadUnsignedLongToLong(long testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(ByteBuffer.allocate(8).putLong(testValue).array());
    long readValue = testArray.readUnsignedLongToLong();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(8);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-8);
    readValue = testArray.readUnsignedLongToLong();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(8);
  }

  @Test
  public void readLong() {
    testReadLong(0);
    testReadLong(1);
    testReadLong(-1);
    testReadLong(Long.MIN_VALUE);
    testReadLong(Long.MAX_VALUE);
  }

  private static void testReadLong(long testValue) {
    ParsableByteArray testArray =
        new ParsableByteArray(ByteBuffer.allocate(8).putLong(testValue).array());
    long readValue = testArray.readLong();

    // Assert that the value we read was the value we wrote.
    assertThat(readValue).isEqualTo(testValue);
    // And that the position advanced as expected.
    assertThat(testArray.getPosition()).isEqualTo(8);

    // And that skipping back and reading gives the same results.
    testArray.skipBytes(-8);
    readValue = testArray.readLong();
    assertThat(readValue).isEqualTo(testValue);
    assertThat(testArray.getPosition()).isEqualTo(8);
  }

  @Test
  public void readingMovesPosition() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // Given an array at the start
    assertThat(parsableByteArray.getPosition()).isEqualTo(0);
    // When reading an integer, the position advances
    parsableByteArray.readUnsignedInt();
    assertThat(parsableByteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void outOfBoundsThrows() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // Given an array at the end
    parsableByteArray.readUnsignedLongToLong();
    assertThat(parsableByteArray.getPosition()).isEqualTo(TEST_DATA.length);
    // Then reading more data throws.
    try {
      parsableByteArray.readUnsignedInt();
      fail();
    } catch (Exception e) {
      // Expected.
    }
  }

  @Test
  public void modificationsAffectParsableArray() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // When modifying the wrapped byte array
    byte[] data = parsableByteArray.getData();
    long readValue = parsableByteArray.readUnsignedInt();
    data[0] = (byte) (TEST_DATA[0] + 1);
    parsableByteArray.setPosition(0);
    // Then the parsed value changes.
    assertThat(parsableByteArray.readUnsignedInt()).isNotEqualTo(readValue);
  }

  @Test
  public void readingUnsignedLongWithMsbSetThrows() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // Given an array with the most-significant bit set on the top byte
    byte[] data = parsableByteArray.getData();
    data[0] = (byte) 0x80;
    // Then reading an unsigned long throws.
    try {
      parsableByteArray.readUnsignedLongToLong();
      fail();
    } catch (Exception e) {
      // Expected.
    }
  }

  @Test
  public void readUnsignedFixedPoint1616() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // When reading the integer part of a 16.16 fixed point value
    int value = parsableByteArray.readUnsignedFixedPoint1616();
    // Then the read value is equal to the array elements interpreted as a short.
    assertThat(value).isEqualTo((0xFF & TEST_DATA[0]) << 8 | (TEST_DATA[1] & 0xFF));
    assertThat(parsableByteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readingBytesReturnsCopy() {
    ParsableByteArray parsableByteArray = getTestDataArray();

    // When reading all the bytes back
    int length = parsableByteArray.limit();
    assertThat(length).isEqualTo(TEST_DATA.length);
    byte[] copy = new byte[length];
    parsableByteArray.readBytes(copy, 0, length);
    // Then the array elements are the same.
    assertThat(copy).isEqualTo(parsableByteArray.getData());
  }

  @Test
  public void readLittleEndianLong() {
    ParsableByteArray byteArray =
        new ParsableByteArray(new byte[] {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xFF});

    assertThat(byteArray.readLittleEndianLong()).isEqualTo(0xFF00000000000001L);
    assertThat(byteArray.getPosition()).isEqualTo(8);
  }

  @Test
  public void readLittleEndianUnsignedInt() {
    ParsableByteArray byteArray = new ParsableByteArray(new byte[] {0x10, 0x00, 0x00, (byte) 0xFF});

    assertThat(byteArray.readLittleEndianUnsignedInt()).isEqualTo(0xFF000010L);
    assertThat(byteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readLittleEndianInt() {
    ParsableByteArray byteArray = new ParsableByteArray(new byte[] {0x01, 0x00, 0x00, (byte) 0xFF});

    assertThat(byteArray.readLittleEndianInt()).isEqualTo(0xFF000001);
    assertThat(byteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readLittleEndianUnsignedInt24() {
    byte[] data = {0x01, 0x02, (byte) 0xFF};
    ParsableByteArray byteArray = new ParsableByteArray(data);

    assertThat(byteArray.readLittleEndianUnsignedInt24()).isEqualTo(0xFF0201);
    assertThat(byteArray.getPosition()).isEqualTo(3);
  }

  @Test
  public void readInt24Positive() {
    byte[] data = {0x01, 0x02, (byte) 0xFF};
    ParsableByteArray byteArray = new ParsableByteArray(data);

    assertThat(byteArray.readInt24()).isEqualTo(0x0102FF);
    assertThat(byteArray.getPosition()).isEqualTo(3);
  }

  @Test
  public void readInt24Negative() {
    byte[] data = {(byte) 0xFF, 0x02, (byte) 0x01};
    ParsableByteArray byteArray = new ParsableByteArray(data);

    assertThat(byteArray.readInt24()).isEqualTo(0xFFFF0201);
    assertThat(byteArray.getPosition()).isEqualTo(3);
  }

  @Test
  public void readLittleEndianUnsignedShort() {
    ParsableByteArray byteArray =
        new ParsableByteArray(new byte[] {0x01, (byte) 0xFF, 0x02, (byte) 0xFF});

    assertThat(byteArray.readLittleEndianUnsignedShort()).isEqualTo(0xFF01);
    assertThat(byteArray.getPosition()).isEqualTo(2);
    assertThat(byteArray.readLittleEndianUnsignedShort()).isEqualTo(0xFF02);
    assertThat(byteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readLittleEndianShort() {
    ParsableByteArray byteArray =
        new ParsableByteArray(new byte[] {0x01, (byte) 0xFF, 0x02, (byte) 0xFF});

    assertThat(byteArray.readLittleEndianShort()).isEqualTo((short) 0xFF01);
    assertThat(byteArray.getPosition()).isEqualTo(2);
    assertThat(byteArray.readLittleEndianShort()).isEqualTo((short) 0xFF02);
    assertThat(byteArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readString() {
    byte[] data = {
      (byte) 0xC3,
      (byte) 0xA4,
      (byte) 0x20,
      (byte) 0xC3,
      (byte) 0xB6,
      (byte) 0x20,
      (byte) 0xC2,
      (byte) 0xAE,
      (byte) 0x20,
      (byte) 0xCF,
      (byte) 0x80,
      (byte) 0x20,
      (byte) 0xE2,
      (byte) 0x88,
      (byte) 0x9A,
      (byte) 0x20,
      (byte) 0xC2,
      (byte) 0xB1,
      (byte) 0x20,
      (byte) 0xE8,
      (byte) 0xB0,
      (byte) 0xA2,
      (byte) 0x20,
    };
    ParsableByteArray byteArray = new ParsableByteArray(data);

    assertThat(byteArray.readString(data.length)).isEqualTo("ä ö ® π √ ± 谢 ");
    assertThat(byteArray.getPosition()).isEqualTo(data.length);
  }

  @Test
  public void readAsciiString() {
    byte[] data = new byte[] {'t', 'e', 's', 't'};
    ParsableByteArray testArray = new ParsableByteArray(data);

    assertThat(testArray.readString(data.length, US_ASCII)).isEqualTo("test");
    assertThat(testArray.getPosition()).isEqualTo(data.length);
  }

  @Test
  public void readStringOutOfBoundsDoesNotMovePosition() {
    byte[] data = {(byte) 0xC3, (byte) 0xA4, (byte) 0x20};
    ParsableByteArray byteArray = new ParsableByteArray(data);

    try {
      byteArray.readString(data.length + 1);
      fail();
    } catch (StringIndexOutOfBoundsException e) {
      assertThat(byteArray.getPosition()).isEqualTo(0);
    }
  }

  @Test
  public void readEmptyString() {
    byte[] bytes = new byte[0];
    ParsableByteArray parser = new ParsableByteArray(bytes);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readNullTerminatedStringWithLengths_readLengthsMatchNullPositions() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};

    ParsableByteArray parser = new ParsableByteArray(bytes);
    assertThat(parser.readNullTerminatedString(4)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readNullTerminatedString(4)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedStringWithLengths_readLengthsDontMatchNullPositions() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readNullTerminatedString(2)).isEqualTo("fo");
    assertThat(parser.getPosition()).isEqualTo(2);
    assertThat(parser.readNullTerminatedString(2)).isEqualTo("o");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readNullTerminatedString(3)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(7);
    assertThat(parser.readNullTerminatedString(1)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedStringWithLengths_limitAtNull() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 4);

    assertThat(parser.readNullTerminatedString(4)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedStringWithLengths_limitBeforeNull() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 3);

    assertThat(parser.readNullTerminatedString(3)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(3);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedString() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readNullTerminatedString()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readNullTerminatedString()).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedString_withLimitAtNull() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 4);

    assertThat(parser.readNullTerminatedString()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedString_withLimitBeforeNull() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r', 0};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 3);

    assertThat(parser.readNullTerminatedString()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(3);
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readNullTerminatedStringWithoutEndingNull() {
    byte[] bytes = new byte[] {'f', 'o', 'o', 0, 'b', 'a', 'r'};
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readNullTerminatedString()).isEqualTo("foo");
    assertThat(parser.readNullTerminatedString()).isEqualTo("bar");
    assertThat(parser.readNullTerminatedString()).isNull();
  }

  @Test
  public void readDelimiterTerminatedString() {
    byte[] bytes = new byte[] {'f', 'o', 'o', '*', 'b', 'a', 'r', '*'};
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readDelimiterTerminatedString('*')).isNull();
  }

  @Test
  public void readDelimiterTerminatedString_limitAtDelimiter() {
    byte[] bytes = new byte[] {'f', 'o', 'o', '*', 'b', 'a', 'r', '*'};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 4);

    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readDelimiterTerminatedString('*')).isNull();
  }

  @Test
  public void readDelimiterTerminatedString_limitBeforeDelimiter() {
    byte[] bytes = new byte[] {'f', 'o', 'o', '*', 'b', 'a', 'r', '*'};
    ParsableByteArray parser = new ParsableByteArray(bytes, /* limit= */ 3);

    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(3);
    assertThat(parser.readDelimiterTerminatedString('*')).isNull();
  }

  @Test
  public void readDelimiterTerminatedStringW_noDelimiter() {
    byte[] bytes = new byte[] {'f', 'o', 'o', '*', 'b', 'a', 'r'};
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("foo");
    assertThat(parser.readDelimiterTerminatedString('*')).isEqualTo("bar");
    assertThat(parser.readDelimiterTerminatedString('*')).isNull();
  }

  @Test
  public void readSingleLineWithoutEndingTrail_ascii() {
    byte[] bytes = "foo".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(3);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readSingleLineWithEndingLf_ascii() {
    byte[] bytes = "foo\n".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readSingleLineWithEndingCr_ascii() {
    byte[] bytes = "foo\r".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readTwoLinesWithCrFollowedByLf_ascii() {
    byte[] bytes = "foo\r\nbar".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(5);
    assertThat(parser.readLine(US_ASCII)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readThreeLinesWithEmptyLine_ascii() {
    byte[] bytes = "foo\r\n\rbar".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(5);
    assertThat(parser.readLine(US_ASCII)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine(US_ASCII)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(9);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readFourLinesWithLfFollowedByCr_ascii() {
    byte[] bytes = "foo\n\r\rbar\r\n".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(US_ASCII)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine(US_ASCII)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(5);
    assertThat(parser.readLine(US_ASCII)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine(US_ASCII)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(11);
    assertThat(parser.readLine(US_ASCII)).isNull();
  }

  @Test
  public void readSingleLineWithoutEndingTrail_utf8() {
    byte[] bytes = "foo".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(3);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readSingleLineWithEndingLf_utf8() {
    byte[] bytes = "foo\n".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readSingleLineWithEndingCr_utf8() {
    byte[] bytes = "foo\r".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readTwoLinesWithCr_utf8() {
    byte[] bytes = "foo\rbar".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine()).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(7);
    assertThat(parser.readLine()).isNull();
  }

  // https://github.com/androidx/media/issues/2167
  @Test
  public void readTwoLinesWithCrAndWideChar_utf8() {
    byte[] bytes = "foo\r\uD83D\uDE1B".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine()).isEqualTo("\uD83D\uDE1B");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readTwoLinesWithCrFollowedByLf_utf8() {
    byte[] bytes = "foo\r\nbar".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(5);
    assertThat(parser.readLine()).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readThreeLinesWithEmptyLineAndLeadingBom_utf8() {
    byte[] bytes = Bytes.concat(createByteArray(0xEF, 0xBB, 0xBF), "foo\r\n\rbar".getBytes(UTF_8));
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine()).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(9);
    assertThat(parser.readLine()).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readFourLinesWithLfFollowedByCr_utf8() {
    byte[] bytes = "foo\n\r\rbar\r\n".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine()).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(4);
    assertThat(parser.readLine()).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(5);
    assertThat(parser.readLine()).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine()).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(11);
    assertThat(parser.readLine()).isNull();
  }

  @Test
  public void readSingleLineWithoutEndingTrail_utf16() {
    // Use UTF_16BE because we don't want the leading BOM that's added by getBytes(UTF_16). We
    // explicitly test with a BOM elsewhere.
    byte[] bytes = "foo".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readSingleLineWithEndingLf_utf16() {
    // Use UTF_16BE because we don't want the leading BOM that's added by getBytes(UTF_16). We
    // explicitly test with a BOM elsewhere.
    byte[] bytes = "foo\n".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readSingleLineWithEndingCr_utf16() {
    // Use UTF_16BE because we don't want the leading BOM that's added by getBytes(UTF_16). We
    // explicitly test with a BOM elsewhere.
    byte[] bytes = "foo\r".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readTwoLinesWithCrFollowedByLf_utf16() {
    // Use UTF_16BE because we don't want the leading BOM that's added by getBytes(UTF_16). We
    // explicitly test with a BOM elsewhere.
    byte[] bytes = "foo\r\nbar".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(16);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readThreeLinesWithEmptyLineAndLeadingBom_utf16() {
    // getBytes(UTF_16) always adds the leading BOM.
    byte[] bytes = "foo\r\n\rbar".getBytes(UTF_16);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(14);
    assertThat(parser.readLine(UTF_16)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(20);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readFourLinesWithLfFollowedByCr_utf16() {
    // Use UTF_16BE because we don't want the leading BOM that's added by getBytes(UTF_16). We
    // explicitly test with a BOM elsewhere.
    byte[] bytes = "foo\n\r\rbar\r\n".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(22);
    assertThat(parser.readLine(UTF_16)).isNull();
  }

  @Test
  public void readSingleLineWithoutEndingTrail_utf16be() {
    byte[] bytes = "foo".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readSingleLineWithEndingLf_utf16be() {
    byte[] bytes = "foo\n".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readSingleLineWithEndingCr_utf16be() {
    byte[] bytes = "foo\r".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readTwoLinesWithCrFollowedByLf_utf16be() {
    byte[] bytes = "foo\r\nbar".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16BE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(16);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readThreeLinesWithEmptyLineAndLeadingBom_utf16be() {
    byte[] bytes = Bytes.concat(createByteArray(0xFE, 0xFF), "foo\r\n\rbar".getBytes(UTF_16BE));
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16BE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(14);
    assertThat(parser.readLine(UTF_16BE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(20);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readFourLinesWithLfFollowedByCr_utf16be() {
    byte[] bytes = "foo\n\r\rbar\r\n".getBytes(UTF_16BE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16BE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16BE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16BE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16BE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(22);
    assertThat(parser.readLine(UTF_16BE)).isNull();
  }

  @Test
  public void readSingleLineWithoutEndingTrail_utf16le() {
    byte[] bytes = "foo".getBytes(UTF_16LE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(6);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void readSingleLineWithEndingLf_utf16le() {
    byte[] bytes = "foo\n".getBytes(UTF_16LE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void readSingleLineWithEndingCr_utf16le() {
    byte[] bytes = "foo\r".getBytes(UTF_16LE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void readTwoLinesWithCrFollowedByLf_utf16le() {
    byte[] bytes = "foo\r\nbar".getBytes(UTF_16LE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16LE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(16);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void readThreeLinesWithEmptyLineAndLeadingBom_utf16le() {
    byte[] bytes = Bytes.concat(createByteArray(0xFF, 0xFE), "foo\r\n\rbar".getBytes(UTF_16LE));
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16LE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(14);
    assertThat(parser.readLine(UTF_16LE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(20);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void readFourLinesWithLfFollowedByCr_utf16le() {
    byte[] bytes = "foo\n\r\rbar\r\n".getBytes(UTF_16LE);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.readLine(UTF_16LE)).isEqualTo("foo");
    assertThat(parser.getPosition()).isEqualTo(8);
    assertThat(parser.readLine(UTF_16LE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(10);
    assertThat(parser.readLine(UTF_16LE)).isEmpty();
    assertThat(parser.getPosition()).isEqualTo(12);
    assertThat(parser.readLine(UTF_16LE)).isEqualTo("bar");
    assertThat(parser.getPosition()).isEqualTo(22);
    assertThat(parser.readLine(UTF_16LE)).isNull();
  }

  @Test
  public void peekCodePoint_ascii() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(US_ASCII));

    assertThat(parser.peekCodePoint(US_ASCII)).isEqualTo((int) 'f');
  }

  @Test
  public void peekCodePoint_ascii_invalid() {
    // Choose é from ISO 8859-1 which is not valid 7-bit ASCII (since it has a high MSB).
    ParsableByteArray parser = new ParsableByteArray(TestUtil.createByteArray(0xE9));

    assertThat(parser.peekCodePoint(US_ASCII)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);
  }

  @Test
  public void peekCodePoint_ascii_atLimit_throwsException() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(US_ASCII), /* limit= */ 2);
    parser.setPosition(2);

    IndexOutOfBoundsException e =
        assertThrows(IndexOutOfBoundsException.class, () -> parser.peekCodePoint(US_ASCII));
    assertThat(e).hasMessageThat().contains("position=2");
    assertThat(e).hasMessageThat().contains("limit=2");
  }

  @Test
  public void peekCodePoint_utf8() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_8));

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo((int) 'f');
  }

  @Test
  public void peekCodePoint_utf8_twoByteCharacter() {
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_8));

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo((int) 'é');
  }

  @Test
  public void peekCodePoint_utf8_twoByteCharacter_misaligned() {
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_8));
    parser.setPosition(1);

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);
  }

  @Test
  public void peekCodePoint_utf8_threeByteCharacter() {
    ParsableByteArray parser = new ParsableByteArray("ऊ".getBytes(UTF_8));

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo((int) 'ऊ');
  }

  @Test
  public void peekCodePoint_utf8_threeByteCharacter_misaligned() {
    ParsableByteArray parser = new ParsableByteArray("ऊ".getBytes(UTF_8));
    parser.setPosition(1);

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);
  }

  @Test
  public void peekCodePoint_utf8_fourByteCharacter() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_8));

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(Character.codePointAt("\uD83D\uDE1B", 0));
  }

  @Test
  public void peekCodePoint_utf8_fourByteCharacter_misaligned() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_8));
    parser.setPosition(1);

    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);
  }

  @Test
  public void peekCodePoint_utf8_atLimit_throwsException() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_8), /* limit= */ 2);
    parser.setPosition(2);

    IndexOutOfBoundsException e =
        assertThrows(IndexOutOfBoundsException.class, () -> parser.peekCodePoint(UTF_8));
    assertThat(e).hasMessageThat().contains("position=2");
    assertThat(e).hasMessageThat().contains("limit=2");
  }

  @Test
  public void peekCodePoint_utf8_invalidByteSequence() {
    // 2-byte start character not followed by anything.
    ParsableByteArray parser = new ParsableByteArray(TestUtil.createByteArray(0xC1));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 2-byte character truncated by limit.
    parser = new ParsableByteArray("é".getBytes(UTF_8), /* limit= */ 1);
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 2-byte start character not followed by a continuation byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xC1, 'a'));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 3-byte start character followed by only one byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xE1, 0x81));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 3-byte character truncated by limit.
    parser = new ParsableByteArray("ऊ".getBytes(UTF_8), /* limit= */ 2);
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 3-byte start character followed by only one continuation byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xE1, 0x81, 'a'));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 4-byte start character followed by only two bytes.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xF1, 0x81, 0x81));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 4-byte character truncated by limit.
    parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_8), /* limit= */ 3);
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);

    // 4-byte start character followed by only two continuation bytes.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xF1, 0x81, 0x81, 'a'));
    assertThat(parser.peekCodePoint(UTF_8)).isEqualTo(ParsableByteArray.INVALID_CODE_POINT);
  }

  @Test
  public void peekCodePoint_utf16() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));

    int expectedCodePoint = 'f';
    assertThat(parser.peekCodePoint(UTF_16)).isEqualTo(expectedCodePoint);
    assertThat(parser.peekCodePoint(UTF_16BE)).isEqualTo(expectedCodePoint);
  }

  @Test
  public void peekCodePoint_utf16_basicMultilingualPlane() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_16BE));

    int expectedCodePoint = 'é';
    assertThat(parser.peekCodePoint(UTF_16)).isEqualTo(expectedCodePoint);
    assertThat(parser.peekCodePoint(UTF_16BE)).isEqualTo(expectedCodePoint);
  }

  @Test
  public void peekCodePoint_utf16_surrogatePair() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));

    int expectedCodePoint = Character.codePointAt("\uD83D\uDE1B", 0);
    assertThat(parser.peekCodePoint(UTF_16)).isEqualTo(expectedCodePoint);
    assertThat(parser.peekCodePoint(UTF_16BE)).isEqualTo(expectedCodePoint);
  }

  @Test
  public void peekCodePoint_utf16_splitSurrogatePair_returnsLowSurrogate() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));
    parser.skipBytes(2);

    int expectedCodePoint = 0xDE1B;
    assertThat(parser.peekCodePoint(UTF_16)).isEqualTo(expectedCodePoint);
    assertThat(parser.peekCodePoint(UTF_16BE)).isEqualTo(expectedCodePoint);
  }

  @Test
  public void peekCodePoint_utf16_misaligned_returnsGarbage() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));
    // Move the position so we are reading the second byte of 'f' and the first byte of 'o'.
    parser.setPosition(1);

    int expectedCodePoint = '昀';
    assertThat(parser.peekCodePoint(UTF_16)).isEqualTo(expectedCodePoint);
    assertThat(parser.peekCodePoint(UTF_16BE)).isEqualTo(expectedCodePoint);
  }

  @Test
  public void peekCodePoint_utf16_atLimit_throwsException() {
    // Use UTF_16BE to avoid encoding a BOM. Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE), /* limit= */ 2);
    // Only one readable byte, not enough for a UTF-16 code unit.
    parser.setPosition(1);

    IndexOutOfBoundsException e1 =
        assertThrows(IndexOutOfBoundsException.class, () -> parser.peekCodePoint(UTF_16));
    assertThat(e1).hasMessageThat().contains("position=1");
    assertThat(e1).hasMessageThat().contains("limit=2");
    IndexOutOfBoundsException e2 =
        assertThrows(IndexOutOfBoundsException.class, () -> parser.peekCodePoint(UTF_16BE));
    assertThat(e2).hasMessageThat().contains("position=1");
    assertThat(e2).hasMessageThat().contains("limit=2");
  }

  @Test
  public void peekCodePoint_utf16le() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE));

    assertThat(parser.peekCodePoint(UTF_16LE)).isEqualTo((int) 'f');
  }

  @Test
  public void peekCodePoint_utf16le_basicMultilingualPlane() {
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_16LE));

    assertThat(parser.peekCodePoint(UTF_16LE)).isEqualTo((int) 'é');
  }

  @Test
  public void peekCodePoint_utf16le_surrogatePair() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16LE));

    assertThat(parser.peekCodePoint(UTF_16LE)).isEqualTo(Character.codePointAt("\uD83D\uDE1B", 0));
  }

  @Test
  public void peekCodePoint_utf16le_splitSurrogatePair_returnsLowSurrogate() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16LE));
    parser.skipBytes(2);

    assertThat(parser.peekCodePoint(UTF_16LE)).isEqualTo(0xDE1B);
  }

  @Test
  public void peekCodePoint_utf16le_misaligned_returnsGarbage() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE));
    // Move the position so we are reading the second byte of 'f' and the first byte of 'o'.
    parser.setPosition(1);

    assertThat(parser.peekCodePoint(UTF_16LE)).isEqualTo((int) '漀');
  }

  @Test
  public void peekCodePoint_utf16le_atLimit_throwsException() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE), /* limit= */ 2);
    // Only one readable byte, not enough for a UTF-16 code unit.
    parser.setPosition(1);

    IndexOutOfBoundsException e =
        assertThrows(IndexOutOfBoundsException.class, () -> parser.peekCodePoint(UTF_16LE));
    assertThat(e).hasMessageThat().contains("position=1");
    assertThat(e).hasMessageThat().contains("limit=2");
  }

  @Test
  public void peekChar() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));

    assertThat(parser.peekChar()).isEqualTo('f');
  }

  @Test
  public void peekChar_returnsHighSurrogateFromPair() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar()).isEqualTo(0xD83D);
  }

  @Test
  public void peekChar_returnsLowSurrogateFromPair() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));
    parser.setPosition(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar()).isEqualTo(0xDE1B);
  }

  @Test
  public void peekChar_splitSurrogate() {
    // Encode only a low surrogate char
    ParsableByteArray parser = new ParsableByteArray(TestUtil.createByteArray(0xDE, 0x1B));

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar()).isEqualTo(0xDE1B);
  }

  @Test
  public void peekChar_misaligned_returnsGarbage() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));
    // Move the position so we are reading the second byte of 'f' and the first byte of 'o'.
    parser.setPosition(1);

    assertThat(parser.peekChar()).isEqualTo('昀');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_ascii() {
    byte[] bytes = "foo".getBytes(US_ASCII);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.peekChar(US_ASCII)).isEqualTo('f');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_ascii_invalid_returns8BitCharacterAnyway() {
    // Choose é from ISO 8859-1 which is not valid 7-bit ASCII (since it has a high MSB).
    ParsableByteArray parser = new ParsableByteArray(TestUtil.createByteArray(0xE9));

    assertThat(parser.peekChar(US_ASCII)).isEqualTo('é');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_ascii_atLimit_throwsException() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(US_ASCII), /* limit= */ 2);
    parser.setPosition(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(US_ASCII)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_oneByteCharacter() {
    byte[] bytes = "foo".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    assertThat(parser.peekChar(UTF_8)).isEqualTo('f');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_twoByteCharacter_returnsZero() {
    byte[] bytes = "étude".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_threeByteCharacter_returnsZero() {
    ParsableByteArray parser = new ParsableByteArray("ऊ".getBytes(UTF_8));

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_fourByteCharacter_returnsZero() {
    byte[] bytes = "\uD83D\uDE1B".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_splitFourByteChar_returnsZero() {
    byte[] bytes = "\uD83D\uDE1B".getBytes(UTF_8);
    ParsableByteArray parser = new ParsableByteArray(bytes);
    parser.skipBytes(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_atLimit_returnsZero() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_8), /* limit= */ 2);
    parser.setPosition(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf8_invalidByteSequence() {
    // 2-byte start character not followed by anything.
    ParsableByteArray parser = new ParsableByteArray(TestUtil.createByteArray(0xC1));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 2-byte character truncated by limit.
    parser = new ParsableByteArray("é".getBytes(UTF_8), /* limit= */ 1);
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 2-byte start character not followed by a continuation byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xC1, 'a'));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 3-byte start character followed by only one byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xE1, 0x81));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 3-byte character truncated by limit.
    parser = new ParsableByteArray("ऊ".getBytes(UTF_8), /* limit= */ 2);
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 3-byte start character followed by only one continuation byte.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xE1, 0x81, 'a'));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 4-byte start character followed by only two bytes.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xF1, 0x81, 0x81));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 4-byte character truncated by limit.
    parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_8), /* limit= */ 3);
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);

    // 4-byte start character followed by only two continuation bytes.
    parser = new ParsableByteArray(TestUtil.createByteArray(0xF1, 0x81, 0x81, 'a'));
    assertThat(parser.peekChar(UTF_8)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));

    char expectedChar = 'f';
    assertThat(parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat(parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16_basicMultilingualPlane() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_16BE));

    char expectedChar = 'é';
    assertThat(parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat(parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16_surrogatePair_returnsHighSurrogate() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    int expectedChar = 0xD83D;
    assertThat((int) parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat((int) parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16_splitSurrogatePair_returnsLowSurrogate() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16BE));
    parser.skipBytes(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    int expectedChar = 0xDE1B;
    assertThat((int) parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat((int) parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16_misaligned_returnsGarbage() {
    // Use UTF_16BE to avoid encoding a BOM.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE));
    // Move the position so we are reading the second byte of 'f' and the first byte of 'o'.
    parser.setPosition(1);

    char expectedChar = '昀';
    assertThat(parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat(parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16_atLimit_returnsZero() {
    // Use UTF_16BE to avoid encoding a BOM. Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16BE), /* limit= */ 2);
    // Only one readable byte, not enough for a UTF-16 code unit.
    parser.setPosition(1);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    int expectedChar = 0;
    assertThat((int) parser.peekChar(UTF_16)).isEqualTo(expectedChar);
    assertThat((int) parser.peekChar(UTF_16BE)).isEqualTo(expectedChar);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE));

    assertThat(parser.peekChar(UTF_16LE)).isEqualTo('f');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le_basicMultilingualPlane() {
    ParsableByteArray parser = new ParsableByteArray("étude".getBytes(UTF_16LE));

    assertThat(parser.peekChar(UTF_16LE)).isEqualTo('é');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le_surrogatePair_returnsHighSurrogate() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16LE));

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_16LE)).isEqualTo(0xD83D);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le_splitSurrogatePair_returnsLowSurrogate() {
    ParsableByteArray parser = new ParsableByteArray("\uD83D\uDE1B".getBytes(UTF_16LE));
    parser.skipBytes(2);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_16LE)).isEqualTo(0xDE1B);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le_misaligned_returnsGarbage() {
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE));
    // Move the position so we are reading the second byte of 'f' and the first byte of 'o'.
    parser.setPosition(1);

    assertThat(parser.peekChar(UTF_16LE)).isEqualTo('漀');
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekChar_utf16le_atLimit_returnsZero() {
    // Set the limit before the end of the byte array.
    ParsableByteArray parser = new ParsableByteArray("foo".getBytes(UTF_16LE), /* limit= */ 2);
    // Only one readable byte, not enough for a UTF-16 code unit.
    parser.setPosition(1);

    // Compare ints instead of chars for unprintable characters, so the failure messages are better.
    assertThat((int) parser.peekChar(UTF_16LE)).isEqualTo(0);
  }

  @Test
  public void peekUnsignedInt24_doesntModifyPosition() {
    ParsableByteArray parser =
        new ParsableByteArray(
            Bytes.concat(
                TestUtil.buildTestData(3),
                Ints.toByteArray((1 << 23) + 10),
                TestUtil.buildTestData(2)));
    // Skip over the first byte of Ints.toByteArray
    parser.setPosition(4);
    parser.setLimit(7);

    assertThat(parser.peekUnsignedInt24()).isEqualTo((1 << 23) + 10);
    assertThat(parser.getPosition()).isEqualTo(4);
  }

  @Test
  public void peekUnsignedInt24_exceedsLimit() {
    ParsableByteArray parser =
        new ParsableByteArray(
            Bytes.concat(
                TestUtil.buildTestData(3),
                Ints.toByteArray((1 << 23) + 10),
                TestUtil.buildTestData(2)));
    // Skip over the first byte of Ints.toByteArray
    parser.setPosition(4);
    parser.setLimit(6);

    assertThrows(IndexOutOfBoundsException.class, parser::peekUnsignedInt24);
    assertThat(parser.getPosition()).isEqualTo(4);
  }

  @Test
  public void peekInt_doesntModifyPosition() {
    ParsableByteArray parser =
        new ParsableByteArray(
            Bytes.concat(
                TestUtil.buildTestData(3), Ints.toByteArray(-5), TestUtil.buildTestData(2)));
    parser.setPosition(3);
    parser.setLimit(7);

    assertThat(parser.peekInt()).isEqualTo(-5);
    assertThat(parser.getPosition()).isEqualTo(3);
  }

  @Test
  public void peekInt_exceedsLimit() {
    ParsableByteArray parser =
        new ParsableByteArray(
            Bytes.concat(
                TestUtil.buildTestData(3), Ints.toByteArray(10), TestUtil.buildTestData(2)));
    parser.setPosition(3);
    parser.setLimit(6);

    assertThrows(IndexOutOfBoundsException.class, parser::peekInt);
    assertThat(parser.getPosition()).isEqualTo(3);
  }

  @Test
  public void readUnsignedLeb128ToLong() {
    byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F};
    ParsableByteArray testArray = new ParsableByteArray(bytes);

    long readValue = testArray.readUnsignedLeb128ToLong();

    assertThat(readValue).isEqualTo(0xFFFFFFFFL);
    assertThat(testArray.getPosition()).isEqualTo(5);
  }

  @Test
  public void readMaxUnsignedLeb128ToLong() {
    byte[] bytes =
        new byte[] {
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0xFF,
          (byte) 0x7F
        };
    ParsableByteArray testArray = new ParsableByteArray(bytes);

    long readValue = testArray.readUnsignedLeb128ToLong();

    assertThat(readValue).isEqualTo(Long.MAX_VALUE);
    assertThat(testArray.getPosition()).isEqualTo(9);
  }

  @Test
  public void readUnsignedLeb128ToInt() {
    byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F};
    ParsableByteArray testArray = new ParsableByteArray(bytes);

    int readValue = testArray.readUnsignedLeb128ToInt();

    assertThat(readValue).isEqualTo(0x1FFFFFF);
    assertThat(testArray.getPosition()).isEqualTo(4);
  }

  @Test
  public void readMaxUnsignedLeb128ToInt() {
    byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x07};
    ParsableByteArray testArray = new ParsableByteArray(bytes);

    int readValue = testArray.readUnsignedLeb128ToInt();

    assertThat(readValue).isEqualTo(Integer.MAX_VALUE);
    assertThat(testArray.getPosition()).isEqualTo(5);
  }

  @Test
  public void readTooLongUnsignedLeb128ToInt() {
    byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F};
    ParsableByteArray testArray = new ParsableByteArray(bytes);

    assertThrows(IllegalArgumentException.class, testArray::readUnsignedLeb128ToInt);
  }
}
