/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static com.google.common.truth.Truth.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.widget.Button;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

/** Unit test for {@link FakeClock}. */
@RunWith(AndroidJUnit4.class)
public final class FakeClockTest {

  @Test
  public void currentTimeMillis_withoutBootTime() {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 10);

    assertThat(fakeClock.currentTimeMillis()).isEqualTo(10);
  }

  @Test
  public void currentTimeMillis_withBootTime() {
    FakeClock fakeClock =
        new FakeClock(
            /* bootTimeMs= */ 150, /* initialTimeMs= */ 200, /* isAutoAdvancing= */ false);

    assertThat(fakeClock.currentTimeMillis()).isEqualTo(350);
  }

  @Test
  public void currentTimeMillis_afterAdvanceTime_currentTimeHasAdvanced() {
    FakeClock fakeClock =
        new FakeClock(/* bootTimeMs= */ 100, /* initialTimeMs= */ 50, /* isAutoAdvancing= */ false);

    fakeClock.advanceTime(/* timeDiffMs */ 250);

    assertThat(fakeClock.currentTimeMillis()).isEqualTo(400);
  }

  @Test
  public void elapsedRealtime_afterAdvanceTime_timeHasAdvanced() {
    FakeClock fakeClock = new FakeClock(2000);

    assertThat(fakeClock.elapsedRealtime()).isEqualTo(2000);

    fakeClock.advanceTime(500);

    assertThat(fakeClock.elapsedRealtime()).isEqualTo(2500);

    fakeClock.advanceTime(0);

    assertThat(fakeClock.elapsedRealtime()).isEqualTo(2500);
  }

  @Test
  public void createHandler_obtainMessageSendToTarget_triggersMessage() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);

    Object testObject = new Object();
    handler.obtainMessage(/* what= */ 1).sendToTarget();
    handler.obtainMessage(/* what= */ 2, /* obj= */ testObject).sendToTarget();
    handler.obtainMessage(/* what= */ 3, /* arg1= */ 99, /* arg2= */ 44).sendToTarget();
    handler
        .obtainMessage(/* what= */ 4, /* arg1= */ 88, /* arg2= */ 33, /* obj= */ testObject)
        .sendToTarget();
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages)
        .containsExactly(
            new MessageData(/* what= */ 1, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 2, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ testObject),
            new MessageData(/* what= */ 3, /* arg1= */ 99, /* arg2= */ 44, /* obj= */ null),
            new MessageData(/* what= */ 4, /* arg1= */ 88, /* arg2= */ 33, /* obj= */ testObject))
        .inOrder();
  }

  @Test
  public void createHandler_sendEmptyMessage_triggersMessageAtCorrectTime() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);

    handler.sendEmptyMessage(/* what= */ 1);
    handler.sendEmptyMessageAtTime(/* what= */ 2, /* uptimeMs= */ fakeClock.uptimeMillis() + 60);
    handler.sendEmptyMessageDelayed(/* what= */ 3, /* delayMs= */ 50);
    handler.sendEmptyMessage(/* what= */ 4);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();

    assertThat(callback.messages)
        .containsExactly(
            new MessageData(/* what= */ 1, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 4, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null))
        .inOrder();

    fakeClock.advanceTime(50);
    shadowOf(handler.getLooper()).idle();

    assertThat(callback.messages).hasSize(3);
    assertThat(Iterables.getLast(callback.messages))
        .isEqualTo(new MessageData(/* what= */ 3, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));

    fakeClock.advanceTime(50);
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages).hasSize(4);
    assertThat(Iterables.getLast(callback.messages))
        .isEqualTo(new MessageData(/* what= */ 2, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));
  }

  @Test
  public void createHandler_sendMessageAtFrontOfQueue_sendsMessageFirst() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);

    handler.obtainMessage(/* what= */ 1).sendToTarget();
    handler.sendMessageAtFrontOfQueue(handler.obtainMessage(/* what= */ 2));
    handler.sendMessageAtFrontOfQueue(handler.obtainMessage(/* what= */ 3));
    handler.obtainMessage(/* what= */ 4).sendToTarget();
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages)
        .containsExactly(
            new MessageData(/* what= */ 3, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 2, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 1, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 4, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null))
        .inOrder();
  }

  @Test
  public void createHandler_postDelayed_triggersMessagesUpToCurrentTime() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(0);
    HandlerWrapper handler =
        fakeClock.createHandler(handlerThread.getLooper(), /* callback= */ null);

    TestRunnable[] testRunnables = {
      new TestRunnable(),
      new TestRunnable(),
      new TestRunnable(),
      new TestRunnable(),
      new TestRunnable()
    };
    handler.postDelayed(testRunnables[0], 0);
    handler.postDelayed(testRunnables[1], 100);
    handler.postDelayed(testRunnables[2], 200);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    assertTestRunnableStates(new boolean[] {true, false, false, false, false}, testRunnables);

    fakeClock.advanceTime(150);
    handler.postDelayed(testRunnables[3], 50);
    handler.postDelayed(testRunnables[4], 100);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    assertTestRunnableStates(new boolean[] {true, true, false, false, false}, testRunnables);

    fakeClock.advanceTime(50);
    shadowOf(handler.getLooper()).idle();
    assertTestRunnableStates(new boolean[] {true, true, true, true, false}, testRunnables);

    fakeClock.advanceTime(1000);
    shadowOf(handler.getLooper()).idle();
    assertTestRunnableStates(new boolean[] {true, true, true, true, true}, testRunnables);

    quitAndJoin(handlerThread);
  }

  @Test
  public void createHandler_removeMessages_removesMessages() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);
    TestCallback otherCallback = new TestCallback();
    HandlerWrapper otherHandler = fakeClock.createHandler(handlerThread.getLooper(), otherCallback);
    TestRunnable testRunnable1 = new TestRunnable();
    TestRunnable testRunnable2 = new TestRunnable();
    Object messageToken = new Object();
    handler.obtainMessage(/* what= */ 1, /* obj= */ messageToken).sendToTarget();
    handler.sendEmptyMessageDelayed(/* what= */ 2, /* delayMs= */ 50);
    handler.post(testRunnable1);
    handler.postDelayed(testRunnable2, /* delayMs= */ 25);
    handler.sendEmptyMessage(/* what= */ 3);
    otherHandler.sendEmptyMessage(/* what= */ 2);

    handler.removeMessages(/* what= */ 2);
    handler.removeCallbacksAndMessages(messageToken);
    fakeClock.advanceTime(50);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages)
        .containsExactly(
            new MessageData(/* what= */ 3, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));
    assertThat(testRunnable1.hasRun).isTrue();
    assertThat(testRunnable2.hasRun).isTrue();

    // Assert that message with same "what" on other handler wasn't removed.
    assertThat(otherCallback.messages)
        .containsExactly(
            new MessageData(/* what= */ 2, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));
  }

  @Test
  public void createHandler_removeCallbacks_removesRunnables() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);
    TestRunnable testRunnable1 = new TestRunnable();
    TestRunnable testRunnable2 = new TestRunnable();
    handler.sendEmptyMessage(/* what= */ 1);
    handler.sendEmptyMessageDelayed(/* what= */ 2, /* delayMs= */ 50);
    handler.post(testRunnable1);
    handler.postDelayed(testRunnable2, /* delayMs= */ 25);
    handler.postDelayed(testRunnable1, /* delayMs= */ 50);

    handler.removeCallbacks(testRunnable1);
    fakeClock.advanceTime(50);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages)
        .containsExactly(
            new MessageData(/* what= */ 1, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null),
            new MessageData(/* what= */ 2, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));
    assertThat(testRunnable2.hasRun).isTrue();
    assertThat(testRunnable1.hasRun).isFalse();
  }

  @Test
  public void createHandler_removeCallbacks_doesNotRemoveRunnablesFromOtherHandler()
      throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);
    TestCallback otherCallback = new TestCallback();
    HandlerWrapper otherHandler = fakeClock.createHandler(handlerThread.getLooper(), otherCallback);
    TestRunnable testRunnable = new TestRunnable();
    otherHandler.post(testRunnable);

    handler.removeCallbacks(testRunnable);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(testRunnable.hasRun).isTrue();
  }

  @Test
  public void createHandler_removeAllMessages_removesAllMessages() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestCallback callback = new TestCallback();
    HandlerWrapper handler = fakeClock.createHandler(handlerThread.getLooper(), callback);
    TestCallback otherCallback = new TestCallback();
    HandlerWrapper otherHandler = fakeClock.createHandler(handlerThread.getLooper(), otherCallback);
    TestRunnable testRunnable1 = new TestRunnable();
    TestRunnable testRunnable2 = new TestRunnable();
    Object messageToken = new Object();
    handler.obtainMessage(/* what= */ 1, /* obj= */ messageToken).sendToTarget();
    handler.sendEmptyMessageDelayed(/* what= */ 2, /* delayMs= */ 50);
    handler.post(testRunnable1);
    handler.postDelayed(testRunnable2, /* delayMs= */ 25);
    handler.sendEmptyMessage(/* what= */ 3);
    otherHandler.sendEmptyMessage(/* what= */ 1);

    handler.removeCallbacksAndMessages(/* token= */ null);
    fakeClock.advanceTime(50);
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(callback.messages).isEmpty();
    assertThat(testRunnable1.hasRun).isFalse();
    assertThat(testRunnable2.hasRun).isFalse();
    // Assert that message on other handler wasn't removed.
    assertThat(otherCallback.messages)
        .containsExactly(
            new MessageData(/* what= */ 1, /* arg1= */ 0, /* arg2= */ 0, /* obj= */ null));
  }

  @Test
  public void createHandler_withIsAutoAdvancing_advancesTimeToNextMessages() throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler =
        fakeClock.createHandler(handlerThread.getLooper(), /* callback= */ null);

    // Post a series of immediate and delayed messages.
    ArrayList<Long> clockTimes = new ArrayList<>();
    handler.post(
        () -> {
          handler.postDelayed(
              () -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 100);
          handler.postDelayed(() -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 50);
          handler.post(() -> clockTimes.add(fakeClock.elapsedRealtime()));
          handler.postDelayed(
              () -> {
                clockTimes.add(fakeClock.elapsedRealtime());
                handler.postDelayed(
                    () -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 50);
              },
              /* delayMs= */ 20);
        });
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(clockTimes).containsExactly(0L, 20L, 50L, 70L, 100L).inOrder();
  }

  @Test
  public void createHandler_withIsAutoAdvancing_triggersOnlyMessagesWithinDefinedMaxTimeDiff()
      throws Exception {
    HandlerThread handlerThread = new HandlerThread("FakeClockTest");
    handlerThread.start();
    FakeClock fakeClock =
        new FakeClock.Builder()
            .setInitialTimeMs(0)
            .setIsAutoAdvancing(true)
            .setMaxAutoAdvancingTimeDiffMs(400)
            .build();
    HandlerWrapper handler =
        fakeClock.createHandler(handlerThread.getLooper(), /* callback= */ null);

    // Post a series of immediate and delayed messages with one that is too far in the future.
    ArrayList<Long> clockTimes = new ArrayList<>();
    handler.post(
        () -> {
          handler.postDelayed(
              () -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 400);
          handler.postDelayed(
              () -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 801);
          handler.post(() -> clockTimes.add(fakeClock.elapsedRealtime()));
          handler.postDelayed(
              () -> clockTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 200);
        });
    ShadowLooper.idleMainLooper();
    shadowOf(handler.getLooper()).idle();
    quitAndJoin(handlerThread);

    assertThat(clockTimes).containsExactly(0L, 200L, 400L).inOrder();
  }

  @Test
  public void createHandler_multiThreadCommunication_deliversMessagesDeterministicallyInOrder()
      throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    ConditionVariable messagesFinished = new ConditionVariable();
    ArrayList<Integer> executionOrder = new ArrayList<>();
    handler1.post(
        () -> {
          executionOrder.add(1);
          handler2.post(() -> executionOrder.add(2));
          handler1.post(() -> executionOrder.add(3));
          handler2.post(
              () -> {
                executionOrder.add(4);
                handler2.post(() -> executionOrder.add(7));
                handler1.post(
                    () -> {
                      executionOrder.add(8);
                      messagesFinished.open();
                    });
              });
          handler2.post(() -> executionOrder.add(5));
          handler1.post(() -> executionOrder.add(6));
        });
    ShadowLooper.idleMainLooper();
    messagesFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionOrder).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder();
  }

  @Test
  public void createHandler_blockingThreadWithOnThreadBlocked_canBeUnblockedByOtherThread()
      throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    ConditionVariable testFinished = new ConditionVariable();
    ArrayList<Integer> executionOrder = new ArrayList<>();
    handler1.post(
        () -> {
          executionOrder.add(1);
          ConditionVariable blockingCondition = new ConditionVariable();
          handler2.postDelayed(
              () -> {
                executionOrder.add(2);
                blockingCondition.open();
              },
              /* delayMs= */ 50);
          handler1.post(
              () -> {
                executionOrder.add(4);
                testFinished.open();
              });
          fakeClock.onThreadBlocked();
          try {
            blockingCondition.block();
          } catch (InterruptedException e) {
            // Ignore.
          }
          executionOrder.add(3);
        });
    ShadowLooper.idleMainLooper();
    testFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionOrder).containsExactly(1, 2, 3, 4).inOrder();
  }

  @Test
  public void
      createHandler_blockingThreadUntilProgressOnLooperWithOnThreadBlocked_canBeUnblockedByOtherThread()
          throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    ConditionVariable testFinished = new ConditionVariable();
    ArrayList<Integer> executionOrder = new ArrayList<>();
    handler1.post(
        () -> {
          executionOrder.add(1);
          ConditionVariable blockingCondition = new ConditionVariable();
          handler2.postDelayed(
              () -> {
                executionOrder.add(2);
                ThreadTestUtil.unblockThreadsWaitingForProgressOnCurrentLooper();
              },
              /* delayMs= */ 100);
          handler1.post(
              () -> {
                executionOrder.add(4);
                testFinished.open();
              });
          ThreadTestUtil.registerThreadIsBlockedUntilProgressOnLooper(
              blockingCondition, handlerThread2.getLooper());
          fakeClock.onThreadBlocked();
          try {
            blockingCondition.block();
          } catch (InterruptedException e) {
            // Ignore.
          }
          executionOrder.add(3);
        });
    ShadowLooper.idleMainLooper();
    testFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionOrder).containsExactly(1, 2, 3, 4).inOrder();
  }

  @Test
  public void createHandler_blockingDeadlock_unblocksItself() throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    ConditionVariable testFinished = new ConditionVariable();
    ArrayList<Integer> executionOrder = new ArrayList<>();

    handler1.post(
        () -> {
          executionOrder.add(1);
          ConditionVariable deadlockCondition1 = new ConditionVariable();
          ConditionVariable deadlockCondition2 = new ConditionVariable();
          handler2.postDelayed(
              () -> {
                executionOrder.add(2);
                fakeClock.onThreadBlocked();
                try {
                  deadlockCondition2.block();
                } catch (InterruptedException e) {
                  // Ignore.
                }
              },
              /* delayMs= */ 100);
          handler1.post(
              () -> {
                executionOrder.add(4);
                testFinished.open();
              });
          ThreadTestUtil.registerThreadIsBlockedUntilProgressOnLooper(
              deadlockCondition1, handlerThread2.getLooper());
          fakeClock.onThreadBlocked();
          try {
            deadlockCondition1.block();
          } catch (InterruptedException e) {
            // Ignore.
          }
          executionOrder.add(3);
          deadlockCondition2.open();
        });
    ShadowLooper.idleMainLooper();
    testFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionOrder).containsExactly(1, 2, 3, 4).inOrder();
  }

  @Test
  public void createHandler_messageOnDeadThread_doesNotBlockExecution() throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    quitAndJoin(handlerThread1);

    ConditionVariable messagesFinished = new ConditionVariable();
    AtomicBoolean messageOnDeadThreadExecuted = new AtomicBoolean();
    handler1.post(() -> messageOnDeadThreadExecuted.set(true));
    handler2.post(messagesFinished::open);
    ShadowLooper.idleMainLooper();
    messagesFinished.block();
    quitAndJoin(handlerThread2);

    assertThat(messageOnDeadThreadExecuted.get()).isFalse();
  }

  @Test
  public void createHandler_idlingBackgroundThreadWhileTrackingBlockedThreads_doesNotDeadlock()
      throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);

    ArrayList<Integer> executionOrder = new ArrayList<>();
    handler1.post(
        () -> {
          executionOrder.add(1);
          ConditionVariable blockingCondition = new ConditionVariable(fakeClock);
          handler2.postDelayed(
              () -> {
                executionOrder.add(2);
                blockingCondition.open();
              },
              /* delayMs= */ 50);
          handler1.post(() -> executionOrder.add(4));
          try {
            blockingCondition.block();
          } catch (InterruptedException e) {
            // Ignore.
          }
          executionOrder.add(3);
        });
    ShadowLooper.idleMainLooper();
    shadowOf(handler1.getLooper()).idle();
    shadowOf(handler2.getLooper()).idle();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionOrder).containsExactly(1, 2, 3, 4).inOrder();
  }

  @Test
  public void espressoViewInteraction_doesNotHandleDelayedPendingMessages() {
    try (ActivityController<TestActivity> activityController =
        Robolectric.buildActivity(TestActivity.class)) {
      TestActivity activity = activityController.setup().get();
      FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
      AtomicBoolean delayedChange = new AtomicBoolean();
      fakeClock
          .createHandler(Looper.myLooper(), /* callback= */ null)
          .postDelayed(() -> delayedChange.set(true), /* delayMs= */ 50);

      onView(equalTo(activity.button)).perform(click());

      assertThat(delayedChange.get()).isFalse();

      // Verify test setup that the delayed message gets executed with manually triggered progress.
      ShadowLooper.runMainLooperToNextTask();
      assertThat(delayedChange.get()).isTrue();
    }
  }

  @Test
  public void backgroundLooperQuit_doesNotStallMainLooper() throws Exception {
    HandlerThread handlerThread = new HandlerThread("BackgroundThread");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper backgroundHandler =
        fakeClock.createHandler(handlerThread.getLooper(), /* callback= */ null);
    HandlerWrapper mainHandler =
        fakeClock.createHandler(Looper.getMainLooper(), /* callback= */ null);
    AtomicBoolean mainMessageRun = new AtomicBoolean();

    // 1. Post a quit task to the background handler to initiate self-quit.
    backgroundHandler.post(handlerThread::quit);

    // 2. Post the target main thread task, which is blocked by the active background looper lock.
    mainHandler.post(() -> mainMessageRun.set(true));

    // 3. Idle the background looper to execute the quit task.
    shadowOf(handlerThread.getLooper()).idle();

    // 4. Wait for the background HandlerThread to terminate.
    long timeoutMs = System.currentTimeMillis() + 5000;
    while (handlerThread.isAlive()) {
      if (System.currentTimeMillis() > timeoutMs) {
        throw new AssertionError("Timed out waiting for background thread to die");
      }
      ShadowLooper.idleMainLooper();
      Thread.sleep(10);
    }

    // 5. Trigger the now unblocked message on the main thread.
    ShadowLooper.idleMainLooper();

    assertThat(mainMessageRun.get()).isTrue();
  }

  @Test(timeout = 1000)
  public void postFromQuittingExternalLooper_doesNotStallMainLooper() throws Exception {
    HandlerThread handlerThread = new HandlerThread("ExternalBackgroundThread");
    handlerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper mainHandler =
        fakeClock.createHandler(Looper.getMainLooper(), /* callback= */ null);
    Handler rawBackgroundHandler = new Handler(handlerThread.getLooper());
    AtomicBoolean mainMessageRun = new AtomicBoolean();
    ConditionVariable backgroundTaskFinished = new ConditionVariable();

    rawBackgroundHandler.post(
        () -> {
          handlerThread.quit();
          mainHandler.post(() -> mainMessageRun.set(true));
          backgroundTaskFinished.open();
        });

    backgroundTaskFinished.block();
    quitAndJoin(handlerThread);

    // Trigger the message on the main thread. If the clock is locked up, this will timeout or do
    // nothing.
    ShadowLooper.idleMainLooper();

    assertThat(mainMessageRun.get()).isTrue();
  }

  @Test
  public void onThreadBlocked_fromNonActiveLooperWhileBackgroundMessageRunning_doesNotAdvanceTime()
      throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest1");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest2");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);
    ConditionVariable backgroundMessageStarted = new ConditionVariable();
    ConditionVariable allowBackgroundMessageToFinish = new ConditionVariable();
    ConditionVariable allMessagesFinished = new ConditionVariable();
    List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());
    handler2.postDelayed(
        () -> {
          executionTimes.add(fakeClock.elapsedRealtime());
          allMessagesFinished.open();
        },
        /* delayMs= */ 500);
    handler1.post(
        () -> {
          backgroundMessageStarted.open();
          allowBackgroundMessageToFinish.blockUninterruptible();
          executionTimes.add(fakeClock.elapsedRealtime());
          handler1.postDelayed(
              () -> executionTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 10);
        });
    ShadowLooper.idleMainLooper();
    backgroundMessageStarted.block();

    // Call onThreadBlocked() from the main looper while handlerThread1 is mid-message.
    fakeClock.onThreadBlocked();
    allowBackgroundMessageToFinish.open();
    allMessagesFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionTimes).containsExactly(0L, 10L, 500L).inOrder();
  }

  @Test
  public void
      onThreadBlocked_fromNonActiveLooperWhileExternalBackgroundMessageRunning_doesNotAdvanceTime()
          throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("FakeClockTest1");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("FakeClockTest2");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ null);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);
    Handler rawHandler1 = new Handler(handlerThread1.getLooper());
    ConditionVariable backgroundMessageStarted = new ConditionVariable();
    ConditionVariable allowBackgroundMessageToFinish = new ConditionVariable();
    ConditionVariable allMessagesFinished = new ConditionVariable();
    List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());
    rawHandler1.post(
        () -> {
          handler2.postDelayed(
              () -> {
                executionTimes.add(fakeClock.elapsedRealtime());
                allMessagesFinished.open();
              },
              /* delayMs= */ 500);
          handler1.postDelayed(
              () -> executionTimes.add(fakeClock.elapsedRealtime()), /* delayMs= */ 10);
          backgroundMessageStarted.open();
          allowBackgroundMessageToFinish.blockUninterruptible();
          executionTimes.add(fakeClock.elapsedRealtime());
        });
    backgroundMessageStarted.block();

    // Call onThreadBlocked() from the main looper while handlerThread1 is mid-message after
    // claiming activeMessageLooper via addPendingHandlerMessage().
    fakeClock.onThreadBlocked();
    allowBackgroundMessageToFinish.open();
    allMessagesFinished.block();
    quitAndJoin(handlerThread1, handlerThread2);

    assertThat(executionTimes).containsExactly(0L, 10L, 500L).inOrder();
  }

  @Test(timeout = 5000)
  public void
      onThreadBlocked_fromSecondaryLooperWhileMainLooperMessagePending_triggersBackgroundMessage()
          throws Exception {
    HandlerThread callerThread = new HandlerThread("CallerLooper");
    callerThread.start();
    HandlerThread workerThread = new HandlerThread("WorkerLooper");
    workerThread.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper mainHandler =
        fakeClock.createHandler(Looper.getMainLooper(), /* callback= */ null);
    HandlerWrapper workerHandler =
        fakeClock.createHandler(workerThread.getLooper(), /* callback= */ null);
    Handler rawCallerHandler = new Handler(callerThread.getLooper());
    AtomicBoolean mainMessageRun = new AtomicBoolean();
    AtomicBoolean callerUnblocked = new AtomicBoolean();
    ConditionVariable callerFinished = new ConditionVariable();

    // Queue a FakeClock message on the main looper from a background looper and idle callerThread's
    // onMessageHandled callback so mainLooper becomes activeMessageLooper (queued in Robolectric's
    // paused main MessageQueue, not yet executing).
    ConditionVariable mainMessageQueued = new ConditionVariable();
    rawCallerHandler.post(
        () -> {
          mainHandler.post(() -> mainMessageRun.set(true));
          mainMessageQueued.open();
        });
    mainMessageQueued.block();
    shadowOf(callerThread.getLooper()).idle();

    // Now post a task on callerThread that posts to workerHandler and blocks waiting for it,
    // without idling the main looper first (simulating ExoPlayer.release() from a secondary
    // looper while a main-looper listener notification is still pending).
    rawCallerHandler.post(
        () -> {
          ConditionVariable workerDone = new ConditionVariable(fakeClock);
          workerHandler.post(workerDone::open);
          workerDone.blockUninterruptible();
          callerUnblocked.set(true);
          callerFinished.open();
        });
    callerFinished.block();
    quitAndJoin(callerThread, workerThread);

    assertThat(callerUnblocked.get()).isTrue();
    assertThat(mainMessageRun.get()).isFalse();

    ShadowLooper.idleMainLooper();

    assertThat(mainMessageRun.get()).isTrue();
  }

  @Test(timeout = 5000)
  public void
      onThreadBlocked_fromNonActiveLooperAfterActiveLooperQuits_drainsDeadLooperAndTriggersNextMessage()
          throws Exception {
    HandlerThread handlerThread1 = new HandlerThread("HandlerThread1");
    handlerThread1.start();
    HandlerThread handlerThread2 = new HandlerThread("HandlerThread2");
    handlerThread2.start();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    HandlerWrapper handler1 =
        fakeClock.createHandler(handlerThread1.getLooper(), /* callback= */ msg -> true);
    HandlerWrapper handler2 =
        fakeClock.createHandler(handlerThread2.getLooper(), /* callback= */ null);
    Handler rawHandler1 = new Handler(handlerThread1.getLooper());
    ConditionVariable deadLooperPrepared = new ConditionVariable();
    ConditionVariable handler2Done = new ConditionVariable();
    rawHandler1.post(
        () -> {
          // Claim activeMessageLooper on handlerThread1, queue a message on handler1 and handler2,
          // and then quit handlerThread1 before its onMessageHandled callback can run.
          handler1.sendEmptyMessage(1);
          handler2.post(handler2Done::open);
          handlerThread1.quit();
          deadLooperPrepared.open();
        });
    deadLooperPrepared.block();
    handlerThread1.join();

    fakeClock.onThreadBlocked();
    handler2Done.block();
    quitAndJoin(handlerThread2);

    assertThat(handler1.hasMessages(1)).isFalse();
  }

  private static class TestActivity extends Activity {

    public Button button;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      button = new Button(this);
      setContentView(button);
    }
  }

  private static void assertTestRunnableStates(boolean[] states, TestRunnable[] testRunnables) {
    for (int i = 0; i < testRunnables.length; i++) {
      assertThat(testRunnables[i].hasRun).isEqualTo(states[i]);
    }
  }

  private static void quitAndJoin(HandlerThread... handlerThreads) throws InterruptedException {
    for (HandlerThread handlerThread : handlerThreads) {
      handlerThread.quitSafely();
    }
    for (HandlerThread handlerThread : handlerThreads) {
      handlerThread.join();
    }
  }

  private static final class TestRunnable implements Runnable {

    public boolean hasRun;

    @Override
    public void run() {
      hasRun = true;
    }
  }

  private static final class TestCallback implements Handler.Callback {

    public final List<MessageData> messages;

    public TestCallback() {
      messages = new ArrayList<>();
    }

    @Override
    public boolean handleMessage(Message msg) {
      messages.add(new MessageData(msg.what, msg.arg1, msg.arg2, msg.obj));
      return true;
    }
  }

  private static final class MessageData {

    public final int what;
    public final int arg1;
    public final int arg2;
    public final @Nullable Object obj;

    public MessageData(int what, int arg1, int arg2, @Nullable Object obj) {
      this.what = what;
      this.arg1 = arg1;
      this.arg2 = arg2;
      this.obj = obj;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof MessageData)) {
        return false;
      }
      MessageData that = (MessageData) o;
      return what == that.what
          && arg1 == that.arg1
          && arg2 == that.arg2
          && Objects.equals(obj, that.obj);
    }

    @Override
    public int hashCode() {
      return Objects.hash(what, arg1, arg2, obj);
    }
  }
}
