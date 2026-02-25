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
package androidx.media3.transformer;

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.effect.HardwareBufferFrame;

/**
 * Processes {@link HardwareBufferFrame} instances.
 *
 * <p>This interface should only be used to improve backwards compatibility with older API versions,
 * and is not intended for application use.
 */
@RestrictTo(Scope.LIBRARY_GROUP)
public interface HardwareBufferFrameProcessor extends AutoCloseable {

  /**
   * Converts the {@code inputFrame} to a new {@link HardwareBufferFrame}.
   *
   * <p>The implementation may return the {@code inputFrame} itself if no conversion is needed, or a
   * new frame backed by a {@link android.hardware.HardwareBuffer}.
   *
   * @param inputFrame The input {@link HardwareBufferFrame}.
   * @return The processed {@link HardwareBufferFrame}.
   */
  HardwareBufferFrame process(HardwareBufferFrame inputFrame);
}
