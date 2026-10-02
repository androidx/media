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
package androidx.media3.test.utils;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.effect.HardwareBufferJniWrapper;

/**
 * Fake {@link HardwareBufferJniWrapper} that doesn't call into native code.
 *
 * <p>The bitmap and buffer copy methods are inherited from {@link FakeHardwareBufferNativeHelpers};
 * this class only adds fakes for the EGLImage methods. All operations return a successful result if
 * {@link #shouldSucceed} is {@code true} (the default), or a failure result otherwise.
 */
@RequiresApi(26)
@RestrictTo(LIBRARY_GROUP)
public final class FakeHardwareBufferJniWrapper extends FakeHardwareBufferNativeHelpers
    implements HardwareBufferJniWrapper {

  /** The fake EGLImage handle returned on success. */
  public static final long FAKE_EGL_IMAGE_HANDLE = 1L;

  @Override
  public long nativeCreateEglImageFromHardwareBuffer(
      long displayHandle, HardwareBuffer hardwareBuffer) {
    return shouldSucceed ? FAKE_EGL_IMAGE_HANDLE : 0L;
  }

  @Override
  public boolean nativeBindEGLImage(int target, long eglImageHandle) {
    return shouldSucceed;
  }

  @Override
  public boolean nativeDestroyEGLImage(long displayHandle, long imageHandle) {
    return shouldSucceed;
  }
}
