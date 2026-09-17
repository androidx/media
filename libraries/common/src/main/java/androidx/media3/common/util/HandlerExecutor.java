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

import android.os.Handler;
import java.util.concurrent.Executor;

/**
 * An {@link Executor} that posts commands to a {@link Handler} or {@link HandlerWrapper} and
 * forwards uncaught {@link RuntimeException runtime exceptions} to a {@link Listener}.
 */
@UnstableApi
public final class HandlerExecutor implements Executor {

  /** Listener for uncaught exceptions thrown during command execution. */
  public interface Listener {
    /**
     * Called when executing a {@link Runnable} throws an uncaught {@link RuntimeException}.
     *
     * <p>This callback is invoked on the {@link Handler} thread where the command ran.
     *
     * @param exception The uncaught {@link RuntimeException}.
     */
    void onError(RuntimeException exception);
  }

  private final HandlerWrapper handler;
  private final Listener listener;

  /**
   * Creates an instance that posts commands to the specified {@link HandlerWrapper}.
   *
   * @param handler The {@link HandlerWrapper} to post commands to.
   * @param listener The {@link Listener} to notify of uncaught {@link RuntimeException runtime
   *     exceptions}.
   */
  public HandlerExecutor(HandlerWrapper handler, Listener listener) {
    this.handler = handler;
    this.listener = listener;
  }

  /**
   * Creates an instance that posts commands to the specified {@link Handler}.
   *
   * @param handler The {@link Handler} to post commands to.
   * @param listener The {@link Listener} to notify of uncaught {@link RuntimeException runtime
   *     exceptions}.
   */
  public HandlerExecutor(Handler handler, Listener listener) {
    this(new SystemHandlerWrapper(handler), listener);
  }

  @Override
  public void execute(Runnable command) {
    handler.post(
        () -> {
          try {
            command.run();
          } catch (RuntimeException e) {
            listener.onError(e);
          }
        });
  }
}
