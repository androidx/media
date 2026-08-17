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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener

// Snippets for the analytics developer guide.

object AnalyticsKt {

  @OptIn(UnstableApi::class)
  fun addAnalyticsListener(exoPlayer: ExoPlayer) {
    // [START add_analytics_listener]
    exoPlayer.addAnalyticsListener(
      object : AnalyticsListener {
        override fun onPlaybackStateChanged(eventTime: EventTime, @Player.State state: Int) {}

        override fun onDroppedVideoFrames(
          eventTime: EventTime,
          droppedFrames: Int,
          elapsedMs: Long,
        ) {}
      }
    )
    // [END add_analytics_listener]
  }

  @OptIn(UnstableApi::class)
  fun addPlaybackStatsListener(exoPlayer: ExoPlayer) {
    // [START add_playback_stats_listener]
    exoPlayer.addAnalyticsListener(
      PlaybackStatsListener(/* keepHistory= */ true) {
        eventTime: EventTime?,
        playbackStats: PlaybackStats?
        -> // Analytics data for the session started at `eventTime` is ready.
      }
    )
    // [END add_playback_stats_listener]
  }

  @OptIn(UnstableApi::class)
  fun logBasicPlaybackStats(playbackStats: PlaybackStats) {
    // [START log_basic_playback_stats]
    Log.d(
      "DEBUG",
      "Playback summary: " +
        "play time = " +
        playbackStats.totalPlayTimeMs +
        ", rebuffers = " +
        playbackStats.totalRebufferCount,
    )
    // [END log_basic_playback_stats]
  }

  @OptIn(UnstableApi::class)
  fun logDerivedPlaybackStats(playbackStats: PlaybackStats) {
    // [START log_derived_playback_stats]
    Log.d(
      "DEBUG",
      "Additional calculated summary metrics: " +
        "average video bitrate = " +
        playbackStats.meanVideoFormatBitrate +
        ", mean time between rebuffers = " +
        playbackStats.meanTimeBetweenRebuffers,
    )
    // [END log_derived_playback_stats]
  }

  @OptIn(UnstableApi::class)
  fun getTagForPlaybackState() {
    // [START get_tag_for_playback_state]
    PlaybackStatsListener(/* keepHistory= */ false) {
      eventTime: EventTime,
      playbackStats: PlaybackStats ->
      val mediaTag =
        eventTime.timeline
          .getWindow(eventTime.windowIndex, Timeline.Window())
          .mediaItem
          .localConfiguration
          ?.tag
      // Report playbackStats with mediaTag metadata.
    }
    // [END get_tag_for_playback_state]
  }

  private const val CUSTOM_EVENT_ID = 0

  // [START use_extended_analytics_collector]
  @OptIn(UnstableApi::class)
  private interface ExtendedListener : AnalyticsListener {
    fun onCustomEvent(eventTime: EventTime)
  }

  @OptIn(UnstableApi::class)
  private class ExtendedCollector : DefaultAnalyticsCollector(Clock.DEFAULT) {

    fun customEvent() {
      val eventTime = super.generateCurrentPlayerMediaPeriodEventTime()
      super.sendEvent(eventTime, CUSTOM_EVENT_ID) { listener: AnalyticsListener ->
        if (listener is ExtendedListener) {
          listener.onCustomEvent(eventTime)
        }
      }
    }
  }

  @OptIn(UnstableApi::class)
  fun useExtendedAnalyticsCollector(context: Context) {
    // Usage - Setup and listener registration.
    val player = ExoPlayer.Builder(context).setAnalyticsCollector(ExtendedCollector()).build()
    player.addAnalyticsListener(
      object : ExtendedListener {
        override fun onCustomEvent(eventTime: EventTime) {
          // Save custom event for analytics data.
        }
      }
    )
    // Usage - Triggering the custom event.
    (player.analyticsCollector as ExtendedCollector).customEvent()
  }
  // [END use_extended_analytics_collector]
}
