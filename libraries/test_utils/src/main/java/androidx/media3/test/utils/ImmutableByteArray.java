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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkPositionIndexes;
import static com.google.common.math.IntMath.checkedAdd;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedBytes;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.Collection;

/**
 * {@code byte} version of {@link com.google.common.primitives.ImmutableIntArray}.
 *
 * <p>This avoids the boxing/un-boxing costs of {@code ImmutableList<Byte>} and offers more
 * ergonomic integration with APIs that expect a {@code byte[]}.
 */
@UnstableApi
public final class ImmutableByteArray {

  /** Builder for {@link ImmutableByteArray} instances. */
  public static final class Builder {
    byte[] array;
    int end;

    /** Constructs a new instance with a default initial capacity. */
    public Builder() {
      this(/* initialCapacity= */ 10);
    }

    /** Constructs a new instance with the provided initial capacity. */
    public Builder(int initialCapacity) {
      array = new byte[initialCapacity];
      end = 0;
    }

    /** Add the provided {@code byte} to the builder. */
    @CanIgnoreReturnValue
    public Builder add(byte b) {
      ensureCapacityForAppending(1, /* padding= */ 10);
      array[end++] = b;
      return this;
    }

    /**
     * Cast the provided {@code long} with {@link UnsignedBytes#checkedCast(long)} and add it to the
     * builder.
     */
    @CanIgnoreReturnValue
    public Builder addUnsigned(long b) {
      ensureCapacityForAppending(1, /* padding= */ 10);
      array[end++] = UnsignedBytes.checkedCast(b);
      return this;
    }

    /** Add the contents of the provided array to this builder. */
    @CanIgnoreReturnValue
    public Builder addAll(byte[] bytes) {
      ensureCapacityForAppending(bytes.length);
      System.arraycopy(bytes, 0, array, end, bytes.length);
      end += bytes.length;
      return this;
    }

    /** Add the contents of the provided array to this builder. */
    @CanIgnoreReturnValue
    public Builder addAll(ImmutableByteArray bytes) {
      ensureCapacityForAppending(bytes.length());
      System.arraycopy(bytes.array, bytes.start, array, end, bytes.length());
      end += bytes.length();
      return this;
    }

    /** Add the contents of the provided collection to this builder. */
    @CanIgnoreReturnValue
    public Builder addAll(Collection<Byte> bytes) {
      ensureCapacityForAppending(bytes.size());
      for (Byte b : bytes) {
        array[end++] = b;
      }
      return this;
    }

    /**
     * Cast the elements of the provided {@code long[]} with {@link UnsignedBytes#checkedCast(long)}
     * and add them to the builder.
     */
    @CanIgnoreReturnValue
    public Builder addAllUnsigned(long[] bytes) {
      ensureCapacityForAppending(bytes.length);
      for (long b : bytes) {
        array[end++] = UnsignedBytes.checkedCast(b);
      }
      return this;
    }

    /**
     * Cast the elements of the provided {@code int[]} with {@link UnsignedBytes#checkedCast(long)}
     * and add them to the builder.
     */
    @CanIgnoreReturnValue
    public Builder addAllUnsigned(int[] bytes) {
      ensureCapacityForAppending(bytes.length);
      for (int b : bytes) {
        array[end++] = UnsignedBytes.checkedCast(b);
      }
      return this;
    }

    /**
     * Cast the elements of the provided {@link Collection} with {@link
     * UnsignedBytes#checkedCast(long)} and add them to the builder.
     */
    @CanIgnoreReturnValue
    public Builder addAllUnsigned(Collection<Long> bytes) {
      ensureCapacityForAppending(bytes.size());
      for (Long b : bytes) {
        array[end++] = UnsignedBytes.checkedCast(b);
      }
      return this;
    }

    private void ensureCapacityForAppending(int newLength) {
      ensureCapacityForAppending(newLength, /* padding= */ newLength);
    }

    private void ensureCapacityForAppending(int newLength, int padding) {
      array = Bytes.ensureCapacity(array, end + newLength, padding);
    }

    /** Returns the built array. */
    public ImmutableByteArray build() {
      return new ImmutableByteArray(Arrays.copyOfRange(array, /* from= */ 0, end));
    }
  }

  private static final ImmutableByteArray EMPTY = new ImmutableByteArray(Util.EMPTY_BYTE_ARRAY);

  private final byte[] array;
  private final int start;
  private final int end;

  private ImmutableByteArray(byte[] array) {
    this(array, /* start= */ 0, array.length);
  }

  private ImmutableByteArray(byte[] array, int start, int end) {
    this.array = array;
    this.start = start;
    this.end = end;
  }

  /** Returns an empty instance. */
  public static ImmutableByteArray of() {
    return EMPTY;
  }

  /**
   * Returns an instance containing the provided {@code long} values converted to {@code byte} with
   * {@link UnsignedBytes#checkedCast(long)}.
   */
  public static ImmutableByteArray ofUnsigned(long value1, long... otherValues) {
    return new Builder(otherValues.length + 1)
        .addUnsigned(value1)
        .addAllUnsigned(otherValues)
        .build();
  }

  /** Returns an instance containing the bytes encoded in {@code hexString}. */
  public static ImmutableByteArray ofHexString(String hexString) {
    return new ImmutableByteArray(BaseEncoding.base16().ignoreCase().decode(hexString));
  }

  /** Returns an instance backed by a copy of {@code values}. */
  public static ImmutableByteArray copyOf(byte[] values) {
    return new ImmutableByteArray(values.clone());
  }

  /** Returns an instance backed by a copy of {@code collection}. */
  public static ImmutableByteArray copyOf(Collection<Byte> collection) {
    return new ImmutableByteArray(Bytes.toArray(collection));
  }

  /** Returns an instance containing the concatenated contents of {@code arrays}. */
  public static ImmutableByteArray concat(ImmutableByteArray... arrays) {
    return new ImmutableByteArray(concatToArray(arrays));
  }

  /** Returns a {@code byte[]} containing the concatenated contents of {@code arrays}. */
  public static byte[] concatToArray(ImmutableByteArray... arrays) {
    int resultLength = 0;
    for (ImmutableByteArray array : arrays) {
      resultLength = checkedAdd(resultLength, array.length());
    }
    int resultOffset = 0;
    byte[] result = new byte[resultLength];
    for (ImmutableByteArray array : arrays) {
      System.arraycopy(array.array, array.start, result, resultOffset, array.length());
      resultOffset += array.length();
    }
    return result;
  }

  /** Returns the {@code byte} at index {@code i}. */
  public byte get(int i) {
    checkElementIndex(i, length());
    return array[start + i];
  }

  /** Returns true if this array contains no elements. */
  public boolean isEmpty() {
    return length() == 0;
  }

  /** Returns the number of elements in this array. */
  public int length() {
    return end - start;
  }

  /** Returns a new {@code byte[]} containing the same elements as this instance. */
  public byte[] toArray() {
    return Arrays.copyOfRange(array, start, end);
  }

  /**
   * Returns a new {@code byte[]} containing the elements from this instance between indexes {@code
   * startIndex} (inclusive) and {@code endIndex} (exclusive).
   */
  public ImmutableByteArray subArray(int startIndex, int endIndex) {
    checkPositionIndexes(startIndex, endIndex, length());
    return startIndex == endIndex
        ? EMPTY
        : new ImmutableByteArray(array, start + startIndex, start + endIndex);
  }
}
