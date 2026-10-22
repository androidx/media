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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.ImagePlanesFrame;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Converts a {@link Frame} to a {@link GlTextureFrame}.
 *
 * <p>Lazily creates and routes to a {@link HardwareBufferToGlTextureConverter} (for {@link
 * HardwareBufferFrame} on API 26+) or an {@link ImagePlanesToGlTextureConverter} (for {@link
 * ImagePlanesFrame}).
 */
/* package */ final class DefaultFrameToGlTextureConverter implements FrameToGlTextureConverter {

  private final Context context;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final ColorInfo outputColorInfo;
  private final Consumer<VideoFrameProcessingException> errorConsumer;

  private @Nullable FrameToGlTextureConverter hardwareBufferConverter;
  private @Nullable FrameToGlTextureConverter imagePlanesConverter;
  private boolean isClosed;

  /** Creates an instance. */
  /* package */ DefaultFrameToGlTextureConverter(
      Context context,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      ColorInfo outputColorInfo,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.context = context;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.outputColorInfo = outputColorInfo;
    this.errorConsumer = errorConsumer;
  }

  @Override
  public @Nullable GlTextureFrame convert(
      Frame frame, Executor glExecutor, Executor listenerExecutor, FrameProcessor.Listener listener)
      throws VideoFrameProcessingException {
    if (isClosed) {
      return null;
    }
    if (SDK_INT >= 26 && frame instanceof HardwareBufferFrame) {
      if (hardwareBufferConverter == null) {
        hardwareBufferConverter =
            new HardwareBufferToGlTextureConverter(
                context, hardwareBufferJniWrapper, outputColorInfo, errorConsumer);
      }
      return hardwareBufferConverter.convert(frame, glExecutor, listenerExecutor, listener);
    }
    if (frame instanceof ImagePlanesFrame) {
      if (imagePlanesConverter == null) {
        imagePlanesConverter =
            new ImagePlanesToGlTextureConverter(
                context, hardwareBufferJniWrapper, outputColorInfo, errorConsumer);
      }
      return imagePlanesConverter.convert(frame, glExecutor, listenerExecutor, listener);
    }
    throw new IllegalArgumentException("Unsupported frame type on API " + SDK_INT + ": " + frame);
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    if (isClosed) {
      return;
    }
    try {
      runAllAndAccumulateExceptions(
          () -> {
            if (hardwareBufferConverter != null) {
              hardwareBufferConverter.close();
            }
          },
          () -> {
            if (imagePlanesConverter != null) {
              imagePlanesConverter.close();
            }
          });
    } finally {
      hardwareBufferConverter = null;
      imagePlanesConverter = null;
      isClosed = true;
    }
  }
}
