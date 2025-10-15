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
   * @deprecated Use {@link Preconditions#checkArgument} instead.
   */
  @Deprecated
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkArgument(expression)",
      imports = "com.google.common.base.Preconditions")
  public static void checkArgument(boolean expression) {
    Preconditions.checkArgument(expression);
  }

  /**
   * @deprecated Use {@link Preconditions#checkArgument} instead.
   */
  @Deprecated
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkArgument(expression, errorMessage)",
      imports = "com.google.common.base.Preconditions")
  public static void checkArgument(boolean expression, Object errorMessage) {
    Preconditions.checkArgument(expression, errorMessage);
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
   * @deprecated Use {@link Preconditions#checkState} instead.
   */
  @Deprecated
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkState(expression)",
      imports = "com.google.common.base.Preconditions")
  public static void checkState(boolean expression) {
    Preconditions.checkState(expression);
  }

  /**
   * @deprecated Use {@link Preconditions#checkState} instead.
   */
  @Deprecated
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkState(expression, errorMessage)",
      imports = "com.google.common.base.Preconditions")
  public static void checkState(boolean expression, Object errorMessage) {
    Preconditions.checkState(expression, errorMessage);
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
   * @deprecated Use {@link Preconditions#checkNotNull} instead.
   */
  @Deprecated
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkNotNull(reference)",
      imports = "com.google.common.base.Preconditions")
  public static <T> T checkNotNull(@Nullable T reference) {
    return Preconditions.checkNotNull(reference);
  }

  /**
   * @deprecated Use {@link Preconditions#checkNotNull} instead.
   */
  @Deprecated
  @SuppressWarnings({"nullness:contracts.postcondition", "nullness:return"})
  @EnsuresNonNull({"#1"})
  @Pure
  @InlineMe(
      replacement = "Preconditions.checkNotNull(reference, errorMessage)",
      imports = "com.google.common.base.Preconditions")
  public static <T> T checkNotNull(@Nullable T reference, Object errorMessage) {
    return Preconditions.checkNotNull(reference, errorMessage);
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
   * @deprecated Use {@link Preconditions#checkState} to assert {@code Looper.myLooper() ==
   *     Looper.getMainLooper()} instead.
   */
  @Deprecated
  @Pure
  @InlineMe(
      replacement =
          "Preconditions.checkState(Looper.myLooper() == Looper.getMainLooper(), \"Not in"
              + " application's main thread\")",
      imports = {"com.google.common.base.Preconditions", "android.os.Looper"})
  public static void checkMainThread() {
    Preconditions.checkState(
        Looper.myLooper() == Looper.getMainLooper(), "Not in application's main thread");
  }
}
