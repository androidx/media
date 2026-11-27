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
package androidx.media3.common.util;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import androidx.annotation.RestrictTo;

/**
 * A circular int array offering simple queue access without integer boxing.
 *
 * <p>Code adapted from <a
 * href="https://android.googlesource.com/platform/frameworks/support/+/ac5fe7c617c66850fff75a9fce9979c6e5674b0f/collections/src/main/java/androidx/collection/CircularIntArray.java">AndroidX
 * Collection's CircularIntArray</a>
 */
@RestrictTo(LIBRARY_GROUP)
public final class CircularIntArray {

  private int[] elements;
  private int head;
  private int tail;
  private int capacityBitmask;

  /** Creates a circular array with default capacity. */
  public CircularIntArray() {
    capacityBitmask = 7;
    elements = new int[8];
  }

  /**
   * Add an integer at end of the array.
   *
   * @param e Value to add.
   */
  public void addLast(int e) {
    elements[tail] = e;
    tail = (tail + 1) & capacityBitmask;
    if (tail == head) {
      doubleCapacity();
    }
  }

  /** Remove first integer from front of the array and returns it. */
  public int popFirst() {
    if (head == tail) {
      throw new ArrayIndexOutOfBoundsException();
    }
    int result = elements[head];
    head = (head + 1) & capacityBitmask;
    return result;
  }

  /** Remove all integers from the array. */
  public void clear() {
    tail = head;
  }

  /** Returns whether the array is empty. */
  public boolean isEmpty() {
    return head == tail;
  }

  private void doubleCapacity() {
    int n = elements.length;
    int r = n - head;
    int newCapacity = n << 1;
    int[] a = new int[newCapacity];
    System.arraycopy(elements, head, a, 0, r);
    System.arraycopy(elements, 0, a, r, head);
    elements = a;
    head = 0;
    tail = n;
    capacityBitmask = newCapacity - 1;
  }
}
