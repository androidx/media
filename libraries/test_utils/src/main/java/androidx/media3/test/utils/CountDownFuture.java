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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;

import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ListenableFuture} that completes when {@link #countDown()} has been called a specified
 * number of times.
 */
@UnstableApi
@RequiresApi(24)
public final class CountDownFuture extends AbstractFuture<Void> {
  private final AtomicInteger remaining;

  /**
   * Creates an instance initialized with the specified count.
   *
   * @param count The number of times {@link #countDown()} must be invoked before completing. If 0,
   *     the future completes immediately.
   * @throws IllegalArgumentException if count is negative.
   */
  public CountDownFuture(int count) {
    checkArgument(count >= 0, "count < 0");
    this.remaining = new AtomicInteger(count);
    if (count == 0) {
      set(null);
    }
  }

  /** Decrements the remaining count, completing the future when it reaches 0. */
  public void countDown() {
    if (remaining.getAndUpdate(v -> max(0, v - 1)) == 1) {
      set(null);
    }
  }
}
