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
package androidx.media3.exoplayer.video;

import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A non-secure {@link PlaceholderSurfaceInterface} backed by an {@link ImageReader}, which does not
 * require an EGL context, a GL texture, or a dedicated thread.
 *
 * <p>This implementation cannot be used for secure placeholder surfaces: configuring an {@link
 * ImageReader} for protected content requires passing {@link
 * HardwareBuffer#USAGE_PROTECTED_CONTENT}, which sets {@code GRALLOC_USAGE_PROTECTED} in the
 * consumer usage bits. When switching away from a secure placeholder surface, {@code ACodec}'s
 * {@code pushBlankBuffersToNativeWindow} allocates a 1x1 buffer combining the consumer usage bits
 * with {@code GRALLOC_USAGE_SW_WRITE_OFTEN} and attempts to CPU-map and zero it, crashing with a
 * {@code SIGSEGV} on devices whose Gralloc cannot CPU-map protected buffers (whereas {@link
 * android.graphics.SurfaceTexture} sets {@code NATIVE_WINDOW_CONSUMER_IS_PROTECTED} without adding
 * {@code GRALLOC_USAGE_PROTECTED} to the consumer usage bits).
 */
@RequiresApi(29)
/* package */ final class ImageReaderPlaceholderSurface implements PlaceholderSurfaceInterface {

  private final ImageReader imageReader;
  private final Surface surface;

  // HardwareBuffer.USAGE_COMPOSER_OVERLAY (added in API 33) is inlined as 0x800L, matching the
  // underlying GRALLOC_USAGE_HW_COMPOSER constant present in AOSP since API 14 (Android 4.0) and
  // AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY since API 29 (Android 10).
  @SuppressWarnings("InlinedApi")
  /* package */ ImageReaderPlaceholderSurface() {
    long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_COMPOSER_OVERLAY;
    // The size is irrelevant: the producer (e.g. MediaCodec) sets the buffer size on the queue.
    imageReader =
        ImageReader.newInstance(
            /* width= */ 1, /* height= */ 1, ImageFormat.PRIVATE, /* maxImages= */ 1, usage);
    // Drop any frames rendered to the surface, like the GL implementation does via updateTexImage.
    // Skipped frames released with releaseOutputBuffer(index, /* render= */ false) do not reach the
    // placeholder Surface, so this listener is unlikely to run frequently on the handler returned
    // by createHandlerForCurrentOrMainLooper().
    imageReader.setOnImageAvailableListener(
        ImageReaderPlaceholderSurface::acquireAndCloseNextImage,
        Util.createHandlerForCurrentOrMainLooper());
    surface = imageReader.getSurface();
  }

  @Override
  public Surface getSurface() {
    return surface;
  }

  @Override
  public boolean isSecure() {
    return false;
  }

  @Override
  public void release() {
    surface.release();
    imageReader.close();
  }

  private static void acquireAndCloseNextImage(ImageReader imageReader) {
    try {
      @Nullable Image image = imageReader.acquireNextImage();
      if (image != null) {
        image.close();
      }
    } catch (RuntimeException e) {
      // Ignore. The reader may already be closed.
    }
  }
}
