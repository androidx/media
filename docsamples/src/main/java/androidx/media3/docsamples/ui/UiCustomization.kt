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

package androidx.media3.docsamples.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.Button
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.image.ImageOutput

/** Snippets for customization.md. */
object UiCustomizationKt {

  fun uiCustomizationsPlayPauseButton(
    player: Player,
    playPauseButton: ImageButton,
    playDrawable: Drawable,
    pauseDrawable: Drawable,
  ) {
    // [START play_pause_button]
    val shouldShowPlayButton: Boolean = Util.shouldShowPlayButton(player)
    playPauseButton.setImageDrawable(if (shouldShowPlayButton) playDrawable else pauseDrawable)
    playPauseButton.setOnClickListener { Util.handlePlayPauseButtonAction(player) }
    // [END play_pause_button]
  }

  private fun updatePlayPauseButton() {}

  private fun updateRepeatModeButton() {}

  fun uiCustomizationsListenToPlayerEvents(player: Player) {
    // [START listen_to_player_events]
    player.addListener(
      object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
          if (
            events.containsAny(
              Player.EVENT_PLAY_WHEN_READY_CHANGED,
              Player.EVENT_PLAYBACK_STATE_CHANGED,
              Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            )
          ) {
            updatePlayPauseButton()
          }
          if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED)) {
            updateRepeatModeButton()
          }
        }
      }
    )
    // [END listen_to_player_events]
  }

  fun uiCustomizationsAvailableCommands(nextButton: Button, player: Player) {
    // [START available_commands]
    nextButton.isEnabled = player.isCommandAvailable(COMMAND_SEEK_TO_NEXT)
    // [END available_commands]
  }

  @OptIn(UnstableApi::class)
  private abstract class FirstFrameAndImageExample : Player.Listener, ImageOutput {
    // [START first_frame_and_image]
    override fun onEvents(player: Player, events: Player.Events) {
      if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
        // If no video or image track: show shutter, hide image view.
        // Otherwise: do nothing to wait for first frame or image.
      }
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
        // Hide shutter, hide image view.
      }
    }

    override fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap) {
      // Show shutter, set image and show image view.
    }

    // [END first_frame_and_image]

    override fun onDisabled() {}
  }
}
