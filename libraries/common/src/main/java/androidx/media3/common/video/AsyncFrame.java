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

import androidx.annotation.Nullable;
import androidx.media3.common.util.ExperimentalApi;

/**
 * A data class storing a {@link Frame} and a {@link SyncFenceWrapper} that must be waited on before
 * accessing the frame's data.
 */
@ExperimentalApi // TODO: b/498176910 Remove once AsyncFrame is production ready.
public final class AsyncFrame {

  /** The underlying {@link Frame}. */
  public final Frame frame;

  /**
   * A sync fence which signals when the {@link #frame} can be used.
   *
   * <p>If this fence is set, the consumer must wait on the fence before reading or writing from the
   * {@linkplain #frame frame's} underlying data.
   *
   * <p>See <a
   * href="https://developer.android.com/reference/android/hardware/SyncFence">SyncFence</a>.
   *
   * <p>The consumer of this {@link AsyncFrame} is responsible for closing the fence after it has
   * been waited on.
   */
  @Nullable public final SyncFenceWrapper acquireFence;

  /** Creates a new instance. */
  public AsyncFrame(Frame frame, @Nullable SyncFenceWrapper acquireFence) {
    this.frame = frame;
    this.acquireFence = acquireFence;
  }
}
