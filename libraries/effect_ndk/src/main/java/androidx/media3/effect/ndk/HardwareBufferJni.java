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

import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.effect.HardwareBufferJniWrapper;

/** JNI methods for HardwareBuffer interaction. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class HardwareBufferJni implements HardwareBufferJniWrapper {

  private static final LibraryLoader LOADER =
      new LibraryLoader("hardwareBufferJNI") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  public static final HardwareBufferJni INSTANCE = new HardwareBufferJni();

  private HardwareBufferJni() {
    checkState(LOADER.isAvailable());
  }

  /** Creates an EGLImage from a {@link HardwareBuffer}. */
  @Override
  public native long nativeCreateEglImageFromHardwareBuffer(
      long displayHandle, HardwareBuffer hardwareBuffer);

  /**
   * Binds an EGLImage to the specified texture target. Returns whether the binding is successful.
   */
  @Override
  public native boolean nativeBindEGLImage(int target, long eglImageHandle);

  /** Destroys an EGLImage. Returns whether the deletion is successful. */
  @Override
  public native boolean nativeDestroyEGLImage(long displayHandle, long imageHandle);

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
   */
  @Override
  public native boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb);

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
   */
  @Override
  public native boolean nativeCopyHardwareBufferToHardwareBuffer(
      HardwareBuffer srcHb, HardwareBuffer dstHb);
}
