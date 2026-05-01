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

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.HardwareBufferNativeHelpers;

/** Fake class to simulate {@link HardwareBufferNativeHelpers}. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 - Remove once Frame is production ready.
public class FakeHardwareBufferNativeHelpers implements HardwareBufferNativeHelpers {

  /** Whether the operations should succeed. */
  public boolean shouldSucceed;

  public FakeHardwareBufferNativeHelpers() {
    shouldSucceed = true;
  }

  @Override
  public boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb) {
    return shouldSucceed;
  }

  @Override
  public boolean nativeCopyHardwareBufferToHardwareBuffer(
      HardwareBuffer srcHb, HardwareBuffer dstHb) {
    return shouldSucceed;
  }
}
