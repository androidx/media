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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import androidx.media3.common.util.BackgroundExecutor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CastUtilsTest {

  @Test
  public void checkRunningOnMainThread_onMainThread_doesNotThrowException() {
    CastUtils.verifyMainThread();
  }

  @Test
  public void checkRunningOnMainThread_onBackgroundThread_throwsIllegalStateException()
      throws Exception {
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    BackgroundExecutor.get()
        .execute(
            () -> {
              try {
                CastUtils.verifyMainThread();
              } catch (Throwable t) {
                exceptionRef.set(t);
              } finally {
                latch.countDown();
              }
            });
    assertThat(latch.await(5, SECONDS)).isTrue();

    Throwable thrown = exceptionRef.get();
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
  }
}
