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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracker for active media periods in the player.
 *
 * <p>This class tracks the set of {@linkplain Renderer#STATE_ENABLED enabled} {@link Renderer}s and
 * determines which one is currently "active" (i.e., corresponds to the earliest period in the
 * player's timeline).
 *
 * <p>Methods in this class are not thread-safe and must be called from the ExoPlayer playback
 * thread.
 */
/* package */ final class RendererTracker {

  /**
   * A {@link Renderer} that provides its current {@link MediaPeriodId} and {@link Timeline} for
   * tracking.
   */
  /* package */ interface TrackedRenderer extends Renderer {
    /** Returns the {@link MediaPeriodId} of the renderer, or null if unset. */
    @Nullable
    MediaPeriodId getTrackedMediaPeriodId();

    /** Returns the current {@link Timeline} containing the renderer's stream, or null if unset. */
    @Nullable
    Timeline getTrackedTimeline();
  }

  private final Set<TrackedRenderer> renderers = new HashSet<>();

  public RendererTracker() {}

  /**
   * Registers a renderer.
   *
   * <p>Should be called when the renderer is enabled.
   */
  public void addRenderer(TrackedRenderer renderer) {
    renderers.add(renderer);
  }

  /**
   * Unregisters a renderer.
   *
   * <p>Should be called when the renderer is disabled.
   */
  public void removeRenderer(TrackedRenderer renderer) {
    renderers.remove(renderer);
  }

  /**
   * Returns the currently active renderer from the registered ones, or null if none are active.
   *
   * <p>The active renderer is the one associated with the earliest period in its current {@link
   * Timeline}.
   */
  @Nullable
  public Renderer getActiveRenderer() {
    if (renderers.isEmpty()) {
      return null;
    }

    @Nullable Renderer activeRenderer = null;
    @Nullable MediaPeriodId activePeriodId = null;
    int activePeriodIndex = C.INDEX_UNSET;

    for (TrackedRenderer renderer : renderers) {
      Timeline timeline = renderer.getTrackedTimeline();
      if (timeline == null || timeline.isEmpty()) {
        continue;
      }
      MediaPeriodId periodId = renderer.getTrackedMediaPeriodId();
      if (periodId == null) {
        // The renderer is enabled but has not yet been assigned a media period.
        continue;
      }
      int periodIndex = timeline.getIndexOfPeriod(periodId.periodUid);
      if (periodIndex == C.INDEX_UNSET) {
        // The renderer's period does not exist in the current timeline (e.g. from an earlier
        // playlist state or a removed item), so ignore it.
        continue;
      }

      if (activeRenderer == null
          || comparePeriods(periodId, periodIndex, checkNotNull(activePeriodId), activePeriodIndex)
              < 0) {
        activeRenderer = renderer;
        activePeriodId = periodId;
        activePeriodIndex = periodIndex;
      }
    }

    return activeRenderer;
  }

  /**
   * Compares two periods to determine which one is earlier in playback order.
   *
   * <p>Comparison prioritizes {@link MediaPeriodId#windowSequenceNumber} to correctly order periods
   * across playlist window transitions and repeated/looping sequences. If window sequence numbers
   * are identical, relative order is determined by period index within the {@link Timeline}.
   */
  private static int comparePeriods(
      MediaPeriodId p1, int periodIndex1, MediaPeriodId p2, int periodIndex2) {
    // Window sequence numbers are assigned monotonically by ExoPlayer in playback order.
    // Periods with smaller window sequence numbers are played earlier, even if periods repeat
    // in a loop or playlist.
    if (p1.windowSequenceNumber != p2.windowSequenceNumber) {
      return Long.compare(p1.windowSequenceNumber, p2.windowSequenceNumber);
    }
    // For periods within the same window sequence, order is determined by their index in the
    // timeline.
    return Integer.compare(periodIndex1, periodIndex2);
  }
}
