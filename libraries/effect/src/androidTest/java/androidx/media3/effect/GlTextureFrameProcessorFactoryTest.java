/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Matrix;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.util.GlUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link GlTextureFrameProcessorFactory}. */
@RunWith(AndroidJUnit4.class)
public final class GlTextureFrameProcessorFactoryTest {
  private static final int TEST_TIMEOUT_MS = 1000;
  private @MonotonicNonNull ListeningExecutorService glThreadExecutorService;
  private @MonotonicNonNull GlTextureFrameProcessorFactory factory;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull EGLDisplay eglDisplay;

  @Before
  public void setUp() throws Exception {
    glThreadExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    glThreadExecutorService
        .submit(
            () -> {
              eglDisplay = GlUtil.getDefaultEglDisplay();
              EGLContext eglContext = GlUtil.createEglContext(eglDisplay);
              GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
              return null;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    factory =
        new GlTextureFrameProcessorFactory(
            getApplicationContext(), glThreadExecutorService, glObjectsProvider);
  }

  @After
  public void tearDown() throws Exception {
    glObjectsProvider.release(eglDisplay);
    glThreadExecutorService.shutdownNow();
  }

  @Test
  public void buildProcessors_withNoEffects_succeeds() throws Exception {
    List<GlShaderProgramFrameProcessor> processors =
        glThreadExecutorService
            .submit(
                () ->
                    factory.buildFrameProcessors(
                        /* effects= */ ImmutableList.of(), /* useHdr= */ false))
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(processors).isEmpty();
  }

  @Test
  public void buildProcessors_withOneEffect_createsOneFrameProcessor() throws Exception {
    ImmutableList<GlEffect> effects = ImmutableList.of(new AlphaScale(0.5f));

    List<GlShaderProgramFrameProcessor> processors =
        glThreadExecutorService
            .submit(() -> factory.buildFrameProcessors(effects, /* useHdr= */ false))
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(processors).hasSize(1);
  }

  @Test
  public void buildProcessors_withMatrixEffects_combinesMatrices() throws Exception {
    Matrix rotate90Matrix = new Matrix();
    rotate90Matrix.postRotate(/* degrees= */ 90);
    MatrixTransformation rotate90Transformation = (long presentationTimeUs) -> rotate90Matrix;
    ImmutableList<GlEffect> effects =
        ImmutableList.of(
            RgbFilter.createGrayscaleFilter(),
            rotate90Transformation,
            RgbFilter.createInvertedFilter());

    List<GlShaderProgramFrameProcessor> processors =
        glThreadExecutorService
            .submit(() -> factory.buildFrameProcessors(effects, /* useHdr= */ false))
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(processors).hasSize(1);
  }

  @Test
  public void buildProcessors_withNonMatrixEffects_doesNotCombineMatrices() throws Exception {
    Matrix rotate90Matrix = new Matrix();
    rotate90Matrix.postRotate(/* degrees= */ 90);
    MatrixTransformation rotate90Transformation = (long presentationTimeUs) -> rotate90Matrix;
    ImmutableList<GlEffect> effects =
        ImmutableList.of(
            RgbFilter.createGrayscaleFilter(),
            new AlphaScale(0.5f),
            rotate90Transformation,
            new AlphaScale(0.5f),
            RgbFilter.createInvertedFilter());

    List<GlShaderProgramFrameProcessor> processors =
        glThreadExecutorService
            .submit(() -> factory.buildFrameProcessors(effects, /* useHdr= */ false))
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(processors).hasSize(5);
  }
}
