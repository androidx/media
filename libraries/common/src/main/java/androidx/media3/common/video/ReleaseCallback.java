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

/** A callback invoked when a reference-counted resource is fully released. */
@RestrictTo(Scope.LIBRARY_GROUP)
public interface ReleaseCallback {

  /**
   * Releases the underlying resources.
   *
   * @param releaseFence A {@link SyncFenceWrapper} that must signal when resources are no longer
   *     used by hardware units, or {@code null} if the resources can be released immediately.
   */
  void release(@Nullable SyncFenceWrapper releaseFence);
}
