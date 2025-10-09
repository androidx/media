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
package androidx.media3.exoplayer.upstream;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import androidx.annotation.RestrictTo;
import androidx.media3.exoplayer.analytics.PlayerId;

/**
 * An {@link Allocator} that is aware of the {@link PlayerId} which it will make allocations for.
 */
@RestrictTo(LIBRARY_GROUP)
public interface PlayerIdAwareAllocator extends Allocator {

  /** Sets the {@link PlayerId} which the {@link Allocator} will make allocations for. */
  void setPlayerId(PlayerId playerId);

  /**
   * {@inheritDoc}
   *
   * <p>Returns the total number of bytes currently allocated for the {@link PlayerId} that has been
   * set to this instance.
   */
  @Override
  int getTotalBytesAllocated();
}
