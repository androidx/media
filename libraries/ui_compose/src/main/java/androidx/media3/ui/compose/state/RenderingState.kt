/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.ui.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun rememberRenderingState(player: Player): RenderingState {
  val renderingState = remember(player) { RenderingState(player) }
  LaunchedEffect(player) { renderingState.observe() }
  return renderingState
}

@UnstableApi
class RenderingState(private val player: Player) {
  var aspectRatio by mutableFloatStateOf(getAspectRatio(player))
    private set

  var renderedFirstFrame by mutableStateOf(false)
    private set

  var onRenderedFirstFrame: () -> Unit = {}

  var keepContentOnPlayerReset: Boolean = false

  private var lastPeriodUidWithTracks: Any? = null

  suspend fun observe(): Nothing =
    player.listen { events ->
      if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
        if (videoSize != VideoSize.UNKNOWN) {
          aspectRatio = getAspectRatio(player)
        }
      }
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
        renderedFirstFrame = true
        onRenderedFirstFrame()
      }
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
        // PlayerView's combo of updateForCurrentTrackSelections and onTracksChanged
        if (!suppressShutter(player)) {
          resetRenderedFirstFrame(player)
        }
      }
    }

  private fun getAspectRatio(player: Player): Float {
    val videoSize = player.videoSize
    val width = videoSize.width
    val height = videoSize.height
    return if ((height == 0 || width == 0)) 0f
    else (width * videoSize.pixelWidthHeightRatio) / height
  }

  private fun resetRenderedFirstFrame(player: Player) {
    val hasTracks =
      player.isCommandAvailable(Player.COMMAND_GET_TRACKS) && !player.currentTracks.isEmpty
    if (!keepContentOnPlayerReset && !hasTracks) {
      renderedFirstFrame = false
      return
    }
    if (hasTracks && !hasSelectedVideoTrack()) {
      renderedFirstFrame = false
      return
    }
  }

  private fun suppressShutter(player: Player): Boolean {
    // Suppress the update if transitioning to an unprepared period within the same window. This
    // is necessary to avoid closing the shutter when such a transition occurs. See:
    // https://github.com/google/ExoPlayer/issues/5507.
    val timeline =
      if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) player.currentTimeline
      else Timeline.EMPTY

    if (timeline.isEmpty) {
      lastPeriodUidWithTracks = null
      return false
    } else {
      val period = Timeline.Period()
      if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS) && !player.currentTracks.isEmpty) {
        lastPeriodUidWithTracks = timeline.getPeriod(player.currentPeriodIndex, period, true).uid
      } else {
        if (lastPeriodUidWithTracks != null) {
          val lastPeriodIndexWithTracks = timeline.getIndexOfPeriod(lastPeriodUidWithTracks!!)
          if (lastPeriodIndexWithTracks != C.INDEX_UNSET) {
            val lastWindowIndexWithTracks =
              timeline.getPeriod(lastPeriodIndexWithTracks, period).windowIndex
            if (player.currentMediaItemIndex == lastWindowIndexWithTracks) {
              // We're in the same media item. Suppress the update.
              return true
            }
          }
          lastPeriodUidWithTracks = null
        }
      }
    }
    return false
  }

  private fun hasSelectedVideoTrack(): Boolean {
    return player.isCommandAvailable(Player.COMMAND_GET_TRACKS) &&
        player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
  }
}