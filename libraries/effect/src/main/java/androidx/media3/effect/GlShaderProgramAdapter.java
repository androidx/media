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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlShaderProgram.InputListener;
import androidx.media3.effect.GlShaderProgram.OutputListener;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.jspecify.annotations.Nullable;

/**
 * Bridges a legacy {@link GlShaderProgram} to {@link
 * GlTextureFrameConsumer.GlTextureFrameProcessor}.
 *
 * <p>Methods must be called on the dedicated OpenGL thread.
 *
 * <p>The underlying {@link GlShaderProgram} must not alter frame presentation times.
 */
/* package */ final class GlShaderProgramAdapter extends BaseGlTextureFrameProcessor
    implements InputListener, OutputListener {
  // TODO: b/525353235 - Consider supporting async GlShaderPrograms, or deprecate them.

  private final GlShaderProgram glShaderProgram;
  private final GlObjectsProvider glObjectsProvider;
  private final Map<Integer, GlTextureFrame> inFlightFrames;
  private final Queue<GlTextureFrame> pendingOutputFrames;
  private final Executor glExecutor;
  private final ColorInfo outputColorInfo;

  private @Nullable ImmutableMap<String, Object> lastQueuedMetadata;
  private int shaderInputCapacity;
  private @Nullable Format currentFormat;
  private boolean inputStreamEnded;
  private boolean hasSignaledEosToWrappedShader;
  private boolean receivedEosSignalFromWrappedShader;

  /**
   * Creates an instance.
   *
   * @param glShaderProgram The {@link GlShaderProgram} to wrap.
   * @param glObjectsProvider The {@link GlObjectsProvider}.
   * @param glExecutor The {@link Executor} to run GL operations on.
   * @param outputColorInfo The {@link ColorInfo} to propagate to output frames.
   * @param errorConsumerExecutor The {@link Executor} on which {@code errorConsumer} is invoked.
   * @param errorConsumer The {@link Consumer} to report errors to.
   */
  public GlShaderProgramAdapter(
      GlShaderProgram glShaderProgram,
      GlObjectsProvider glObjectsProvider,
      Executor glExecutor,
      ColorInfo outputColorInfo,
      Executor errorConsumerExecutor,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    super(errorConsumerExecutor, errorConsumer);
    this.glShaderProgram = glShaderProgram;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutor = glExecutor;
    this.outputColorInfo = outputColorInfo;
    inFlightFrames = new HashMap<>();
    pendingOutputFrames = new ArrayDeque<>();
    @SuppressWarnings("nullness:assignment")
    @Initialized
    GlShaderProgramAdapter thisRef = this;
    this.glShaderProgram.setInputListener(thisRef);
    this.glShaderProgram.setOutputListener(thisRef);
    this.glShaderProgram.setErrorListener(directExecutor(), thisRef::onError);
  }

  @Override
  public void onReadyToAcceptInputFrame() {
    shaderInputCapacity++;
    maybeInvokePendingWakeupListener();
  }

  @Override
  public void onInputFrameProcessed(GlTextureInfo inputTexture) {
    // TODO: b/505721737 - GlTextureFrame inherit from Frame, simplify the release method.
    checkNotNull(inFlightFrames.remove(inputTexture.texId)).release(/* releaseFence= */ null);
  }

  @Override
  public void onOutputFrameAvailable(GlTextureInfo outputTexture, long presentationTimeUs) {
    // We don't support GlShaderPrograms that modify presentationTimeUs.
    Format inputFormat = checkNotNull(currentFormat);
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
                    .setColorInfo(outputColorInfo)
                    .setWidth(outputTexture.width)
                    .setHeight(outputTexture.height)
                    .build())
            .setMetadata(checkNotNull(lastQueuedMetadata))
            .build();

    pendingOutputFrames.add(outputGlTextureFrame);
    tryQueueFramesDownstream();
  }

  @Override
  public void onCurrentOutputStreamEnded() {
    receivedEosSignalFromWrappedShader = true;
    currentFormat = null;
    // Attempts to drain the output queue and sends EOS downstream if there's no frame pending.
    tryQueueFramesDownstream();
    maybeInvokePendingWakeupListener();
  }

  @Override
  protected boolean hasInFlightFrames() {
    return !inFlightFrames.isEmpty();
  }

  @Override
  protected void onFrameDiscontinuity() {
    shaderInputCapacity = 0;
    releasePendingFrames();
    glShaderProgram.flush();
    hasSignaledEosToWrappedShader = false;
    receivedEosSignalFromWrappedShader = false;
  }

  @Override
  protected boolean onQueueFrame(
      GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
    if (currentFormat == null) {
      // currentFormat is set to null after the wrapped shader has processed the EOS
      currentFormat = frame.format;
      // Resets pending EOS signal from format transition to prevent prematurely signaling EOS to
      // downstreamConsumer if the upstream signals EOS.
      receivedEosSignalFromWrappedShader = false;
      hasSignaledEosToWrappedShader = false;
    }
    if (!frame.format.equals(currentFormat)) {
      setPendingWakeupListener(listenerExecutor, wakeupListener);
      // TODO: b/524240959 - Don't use format change to signal EOS.
      if (!hasSignaledEosToWrappedShader) {
        hasSignaledEosToWrappedShader = true;
        glShaderProgram.signalEndOfCurrentInputStream();
      }
      return false;
    }

    if (shaderInputCapacity > 0) {
      shaderInputCapacity--;
      inFlightFrames.put(frame.glTextureInfo.texId, frame);
      lastQueuedMetadata = frame.getMetadata();
      glShaderProgram.queueInputFrame(
          glObjectsProvider, frame.glTextureInfo, frame.presentationTimeUs);
      return true;
    } else {
      // We keep only the last wakeup, if queue() is called repetitively
      setPendingWakeupListener(listenerExecutor, wakeupListener);
      return false;
    }
  }

  @Override
  protected void onEndOfStream() {
    if (!inputStreamEnded) {
      inputStreamEnded = true;
      if (!hasSignaledEosToWrappedShader) {
        hasSignaledEosToWrappedShader = true;
        glShaderProgram.signalEndOfCurrentInputStream();
      }
    }
  }

  @Override
  protected void onClose() throws VideoFrameProcessingException {
    try {
      glShaderProgram.release();
    } finally {
      releasePendingFrames();
      receivedEosSignalFromWrappedShader = false;
    }
  }

  private void releasePendingFrames() {
    if (!inFlightFrames.isEmpty()) {
      for (GlTextureFrame frame : inFlightFrames.values()) {
        frame.release(/* releaseFence= */ null);
      }
      inFlightFrames.clear();
    }
    while (!pendingOutputFrames.isEmpty()) {
      pendingOutputFrames.remove().release(/* releaseFence= */ null);
    }
    currentFormat = null;
    inputStreamEnded = false;
    lastQueuedMetadata = null;
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
      } catch (RuntimeException | VideoFrameProcessingException e) {
        onError(e);
        return;
      }
    }
    if (pendingOutputFrames.isEmpty() && receivedEosSignalFromWrappedShader) {
      receivedEosSignalFromWrappedShader = false;
      if (inputStreamEnded) {
        downstreamConsumer.signalEndOfStream();
      }
    }
  }
}
