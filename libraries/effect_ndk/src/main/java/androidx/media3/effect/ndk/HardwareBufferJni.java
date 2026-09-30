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
package androidx.media3.effect.ndk;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.HardwareBufferJniWrapper;
import com.google.errorprone.annotations.InlineMe;

/**
 * JNI methods for HardwareBuffer interaction.
 *
 * @deprecated Use {@link androidx.media3.effect.HardwareBufferJni} instead.
 */
@RequiresApi(26)
@Deprecated
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class HardwareBufferJni implements HardwareBufferJniWrapper {

  /**
   * @deprecated Use {@link androidx.media3.effect.HardwareBufferJni#INSTANCE} instead.
   */
  @Deprecated public static final HardwareBufferJni INSTANCE = new HardwareBufferJni();

  private HardwareBufferJni() {}

  /**
   * Creates an EGLImage from a {@link HardwareBuffer}.
   *
   * @deprecated Use {@link
   *     androidx.media3.effect.HardwareBufferJni#nativeCreateEglImageFromHardwareBuffer(long,
   *     HardwareBuffer)} instead.
   */
  @InlineMe(
      replacement =
          "androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeCreateEglImageFromHardwareBuffer(displayHandle,"
              + " hardwareBuffer)")
  @Deprecated
  @Override
  public long nativeCreateEglImageFromHardwareBuffer(
      long displayHandle, HardwareBuffer hardwareBuffer) {
    return androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeCreateEglImageFromHardwareBuffer(
        displayHandle, hardwareBuffer);
  }

  /**
   * Binds an EGLImage to the specified texture target. Returns whether the binding is successful.
   *
   * @deprecated Use {@link androidx.media3.effect.HardwareBufferJni#nativeBindEGLImage(int, long)}
   *     instead.
   */
  @InlineMe(
      replacement =
          "androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeBindEGLImage(target,"
              + " eglImageHandle)")
  @Deprecated
  @Override
  public boolean nativeBindEGLImage(int target, long eglImageHandle) {
    return androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeBindEGLImage(
        target, eglImageHandle);
  }

  /**
   * Destroys an EGLImage. Returns whether the deletion is successful.
   *
   * @deprecated Use {@link androidx.media3.effect.HardwareBufferJni#nativeDestroyEGLImage(long,
   *     long)} instead.
   */
  @InlineMe(
      replacement =
          "androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeDestroyEGLImage(displayHandle,"
              + " imageHandle)")
  @Deprecated
  @Override
  public boolean nativeDestroyEGLImage(long displayHandle, long imageHandle) {
    return androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeDestroyEGLImage(
        displayHandle, imageHandle);
  }

  /**
   * Copies a {@link Bitmap} to a {@link HardwareBuffer}. Returns whether the copy is successful.
   *
   * <p>The destination {@link HardwareBuffer} must have {@link
   * HardwareBuffer#USAGE_CPU_WRITE_OFTEN} usage.
   *
   * <p>The source {@link Bitmap.Config} must match the destination {@link
   * HardwareBuffer#getFormat}, and be either {@link Bitmap.Config#ARGB_8888} and {@link
   * HardwareBuffer#RGBA_8888} or {@link Bitmap.Config#RGBA_1010102} and {@link
   * HardwareBuffer#RGBA_1010102}.
   *
   * @deprecated Use {@link
   *     androidx.media3.effect.HardwareBufferJni#nativeCopyBitmapToHardwareBuffer(Bitmap,
   *     HardwareBuffer)} instead.
   */
  @InlineMe(
      replacement =
          "androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(bitmap,"
              + " hb)")
  @Deprecated
  @Override
  public boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb) {
    return androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
        bitmap, hb);
  }

  /**
   * Copies the contents of a source {@link HardwareBuffer} to a destination {@link HardwareBuffer}.
   * Returns whether the copy is successful.
   *
   * <p>The source {@link HardwareBuffer} must have {@link HardwareBuffer#USAGE_CPU_READ_OFTEN}
   * usage, and the destination {@link HardwareBuffer} must have {@link
   * HardwareBuffer#USAGE_CPU_WRITE_OFTEN} usage.
   *
   * <p>The formats of the source and destination buffers must match, and be either {@link
   * HardwareBuffer#RGBA_8888} or {@link HardwareBuffer#RGBA_1010102}.
   *
   * @deprecated Use {@link
   *     androidx.media3.effect.HardwareBufferJni#nativeCopyHardwareBufferToHardwareBuffer(HardwareBuffer,
   *     HardwareBuffer)} instead.
   */
  @InlineMe(
      replacement =
          "androidx.media3.effect.HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(srcHb,"
              + " dstHb)")
  @Deprecated
  @Override
  public boolean nativeCopyHardwareBufferToHardwareBuffer(
      HardwareBuffer srcHb, HardwareBuffer dstHb) {
    return androidx.media3.effect.HardwareBufferJni.INSTANCE
        .nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb);
  }
}
