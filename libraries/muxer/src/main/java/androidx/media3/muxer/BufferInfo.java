/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/** Contains metadata of a sample {@link ByteBuffer}. */
@UnstableApi
public final class BufferInfo {
  /** The presentation timestamp of sample (in microseconds). */
  public final long presentationTimeUs;

  /** The amount of data (in bytes) in the buffer. */
  public final int size;

  /** The buffer flags, which should be a combination of the {@code C.BUFFER_FLAG_*}. */
  public final @C.BufferFlags int flags;

  /**
   * Creates an instance.
   *
   * @param presentationTimeUs The presentation timestamp of sample (in microseconds).
   * @param size The amount of data (in bytes) in the buffer.
   * @param flags The buffer flags, which should be a combination of the {@code C.BUFFER_FLAG_*}.
   */
  public BufferInfo(long presentationTimeUs, int size, @C.BufferFlags int flags) {
    this.presentationTimeUs = presentationTimeUs;
    this.size = size;
    this.flags = flags;
  }
}
