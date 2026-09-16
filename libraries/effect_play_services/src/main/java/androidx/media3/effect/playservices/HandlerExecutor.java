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
package androidx.media3.effect.playservices;

import android.os.Handler;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import java.util.concurrent.Executor;

/**
 * An {@link Executor} that posts commands to a {@link Handler} and forwards uncaught {@link
 * RuntimeException}s to an error handler.
 */
/* package */ final class HandlerExecutor implements Executor {
  private final Handler handler;
  private final Consumer<VideoFrameProcessingException> errorHandler;

  /* package */ HandlerExecutor(
      Handler handler, Consumer<VideoFrameProcessingException> errorHandler) {
    this.handler = handler;
    this.errorHandler = errorHandler;
  }

  @Override
  public void execute(Runnable command) {
    handler.post(
        () -> {
          try {
            command.run();
          } catch (RuntimeException e) {
            errorHandler.accept(VideoFrameProcessingException.from(e));
          }
        });
  }
}
