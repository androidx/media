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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.FrameProcessorUtils;
import androidx.media3.effect.ndk.HardwareBufferJni;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.rules.ExternalResource;

/**
 * A JUnit rule that manages frame processor GL resources for testing.
 *
 * <p>GL resources (executor service and objects provider) are initialized lazily on the first
 * request and automatically released after each test.
 */
public final class GlFrameProcessorTestRule extends ExternalResource {
  private final long timeoutMs;
  // Guards lazy setup of GL resources against concurrent initialization across threads.
  private final Object setupLock = new Object();
  @Nullable private volatile ListeningExecutorService glExecutorService;
  @Nullable private volatile GlObjectsProvider glObjectsProvider;

  public GlFrameProcessorTestRule(long timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  @Override
  protected void after() {
    if (SDK_INT >= 26 && glExecutorService != null) {
      @Nullable Exception releasingException = null;
      try {
        if (glObjectsProvider != null) {
          releasingException =
              GlFrameProcessorTestUtil.closeTestingGlResources(
                  glExecutorService, glObjectsProvider, timeoutMs);
        }
      } finally {
        FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
        glExecutorService = null;
        glObjectsProvider = null;
      }
      if (releasingException != null) {
        throw new AssertionError("Failed to release GL resources", releasingException);
      }
    }
  }

  private void setUp() {
    if (SDK_INT < 26) {
      return;
    }
    synchronized (setupLock) {
      if (glExecutorService == null) {
        glObjectsProvider = new DefaultGlObjectsProvider();
        glExecutorService =
            MoreExecutors.listeningDecorator(
                Util.newSingleThreadExecutor("GlFrameProcessorTestRule:GL"));
        try {
          glExecutorService
              .submit(
                  () -> {
                    try {
                      FrameProcessorUtils.setupOpenGl(checkNotNull(glObjectsProvider));
                    } catch (GlException e) {
                      throw new AssertionError("Failed to set up OpenGL", e);
                    }
                  })
              .get(timeoutMs, MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while setting up OpenGL", e);
        } catch (ExecutionException | TimeoutException e) {
          throw new AssertionError("Failed to set up OpenGL", e);
        }
      }
    }
  }

  /** Returns the initialized {@link ListeningExecutorService} for GL operations. */
  @RequiresApi(26)
  public ListeningExecutorService getExecutorService() {
    setUp();
    return checkNotNull(glExecutorService);
  }

  /** Returns the initialized {@link GlObjectsProvider}. */
  @RequiresApi(26)
  public GlObjectsProvider getGlObjectsProvider() {
    setUp();
    return checkNotNull(glObjectsProvider);
  }

  /**
   * Returns a {@link DefaultGlFrameProcessor.Factory} configured with EGL context managed by this
   * rule.
   */
  @RequiresApi(26)
  public DefaultGlFrameProcessor.Factory createDefaultGlFrameProcessorFactory(Context context) {
    return new DefaultGlFrameProcessor.Factory(
        context, getGlObjectsProvider(), HardwareBufferJni.INSTANCE, getExecutorService());
  }

  /**
   * Returns a {@link Transformer.Builder} configured with {@link DefaultGlFrameProcessor.Factory}
   * and EGL context managed by this rule.
   */
  @RequiresApi(AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK)
  public Transformer.Builder createTransformerBuilder(Context context) {
    return new Transformer.Builder(context)
        .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
        .setFrameProcessorFactory(createDefaultGlFrameProcessorFactory(context));
  }

  /**
   * Returns a {@link CompositionPlayer.Builder} configured with {@link
   * DefaultGlFrameProcessor.Factory} and EGL context managed by this rule.
   */
  @RequiresApi(AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK)
  public CompositionPlayer.Builder createCompositionPlayerBuilder(Context context) {
    return new CompositionPlayer.Builder(context)
        .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
        .setFrameProcessorFactory(createDefaultGlFrameProcessorFactory(context));
  }
}
