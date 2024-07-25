/*
 * Copyright (C) 2024 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;

/** Provides methods to check the suitability of media outputs. */
@RequiresApi(35)
@RestrictTo(LIBRARY_GROUP)
@UnstableApi
public interface SuitableOutputChecker {
  /**
   * Enables the current instance to receive updates on the suitable media outputs.
   *
   * <p>When the caller no longer requires updated information, they must call this method with
   * {@code false}.
   *
   * @param isEnabled True if this instance should receive the updates.
   */
  void setEnabled(boolean isEnabled);

  /**
   * Returns whether any audio output is suitable for the media playback.
   *
   * @throws IllegalStateException if this instance is not enabled to receive the updates on
   *     suitable media outputs.
   */
  boolean isSelectedRouteSuitableForPlayback();
}
