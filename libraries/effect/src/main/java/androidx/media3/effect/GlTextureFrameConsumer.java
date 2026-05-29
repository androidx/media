/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.FrameProcessor;
import java.util.concurrent.Executor;

/**
 * Consumes a {@link GlTextureFrame}.
 *
 * <p>This interface aims at resembling {@link FrameProcessor}.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
public interface GlTextureFrameConsumer extends AutoCloseable {

  /** Allowing outputting {@link GlTextureFrame} from the {@link GlTextureFrameConsumer}. */
  interface GlTextureFrameProcessor extends GlTextureFrameConsumer {
    /** Sets the {@link GlTextureFrameConsumer} to which this processor outputs. */
    void setOutput(GlTextureFrameConsumer downstream);
  }

  // TODO: b/505721737 - Consider extending to multiple frame input for compositor implementation.
  // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
  /**
   * Attempts to queue a frame for processing.
   *
   * <p>If the consumer is at capacity, it returns {@code false} and the {@code wakeupListener} will
   * be invoked on the {@code listenerExecutor} when capacity becomes available.
   *
   * @param frame The input frame to process.
   * @param listenerExecutor The {@link Executor} to run the {@code wakeupListener}. Must not
   *     execute synchronously (e.g., must not be a direct executor) to avoid re-entrant calls to
   *     {@code queue()}.
   * @param wakeupListener The callback invoked when capacity is freed.
   * @return true if queued successfully, false if at capacity.
   */
  boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException;

  /** Notifies the current stream has ended. */
  void signalEndOfStream();

  @Override
  void close() throws VideoFrameProcessingException;
}
