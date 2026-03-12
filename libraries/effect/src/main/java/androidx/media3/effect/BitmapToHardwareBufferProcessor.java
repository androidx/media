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

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.system.ErrnoException;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link HardwareBufferFrameProcessor} that converts {@link Bitmap}-backed {@link
 * HardwareBufferFrame} instances into {@link android.hardware.HardwareBuffer}-backed ones.
 *
 * <p>This processor caches the underlying {@link android.hardware.HardwareBuffer} as long as the
 * input {@link Bitmap} remains the same (verified via {@link Bitmap#getGenerationId()}). It uses
 * JNI to copy pixels from the bitmap to the hardware buffer.
 *
 * <p>The processor manages the lifecycle of the hardware buffer using reference counting, ensuring
 * it is only closed once all consumer frames and the processor itself have released their
 * references.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/479415385 - remove when packet consumer is production-ready.
public class BitmapToHardwareBufferProcessor implements HardwareBufferFrameProcessor {

  private static final int RELEASE_TIMEOUT_MS = 500;

  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final ExecutorService internalExecutor;
  private final Executor errorExecutor;
  private final Consumer<VideoFrameProcessingException> errorCallback;

  @GuardedBy("this")
  @Nullable
  private HardwareBufferFrame currentFrame;

  @GuardedBy("this")
  @Nullable
  private Bitmap currentBitmap;

  @GuardedBy("this")
  private int currentBitmapGenerationId;

  /**
   * Creates an instance.
   *
   * @param hardwareBufferJniWrapper The {@link HardwareBufferJniWrapper} that supplies the native
   *     helpers needed by this class.
   * @param internalExecutor The {@link ExecutorService} that waits for the release fence to signal
   *     before closing created {@link HardwareBuffer}s. Will be {@link ExecutorService#shutdown()}
   *     when this class is {@linkplain #close() closed}.
   * @param errorExecutor The {@link Executor} on which the {@link #errorCallback} will be called.
   * @param errorCallback The {@link Consumer<VideoFrameProcessingException>} called when waiting or
   *     closing the created {@link HardwareBuffer}s fails.
   */
  public BitmapToHardwareBufferProcessor(
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      ExecutorService internalExecutor,
      Executor errorExecutor,
      Consumer<VideoFrameProcessingException> errorCallback) {
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.internalExecutor = internalExecutor;
    this.errorExecutor = errorExecutor;
    this.errorCallback = errorCallback;
  }

  @Override
  public HardwareBufferFrame process(HardwareBufferFrame inputFrame) {
    if (inputFrame.hardwareBuffer != null || !(inputFrame.internalFrame instanceof Bitmap)) {
      return inputFrame;
    }
    Bitmap nextBitmap = checkNotNull((Bitmap) inputFrame.internalFrame);
    HardwareBufferFrame outputFrame;

    synchronized (this) {
      // process should not be called after close.
      checkState(!internalExecutor.isShutdown());
      // Check whether the current bitmap should be updated.
      if (currentFrame != null
          && (currentBitmap != nextBitmap
              || currentBitmapGenerationId != nextBitmap.getGenerationId())) {
        // Release this reference, the buffer will be cleaned up once other references are released.
        checkNotNull(currentFrame).release(/* releaseFence= */ null);
        currentFrame = null;
      }

      if (currentFrame == null) {
        HardwareBuffer buffer = null;
        try {
          HardwareBuffer currentBuffer =
              HardwareBuffer.create(
                  nextBitmap.getWidth(),
                  nextBitmap.getHeight(),
                  /* pixelFormat= */ HardwareBuffer.RGBA_8888,
                  /* layers= */ 1,
                  /* usageFlags= */ HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                      | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                      | HardwareBuffer.USAGE_CPU_READ_RARELY
                      | HardwareBuffer.USAGE_CPU_WRITE_OFTEN);
          buffer = currentBuffer;

          // The input frame is backed by a CPU bitmap so can be immediately read.
          checkState(hardwareBufferJniWrapper.nativeCopyBitmapToHardwareBuffer(nextBitmap, buffer));
          currentBitmap = nextBitmap;
          currentFrame =
              new HardwareBufferFrame.Builder(
                      buffer,
                      /* releaseExecutor= */ directExecutor(),
                      /* releaseCallback= */ (fence) -> releaseBuffer(currentBuffer, fence))
                  .build();
          // Save the generationId from after the native copy, as AndroidBitmap_unlockPixels can
          // cause
          // the generationId to increment.
          currentBitmapGenerationId = nextBitmap.getGenerationId();
        } catch (IllegalStateException e) {
          // If the native copy failed, the buffer is not wrapped in a HardwareBufferFrame, so
          // it needs to be closed here to avoid leaking.
          if (buffer != null) {
            buffer.close();
          }
          throw e;
        }
      }

      outputFrame = checkNotNull(currentFrame).retain();
    }

    inputFrame.release(/* releaseFence= */ null);
    return outputFrame
        .buildUpon()
        .setFormat(inputFrame.format)
        .setInternalFrame(nextBitmap)
        .setReleaseTimeNs(inputFrame.releaseTimeNs)
        .setPresentationTimeUs(inputFrame.presentationTimeUs)
        .setMetadata(inputFrame.getMetadata())
        .build();
  }

  @Override
  public void close() {
    synchronized (this) {
      if (currentFrame != null) {
        currentFrame.release(/* releaseFence= */ null);
        currentFrame = null;
        currentBitmap = null;
        currentBitmapGenerationId = 0;
      }
      if (!internalExecutor.isShutdown()) {
        internalExecutor.shutdown();
      }
    }
  }

  private void releaseBuffer(HardwareBuffer buffer, @Nullable SyncFenceCompat releaseFence) {
    if (releaseFence == null) {
      buffer.close();
      return;
    }
    try {
      internalExecutor.execute(
          () -> {
            try {
              // Wait on the fence on the executor thread to avoid blocking the caller.
              checkState(releaseFence.await(RELEASE_TIMEOUT_MS));
            } catch (ErrnoException | IllegalStateException e) {
              errorExecutor.execute(
                  () -> errorCallback.accept(new VideoFrameProcessingException(e)));
            } finally {
              closeBufferAndFence(buffer, releaseFence);
            }
          });
    } catch (RejectedExecutionException e) {
      // This class has been released, shut down the fence without waiting on it to avoid leaking
      // it.
      closeBufferAndFence(buffer, releaseFence);
    }
  }

  private void closeBufferAndFence(HardwareBuffer buffer, SyncFenceCompat releaseFence) {
    try {
      releaseFence.close();
    } catch (IOException e) {
      errorExecutor.execute(() -> errorCallback.accept(new VideoFrameProcessingException(e)));
    } finally {
      buffer.close();
    }
  }
}
