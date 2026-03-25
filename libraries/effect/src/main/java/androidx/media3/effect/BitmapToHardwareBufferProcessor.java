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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

// TODO: b/475511702 - Handle HDR bitmaps.
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

  private static final Duration RELEASE_TIMEOUT = Duration.ofMillis(500);

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
          if (SDK_INT >= 31) {
            if (nextBitmap.getConfig() == Config.HARDWARE) {
              // Input is HARDWARE and API >= 31: Direct access to HardwareBuffer is possible.
              buffer = nextBitmap.getHardwareBuffer();
            } else {
              // Input is not HARDWARE and API >= 31: We can create a HARDWARE Bitmap copy
              // and get its HardwareBuffer.
              buffer = nextBitmap.copy(Config.HARDWARE, /* isMutable= */ false).getHardwareBuffer();
            }
          } else { // SDK_INT < 31
            if (nextBitmap.getConfig() == Config.HARDWARE) {
              // Input is HARDWARE but API < 31: HardwareBuffer is not directly accessible.
              // We must first copy to a software Bitmap (e.g., ARGB_8888)
              // and then copy that to a new HardwareBuffer via JNI.
              Bitmap softwareBitmap = nextBitmap.copy(Config.ARGB_8888, /* isMutable= */ false);
              buffer = copyCpuBitmapToHardwareBuffer(softwareBitmap, hardwareBufferJniWrapper);
            } else {
              // Input is not HARDWARE and API < 31: Copy the software Bitmap
              // to a new HardwareBuffer via JNI.
              buffer = copyCpuBitmapToHardwareBuffer(nextBitmap, hardwareBufferJniWrapper);
            }
          }

          currentBitmap = nextBitmap;
          HardwareBuffer currentBuffer = buffer;
          currentFrame =
              new HardwareBufferFrame.Builder(
                      buffer,
                      /* releaseExecutor= */ directExecutor(),
                      /* releaseCallback= */ (fence) -> releaseBuffer(currentBuffer, fence))
                  .build();
          // Save the generationId from after the native copy, as AndroidBitmap_unlockPixels can
          // cause the generationId to increment.
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

  /**
   * Copies a {@link Bitmap.Config#ARGB_8888}, {@link Bitmap.Config#RGBA_F16} or {@link
   * Bitmap.Config#RGBA_1010102} {@link Bitmap} to a {@link HardwareBuffer} using JNI.
   *
   * <p>The created buffer will have {@linkplain HardwareBuffer#USAGE_GPU_SAMPLED_IMAGE GPU read},
   * {@linkplain HardwareBuffer#USAGE_GPU_COLOR_OUTPUT GPU write}, {@linkplain
   * HardwareBuffer#USAGE_CPU_READ_OFTEN CPU read} and {@linkplain
   * HardwareBuffer#USAGE_CPU_WRITE_OFTEN CPU write} usage flags set, and pixelFormat of {@link
   * HardwareBuffer#RGBA_8888}.
   */
  private static HardwareBuffer copyCpuBitmapToHardwareBuffer(
      Bitmap bitmap, HardwareBufferJniWrapper hardwareBufferJniWrapper) {
    HardwareBuffer buffer =
        HardwareBuffer.create(
            bitmap.getWidth(),
            bitmap.getHeight(),
            /* pixelFormat= */ HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            /* usageFlags= */ HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                | HardwareBuffer.USAGE_CPU_READ_OFTEN
                | HardwareBuffer.USAGE_CPU_WRITE_OFTEN);

    checkState(hardwareBufferJniWrapper.nativeCopyBitmapToHardwareBuffer(bitmap, buffer));
    return buffer;
  }

  private void releaseBuffer(HardwareBuffer buffer, @Nullable SyncFenceWrapper releaseFence) {
    if (releaseFence == null) {
      buffer.close();
      return;
    }
    try {
      internalExecutor.execute(
          () -> {
            try {
              // Wait on the fence on the executor thread to avoid blocking the caller.
              checkState(releaseFence.await(RELEASE_TIMEOUT));
            } catch (IllegalStateException e) {
              errorExecutor.execute(
                  () -> errorCallback.accept(new VideoFrameProcessingException(e)));
            } finally {
              releaseFence.close();
              buffer.close();
            }
          });
    } catch (RejectedExecutionException e) {
      // This class has been released, shut down the fence without waiting on it to avoid leaking
      // it.
      releaseFence.close();
      buffer.close();
    }
  }
}
