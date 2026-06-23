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

import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.getLast;

import android.content.Context;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Util;
import androidx.media3.effect.FrameProcessorUtils.ThrowingRunnable;
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

  private GlTextureFrameConsumer firstGlTextureFrameConsumer;

  public GlTextureFrameProcessorChain(
      Context context,
      GlObjectsProvider glObjectsProvider,
      ListeningExecutorService glExecutorService,
      Consumer<VideoFrameProcessingException> errorConsumer,
      GlTextureFrameConsumer downstreamFrameConsumer) {
    this.context = context;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.errorConsumer = errorConsumer;
    this.downstreamFrameConsumer = downstreamFrameConsumer;
    this.firstGlTextureFrameConsumer = downstreamFrameConsumer;
    effectProcessorChain = new ArrayList<>();
    currentEffects = new ArrayList<>();
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
  public void configure(List<Effect> effects) throws VideoFrameProcessingException {
    // TODO: b/505721737 - Implement effect diffing.
    if (currentEffects.equals(effects)) {
      return;
    }

    closeAllProcessors();
    currentEffects.clear();
    List<GlTextureFrameProcessor> newProcessorChain = new ArrayList<>();
    for (int i = 0; i < effects.size(); i++) {
      Effect effect = effects.get(i);
      checkArgument(
          effect instanceof GlEffect,
          Util.formatInvariant("%s supports only GlEffects", getClass().getSimpleName()));
      // TODO: b/505721737 - Support HDR.
      try {
        newProcessorChain.add(
            new GlShaderProgramAdapter(
                ((GlEffect) effect).toGlShaderProgram(context, /* useHdr= */ false),
                glObjectsProvider,
                glExecutorService,
                errorConsumer));
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

  @Override
  public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
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

  private void closeAllProcessors() throws VideoFrameProcessingException {
    ThrowingRunnable[] closeActions = new ThrowingRunnable[effectProcessorChain.size()];
    for (int i = 0; i < effectProcessorChain.size(); i++) {
      closeActions[i] = effectProcessorChain.get(i)::close;
    }
    effectProcessorChain.clear();
    runAllAndAccumulateExceptions(closeActions);
  }
}
