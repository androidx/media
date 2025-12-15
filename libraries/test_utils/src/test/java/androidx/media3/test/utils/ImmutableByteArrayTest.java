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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ImmutableByteArrayTest {

  @Test
  public void emptyInstanceIsEmpty() {
    ImmutableByteArray emptyInstance = ImmutableByteArray.of();
    assertThat(emptyInstance.isEmpty()).isTrue();
    assertThat(emptyInstance.length()).isEqualTo(0);
  }

  @Test
  public void ofUnsigned_validValuesHandledAsPositive() {
    ImmutableByteArray immutableArray = ImmutableByteArray.ofUnsigned(1, 0, 129, 255);

    assertThat(immutableArray.toArray()).isEqualTo(TestUtil.createByteArray(1, 0, 129, 255));
  }

  @Test
  public void ofUnsigned_negativeOrTooLargeThrows() {
    assertThrows(IllegalArgumentException.class, () -> ImmutableByteArray.ofUnsigned(-1));
    assertThrows(IllegalArgumentException.class, () -> ImmutableByteArray.ofUnsigned(256));
  }

  @Test
  public void ofHexString_caseInsensitive() {
    ImmutableByteArray immutableArray = ImmutableByteArray.ofHexString("DEADbeef");

    assertThat(immutableArray.toArray())
        .isEqualTo(TestUtil.createByteArray(0xDE, 0xAD, 0xBE, 0xEF));
  }

  @Test
  public void ofHexString_invalidCharacter() {
    assertThrows(IllegalArgumentException.class, () -> ImmutableByteArray.ofHexString("FOOBAR"));
  }

  @Test
  public void copyOfArray_changingOriginalArrayDoesntAffectImmutableArray() {
    byte[] mutableArray = new byte[] {1, 2, 3};
    ImmutableByteArray immutableArray = ImmutableByteArray.copyOf(mutableArray);

    mutableArray[1] = 5;

    assertThat(immutableArray.get(1)).isEqualTo(2);
    assertThat(immutableArray.toArray()).isEqualTo(new byte[] {1, 2, 3});
  }

  @Test
  public void copyOfList_changingOriginalArrayDoesntAffectImmutableArray() {
    List<Byte> mutableList = Lists.newArrayList((byte) 1, (byte) 2, (byte) 3);
    ImmutableByteArray immutableArray = ImmutableByteArray.copyOf(mutableList);

    mutableList.set(1, (byte) 5);

    assertThat(immutableArray.get(1)).isEqualTo(2);
    assertThat(immutableArray.toArray()).isEqualTo(new byte[] {1, 2, 3});
  }

  @Test
  public void builder_fromPrimitivesArraysAndLists_mutationsNotPropagated() {
    byte[] byteArray = {4, 5, 6};
    List<Byte> mutableList = Lists.newArrayList((byte) 9, (byte) 10, (byte) 11);

    ImmutableByteArray immutableArray =
        new ImmutableByteArray.Builder()
            .add((byte) 1)
            .addUnsigned(255)
            .addAll(byteArray)
            .addAll(mutableList)
            .build();

    byteArray[1] = 10;
    mutableList.set(1, (byte) 50);

    byte[] expectedArray = TestUtil.createByteArray(1, 255, 4, 5, 6, 9, 10, 11);
    assertThat(immutableArray.toArray()).isEqualTo(expectedArray);
    assertThat(immutableArray.length()).isEqualTo(expectedArray.length);
    assertThat(immutableArray.isEmpty()).isFalse();
  }

  @Test
  public void builder_fromPrimitivesArraysAndLists_withInitialCapacity() {
    byte[] byteArray = {4, 5, 6};
    List<Byte> mutableList = Lists.newArrayList((byte) 9, (byte) 10, (byte) 11);
    ImmutableByteArray immutableArray =
        new ImmutableByteArray.Builder(/* initialCapacity= */ 100)
            .add((byte) 1)
            .addUnsigned(255)
            .addAll(byteArray)
            .addAll(mutableList)
            .build();

    byte[] expectedArray = TestUtil.createByteArray(1, 255, 4, 5, 6, 9, 10, 11);
    assertThat(immutableArray.toArray()).isEqualTo(expectedArray);
    assertThat(immutableArray.length()).isEqualTo(expectedArray.length);
    assertThat(immutableArray.isEmpty()).isFalse();
  }

  @Test
  public void builder_withSmallInitialCapacity_grows() {
    ImmutableByteArray immutableArray =
        new ImmutableByteArray.Builder(/* initialCapacity= */ 2)
            .add((byte) 1)
            .add((byte) 2)
            .add((byte) 3)
            .add((byte) 4)
            .build();

    byte[] expectedArray = TestUtil.createByteArray(1, 2, 3, 4);
    assertThat(immutableArray.toArray()).isEqualTo(expectedArray);
    assertThat(immutableArray.length()).isEqualTo(expectedArray.length);
    assertThat(immutableArray.isEmpty()).isFalse();
  }

  @Test
  public void builder_negativeInitialCapacity_fails() {
    assertThrows(
        NegativeArraySizeException.class,
        () -> new ImmutableByteArray.Builder(/* initialCapacity= */ -1));
  }

  @Test
  public void builder_addUnsigned_negativeOrTooLarge_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new ImmutableByteArray.Builder().addUnsigned(-1));
    assertThrows(
        IllegalArgumentException.class, () -> new ImmutableByteArray.Builder().addUnsigned(256));
  }

  @Test
  public void builder_addAllUnsigned_negativeOrTooLarge_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(new int[] {-1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(new long[] {-1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(ImmutableList.of(-1L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(new int[] {256}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(new long[] {256}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImmutableByteArray.Builder().addAllUnsigned(ImmutableList.of(256L)));
  }

  @Test
  public void concat() {
    assertThat(
            ImmutableByteArray.concat(
                    ImmutableByteArray.ofUnsigned(1, 2, 3), ImmutableByteArray.ofUnsigned(4, 5, 6))
                .toArray())
        .isEqualTo(TestUtil.createByteArray(1, 2, 3, 4, 5, 6));
  }

  @Test
  public void concatToArray() {
    assertThat(
            ImmutableByteArray.concatToArray(
                ImmutableByteArray.ofUnsigned(1, 2, 3), ImmutableByteArray.ofUnsigned(4, 5, 6)))
        .isEqualTo(TestUtil.createByteArray(1, 2, 3, 4, 5, 6));
  }

  @Test
  public void equalsHashCode() {
    new EqualsTester()
        .addEqualityGroup(
            ImmutableByteArray.ofUnsigned(1, 2, 3),
            ImmutableByteArray.ofUnsigned(1, 2, 3),
            ImmutableByteArray.ofUnsigned(0, 1, 2, 3, 4).subArray(1, 4))
        .addEqualityGroup(ImmutableByteArray.ofUnsigned(0, 1, 2, 3, 4))
        // Wrong values
        .addEqualityGroup(ImmutableByteArray.ofUnsigned(0, 1, 2, 3, 4).subArray(0, 3))
        // Wrong length
        .addEqualityGroup(ImmutableByteArray.ofUnsigned(0, 1, 2, 3, 4).subArray(0, 2))
        .testEquals();
  }

  @Test
  public void toString_unsignedValuePrintedNegative() {
    assertThat(ImmutableByteArray.ofUnsigned(7, 8, 255).toString()).isEqualTo("[7, 8, -1]");
  }

  @Test
  public void toString_subArray() {
    assertThat(ImmutableByteArray.ofUnsigned(7, 8, 9, 10).subArray(1, 3).toString())
        .isEqualTo("[8, 9]");
  }
}
