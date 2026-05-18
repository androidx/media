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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.GlShaderProgram.InputListener;
import androidx.media3.effect.GlShaderProgram.OutputListener;
import androidx.media3.effect.GlTextureFrameConsumer.GlTextureFrameProcessor;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Bridges a legacy {@link GlShaderProgram} to {@link GlTextureFrameProcessor}.
 *
 * <p>Methods must be called on the dedicated OpenGL thread.
 *
 * <p>The underlying {@link GlShaderProgram} must not alter frame presentation times.
 *
 * <p>Currently, only supports {@link GlShaderProgram GlShaderPrograms} that output the same amount
 * of frames as input.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
/* package */ final class GlShaderProgramAdapter
    implements GlTextureFrameProcessor, InputListener, OutputListener {

  private final GlShaderProgram glShaderProgram;
  private final GlObjectsProvider glObjectsProvider;
  // Map from texId -> input Frame
  private final Map<Integer, GlTextureFrame> inFlightFrames;
  private final Map<Long, Format> inputFormats;
  private final Queue<GlTextureFrame> pendingOutputFrames;
  private final Executor glExecutor;
  private final Consumer<VideoFrameProcessingException> errorConsumer;

  private @MonotonicNonNull GlTextureFrameConsumer downstreamConsumer;
  @Nullable private Runnable pendingWakeupListener;
  @Nullable private Executor pendingWakeupExecutor;
  private int shaderInputCapacity;

  public GlShaderProgramAdapter(
      GlShaderProgram glShaderProgram,
      GlObjectsProvider glObjectsProvider,
      Executor glExecutor,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.glShaderProgram = glShaderProgram;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutor = glExecutor;
    this.errorConsumer = errorConsumer;
    @SuppressWarnings("nullness:assignment")
    @Initialized
    GlShaderProgramAdapter thisRef = this;
    this.glShaderProgram.setInputListener(thisRef);
    this.glShaderProgram.setOutputListener(thisRef);
    this.glShaderProgram.setErrorListener(directExecutor(), errorConsumer::accept);
    inFlightFrames = new HashMap<>();
    pendingOutputFrames = new ArrayDeque<>();
    inputFormats = new HashMap<>();
  }

  @Override
  public void setOutput(GlTextureFrameConsumer downstreamConsumer) {
    checkState(inFlightFrames.isEmpty());
    this.downstreamConsumer = downstreamConsumer;
  }

  @Override
  public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
    // TODO: b/505721737 - Support frame dropping and repeating.
    inputFormats.put(frame.presentationTimeUs, frame.format);
    if (shaderInputCapacity > 0) {
      shaderInputCapacity--;
      inFlightFrames.put(frame.glTextureInfo.texId, frame);
      glShaderProgram.queueInputFrame(
          glObjectsProvider, frame.glTextureInfo, frame.presentationTimeUs);
      return true;
    } else {
      // We keep only the last wakeup, if queue() is called repetitively
      pendingWakeupListener = wakeupListener;
      pendingWakeupExecutor = listenerExecutor;
      return false;
    }
  }

  @Override
  public void onReadyToAcceptInputFrame() {
    shaderInputCapacity++;
    if (pendingWakeupListener != null && pendingWakeupExecutor != null) {
      pendingWakeupExecutor.execute(pendingWakeupListener);
      pendingWakeupListener = null;
      pendingWakeupExecutor = null;
    }
  }

  @Override
  public void onInputFrameProcessed(GlTextureInfo inputTexture) {
    // TODO: b/505721737 - GlTextureFrame inherit from Frame, simplify the release method.
    checkNotNull(inFlightFrames.remove(inputTexture.texId)).release(/* releaseFence= */ null);
  }

  @Override
  public void onFlush() {
    // TODO: b/505721737 - Support seeking.
    throw new UnsupportedOperationException();
  }

  @Override
  public void onOutputFrameAvailable(GlTextureInfo outputTexture, long presentationTimeUs) {
    // We don't support GlShaderPrograms that modify presentationTimeUs.
    Format inputFormat = checkNotNull(inputFormats.remove(presentationTimeUs));
    GlTextureFrame outputGlTextureFrame =
        new GlTextureFrame.Builder(
                outputTexture,
                // We are always on the GL thread
                directExecutor(),
                /* releaseTextureCallback= */ glShaderProgram::releaseOutputFrame)
            // TODO: b/505721737 - Set sequence presentation time.
            .setPresentationTimeUs(presentationTimeUs)
            .setFormat(
                inputFormat
                    .buildUpon()
                    // TODO: b/505721737 - set color info for tone mapping.
                    .setWidth(outputTexture.width)
                    .setHeight(outputTexture.height)
                    .build())
            .build();

    pendingOutputFrames.add(outputGlTextureFrame);
    tryQueueFramesDownstream();
  }

  @Override
  public void onCurrentOutputStreamEnded() {
    checkNotNull(downstreamConsumer).signalEndOfStream();
  }

  /** Called on the GL thread. */
  private void tryQueueFramesDownstream() {
    checkNotNull(downstreamConsumer);
    while (!pendingOutputFrames.isEmpty()) {
      GlTextureFrame frame = checkNotNull(pendingOutputFrames.peek());
      try {
        boolean queued =
            downstreamConsumer.queue(
                frame, glExecutor, /* wakeupListener= */ this::tryQueueFramesDownstream);
        if (queued) {
          pendingOutputFrames.remove();
        } else {
          break;
        }
      } catch (VideoFrameProcessingException e) {
        errorConsumer.accept(e);
        return;
      }
    }
  }

  @Override
  public void signalEndOfStream() {
    glShaderProgram.signalEndOfCurrentInputStream();
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    glShaderProgram.release();
    if (!inFlightFrames.isEmpty()) {
      for (GlTextureFrame frame : inFlightFrames.values()) {
        frame.release(/* releaseFence= */ null);
      }
      inFlightFrames.clear();
    }
    while (!pendingOutputFrames.isEmpty()) {
      pendingOutputFrames.remove().release(/* releaseFence= */ null);
    }
    inputFormats.clear();
  }
}
