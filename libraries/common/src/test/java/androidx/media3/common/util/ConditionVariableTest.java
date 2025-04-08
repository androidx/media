/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ConditionVariableTest}. */
@RunWith(AndroidJUnit4.class)
public class ConditionVariableTest {

  @Test
  public void initialState_isClosed() {
    ConditionVariable conditionVariable = buildTestConditionVariable();
    assertThat(conditionVariable.isOpen()).isFalse();
  }

  @Test
  public void block_withTimeoutUnopened_timesOut() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();
    assertThat(conditionVariable.block(1)).isFalse();
    assertThat(conditionVariable.isOpen()).isFalse();
  }

  @Test
  public void block_withTimeoutUnopened_blocksForAtLeastTimeout() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();
    long startTimeMs = System.currentTimeMillis();
    assertThat(conditionVariable.block(/* timeoutMs= */ 500)).isFalse();
    long endTimeMs = System.currentTimeMillis();
    assertThat(endTimeMs - startTimeMs).isAtLeast(500);
  }

  @Test
  public void block_withMaxTimeoutUnopened_blocksThenThrowsWhenInterrupted()
      throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    AtomicBoolean blockReturned = new AtomicBoolean();
    AtomicBoolean blockWasInterrupted = new AtomicBoolean();
    Thread blockingThread =
        new Thread(
            () -> {
              try {
                conditionVariable.block(/* timeoutMs= */ Long.MAX_VALUE);
                blockReturned.set(true);
              } catch (InterruptedException e) {
                blockWasInterrupted.set(true);
              }
            });

    blockingThread.start();
    Thread.sleep(500);
    assertThat(blockReturned.get()).isFalse();

    blockingThread.interrupt();
    blockingThread.join();
    assertThat(blockWasInterrupted.get()).isTrue();
    assertThat(conditionVariable.isOpen()).isFalse();
  }

  @Test
  public void block_unopened_blocksThenThrowsWhenInterrupted() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    AtomicBoolean blockReturned = new AtomicBoolean();
    AtomicBoolean blockWasInterrupted = new AtomicBoolean();
    Thread blockingThread =
        new Thread(
            () -> {
              try {
                conditionVariable.block();
                blockReturned.set(true);
              } catch (InterruptedException e) {
                blockWasInterrupted.set(true);
              }
            });

    blockingThread.start();
    Thread.sleep(500);
    assertThat(blockReturned.get()).isFalse();

    blockingThread.interrupt();
    blockingThread.join();
    assertThat(blockWasInterrupted.get()).isTrue();
    assertThat(conditionVariable.isOpen()).isFalse();
  }

  @Test
  public void block_opened_blocksThenReturnsWhenOpened() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    AtomicBoolean blockReturned = new AtomicBoolean();
    AtomicBoolean blockWasInterrupted = new AtomicBoolean();
    Thread blockingThread =
        new Thread(
            () -> {
              try {
                conditionVariable.block();
                blockReturned.set(true);
              } catch (InterruptedException e) {
                blockWasInterrupted.set(true);
              }
            });

    blockingThread.start();
    Thread.sleep(500);
    assertThat(blockReturned.get()).isFalse();

    conditionVariable.open();
    blockingThread.join();
    assertThat(blockReturned.get()).isTrue();
    assertThat(conditionVariable.isOpen()).isTrue();
  }

  @Test
  public void blockUninterruptible_blocksIfInterruptedThenUnblocksWhenOpened()
      throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    AtomicBoolean blockReturned = new AtomicBoolean();
    AtomicBoolean interruptedStatusSet = new AtomicBoolean();
    Thread blockingThread =
        new Thread(
            () -> {
              conditionVariable.blockUninterruptible();
              interruptedStatusSet.set(Thread.currentThread().isInterrupted());
              blockReturned.set(true);
            });

    blockingThread.start();
    Thread.sleep(500);
    assertThat(blockReturned.get()).isFalse();

    blockingThread.interrupt();
    Thread.sleep(500);
    // blockUninterruptible should still be blocked.
    assertThat(blockReturned.get()).isFalse();

    conditionVariable.open();
    blockingThread.join();
    // blockUninterruptible should have set the thread's interrupted status on exit.
    assertThat(interruptedStatusSet.get()).isTrue();
    assertThat(conditionVariable.isOpen()).isTrue();
  }

  @Test
  public void blockUninterruptible_withTimeoutUnopened_timesOut() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    assertThat(conditionVariable.blockUninterruptible(1)).isFalse();
    assertThat(conditionVariable.isOpen()).isFalse();
  }

  @Test
  public void blockUninterruptible_withTimeoutUnopened_blocksForAtLeastTimeout()
      throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();

    long startTimeMs = System.currentTimeMillis();
    assertThat(conditionVariable.blockUninterruptible(/* timeoutMs= */ 500)).isFalse();
    long endTimeMs = System.currentTimeMillis();

    assertThat(endTimeMs - startTimeMs).isAtLeast(500);
  }

  @Test
  public void blockUninterruptible_withMaxTimeout_blocksUntilOpened() throws InterruptedException {
    ConditionVariable conditionVariable = buildTestConditionVariable();
    AtomicBoolean blockReturned = new AtomicBoolean();
    AtomicBoolean interruptedStatusSet = new AtomicBoolean();
    Thread blockingThread =
        new Thread(
            () -> {
              conditionVariable.blockUninterruptible(/* timeoutMs= */ Long.MAX_VALUE);
              interruptedStatusSet.set(Thread.currentThread().isInterrupted());
              blockReturned.set(true);
            });

    blockingThread.start();
    Thread.sleep(500);

    assertThat(blockReturned.get()).isFalse();

    blockingThread.interrupt();
    Thread.sleep(500);

    // blockUninterruptible should still be blocked.
    assertThat(blockReturned.get()).isFalse();

    conditionVariable.open();
    blockingThread.join();

    // blockUninterruptible should have set the thread's interrupted status on exit.
    assertThat(interruptedStatusSet.get()).isTrue();
    assertThat(conditionVariable.isOpen()).isTrue();
  }

  private static ConditionVariable buildTestConditionVariable() {
    return new ConditionVariable(
        new SystemClock() {
          @Override
          public long elapsedRealtime() {
            // elapsedRealtime() does not advance during Robolectric test execution, so use
            // currentTimeMillis() instead. This is technically unsafe because this clock is not
            // guaranteed to be monotonic, but in practice it will work provided the clock of the
            // host machine does not change during test execution.
            return Clock.DEFAULT.currentTimeMillis();
          }
        });
  }
}
