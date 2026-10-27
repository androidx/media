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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link WatchdogTimer}. */
@RunWith(AndroidJUnit4.class)
public final class WatchdogTimerTest {

  private static final long TIMEOUT_MS = 10_000;
  private static final long SHORT_TIMEOUT_MS = 10;

  private final WatchdogTimer watchdogTimer = new WatchdogTimer(TIMEOUT_MS, () -> {});

  @After
  public void tearDown() {
    watchdogTimer.stop();
  }

  @Test
  public void start_timeoutElapses_callsOnTimeout() throws Exception {
    CountDownLatch timeoutLatch = new CountDownLatch(1);
    WatchdogTimer shortWatchdogTimer = new WatchdogTimer(SHORT_TIMEOUT_MS, timeoutLatch::countDown);
    try {
      shortWatchdogTimer.start();

      assertThat(timeoutLatch.await(5, SECONDS)).isTrue();
    } finally {
      shortWatchdogTimer.stop();
    }
  }

  @Test
  public void reset_afterStart_callsOnTimeout() throws Exception {
    AtomicReference<CountDownLatch> timeoutLatchRef = new AtomicReference<>(new CountDownLatch(1));
    WatchdogTimer shortWatchdogTimer =
        new WatchdogTimer(SHORT_TIMEOUT_MS, () -> timeoutLatchRef.get().countDown());
    try {
      shortWatchdogTimer.start();
      assertThat(timeoutLatchRef.get().await(5, SECONDS)).isTrue();

      timeoutLatchRef.set(new CountDownLatch(1));
      shortWatchdogTimer.reset();

      assertThat(timeoutLatchRef.get().await(5, SECONDS)).isTrue();
    } finally {
      shortWatchdogTimer.stop();
    }
  }

  @Test
  public void reset_beforeStart_isNoOp() {
    watchdogTimer.reset();

    // Verify start() still succeeds because reset() did not schedule a timer.
    watchdogTimer.start();
  }

  @Test
  public void reset_afterStop_isNoOp() {
    watchdogTimer.start();
    watchdogTimer.stop();

    watchdogTimer.reset();
  }

  @Test
  public void stop_calledMultipleTimes_doesNotThrow() {
    watchdogTimer.start();

    watchdogTimer.stop();
    watchdogTimer.stop();
  }

  @Test
  public void stop_beforeStart_doesNotThrow() {
    watchdogTimer.stop();
  }

  @Test
  public void start_afterStop_throwsIllegalStateException() {
    watchdogTimer.stop();

    assertThrows(IllegalStateException.class, watchdogTimer::start);
  }

  @Test
  public void start_calledTwice_throwsIllegalStateException() {
    watchdogTimer.start();

    assertThrows(IllegalStateException.class, watchdogTimer::start);
  }
}
