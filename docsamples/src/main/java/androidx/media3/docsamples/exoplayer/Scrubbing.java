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
package androidx.media3.docsamples.exoplayer;

import android.view.View;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import org.jspecify.annotations.Nullable;

/** Snippets for Scrubbing mode. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
@OptIn(markerClass = UnstableApi.class)
public class Scrubbing {

  // [START custom_scrubber_lifecycle]
  public static void onScrubStart(ExoPlayer exoPlayer, long positionMs) {
    exoPlayer.setScrubbingModeEnabled(true);
    exoPlayer.seekTo(positionMs);
  }

  public static void onScrubMove(ExoPlayer exoPlayer, long positionMs) {
    exoPlayer.seekTo(positionMs);
  }

  public static void onScrubStop(ExoPlayer exoPlayer, long positionMs, boolean canceled) {
    if (!canceled) {
      exoPlayer.seekTo(positionMs);
    }
    exoPlayer.setScrubbingModeEnabled(false);
  }

  // [END custom_scrubber_lifecycle]

  // [START view_scrubber_fractional_tolerance]
  private static final double SCRUBBING_TOLERANCE_DP = 2.0;

  public static View.OnLayoutChangeListener configureViewFractionalSeekTolerance(
      ExoPlayer exoPlayer, View scrubberView) {
    updateFractionalSeekTolerance(exoPlayer, scrubberView, scrubberView.getWidth());
    View.OnLayoutChangeListener listener =
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int widthPx = right - left;
          if (widthPx != oldRight - oldLeft) {
            updateFractionalSeekTolerance(exoPlayer, v, widthPx);
          }
        };
    scrubberView.addOnLayoutChangeListener(listener);
    return listener;
  }

  public static void clearViewFractionalSeekTolerance(
      ExoPlayer exoPlayer, View scrubberView, View.OnLayoutChangeListener listener) {
    scrubberView.removeOnLayoutChangeListener(listener);
    setFractionalSeekTolerance(exoPlayer, /* tolerance= */ null);
  }

  private static void updateFractionalSeekTolerance(
      ExoPlayer exoPlayer, View scrubberView, int widthPx) {
    float density = scrubberView.getResources().getDisplayMetrics().density;
    if (widthPx > 0 && density > 0f) {
      double tolerancePx = SCRUBBING_TOLERANCE_DP * density;
      setFractionalSeekTolerance(exoPlayer, Math.min(1.0, tolerancePx / widthPx));
    }
  }

  private static void setFractionalSeekTolerance(ExoPlayer exoPlayer, @Nullable Double tolerance) {
    exoPlayer.setScrubbingModeParameters(
        exoPlayer
            .getScrubbingModeParameters()
            .buildUpon()
            .setFractionalSeekTolerance(
                /* toleranceBefore= */ tolerance, /* toleranceAfter= */ tolerance)
            .build());
  }
  // [END view_scrubber_fractional_tolerance]
}
