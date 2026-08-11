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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CountDownFuture}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 24)
public final class CountDownFutureTest {

  @Test
  public void countDown_targetCountReached_completes() throws Exception {
    CountDownFuture future = new CountDownFuture(3);
    assertThat(future.isDone()).isFalse();

    future.countDown();
    assertThat(future.isDone()).isFalse();

    future.countDown();
    assertThat(future.isDone()).isFalse();

    future.countDown();
    assertThat(future.isDone()).isTrue();
    assertThat(future.get()).isNull();
  }

  @Test
  public void zeroCount_completesImmediately() throws Exception {
    CountDownFuture zeroFuture = new CountDownFuture(0);
    assertThat(zeroFuture.isDone()).isTrue();
    assertThat(zeroFuture.get()).isNull();
  }

  @Test
  public void negativeCount_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> new CountDownFuture(-5));
  }

  @Test
  public void countDown_excessCalls_isHarmlessNoOp() throws Exception {
    CountDownFuture future = new CountDownFuture(1);
    future.countDown();
    assertThat(future.isDone()).isTrue();

    future.countDown();
    future.countDown();
    assertThat(future.isDone()).isTrue();
  }

  @Test
  public void countDown_concurrentCalls_completesSafely() throws Exception {
    int limit = 50;
    CountDownFuture future = new CountDownFuture(limit);
    ExecutorService executor = Executors.newFixedThreadPool(10);
    try {
      for (int i = 0; i < limit; i++) {
        executor.execute(future::countDown);
      }
      future.get(5, SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }
}
