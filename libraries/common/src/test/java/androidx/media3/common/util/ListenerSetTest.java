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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.FlagSet;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.robolectric.shadows.ShadowLooper;

/** Unit test for {@link ListenerSet}. */
@RunWith(AndroidJUnit4.class)
public class ListenerSetTest {

  private static final int EVENT_ID_1 = 0;
  private static final int EVENT_ID_2 = 1;
  private static final int EVENT_ID_3 = 2;

  @Test
  public void queueEvent_withoutFlush_sendsNoEvents() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener = mock(TestListener.class);
    listenerSet.add(listener);

    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    verifyNoMoreInteractions(listener);
  }

  @Test
  public void flushEvents_sendsPreviouslyQueuedEventsToAllListeners() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener1 = mock(TestListener.class);
    TestListener listener2 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2);
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void flushEvents_recursive_sendsEventsInCorrectOrder() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    // Listener1 sends callback3 recursively when receiving callback1.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback1() {
                listenerSet.queueEvent(EVENT_ID_3, TestListener::callback3);
                listenerSet.flushEvents();
              }
            });
    TestListener listener2 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2);
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).callback3();
    inOrder.verify(listener2).callback3();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2, EVENT_ID_3));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2, EVENT_ID_3));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      flushEvents_withMultipleMessageQueueIterations_sendsIterationFinishedEventPerIteration() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    // Listener1 sends callback1 recursively when receiving callback3.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback3() {
                listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
              }
            });
    TestListener listener2 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Iteration with single flush.
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    // Iteration with multiple flushes.
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    listenerSet.flushEvents();
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    // Iteration with recursive call.
    listenerSet.sendEvent(EVENT_ID_3, TestListener::callback3);
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2);
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verify(listener1).callback3();
    inOrder.verify(listener2).callback3();
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_3));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_3));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void flushEvents_calledFromIterationFinishedCallback_restartsIterationFinishedEvents() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    // Listener2 sends callback1 recursively when receiving the iteration finished event.
    TestListener listener2 =
        spy(
            new TestListener() {
              boolean eventSent;

              @Override
              public void iterationFinished(FlagSet flags) {
                if (!eventSent) {
                  listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
                  eventSent = true;
                }
              }
            });
    TestListener listener1 = mock(TestListener.class);
    TestListener listener3 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);
    listenerSet.add(listener3);

    listenerSet.sendEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2, listener3);
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener3).callback2();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener2).callback1();
    inOrder.verify(listener3).callback1();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1));
    inOrder.verify(listener3).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void flushEvents_withUnsetEventFlag_doesNotThrow() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);

    listenerSet.queueEvent(/* eventFlag= */ C.INDEX_UNSET, TestListener::callback1);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    // Asserts that negative event flag (INDEX_UNSET) can be used without throwing.
  }

  @Test
  public void add_withRecursion_onlyReceivesUpdatesForFutureEvents() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener2 = mock(TestListener.class);
    // Listener1 adds listener2 recursively.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback1() {
                listenerSet.add(listener2);
              }
            });

    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.add(listener1);
    // This should add listener2, but the event should not be received yet as it happened before
    // listener2 was added.
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.sendEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2);
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void add_withQueueing_onlyReceivesUpdatesForFutureEvents() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener1 = mock(TestListener.class);
    TestListener listener2 = mock(TestListener.class);

    // This event is flushed after listener2 was added, but shouldn't be sent to listener2 because
    // the event itself occurred before the listener was added.
    listenerSet.add(listener1);
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.add(listener2);
    listenerSet.queueEvent(EVENT_ID_2, TestListener::callback2);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    InOrder inOrder = Mockito.inOrder(listener1, listener2);
    inOrder.verify(listener1).callback1();
    inOrder.verify(listener1).callback2();
    inOrder.verify(listener2).callback2();
    inOrder.verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1, EVENT_ID_2));
    inOrder.verify(listener2).iterationFinished(createFlagSet(EVENT_ID_2));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void remove_withRecursion_stopsReceivingEventsImmediately() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener2 = mock(TestListener.class);
    // Listener1 removes listener2 recursively.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback1() {
                listenerSet.remove(listener2);
              }
            });
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Listener2 shouldn't even get this event as it's removed before the event can be invoked.
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.remove(listener1);
    listenerSet.sendEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    verify(listener1).callback1();
    verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1));
    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void remove_withQueueing_stopsReceivingEventsImmediately() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener1 = mock(TestListener.class);
    TestListener listener2 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Listener1 shouldn't even get this event as it's removed before the event can be invoked.
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.remove(listener1);
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    verify(listener2, times(2)).callback1();
    verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1));
    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void clear_withRecursion_stopsReceivingEventsImmediately() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener2 = mock(TestListener.class);
    // Listener1 clears the set from within the callback.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback1() {
                listenerSet.clear();
              }
            });
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Listener2 shouldn't even get this event as the set was cleared before the event can be
    // invoked.
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.sendEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    verify(listener1).callback1();
    // Listener1 should receive IterationFinishedEvent as the first event was invoked before the
    // set was cleared.
    verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1));
    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void clear_withQueueing_stopsReceivingEventsImmediately() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener1 = mock(TestListener.class);
    TestListener listener2 = mock(TestListener.class);
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Both Listener1 and Listener2 shouldn't even get this event as they are cleared before the
    // event can be invoked.
    listenerSet.queueEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.clear();
    listenerSet.flushEvents();
    ShadowLooper.idleMainLooper();

    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void release_stopsForwardingEventsImmediately() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener2 = mock(TestListener.class);
    // Listener1 releases the set from within the callback.
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void callback1() {
                listenerSet.release();
              }
            });
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Listener2 shouldn't even get this event as it's released before the event can be invoked.
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.sendEvent(EVENT_ID_2, TestListener::callback2);
    ShadowLooper.idleMainLooper();

    verify(listener1).callback1();
    verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1));
    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void remove_withRecursionDuringRelease_callsAllPendingEventsAndIterationFinished() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener2 = mock(TestListener.class);
    // Listener1 removes Listener2 from within the callback triggered by release().
    TestListener listener1 =
        spy(
            new TestListener() {
              @Override
              public void iterationFinished(FlagSet flags) {
                listenerSet.remove(listener2);
              }
            });
    listenerSet.add(listener1);
    listenerSet.add(listener2);

    // Listener2 should still get the event and iterationFinished callback because it was triggered
    // before the release and the listener removal.
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);
    listenerSet.release();
    ShadowLooper.idleMainLooper();

    verify(listener1).callback1();
    verify(listener1).iterationFinished(createFlagSet(EVENT_ID_1));
    verify(listener2).callback1();
    verify(listener2).iterationFinished(createFlagSet(EVENT_ID_1));
    verifyNoMoreInteractions(listener1, listener2);
  }

  @Test
  public void release_preventsRegisteringNewListeners() {
    ListenerSet<TestListener> listenerSet =
        new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, TestListener::iterationFinished);
    TestListener listener = mock(TestListener.class);

    listenerSet.release();
    listenerSet.add(listener);
    listenerSet.sendEvent(EVENT_ID_1, TestListener::callback1);

    verify(listener, never()).callback1();
  }

  private interface TestListener {
    default void callback1() {}

    default void callback2() {}

    default void callback3() {}

    default void iterationFinished(FlagSet flags) {}
  }

  private static FlagSet createFlagSet(int... flagValues) {
    FlagSet.Builder flagsBuilder = new FlagSet.Builder();
    for (int value : flagValues) {
      flagsBuilder.add(value);
    }
    return flagsBuilder.build();
  }
}
