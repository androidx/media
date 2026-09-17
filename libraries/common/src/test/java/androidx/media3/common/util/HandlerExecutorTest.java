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
package androidx.media3.common.util;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.HandlerThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HandlerExecutor}. */
@RunWith(AndroidJUnit4.class)
public final class HandlerExecutorTest {

  private HandlerThread handlerThread;
  private Handler handler;

  @Before
  public void setUp() {
    handlerThread = new HandlerThread("HandlerExecutorTest");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  @After
  public void tearDown() {
    handlerThread.quit();
  }

  @Test
  public void execute_withHandler_runsCommandOnHandlerThread() {
    AtomicReference<Thread> executionThread = new AtomicReference<>();
    AtomicReference<RuntimeException> caughtError = new AtomicReference<>();
    HandlerExecutor executor = new HandlerExecutor(handler, caughtError::set);

    executor.execute(() -> executionThread.set(Thread.currentThread()));
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(executionThread.get()).isEqualTo(handlerThread);
    assertThat(caughtError.get()).isNull();
  }

  @Test
  public void execute_withHandlerWrapper_runsCommandOnHandlerThread() {
    HandlerWrapper handlerWrapper =
        Clock.DEFAULT.createHandler(handlerThread.getLooper(), /* callback= */ null);
    AtomicBoolean ran = new AtomicBoolean();
    AtomicReference<RuntimeException> caughtError = new AtomicReference<>();
    HandlerExecutor executor = new HandlerExecutor(handlerWrapper, caughtError::set);

    executor.execute(() -> ran.set(true));
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(ran.get()).isTrue();
    assertThat(caughtError.get()).isNull();
  }

  @Test
  public void execute_whenCommandThrowsRuntimeException_propagatesToListenerWithoutWrapping() {
    IllegalStateException expectedException = new IllegalStateException("Test failure");
    AtomicReference<RuntimeException> caughtError = new AtomicReference<>();
    AtomicReference<Thread> errorCallbackThread = new AtomicReference<>();
    HandlerExecutor executor =
        new HandlerExecutor(
            handler,
            e -> {
              caughtError.set(e);
              errorCallbackThread.set(Thread.currentThread());
            });

    executor.execute(
        () -> {
          throw expectedException;
        });
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(caughtError.get()).isSameInstanceAs(expectedException);
    assertThat(errorCallbackThread.get()).isEqualTo(handlerThread);
  }
}
