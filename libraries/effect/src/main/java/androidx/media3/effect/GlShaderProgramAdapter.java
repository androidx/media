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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_FRAME_DISCONTINUITY_NUMBER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.GlShaderProgram.InputListener;
import androidx.media3.effect.GlShaderProgram.OutputListener;
import androidx.media3.effect.GlTextureFrameConsumer.GlTextureFrameProcessor;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
/* package */ final class GlShaderProgramAdapter
    implements GlTextureFrameProcessor, InputListener, OutputListener {
  // TODO: b/525353235 - Consider supporting async GlShaderPrograms, or deprecate them.

  private final GlShaderProgram glShaderProgram;
  private final GlObjectsProvider glObjectsProvider;
  private final Map<Integer, GlTextureFrame> inFlightFrames;
  private final Queue<GlTextureFrame> pendingOutputFrames;
  private final Executor glExecutor;
  private final Consumer<VideoFrameProcessingException> errorConsumer;

  private @MonotonicNonNull GlTextureFrameConsumer downstreamConsumer;
  @Nullable private ImmutableMap<String, Object> lastQueuedMetadata;
  @Nullable private Runnable pendingWakeupListener;
  @Nullable private Executor pendingWakeupExecutor;
  private int shaderInputCapacity;
  private int currentStreamDiscontinuityNumber;
  @Nullable private Format currentFormat;
  private boolean inputStreamEnded;
  private boolean hasSignaledEosToWrappedShader;
  private boolean receivedEosSignalFromWrappedShader;

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
    currentStreamDiscontinuityNumber = C.INDEX_UNSET;
  }

  @Override
  public void setOutput(GlTextureFrameConsumer downstreamConsumer) {
    checkState(inFlightFrames.isEmpty());
    this.downstreamConsumer = downstreamConsumer;
  }

  @Override
  public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
    checkArgument(
        !Objects.equals(listenerExecutor, directExecutor()),
        "wakeupListener must not be executed on a direct executor");

    int frameDiscontinuityNumber =
        (int)
            frame
                .getMetadata()
                .getOrDefault(KEY_FRAME_DISCONTINUITY_NUMBER, /* defaultValue= */ C.INDEX_UNSET);
    if (frameDiscontinuityNumber != C.INDEX_UNSET) {
      // Previewing mode.
      if (currentStreamDiscontinuityNumber > frameDiscontinuityNumber) {
        // Frame from the old stream arrives, ignore it.
        frame.release(/* releaseFence= */ null);
        return true;
      }

      if (currentStreamDiscontinuityNumber < frameDiscontinuityNumber) {
        // Discontinuity number changed, need to flush the internal shader.
        flushInternal();
        currentStreamDiscontinuityNumber = frameDiscontinuityNumber;
      }
    }

    if (currentFormat == null) {
      // currentFormat is set to null after the wrapped shader has processed the EOS
      currentFormat = frame.format;
      // Resets pending EOS signal from format transition to prevent prematurely signaling EOS to
      // downstreamConsumer if the upstream signals EOS.
      receivedEosSignalFromWrappedShader = false;
      hasSignaledEosToWrappedShader = false;
    }
    if (!frame.format.equals(currentFormat)) {
      pendingWakeupListener = wakeupListener;
      pendingWakeupExecutor = listenerExecutor;
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
      pendingWakeupListener = wakeupListener;
      pendingWakeupExecutor = listenerExecutor;
      return false;
    }
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
                    // TODO: b/505721737 - set color info for tone mapping.
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
  public void signalEndOfStream() {
    if (!inputStreamEnded) {
      inputStreamEnded = true;
      if (!hasSignaledEosToWrappedShader) {
        hasSignaledEosToWrappedShader = true;
        glShaderProgram.signalEndOfCurrentInputStream();
      }
    }
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    try {
      glShaderProgram.release();
    } finally {
      releasePendingFrames();
      receivedEosSignalFromWrappedShader = false;
    }
  }

  private void maybeInvokePendingWakeupListener() {
    if (pendingWakeupListener == null || pendingWakeupExecutor == null) {
      return;
    }
    Runnable listener = pendingWakeupListener;
    Executor executor = pendingWakeupExecutor;
    pendingWakeupListener = null;
    pendingWakeupExecutor = null;
    executor.execute(
        () -> {
          try {
            listener.run();
          } catch (RuntimeException e) {
            errorConsumer.accept(VideoFrameProcessingException.from(e));
          }
        });
  }

  /** Called on the GL thread. */
  private void flushInternal() {
    shaderInputCapacity = 0;
    releasePendingFrames();
    glShaderProgram.flush();
    hasSignaledEosToWrappedShader = false;
    receivedEosSignalFromWrappedShader = false;
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
    pendingWakeupListener = null;
    pendingWakeupExecutor = null;
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
        errorConsumer.accept(VideoFrameProcessingException.from(e));
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
