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
package androidx.media3.effect;

import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil.GlException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Composites a list of {@link GlTextureFrame GlTextureFrames}, and outputs to a downstream {@link
 * GlTextureFrameConsumer}.
 *
 * <p>Methods in this class must be called on a GL thread.
 */
/* package */ interface GlTextureFrameCompositor extends AutoCloseable {

  /** Draws multiple input frames onto one output texture using a GL program. */
  interface CompositorGlProgram {
    /**
     * Draws the input frames onto the output texture.
     *
     * @param framesToComposite The {@linkplain GlCompositionFrame input frames} to composite.
     * @param outputTexture The {@link GlTextureInfo} to draw onto.
     * @throws VideoFrameProcessingException If an error occurs during frame processing.
     */
    void drawFrame(List<GlCompositionFrame> framesToComposite, GlTextureInfo outputTexture)
        throws GlException, VideoFrameProcessingException;

    /** Releases all associated resources. */
    void release() throws VideoFrameProcessingException;
  }

  /**
   * Attempts to queue frames for compositing.
   *
   * <p>If the compositor is at capacity, it returns {@code false} and the {@code wakeupListener}
   * will be invoked on the {@code listenerExecutor} when capacity becomes available.
   *
   * <p>If {@code frames} contains only a single frame, the compositor is bypassed and the frame is
   * passed directly downstream.
   *
   * @param frames The input frames to composite.
   * @param listenerExecutor The executor to run the {@code wakeupListener} on.
   * @param wakeupListener The callback to run when capacity is freed.
   * @return {@code true} if queued successfully, {@code false} otherwise.
   */
  // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
  boolean queue(List<GlTextureFrame> frames, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException;

  /** Notifies the current stream has ended. */
  void signalEndOfStream();

  @Override
  void close() throws VideoFrameProcessingException;
}
