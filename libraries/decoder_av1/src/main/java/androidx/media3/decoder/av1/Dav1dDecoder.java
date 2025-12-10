/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.av1;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** dAV1d decoder. */
@UnstableApi
public final class Dav1dDecoder
    implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, Dav1dDecoderException> {

  // LINT.IfChange
  private static final int DAV1D_ERROR = 0;
  private static final int DAV1D_OK = 1;
  private static final int DAV1D_DECODE_ONLY = 2;
  private static final int DAV1D_EAGAIN = 3;
  // LINT.ThenChange(../../../../../jni/dav1d_jni.cc)

  private final Thread decodeThread;

  private final Object lock;

  @GuardedBy("lock")
  private final ArrayDeque<DecoderInputBuffer> queuedInputBuffers;

  @GuardedBy("lock")
  private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputBuffers;

  @GuardedBy("lock")
  private final DecoderInputBuffer[] availableInputBuffers;

  @GuardedBy("lock")
  private final VideoDecoderOutputBuffer[] availableOutputBuffers;

  private long dav1dDecoderContext;

  private volatile @C.VideoOutputMode int outputMode;

  @GuardedBy("lock")
  private int availableInputBufferCount;

  @GuardedBy("lock")
  private int availableOutputBufferCount;

  @GuardedBy("lock")
  @Nullable
  private DecoderInputBuffer dequeuedInputBuffer;

  @GuardedBy("lock")
  @Nullable
  private Dav1dDecoderException exception;

  @GuardedBy("lock")
  private boolean flushed;

  @GuardedBy("lock")
  private boolean released;

  @GuardedBy("lock")
  private int skippedOutputBufferCount;

  @GuardedBy("lock")
  private long outputStartTimeUs;

  @Nullable private Surface surface;

  /**
   * Creates a Dav1dDecoder.
   *
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer, in bytes.
   * @param threads Number of threads libdav1d will use to decode. If {@link
   *     Libdav1dVideoRenderer#THREAD_COUNT_DECODER_DEFAULT} is passed, then this class use the
   *     default decoder behavior for setting the threads.
   * @param maxFrameDelay Maximum amount of frames libdav1d can be behind on.
   * @param useCustomAllocator Whether to use a custom allocator for the decoder.
   * @throws Dav1dDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  // Suppressing nulless for UnderInitialization and method.invocation.
  @SuppressWarnings("nullness")
  public Dav1dDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      int maxFrameDelay,
      boolean useCustomAllocator)
      throws Dav1dDecoderException {
    if (!Dav1dLibrary.isAvailable()) {
      throw new Dav1dDecoderException("Failed to load decoder native library.");
    }
    lock = new Object();
    outputStartTimeUs = C.TIME_UNSET;
    queuedInputBuffers = new ArrayDeque<>();
    queuedOutputBuffers = new ArrayDeque<>();
    availableInputBuffers = new DecoderInputBuffer[numInputBuffers];
    availableInputBufferCount = numInputBuffers;
    for (int i = 0; i < availableInputBufferCount; i++) {
      availableInputBuffers[i] =
          new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
      availableInputBuffers[i].ensureSpaceForWrite(initialInputBufferSize);
    }
    availableOutputBuffers = new VideoDecoderOutputBuffer[numOutputBuffers];
    availableOutputBufferCount = numOutputBuffers;
    for (int i = 0; i < availableOutputBufferCount; i++) {
      availableOutputBuffers[i] = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
    }
    decodeThread =
        new Thread("ExoPlayer:Dav1dDecoder") {
          @Override
          public void run() {
            Dav1dDecoder.this.dav1dDecoderContext =
                dav1dInit(threads, maxFrameDelay, useCustomAllocator);
            if (dav1dCheckError(Dav1dDecoder.this.dav1dDecoderContext) == DAV1D_ERROR) {
              synchronized (lock) {
                Dav1dDecoder.this.exception =
                    new Dav1dDecoderException(
                        "Failed to initialize decoder. Error: "
                            + dav1dGetErrorMessage(Dav1dDecoder.this.dav1dDecoderContext));
              }
              dav1dClose(Dav1dDecoder.this.dav1dDecoderContext);
              return;
            }
            Dav1dDecoder.this.run();
            releaseUnusedInputBuffers(Dav1dDecoder.this.dav1dDecoderContext, Dav1dDecoder.this);
            dav1dClose(Dav1dDecoder.this.dav1dDecoderContext);
          }
        };
    decodeThread.start();
    maybeThrowException();
  }

  @Override
  public String getName() {
    return "libdav1d";
  }

  @Override
  @Nullable
  public final DecoderInputBuffer dequeueInputBuffer() throws Dav1dDecoderException {
    synchronized (lock) {
      maybeThrowException();
      checkState(dequeuedInputBuffer == null || flushed);
      dequeuedInputBuffer =
          availableInputBufferCount == 0 || flushed
              ? null
              : availableInputBuffers[--availableInputBufferCount];
      return dequeuedInputBuffer;
    }
  }

  @Override
  public final void queueInputBuffer(DecoderInputBuffer inputBuffer) throws Dav1dDecoderException {
    synchronized (lock) {
      maybeThrowException();
      checkArgument(inputBuffer == dequeuedInputBuffer);
      queuedInputBuffers.addLast(inputBuffer);
      maybeNotifyDecodeLoop();
      dequeuedInputBuffer = null;
    }
  }

  @Override
  @Nullable
  public final VideoDecoderOutputBuffer dequeueOutputBuffer() throws Dav1dDecoderException {
    synchronized (lock) {
      maybeThrowException();
      if (queuedOutputBuffers.isEmpty() || flushed) {
        return null;
      }
      return queuedOutputBuffers.removeFirst();
    }
  }

  @Override
  public final void flush() {
    synchronized (lock) {
      flushed = true;
      lock.notify();
    }
  }

  @Override
  public final void setOutputStartTimeUs(long outputStartTimeUs) {
    synchronized (lock) {
      this.outputStartTimeUs = outputStartTimeUs;
    }
  }

  @Override
  public void release() {
    synchronized (lock) {
      released = true;
      lock.notify();
    }
    try {
      decodeThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Sets the output mode for frames rendered by the decoder.
   *
   * @param outputMode The output mode.
   */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  /**
   * Renders output buffer to the given surface. Must only be called when in {@link
   * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
   *
   * @param outputBuffer Output buffer.
   * @param surface Output surface.
   * @throws Dav1dDecoderException Thrown if called with invalid output mode or frame rendering
   *     fails.
   */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws Dav1dDecoderException {
    if (outputMode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new Dav1dDecoderException("Unsupported Output Mode.");
    }
    int error = dav1dRenderFrame(dav1dDecoderContext, surface, outputBuffer);
    if (error != DAV1D_OK) {
      throw new Dav1dDecoderException("Failed to render output buffer to surface.");
    }
  }

  /* package */
  void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    synchronized (lock) {
      dav1dReleaseFrame(dav1dDecoderContext, outputBuffer);
      releaseOutputBufferInternal(outputBuffer);
      maybeNotifyDecodeLoop();
    }
  }

  /* package */
  final boolean isAtLeastOutputStartTimeUs(long timeUs) {
    synchronized (lock) {
      return outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs;
    }
  }

  /* package */
  Dav1dDecoderException createUnexpectedDecodeException(Throwable error) {
    return new Dav1dDecoderException("Unexpected decode error", error);
  }

  // Setting and checking deprecated decode-only flag for compatibility with custom decoders that
  // are still using it.
  private boolean decode() throws InterruptedException {
    DecoderInputBuffer inputBuffer;
    VideoDecoderOutputBuffer outputBuffer;
    // Wait until we have an input and output buffer to decode.
    synchronized (lock) {
      if (flushed) {
        flushInternal();
      }
      while (!released && !(canDecodeInputBuffer() && canDecodeOutputBuffer()) && !flushed) {
        lock.wait();
      }
      if (released) {
        return false;
      }
      if (flushed) {
        // Flushed may have changed after lock.wait() is finished.
        flushInternal();
        // Queued Input Buffers have been cleared, there is no data to decode.
        return true;
      }
      inputBuffer = queuedInputBuffers.removeFirst();
      outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
    }

    if (inputBuffer.isEndOfStream()) {
      outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      releaseInputBuffer(inputBuffer);
      synchronized (lock) {
        if (flushed) {
          outputBuffer.release();
          flushInternal();
        } else {
          outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
          skippedOutputBufferCount = 0;
          queuedOutputBuffers.addLast(outputBuffer);
        }
      }
    } else {
      @C.BufferFlags int flags = 0;
      boolean decodeOnly = false;
      if (!isAtLeastOutputStartTimeUs(inputBuffer.timeUs)) {
        decodeOnly = true;
      }
      if (inputBuffer.isFirstSample()) {
        flags |= C.BUFFER_FLAG_FIRST_SAMPLE;
      }
      @Nullable Dav1dDecoderException exception = null;
      try {
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputOffset = inputData.position();
        int inputSize = inputData.remaining();
        int status =
            dav1dDecode(
                dav1dDecoderContext,
                inputBuffer,
                inputOffset,
                inputSize,
                decodeOnly,
                flags,
                inputBuffer.timeUs,
                outputMode);
        if (status == DAV1D_ERROR) {
          throw new Dav1dDecoderException(
              "dav1dDecode error: " + dav1dGetErrorMessage(dav1dDecoderContext));
        }
        while ((status = dav1dGetFrame(dav1dDecoderContext, outputBuffer)) == DAV1D_OK
            || status == DAV1D_DECODE_ONLY) {
          if (status == DAV1D_DECODE_ONLY) {
            outputBuffer.shouldBeSkipped = true;
          }
          synchronized (lock) {
            if (flushed) {
              outputBuffer.release();
              flushInternal();
              break;
            } else if (!isAtLeastOutputStartTimeUs(outputBuffer.timeUs)
                || outputBuffer.shouldBeSkipped) {
              skippedOutputBufferCount++;
              outputBuffer.release();
            } else {
              outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
              skippedOutputBufferCount = 0;
              queuedOutputBuffers.addLast(outputBuffer);
            }
            while (!released && !canDecodeOutputBuffer() && !flushed) {
              lock.wait();
            }
            if (released) {
              return false;
            }
            if (flushed) {
              flushInternal();
              return true;
            }
            outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
          }
        }
        if (status == DAV1D_ERROR) {
          throw new Dav1dDecoderException(
              "dav1dGetFrame error: " + dav1dGetErrorMessage(dav1dDecoderContext));
        } else if (status == DAV1D_EAGAIN) {
          outputBuffer.release();
        }
      } catch (RuntimeException e) {
        // This can occur if a sample is malformed in a way that the decoder is not robust against.
        // We don't want the process to die in this case, but we do want to propagate the error.
        exception = createUnexpectedDecodeException(e);
      } catch (OutOfMemoryError e) {
        // This can occur if a sample is malformed in a way that causes the decoder to think it
        // needs to allocate a large amount of memory. We don't want the process to die in this
        // case, but we do want to propagate the error.
        exception = createUnexpectedDecodeException(e);
      } catch (Dav1dDecoderException e) {
        exception = e;
      }
      if (exception != null) {
        synchronized (lock) {
          this.exception = exception;
        }
        return false;
      }
    }
    releaseUnusedInputBuffers(dav1dDecoderContext, this);
    return true;
  }

  @GuardedBy("lock")
  private void maybeThrowException() throws Dav1dDecoderException {
    if (this.exception != null) {
      throw this.exception;
    }
  }

  private void releaseInputBuffer(DecoderInputBuffer inputBuffer) {
    synchronized (lock) {
      releaseInputBufferInternal(inputBuffer);
    }
  }

  @GuardedBy("lock")
  private void releaseInputBufferInternal(DecoderInputBuffer inputBuffer) {
    inputBuffer.clear();
    availableInputBuffers[availableInputBufferCount++] = inputBuffer;
  }

  @GuardedBy("lock")
  private void releaseOutputBufferInternal(VideoDecoderOutputBuffer outputBuffer) {
    outputBuffer.clear();
    availableOutputBuffers[availableOutputBufferCount++] = outputBuffer;
  }

  @GuardedBy("lock")
  private boolean canDecodeInputBuffer() {
    return !queuedInputBuffers.isEmpty();
  }

  @GuardedBy("lock")
  private boolean canDecodeOutputBuffer() {
    return availableOutputBufferCount > 0;
  }

  @GuardedBy("lock")
  private void maybeNotifyDecodeLoop() {
    if (canDecodeInputBuffer() || canDecodeOutputBuffer()) {
      lock.notify();
    }
  }

  @GuardedBy("lock")
  private void flushInternal() {
    skippedOutputBufferCount = 0;
    if (dequeuedInputBuffer != null) {
      releaseInputBufferInternal(dequeuedInputBuffer);
      dequeuedInputBuffer = null;
    }
    while (!queuedInputBuffers.isEmpty()) {
      releaseInputBufferInternal(queuedInputBuffers.removeFirst());
    }
    while (!queuedOutputBuffers.isEmpty()) {
      queuedOutputBuffers.removeFirst().release();
    }
    dav1dFlush(dav1dDecoderContext);
    flushed = false;
  }

  private void run() {
    try {
      while (decode()) {
        // Do nothing.
      }
    } catch (InterruptedException e) {
      // Not expected.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Initializes a libdav1d decoder.
   *
   * @param threads Number of threads to be used by a libdav1d decoder.
   * @param maxFrameDelay Max frame delay permitted for libdav1d decoder.
   * @param useCustomAllocator Whether to use a custom picture allocator.
   * @return The address of the decoder context or {@link #DAV1D_ERROR} if there was an error.
   */
  private native long dav1dInit(int threads, int maxFrameDelay, boolean useCustomAllocator);

  /**
   * Deallocates the decoder context.
   *
   * @param context Decoder context.
   */
  private native void dav1dClose(long context);

  /**
   * Decodes the encoded data passed and gets the resulting frame.
   *
   * @param context Decoder context.
   * @param inputBuffer Encoded input buffer.
   * @param inputOffset Offset of the data buffer.
   * @param inputSize Length of the data buffer
   * @param decodeOnly Whether the input data is decode only.
   * @param flags {@link androidx.media3.common.C#BufferFlags} Information about output buffer.
   * @param timeUs Time of input data.
   * @param outputMode Output mode for output buffer.
   * @return {@link #DAV1D_OK} if successful, {@link #DAV1D_ERROR} if an error occurred, {@link
   *     #DAV1D_EAGAIN}
   */
  private native int dav1dDecode(
      long context,
      DecoderInputBuffer inputBuffer,
      int inputOffset,
      int inputSize,
      boolean decodeOnly,
      @C.BufferFlags int flags,
      long timeUs,
      @C.VideoOutputMode int outputMode);

  /**
   * Gets the decoded frame.
   *
   * @param context Decoder context.
   * @param outputBuffer Output buffer with the decoded frame.
   * @return {@link #DAV1D_OK} if successful, {@link #DAV1D_ERROR} if an error occurred, {@link
   *     #DAV1D_EAGAIN} if more input data is needed.
   */
  private native int dav1dGetFrame(long context, VideoDecoderOutputBuffer outputBuffer);

  /**
   * Renders the frame to the surface. Used with {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV} only.
   *
   * @param context Decoder context.
   * @param surface Output surface.
   * @param outputBuffer Output buffer with the decoded frame.
   * @return {@link #DAV1D_OK} if successful, {@link #DAV1D_ERROR} if an error occurred.
   */
  private native int dav1dRenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer outputBuffer);

  /**
   * Releases the frame. Used with {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV} only.
   *
   * @param context Decoder context.
   * @param outputBuffer Output buffer.
   */
  private native void dav1dReleaseFrame(long context, VideoDecoderOutputBuffer outputBuffer);

  /**
   * Returns a human-readable string describing the last error encountered in the given context.
   *
   * @param context Decoder context.
   * @return A string describing the last encountered error.
   */
  private native String dav1dGetErrorMessage(long context);

  /**
   * Returns whether an error occurred.
   *
   * @param context Decoder context.
   * @return {@link #DAV1D_OK} if there was no error, {@link #DAV1D_ERROR} if an error occurred.
   */
  private native int dav1dCheckError(long context);

  /**
   * Flushes the decoder.
   *
   * @param context Decoder context.
   */
  private native void dav1dFlush(long context);

  /**
   * Release unused input buffers.
   *
   * @param context Decoder context.
   * @param decoder Dav1dDecoder instance.
   */
  private native void releaseUnusedInputBuffers(long context, Dav1dDecoder decoder);
}
