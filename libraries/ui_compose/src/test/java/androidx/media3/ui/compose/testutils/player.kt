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

package androidx.media3.ui.compose.testutils

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PeriodData
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.test.utils.FakePlayer

internal fun createReadyPlayerWithTwoItems(): FakePlayer =
  FakePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist =
      listOf(
        MediaItemData.Builder("First").setDurationUs(1_000_000L).setIsSeekable(true).build(),
        MediaItemData.Builder("Second").setDurationUs(2_000_000L).setIsSeekable(true).build(),
      ),
  )

internal fun createReadyPlayerWithSingleItem(durationUs: Long = 10_000_000L): FakePlayer =
  FakePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist = listOf(MediaItemData.Builder("SingleItem").setDurationUs(durationUs).build()),
  )

internal fun createPlayerWithTracks(
  tracks: Tracks = Tracks.EMPTY,
  params: TrackSelectionParameters = TrackSelectionParameters.DEFAULT,
): FakePlayer {
  val mediaItemData = MediaItemData.Builder("id").setTracks(tracks).build()
  return FakePlayer(playlist = listOf(mediaItemData), trackSelectionParameters = params)
}

/**
 * Creates a ready player with a single media item clipped from 10s to the range 2s-7s by default.
 */
internal fun createReadyPlayerWithClippedItem(
  originalDurationUs: Long = 10_000_000L,
  clipStartUs: Long = 2_000_000L,
  clipEndUs: Long = 7_000_000L,
): FakePlayer {
  require(clipStartUs >= 0 && clipEndUs >= clipStartUs && originalDurationUs >= clipEndUs) {
    "Invalid clipping boundaries"
  }

  val windowDurationUs = clipEndUs - clipStartUs
  val positionInFirstPeriodUs = clipStartUs
  val periodDurationUs = clipEndUs

  return FakePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist =
      listOf(
        MediaItemData.Builder("SingleItem")
          .setDurationUs(windowDurationUs)
          .setPositionInFirstPeriodUs(positionInFirstPeriodUs)
          .setPeriods(
            listOf(
              PeriodData.Builder("Period")
                .setDurationUs(periodDurationUs)
                .setOriginalDurationUs(originalDurationUs)
                .build()
            )
          )
          .build()
      ),
  )
}

/**
 * Creates a ready player with a single 90s media item made of two periods of 30s and 60s by
 * default.
 */
internal fun createReadyPlayerWithMultiPeriodItem(
  period0DurationUs: Long = 30_000_000L,
  period1DurationUs: Long = 60_000_000L,
): FakePlayer {
  val totalDurationUs = period0DurationUs + period1DurationUs

  return FakePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist =
      listOf(
        MediaItemData.Builder("SingleItem")
          .setDurationUs(totalDurationUs)
          .setPeriods(
            listOf(
              PeriodData.Builder("Period0").setDurationUs(period0DurationUs).build(),
              PeriodData.Builder("Period1").setDurationUs(period1DurationUs).build(),
            )
          )
          .build()
      ),
  )
}

/**
 * Creates a ready player with a single media item, 10s by default, whose only period has an unknown
 * duration.
 */
internal fun createReadyPlayerWithUnknownPeriodDuration(
  durationUs: Long = 10_000_000L
): FakePlayer =
  FakePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist =
      listOf(
        MediaItemData.Builder("SingleItem")
          .setDurationUs(durationUs)
          .setPeriods(listOf(PeriodData.Builder("Period").setDurationUs(C.TIME_UNSET).build()))
          .build()
      ),
  )

/**
 * Creates a ready player with a single media item, clipped from 10s to the range 2s-7s by default,
 * that is currently playing an ad, 1s by default.
 */
internal fun createReadyPlayerPlayingAd(
  originalDurationUs: Long = 10_000_000L,
  clipStartUs: Long = 2_000_000L,
  clipEndUs: Long = 7_000_000L,
  adDurationUs: Long = 1_000_000L,
): FakePlayer {
  require(
    clipStartUs >= 0 &&
      clipEndUs >= clipStartUs &&
      originalDurationUs >= clipEndUs &&
      adDurationUs > 0
  ) {
    "Invalid clipping boundaries or ad duration"
  }

  val windowDurationUs = clipEndUs - clipStartUs
  val positionInFirstPeriodUs = clipStartUs
  val periodDurationUs = clipEndUs

  return FakePlayer(
      playbackState = STATE_READY,
      playWhenReady = true,
      playlist =
        listOf(
          MediaItemData.Builder("SingleItem")
            .setDurationUs(windowDurationUs)
            .setPositionInFirstPeriodUs(positionInFirstPeriodUs)
            .setPeriods(
              listOf(
                PeriodData.Builder("Period")
                  .setDurationUs(periodDurationUs)
                  .setOriginalDurationUs(originalDurationUs)
                  .setAdPlaybackState(
                    AdPlaybackState(/* adsId= */ "ads", /* adGroupTimesUs...= */ 0L)
                      .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                      .withAdDurationsUs(/* adGroupIndex= */ 0, adDurationUs)
                  )
                  .build()
              )
            )
            .build()
        ),
    )
    .apply { setCurrentAd(adGroupIndex = 0, adIndexInAdGroup = 0) }
}
