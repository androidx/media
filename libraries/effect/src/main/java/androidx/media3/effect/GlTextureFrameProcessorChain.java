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

import static androidx.media3.common.ColorInfo.isWideColorGamut;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.getLast;

import android.content.Context;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link GlTextureFrameConsumer} that creates and executes a chain of {@link
 * GlShaderProgramAdapter}s.
 */
@RequiresApi(26)
/* package */ final class GlTextureFrameProcessorChain implements GlTextureFrameConsumer {
  private final Context context;
  private final GlObjectsProvider glObjectsProvider;
  private final ListeningExecutorService glExecutorService;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final List<GlTextureFrameProcessor> effectProcessorChain;
  private final List<Effect> currentEffects;
  private final GlTextureFrameConsumer downstreamFrameConsumer;
  private final String effectKey;
  private final ColorInfo workingColorSpace;

  private GlTextureFrameConsumer firstGlTextureFrameConsumer;

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   * @param glObjectsProvider The {@link GlObjectsProvider}.
   * @param glExecutorService The {@link ListeningExecutorService} to execute OpenGL commands on.
   * @param errorConsumer The {@link Consumer} to report errors to.
   * @param downstreamFrameConsumer The {@link GlTextureFrameConsumer} to output frames to.
   * @param effectKey The {@linkplain GlTextureFrame#getMetadata() frame metadata} key holding the
   *     {@linkplain Effect effects} to apply.
   * @param workingColorSpace The {@link ColorInfo} that frames are processed in.
   */
  public GlTextureFrameProcessorChain(
      Context context,
      GlObjectsProvider glObjectsProvider,
      ListeningExecutorService glExecutorService,
      Consumer<VideoFrameProcessingException> errorConsumer,
      GlTextureFrameConsumer downstreamFrameConsumer,
      String effectKey,
      ColorInfo workingColorSpace) {
    this.context = context;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.errorConsumer = errorConsumer;
    this.downstreamFrameConsumer = downstreamFrameConsumer;
    this.effectKey = effectKey;
    this.workingColorSpace = workingColorSpace;
    this.firstGlTextureFrameConsumer = downstreamFrameConsumer;
    effectProcessorChain = new ArrayList<>();
    currentEffects = new ArrayList<>();
  }

  @Override
  public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
    configure(extractEffects(frame));
    return firstGlTextureFrameConsumer.queue(frame, listenerExecutor, wakeupListener);
  }

  @Override
  public void signalEndOfStream() {
    firstGlTextureFrameConsumer.signalEndOfStream();
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    currentEffects.clear();
    closeAllProcessors();
  }

  /**
   * Configures the processing chain with the given {@code effects}.
   *
   * <p>If the provided list of effects is different from the current effects, the existing
   * processors are closed, and a new chain of {@link GlTextureFrameProcessor} instances is created
   * and set up based on the new {@code effects}.
   *
   * @param effects The list of {@link Effect} instances to apply in the chain.
   */
  private void configure(List<Effect> effects) throws VideoFrameProcessingException {
    // TODO: b/505721737 - Implement effect diffing.
    if (currentEffects.equals(effects)) {
      return;
    }

    // TODO: b/528240409 - defer reconfiguration after all shaders have output all frames following
    //   an EOS.
    closeAllProcessors();
    currentEffects.clear();
    List<GlTextureFrameProcessor> newProcessorChain = new ArrayList<>();
    ImmutableList.Builder<GlMatrixTransformation> matrixTransformationBuilder =
        new ImmutableList.Builder<>();
    ImmutableList.Builder<RgbMatrix> colorTransformationBuilder = new ImmutableList.Builder<>();
    // TODO: b/562926844 - Derive useHdr per effect from the preceding effect's output color space,
    //   once effects can change the color space.
    // TODO: b/545584738 - GlEffect currently overloads useHdr to mean both color gamut (BT.2020 vs
    //   BT.709) and texture precision (GL_RGBA16F vs GL_RGBA8). For BT709_LINEAR, the
    //   gamut is BT.709 (useHdr = false), causing BaseGlShaderProgram to allocate 8-bit textures
    //   (GL_RGBA8) and band in linear light. Pass ColorInfo to effects instead of a boolean, or
    //   decouple gamut from texture precision.
    boolean useHdr = isWideColorGamut(workingColorSpace);
    try {
      for (int i = 0; i < effects.size(); i++) {
        Effect effect = effects.get(i);
        checkArgument(
            effect instanceof GlEffect,
            Util.formatInvariant("%s supports only GlEffects", getClass().getSimpleName()));
        GlEffect glEffect = (GlEffect) effect;
        // Merge consecutive matrix and color effects.
        if (glEffect instanceof GlMatrixTransformation) {
          matrixTransformationBuilder.add((GlMatrixTransformation) glEffect);
          continue;
        }
        if (glEffect instanceof RgbMatrix) {
          colorTransformationBuilder.add((RgbMatrix) glEffect);
          continue;
        }

        ImmutableList<GlMatrixTransformation> matrixTransformations =
            matrixTransformationBuilder.build();
        ImmutableList<RgbMatrix> colorTransformations = colorTransformationBuilder.build();

        if (!matrixTransformations.isEmpty() || !colorTransformations.isEmpty()) {
          newProcessorChain.add(
              createMergedProcessor(matrixTransformations, colorTransformations, useHdr));
          matrixTransformationBuilder = new ImmutableList.Builder<>();
          colorTransformationBuilder = new ImmutableList.Builder<>();
        }

        newProcessorChain.add(
            new GlShaderProgramAdapter(
                glEffect.toGlShaderProgram(context, useHdr),
                glObjectsProvider,
                glExecutorService,
                errorConsumer));
      }

      ImmutableList<GlMatrixTransformation> remainingMatrixTransformations =
          matrixTransformationBuilder.build();
      ImmutableList<RgbMatrix> remainingColorTransformations = colorTransformationBuilder.build();

      if (!remainingMatrixTransformations.isEmpty() || !remainingColorTransformations.isEmpty()) {
        newProcessorChain.add(
            createMergedProcessor(
                remainingMatrixTransformations, remainingColorTransformations, useHdr));
      }
    } catch (VideoFrameProcessingException | RuntimeException e) {
      for (int j = 0; j < newProcessorChain.size(); j++) {
        try {
          newProcessorChain.get(j).close();
        } catch (VideoFrameProcessingException | RuntimeException closeException) {
          // Ignore exceptions when closing during an error recovery.
        }
      }
      throw e;
    }
    for (int i = 0; i < newProcessorChain.size() - 1; i++) {
      newProcessorChain.get(i).setOutput(newProcessorChain.get(i + 1));
    }
    if (!newProcessorChain.isEmpty()) {
      getLast(newProcessorChain).setOutput(downstreamFrameConsumer);
    }
    firstGlTextureFrameConsumer =
        getFirst(newProcessorChain, /* defaultValue= */ downstreamFrameConsumer);

    effectProcessorChain.clear();
    effectProcessorChain.addAll(newProcessorChain);

    currentEffects.addAll(effects);
  }

  @SuppressWarnings("unchecked") // Metadata values are Objects.
  private ImmutableList<Effect> extractEffects(GlTextureFrame frame) {
    if (!frame.getMetadata().containsKey(effectKey)) {
      return ImmutableList.of();
    }
    return (ImmutableList<Effect>) checkNotNull(frame.getMetadata().get(effectKey));
  }

  private GlTextureFrameProcessor createMergedProcessor(
      List<GlMatrixTransformation> matrixTransformations,
      List<RgbMatrix> colorTransformations,
      boolean useHdr)
      throws VideoFrameProcessingException {
    return new GlShaderProgramAdapter(
        DefaultShaderProgram.create(context, matrixTransformations, colorTransformations, useHdr),
        glObjectsProvider,
        glExecutorService,
        errorConsumer);
  }

  private void closeAllProcessors() throws VideoFrameProcessingException {
    ThrowingRunnable<?>[] closeActions = new ThrowingRunnable<?>[effectProcessorChain.size()];
    for (int i = 0; i < effectProcessorChain.size(); i++) {
      closeActions[i] = effectProcessorChain.get(i)::close;
    }
    effectProcessorChain.clear();
    runAllAndAccumulateExceptions(closeActions);
  }
}
