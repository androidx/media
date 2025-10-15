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

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating {@link FrameProcessor}s that process {@link GlTextureFrame}s.
 *
 * <p>Methods must be called on the GL thread.
 */
/* package */ final class GlTextureFrameProcessorFactory {

  private final Context context;
  private final ListeningExecutorService glThreadExecutorService;
  private final GlObjectsProvider glObjectsProvider;

  /**
   * Creates a new {@link GlTextureFrameProcessorFactory}.
   *
   * @param context The context.
   * @param glThreadExecutorService A single threaded {@link ListeningExecutorService} that wraps
   *     the GL thread. The caller is responsible for shutting down the executor service when it is
   *     no longer needed.
   * @param glObjectsProvider The {@link GlObjectsProvider} for using EGL and GLES. The caller is
   *     responsible for releasing the glObjectsProvider when it is no longer needed.
   */
  public GlTextureFrameProcessorFactory(
      Context context,
      ListeningExecutorService glThreadExecutorService,
      GlObjectsProvider glObjectsProvider) {
    this.context = context;
    this.glThreadExecutorService = glThreadExecutorService;
    this.glObjectsProvider = glObjectsProvider;
  }

  /**
   * Creates a {@link BitmapToGlTextureFrameProcessor}.
   *
   * @param inputColorInfo The {@link ColorInfo} of the input {@link BitmapFrame}.
   * @param outputColorInfo The {@link ColorInfo} of the output {@link GlTextureFrame}.
   * @param videoFrameProcessorTaskExecutorErrorListener The error listener that the {@link
   *     VideoFrameProcessingTaskExecutor} will report errors to.
   */
  public BitmapToGlTextureFrameProcessor buildBitmapToGlTextureFrameProcessor(
      ColorInfo inputColorInfo,
      ColorInfo outputColorInfo,
      Consumer<VideoFrameProcessingException> videoFrameProcessorTaskExecutorErrorListener)
      throws VideoFrameProcessingException {
    return BitmapToGlTextureFrameProcessor.create(
        context,
        glThreadExecutorService,
        glObjectsProvider,
        inputColorInfo,
        outputColorInfo,
        videoFrameProcessorTaskExecutorErrorListener);
  }

  /**
   * Creates a {@link GlTextureToBitmapFrameProcessor}.
   *
   * @param useHdr Whether the input {@link GlTextureFrame}s are HDR, and HDR Bitmaps should be
   *     outputted by the returned processor.
   */
  public GlTextureToBitmapFrameProcessor buildGlTextureToBitmapFrameProcessor(boolean useHdr)
      throws VideoFrameProcessingException {
    return new GlTextureToBitmapFrameProcessor(
        context, useHdr, glThreadExecutorService, glObjectsProvider);
  }

  /**
   * Creates a list {@link GlShaderProgramFrameProcessor}, matching the given {@linkplain
   * List<androidx.media3.common.Effect> effects}.
   *
   * <p>Note that: - Only {@link GlEffect}s are supported. - Some effects may be combined into a
   * single {@link GlShaderProgramFrameProcessor} (e.g. matrix effects).
   *
   * @param effects The {@link List<androidx.media3.common.Effect>} to create processors for.
   * @param useHdr Whether incoming frames are HDR.
   */
  public List<GlShaderProgramFrameProcessor> buildFrameProcessors(
      List<GlEffect> effects, boolean useHdr) throws VideoFrameProcessingException {
    ImmutableList<GlShaderProgram> shaderPrograms = buildShaderPrograms(context, effects, useHdr);
    List<GlShaderProgramFrameProcessor> processors = new ArrayList<>();
    for (GlShaderProgram shaderProgram : shaderPrograms) {
      processors.add(
          GlShaderProgramFrameProcessor.create(
              glThreadExecutorService, shaderProgram, glObjectsProvider));
    }
    return processors;
  }

  private ImmutableList<GlShaderProgram> buildShaderPrograms(
      Context context, List<GlEffect> effects, boolean useHdr)
      throws VideoFrameProcessingException {
    ImmutableList.Builder<GlShaderProgram> shaderProgramListBuilder = new ImmutableList.Builder<>();
    ImmutableList.Builder<GlMatrixTransformation> matrixTransformationListBuilder =
        new ImmutableList.Builder<>();
    ImmutableList.Builder<RgbMatrix> rgbMatrixListBuilder = new ImmutableList.Builder<>();
    for (GlEffect glEffect : effects) {
      // The following logic may change the order of the RgbMatrix and GlMatrixTransformation
      // effects. This does not influence the output since RgbMatrix only changes the individual
      // pixels and does not take any location in account, which the GlMatrixTransformation
      // may change.
      if (glEffect instanceof GlMatrixTransformation) {
        matrixTransformationListBuilder.add((GlMatrixTransformation) glEffect);
        continue;
      }
      if (glEffect instanceof RgbMatrix) {
        rgbMatrixListBuilder.add((RgbMatrix) glEffect);
        continue;
      }
      ImmutableList<GlMatrixTransformation> matrixTransformations =
          matrixTransformationListBuilder.build();
      ImmutableList<RgbMatrix> rgbMatrices = rgbMatrixListBuilder.build();
      if (!matrixTransformations.isEmpty() || !rgbMatrices.isEmpty()) {
        DefaultShaderProgram defaultShaderProgram =
            DefaultShaderProgram.create(context, matrixTransformations, rgbMatrices, useHdr);
        shaderProgramListBuilder.add(defaultShaderProgram);
        matrixTransformationListBuilder = new ImmutableList.Builder<>();
        rgbMatrixListBuilder = new ImmutableList.Builder<>();
      }
      shaderProgramListBuilder.add(glEffect.toGlShaderProgram(context, useHdr));
    }
    ImmutableList<GlMatrixTransformation> matrixTransformations =
        matrixTransformationListBuilder.build();
    ImmutableList<RgbMatrix> rgbMatrices = rgbMatrixListBuilder.build();
    if (!matrixTransformations.isEmpty() || !rgbMatrices.isEmpty()) {
      DefaultShaderProgram defaultShaderProgram =
          DefaultShaderProgram.create(context, matrixTransformations, rgbMatrices, useHdr);
      shaderProgramListBuilder.add(defaultShaderProgram);
    }
    return shaderProgramListBuilder.build();
  }
}
