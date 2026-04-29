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

import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;

/** A {@link Frame} backed by a {@link HardwareBuffer}. */
@ExperimentalApi // TODO: b/498176910 - Remove once Frame is production ready.
public interface HardwareBufferFrame extends Frame {

  /** A Builder for {@link HardwareBufferFrame} instances. */
  interface Builder {

    /**
     * Sets the {@linkplain Frame#getMetadata() metadata} associated with the frame.
     *
     * @param metadata The metadata to associate with this frame.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    Builder setMetadata(Map<String, Object> metadata);

    /**
     * Sets the {@linkplain Frame#getContentTimeUs() content time} of the frame.
     *
     * @param contentTimeUs The presentation timestamp of the frame, in microseconds.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    Builder setContentTimeUs(long contentTimeUs);

    /**
     * Builds a {@link HardwareBufferFrame} instance.
     *
     * @return The built {@link HardwareBufferFrame}.
     */
    HardwareBufferFrame build();
  }

  /**
   * Returns the {@link HardwareBuffer} backing this frame.
   *
   * @return The underlying {@link HardwareBuffer}.
   */
  @RequiresApi(26)
  HardwareBuffer getHardwareBuffer();

  /**
   * Returns a {@link Builder} initialized with the values of this instance.
   *
   * @return A {@link Builder} that can be used to create a modified copy of this frame.
   */
  Builder buildUpon();
}
