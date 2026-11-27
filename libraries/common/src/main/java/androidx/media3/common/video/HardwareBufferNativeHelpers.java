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
package androidx.media3.common.video;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;

/** Wrapper for {@link HardwareBuffer} JNI methods. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 - Remove once Frame is production ready.
public interface HardwareBufferNativeHelpers {

  /**
   * Copies a {@link Bitmap} to a {@link HardwareBuffer}.
   *
   * <p>The destination {@link HardwareBuffer} must have {@link
   * HardwareBuffer#USAGE_CPU_WRITE_OFTEN} usage.
   *
   * <p>The source {@link Bitmap.Config} must match the destination {@link
   * HardwareBuffer#getFormat()}, and be either {@link Bitmap.Config#ARGB_8888} and {@link
   * HardwareBuffer#RGBA_8888} or {@link Bitmap.Config#RGBA_1010102} and {@link
   * HardwareBuffer#RGBA_1010102}.
   *
   * @param bitmap The source bitmap.
   * @param hb The destination hardware buffer.
   * @return Whether the copy is successful.
   */
  boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb);

  /**
   * Copies the contents of a source {@link HardwareBuffer} to a destination {@link HardwareBuffer}.
   *
   * <p>The source {@link HardwareBuffer} must have {@link HardwareBuffer#USAGE_CPU_READ_OFTEN}
   * usage, and the destination {@link HardwareBuffer} must have {@link
   * HardwareBuffer#USAGE_CPU_WRITE_OFTEN} usage.
   *
   * <p>The formats of the source and destination buffers must match, and be either {@link
   * HardwareBuffer#RGBA_8888} or {@link HardwareBuffer#RGBA_1010102}.
   *
   * @param srcHb The source hardware buffer.
   * @param dstHb The destination hardware buffer.
   * @return Whether the copy is successful.
   */
  boolean nativeCopyHardwareBufferToHardwareBuffer(HardwareBuffer srcHb, HardwareBuffer dstHb);
}
