/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.upstream.contentsteering;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.MediaSourceEventListener;

/** Tracks the content steering states for an adaptive stream. */
@UnstableApi
public interface ContentSteeringTracker {

  /**
   * Starts the {@link ContentSteeringTracker}.
   *
   * @param initialSteeringManifestUri The initial {@link Uri} of the steering manifest.
   * @param initialPathwayId The ID of the initial pathway to use before the first steering manifest
   *     is loaded, or {@code null} to allow the tracker to pick any available pathway before the
   *     first steering manifest is loaded.
   * @param eventDispatcher A {@link MediaSourceEventListener.EventDispatcher} for reporting load
   *     events.
   */
  void start(
      Uri initialSteeringManifestUri,
      @Nullable String initialPathwayId,
      MediaSourceEventListener.EventDispatcher eventDispatcher);

  /**
   * Excludes the current pathway for the given duration, in milliseconds.
   *
   * @param excludeDurationMs The duration for which to exclude the current pathway.
   * @return Whether the exclusion was successful.
   */
  boolean excludeCurrentPathway(long excludeDurationMs);

  /**
   * Returns whether the {@link ContentSteeringTracker} is active.
   *
   * <p>If this method returns {@code false}, the caller of the {@link ContentSteeringTracker}
   * should behave as if content steering is absent.
   */
  boolean isActive();

  /** Stops the {@link ContentSteeringTracker}. */
  void stop();
}
