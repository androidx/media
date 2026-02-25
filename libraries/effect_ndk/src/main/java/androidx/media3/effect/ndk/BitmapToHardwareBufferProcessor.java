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
package androidx.media3.effect.ndk;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.system.ErrnoException;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.SyncFenceCompat;
import androidx.media3.transformer.HardwareBufferFrameProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
@ExperimentalApi // TODO: b/479415385 - remove when packet consumer is production-ready.
public class BitmapToHardwareBufferProcessor implements HardwareBufferFrameProcessor {

  private static final String TAG = "BitmapToHbProcessor";
  private static final int RELEASE_TIMEOUT_MS = 500;

  private final Executor releaseBufferExecutor;

  @GuardedBy("this")
  @Nullable
  private ReferenceCountedBuffer currentBuffer;

  @GuardedBy("this")
  @Nullable
  private Bitmap currentBitmap;

  @GuardedBy("this")
  private int currentBitmapGenerationId;

  /**
   * Creates an instance.
   *
   * @param releaseBufferExecutor The {@link Executor} on which the hardware buffer release and
   *     fence waiting logic will be executed.
   */
  public BitmapToHardwareBufferProcessor(Executor releaseBufferExecutor) {
    this.releaseBufferExecutor = releaseBufferExecutor;
  }

  @Override
  public HardwareBufferFrame process(HardwareBufferFrame inputFrame) {
    if (SDK_INT < 26
        || inputFrame.hardwareBuffer != null
        || !(inputFrame.internalFrame instanceof Bitmap)) {
      return inputFrame;
    }
    Bitmap nextBitmap = checkNotNull((Bitmap) inputFrame.internalFrame);

    synchronized (this) {
      // Check whether the current bitmap should be updated.
      if (currentBuffer != null
          && (currentBitmap != nextBitmap
              || currentBitmapGenerationId != nextBitmap.getGenerationId())) {
        ReferenceCountedBuffer currentBuffer = checkNotNull(this.currentBuffer);
        releaseBufferExecutor.execute(() -> currentBuffer.release(/* fence= */ null));
        this.currentBuffer = null;
      }

      if (currentBuffer == null) {
        HardwareBuffer buffer =
            HardwareBuffer.create(
                nextBitmap.getWidth(),
                nextBitmap.getHeight(),
                /* pixelFormat= */ HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                /* usageFlags= */ HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | HardwareBuffer.USAGE_CPU_READ_RARELY
                    | HardwareBuffer.USAGE_CPU_WRITE_OFTEN);

        checkState(HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(nextBitmap, buffer));

        currentBuffer = new ReferenceCountedBuffer(buffer);
        currentBitmap = nextBitmap;
        // Save the generationId from after the native copy, as AndroidBitmap_unlockPixels can cause
        // the generationId to increment.
        currentBitmapGenerationId = nextBitmap.getGenerationId();
      }

      inputFrame.release(/* releaseFence= */ null);
      ReferenceCountedBuffer.BufferReference frameReference =
          checkNotNull(currentBuffer).acquireReference();

      return new HardwareBufferFrame.Builder(
              checkNotNull(currentBuffer).hardwareBuffer,
              releaseBufferExecutor,
              /* releaseCallback= */ frameReference::release)
          .setFormat(inputFrame.format)
          .setInternalFrame(nextBitmap)
          .setReleaseTimeNs(inputFrame.releaseTimeNs)
          .setPresentationTimeUs(inputFrame.presentationTimeUs)
          .setMetadata(inputFrame.getMetadata())
          .build();
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (currentBuffer != null) {
        currentBuffer.release(/* fence= */ null);
        currentBuffer = null;
      }
    }
  }

  /**
   * Manages the shared HardwareBuffer. It collects sync fences from all consumers and waits for
   * them before closing the underlying buffer.
   */
  private static final class ReferenceCountedBuffer {
    private final HardwareBuffer hardwareBuffer;
    private final AtomicInteger refCount;

    @GuardedBy("this")
    private final List<SyncFenceCompat> pendingFences;

    private ReferenceCountedBuffer(HardwareBuffer hardwareBuffer) {
      this.hardwareBuffer = hardwareBuffer;
      this.refCount = new AtomicInteger(1); // Starts at 1 (owned by Processor)
      this.pendingFences = new ArrayList<>();
    }

    private BufferReference acquireReference() {
      refCount.incrementAndGet();
      return new BufferReference(this);
    }

    /**
     * Decrements the shared counter. If zero, waits for all fences and closes the buffer.
     *
     * <p>Called on {@link #releaseBufferExecutor}.
     */
    private void release(@Nullable SyncFenceCompat fence) {
      if (fence != null) {
        synchronized (this) {
          pendingFences.add(fence);
        }
      }
      int newRefCount = refCount.decrementAndGet();
      // The frame should only be released once.
      checkState(newRefCount >= 0);
      if (newRefCount == 0) {
        cleanup();
      }
    }

    /**
     * Waits for all collected fences to signal, closes them, and then closes the HardwareBuffer.
     */
    private void cleanup() {
      List<SyncFenceCompat> fencesToWait;
      synchronized (this) {
        fencesToWait = new ArrayList<>(pendingFences);
        pendingFences.clear();
      }

      for (SyncFenceCompat fence : fencesToWait) {
        waitAndClose(fence);
      }
      if (SDK_INT >= 26) {
        hardwareBuffer.close();
      }
    }

    private static void waitAndClose(@Nullable SyncFenceCompat fence) {
      if (fence == null) {
        return;
      }
      try {
        boolean signaled = fence.await(RELEASE_TIMEOUT_MS);
        if (!signaled) {
          Log.w(TAG, "Timed out waiting for release fence.");
        }
        fence.close();
      } catch (ErrnoException e) {
        Log.w(TAG, "Error waiting for release fence.", e);
      } catch (IOException e) {
        Log.w(TAG, "Error closing release fence.", e);
      }
    }

    /**
     * A unique token representing a single frame's usage. Guaranteed to decrement the shared count
     * exactly once.
     */
    private static final class BufferReference {
      private final ReferenceCountedBuffer parent;
      private final AtomicBoolean hasReleased = new AtomicBoolean(false);

      private BufferReference(ReferenceCountedBuffer parent) {
        this.parent = parent;
      }

      /** Called on {@link #releaseBufferExecutor}. */
      private void release(@Nullable SyncFenceCompat fence) {
        // Ensure the parent count is only decremented once per reference instance.
        if (hasReleased.compareAndSet(false, true)) {
          parent.release(fence);
        } else {
          // If release is called multiple times, still close the extra fences to prevent leaks.
          waitAndClose(fence);
        }
      }
    }
  }
}
