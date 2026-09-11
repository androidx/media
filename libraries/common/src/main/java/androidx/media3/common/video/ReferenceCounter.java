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
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Interface with lifecycle management and reference counting. */
@RestrictTo(Scope.LIBRARY_GROUP)
public interface ReferenceCounter {

  /** A builder for objects with lifecycle management and reference counting. */
  interface Builder {

    /**
     * Increments the reference count of the underlying resources when the object is built.
     *
     * @return This builder.
     */
    @CanIgnoreReturnValue
    Builder shouldIncrementReferenceCount();
  }

  /**
   * Releases this handle to the underlying storage.
   *
   * @param releaseFence A {@link SyncFenceWrapper} that must signal before the underlying resources
   *     can be fully released, or {@code null} if the resources can be released immediately.
   */
  void release(@Nullable SyncFenceWrapper releaseFence);
}
