/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.extractor;

import androidx.media3.common.util.UnstableApi;

/** A {@link SeekMap} that also allows seeking based on a specific track ID. */
@UnstableApi
public interface TrackAwareSeekMap extends SeekMap {

  /**
   * Returns whether seeking is possible for the given track ID.
   *
   * <p>This method is similar to {@link #isSeekable()}, but allows specifying a {@code trackId}.
   * The {@code trackId} is the same ID passed to {@link ExtractorOutput#track(int, int)}.
   *
   * @param trackId The ID of the track.
   * @return Whether seeking is possible for the track.
   */
  boolean isSeekable(int trackId);

  /**
   * Obtains seek points for the specified seek time in microseconds, using cue points from a
   * specific track.
   *
   * <p>This method is similar to {@link #getSeekPoints(long)}, but allows specifying a {@code
   * trackId}. The {@code trackId} is the same ID passed to {@link ExtractorOutput#track(int, int)}.
   * If the given {@code trackId} does not have any associated cue points, the implementation may
   * fall back to using a default or primary track.
   *
   * @param timeUs A seek time in microseconds.
   * @param trackId The ID of the track to use for finding seek points.
   * @return The corresponding seek points.
   */
  SeekPoints getSeekPoints(long timeUs, int trackId);
}
