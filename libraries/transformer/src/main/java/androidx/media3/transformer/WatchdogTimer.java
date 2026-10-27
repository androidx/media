/*
 * Copyright 2024 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.GuardedBy;
import androidx.media3.common.util.Util;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A watchdog timer.
 *
 * <p>Callers must follow this sequence:
 *
 * <ul>
 *   <li>Call {@link #start()} once to start the timer.
 *   <li>Call {@link #reset()} periodically to reset the timer and prevent a timeout.
 *   <li>Call {@link #stop()} to stop the timer.
 * </ul>
 *
 * <p>This class is thread-safe. {@link #reset()} may be called from any thread, including before
 * {@link #start()} or after {@link #stop()}, in which case it is a no-op.
 */
/* package */ final class WatchdogTimer {
  /** A listener for timeout events. */
  public interface Listener {
    /** Called when a timeout occurs. */
    void onTimeout();
  }

  private final long timeoutDurationMs;
  private final Listener listener;
  private final ScheduledExecutorService watchdogScheduledExecutorService;
  private final Object lock;

  @GuardedBy("lock")
  private @MonotonicNonNull ScheduledFuture<?> timeoutScheduledFuture;

  @GuardedBy("lock")
  private boolean stopped;

  /**
   * Creates an instance.
   *
   * @param timeoutDurationMs The timeout duration in milliseconds.
   * @param listener The {@link Listener} to be notified when a timeout occurs.
   */
  public WatchdogTimer(long timeoutDurationMs, Listener listener) {
    this.timeoutDurationMs = timeoutDurationMs;
    this.listener = listener;
    watchdogScheduledExecutorService = Util.newSingleThreadScheduledExecutor("WatchdogTimer");
    lock = new Object();
  }

  /**
   * Starts the watchdog timer.
   *
   * <p>Must be called at most once, and not after {@link #stop()}.
   */
  public void start() {
    synchronized (lock) {
      checkState(!stopped);
      checkState(timeoutScheduledFuture == null);
      scheduleNewTimer();
    }
  }

  /**
   * Resets the watchdog timer.
   *
   * <p>Can be called from any thread. Does nothing if the timer has not been {@linkplain #start()
   * started} or has been {@linkplain #stop() stopped}.
   */
  public void reset() {
    synchronized (lock) {
      if (stopped || timeoutScheduledFuture == null) {
        return;
      }
      cancelExistingTimer();
      scheduleNewTimer();
    }
  }

  /**
   * Stops the watchdog timer.
   *
   * <p>The watchdog timer cannot be used after it is stopped. Calling this method more than once
   * has no effect.
   */
  public void stop() {
    synchronized (lock) {
      if (stopped) {
        return;
      }
      stopped = true;
      cancelExistingTimer();
      watchdogScheduledExecutorService.shutdownNow();
    }
  }

  @GuardedBy("lock")
  private void cancelExistingTimer() {
    if (timeoutScheduledFuture != null) {
      timeoutScheduledFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  @GuardedBy("lock")
  private void scheduleNewTimer() {
    timeoutScheduledFuture =
        watchdogScheduledExecutorService.schedule(
            listener::onTimeout, timeoutDurationMs, MILLISECONDS);
  }
}
