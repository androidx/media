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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.app.Instrumentation;
import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import androidx.media3.common.C;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility for creating artificial stress on the system process binder.
 *
 * <p>While the class is active, any attempt to call a binder method to the system process will be
 * much slower.
 *
 * <p>This class should be used in a try-with-resources block.
 *
 * <p>Use {@link #verifyNoSystemBinderCalls} to verify no system binder calls happen in a test.
 */
@UnstableApi
public final class BinderStressCreator implements AutoCloseable {

  /**
   * Verifies that the provided system-under-test does not exercise any system binder calls by
   * comparing the run time with and without additional stress caused by {@link
   * BinderStressCreator}.
   *
   * @param systemUnderTest The system-under-test that can return a test object for later clean-up.
   * @param cleanUp The clean-up stage for the system-under-test.
   */
  public static <T> void verifyNoSystemBinderCalls(Supplier<T> systemUnderTest, Consumer<T> cleanUp)
      throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context context = ApplicationProvider.getApplicationContext();

    // Avoid cold start class loading delays by running the system-under-test at least once.
    instrumentation.runOnMainSync(() -> cleanUp.accept(systemUnderTest.get()));

    // Run 10 times and find maximum runtime without additional stress.
    AtomicLong maxTimeWithoutStressUs = new AtomicLong(/* initialValue= */ 0);
    instrumentation.runOnMainSync(
        () -> {
          for (int i = 0; i < 10; i++) {
            long startTimeNs = System.nanoTime();
            T runHandle = systemUnderTest.get();
            long runtimeUs = (System.nanoTime() - startTimeNs) / 1000;
            maxTimeWithoutStressUs.set(max(maxTimeWithoutStressUs.get(), runtimeUs));
            cleanUp.accept(runHandle);
          }
        });

    // Try again with additional binder stress on the system and find minimum runtime.
    AtomicLong minTimeWithStressUs = new AtomicLong(/* initialValue= */ Long.MAX_VALUE);
    try (BinderStressCreator binderStressCreator = new BinderStressCreator(context)) {
      instrumentation.runOnMainSync(
          () -> {
            for (int i = 0; i < 10; i++) {
              long startTimeNs = System.nanoTime();
              T runHandle = systemUnderTest.get();
              long runtimeUs = (System.nanoTime() - startTimeNs) / 1000;
              minTimeWithStressUs.set(min(minTimeWithStressUs.get(), runtimeUs));
              cleanUp.accept(runHandle);
            }
          });
    }

    // Verify that additional binder stress didn't increase time too much. We compare max vs min
    // and allow double the time to account for the general additional CPU usage.
    assertThat(minTimeWithStressUs.get()).isLessThan(2 * maxTimeWithoutStressUs.get());
  }

  private static final int THREAD_COUNT = 500;

  private final Thread[] threads;
  private final AtomicBoolean[] threadCancellationFlags;

  public BinderStressCreator(Context context) throws InterruptedException {
    threads = new Thread[THREAD_COUNT];
    threadCancellationFlags = new AtomicBoolean[THREAD_COUNT];
    CountDownLatch threadsCreated = new CountDownLatch(THREAD_COUNT);
    for (int i = 0; i < THREAD_COUNT; i++) {
      AtomicBoolean cancelled = new AtomicBoolean();
      threadCancellationFlags[i] = cancelled;
      threads[i] =
          new Thread(
              () -> {
                threadsCreated.countDown();
                while (!cancelled.get()) {
                  // Use two different system services to not depend too much on the
                  // implementation details of one of them. Both calls can't be fulfilled in
                  // process and are unlikely to work without binder calls even in the future.
                  ConnectivityManager connectivityManager =
                      (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                  checkNotNull(connectivityManager)
                      .requestBandwidthUpdate(checkNotNull(connectivityManager.getActiveNetwork()));
                  AudioManager audioManager =
                      (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                  checkNotNull(audioManager).setStreamVolume(C.STREAM_TYPE_DEFAULT, 1, 0);
                }
              });
      threads[i].start();
    }
    threadsCreated.await();
  }

  @Override
  public void close() throws Exception {
    for (int i = 0; i < THREAD_COUNT; i++) {
      threadCancellationFlags[i].set(true);
    }
    for (int i = 0; i < THREAD_COUNT; i++) {
      threads[i].join();
    }
  }
}
