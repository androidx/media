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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.NullableType;
import androidx.media3.effect.FrameProcessorUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/** Utility class containing fakes for GlFrameProcessor testing. */
@RequiresApi(26)
public final class GlFrameProcessorTestUtil {
  /** Releases the GL resources on the GL thread, returns the thrown exception during release. */
  @Nullable
  public static Exception closeTestingGlResources(
      ExecutorService glExecutorService, GlObjectsProvider glObjectsProvider, long timeoutMs) {
    AtomicReference<@NullableType Exception> releaseException = new AtomicReference<>();
    try {
      glExecutorService
          .submit(
              () -> {
                try {
                  FrameProcessorUtils.releaseOpenGl(checkNotNull(glObjectsProvider));
                } catch (GlException e) {
                  releaseException.set(e);
                }
              })
          .get(timeoutMs, MILLISECONDS);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      if (releaseException.get() != null) {
        releaseException.get().addSuppressed(e);
      } else {
        releaseException.set(e);
      }
    }
    if (releaseException.get() != null) {
      return releaseException.get();
    }
    return null;
  }

  private GlFrameProcessorTestUtil() {}
}
