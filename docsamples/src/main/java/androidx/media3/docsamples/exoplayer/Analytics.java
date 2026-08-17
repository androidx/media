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

import android.content.Context;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.analytics.PlaybackStats;
import androidx.media3.exoplayer.analytics.PlaybackStatsListener;

/** Snippets for the analytics developer guide. */
@SuppressWarnings({"unused", "CheckReturnValue", "UnusedAnonymousClass", "EffectivelyPrivate"})
public final class Analytics {

  private Analytics() {}

  @OptIn(markerClass = UnstableApi.class)
  public static void addAnalyticsListener(ExoPlayer exoPlayer) {
    // [START add_analytics_listener]
    exoPlayer.addAnalyticsListener(
        new AnalyticsListener() {
          @Override
          public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {}

          @Override
          public void onDroppedVideoFrames(
              EventTime eventTime, int droppedFrames, long elapsedMs) {}
        });
    // [END add_analytics_listener]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void addPlaybackStatsListener(ExoPlayer exoPlayer) {
    // [START add_playback_stats_listener]
    exoPlayer.addAnalyticsListener(
        new PlaybackStatsListener(
            /* keepHistory= */ true,
            (eventTime, playbackStats) -> {
              // Analytics data for the session started at `eventTime` is ready.
            }));
    // [END add_playback_stats_listener]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void logBasicPlaybackStats(PlaybackStats playbackStats) {
    // [START log_basic_playback_stats]
    Log.d(
        "DEBUG",
        "Playback summary: "
            + "play time = "
            + playbackStats.getTotalPlayTimeMs()
            + ", rebuffers = "
            + playbackStats.totalRebufferCount);
    // [END log_basic_playback_stats]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void logDerivedPlaybackStats(PlaybackStats playbackStats) {
    // [START log_derived_playback_stats]
    Log.d(
        "DEBUG",
        "Additional calculated summary metrics: "
            + "average video bitrate = "
            + playbackStats.getMeanVideoFormatBitrate()
            + ", mean time between rebuffers = "
            + playbackStats.getMeanTimeBetweenRebuffers());
    // [END log_derived_playback_stats]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void getTagForPlaybackState() {
    // [START get_tag_for_playback_state]
    new PlaybackStatsListener(
        /* keepHistory= */ false,
        (eventTime, playbackStats) -> {
          Object mediaTag =
              eventTime.timeline.getWindow(eventTime.windowIndex, new Timeline.Window())
                  .mediaItem
                  .localConfiguration
                  .tag;
          // Report playbackStats with mediaTag metadata.
        });
    // [END get_tag_for_playback_state]
  }

  private static final int CUSTOM_EVENT_ID = 0;

  // [START use_extended_analytics_collector]
  @OptIn(markerClass = UnstableApi.class)
  private interface ExtendedListener extends AnalyticsListener {
    void onCustomEvent(EventTime eventTime);
  }

  @OptIn(markerClass = UnstableApi.class)
  private static class ExtendedCollector extends DefaultAnalyticsCollector {
    public ExtendedCollector() {
      super(Clock.DEFAULT);
    }

    public void customEvent() {
      AnalyticsListener.EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
      sendEvent(
          eventTime,
          CUSTOM_EVENT_ID,
          listener -> {
            if (listener instanceof ExtendedListener) {
              ((ExtendedListener) listener).onCustomEvent(eventTime);
            }
          });
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void useExtendedAnalyticsCollector(Context context) {
    // Usage - Setup and listener registration.
    ExoPlayer player =
        new ExoPlayer.Builder(context).setAnalyticsCollector(new ExtendedCollector()).build();
    player.addAnalyticsListener(
        (ExtendedListener)
            eventTime -> {
              // Save custom event for analytics data.
            });
    // Usage - Triggering the custom event.
    ((ExtendedCollector) player.getAnalyticsCollector()).customEvent();
  }
  // [END use_extended_analytics_collector]
}
