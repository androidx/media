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

import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor.Listener;
import java.util.concurrent.Executor;

/** Converts from {@link Frame} to {@link GlTextureFrame}. */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
/* package */ interface FrameToGlTextureConverter extends AutoCloseable {

  /** A factory for {@link FrameToGlTextureConverter} instances. */
  interface Factory {
    FrameToGlTextureConverter create(
        ColorInfo outputColorInfo, Consumer<VideoFrameProcessingException> errorConsumer);
  }

  // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
  /**
   * Converts from {@link Frame} to {@link GlTextureFrame}, or returns {@code null} if this
   * converter is closed.
   *
   * <p>The returned {@link GlTextureFrame}'s texture is in standard OpenGL coordinate space
   * (upright, Y-up, with origin at bottom-left of the image).
   */
  @Nullable
  GlTextureFrame convert(
      Frame frame, Executor glExecutor, Executor listenerExecutor, Listener listener)
      throws VideoFrameProcessingException;

  /**
   * Releases the resources for a converted {@link Frame}.
   *
   * <p>Don't call this method if the {@linkplain #convert converted} {@link GlTextureFrame} is
   * accepted by a downstream {@link GlTextureFrameConsumer}.
   *
   * <p>This releases associated resources without notifying the {@link Listener#onFrameProcessed}
   * that was passed in via {@link #convert}.
   *
   * <p>This is used when a converted frame is rejected by downstream pipeline consumers, preserving
   * the underlying frame for subsequent queue retries.
   */
  void releaseGlResources(Frame frame) throws VideoFrameProcessingException;

  @Override
  void close() throws VideoFrameProcessingException;
}
