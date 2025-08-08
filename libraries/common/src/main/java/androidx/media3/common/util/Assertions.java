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

import android.os.Looper;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.InlineMe;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.dataflow.qual.Pure;

/** Provides methods for asserting the truth of expressions and properties. */
@UnstableApi
public final class Assertions {

  private Assertions() {}

  /**
   * Throws {@link IllegalArgumentException} if {@code expression} evaluates to false.
   *
   * @param expression The expression to evaluate.
   * @throws IllegalArgumentException If {@code expression} is false.
   */
  @Pure
  public static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Throws {@link IllegalArgumentException} if {@code expression} evaluates to false.
   *
   * @param expression The expression to evaluate.
   * @param errorMessage The exception message if an exception is thrown. The message is converted
   *     to a {@link String} using {@link String#valueOf(Object)}.
   * @throws IllegalArgumentException If {@code expression} is false.
   */
  @Pure
  public static void checkArgument(boolean expression, Object errorMessage) {
    if (!expression) {
      throw new IllegalArgumentException(String.valueOf(errorMessage));
    }
  }

  /**
   * @deprecated Use {@link Preconditions#checkElementIndex(int, int)}, with an additional assertion
   *     to check a non-zero {@code start} value if needed.
   */
  @Pure
  @Deprecated
  public static int checkIndex(int index, int start, int limit) {
    if (index < start || index >= limit) {
      throw new IndexOutOfBoundsException();
    }
    return index;
  }

  /**
   * Throws {@link IllegalStateException} if {@code expression} evaluates to false.
   *
   * @param expression The expression to evaluate.
   * @throws IllegalStateException If {@code expression} is false.
   */
  @Pure
  public static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /**
   * Throws {@link IllegalStateException} if {@code expression} evaluates to false.
   *
   * @param expression The expression to evaluate.
   * @param errorMessage The exception message if an exception is thrown. The message is converted
   *     to a {@link String} using {@link String#valueOf(Object)}.
   * @throws IllegalStateException If {@code expression} is false.
   */
  @Pure
  public static void checkState(boolean expression, Object errorMessage) {
    if (!expression) {
      throw new IllegalStateException(String.valueOf(errorMessage));
    }
  }

  /**
   * @deprecated Use {@link Preconditions#checkNotNull} instead.
   */
  @Deprecated
  @EnsuresNonNull({"#1"})
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkNotNull(reference)",
      imports = "com.google.common.base.Preconditions")
  public static <T> T checkStateNotNull(@Nullable T reference) {
    return Preconditions.checkNotNull(reference);
  }

  /**
   * @deprecated Use {@link Preconditions#checkNotNull} instead.
   */
  @Deprecated
  @EnsuresNonNull({"#1"})
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkNotNull(reference, errorMessage)",
      imports = "com.google.common.base.Preconditions")
  public static <T> T checkStateNotNull(@Nullable T reference, Object errorMessage) {
    return Preconditions.checkNotNull(reference, errorMessage);
  }

  /**
   * Throws {@link NullPointerException} if {@code reference} is null.
   *
   * @param <T> The type of the reference.
   * @param reference The reference.
   * @return The non-null reference that was validated.
   * @throws NullPointerException If {@code reference} is null.
   */
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  public static <T> T checkNotNull(@Nullable T reference) {
    if (reference == null) {
      throw new NullPointerException();
    }
    return reference;
  }

  /**
   * Throws {@link NullPointerException} if {@code reference} is null.
   *
   * @param <T> The type of the reference.
   * @param reference The reference.
   * @param errorMessage The exception message to use if the check fails. The message is converted
   *     to a string using {@link String#valueOf(Object)}.
   * @return The non-null reference that was validated.
   * @throws NullPointerException If {@code reference} is null.
   */
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  public static <T> T checkNotNull(@Nullable T reference, Object errorMessage) {
    if (reference == null) {
      throw new NullPointerException(String.valueOf(errorMessage));
    }
    return reference;
  }

  /**
   * @deprecated Use {@link Preconditions#checkArgument} with {@link
   *     TextUtils#isEmpty(CharSequence)} instead.
   */
  @Deprecated
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  public static String checkNotEmpty(@Nullable String string) {
    Preconditions.checkArgument(!TextUtils.isEmpty(string));
    return string;
  }

  /**
   * @deprecated Use {@link Preconditions#checkArgument} with {@link
   *     TextUtils#isEmpty(CharSequence)} instead.
   */
  @Deprecated
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  public static String checkNotEmpty(@Nullable String string, Object errorMessage) {
    Preconditions.checkArgument(!TextUtils.isEmpty(string), errorMessage);
    return string;
  }

  /**
   * Throws {@link IllegalStateException} if the calling thread is not the application's main
   * thread.
   *
   * @throws IllegalStateException If the calling thread is not the application's main thread.
   */
  @Pure
  public static void checkMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      throw new IllegalStateException("Not in applications main thread");
    }
  }
}
