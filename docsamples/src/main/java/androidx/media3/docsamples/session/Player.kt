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

package androidx.media3.docsamples.session

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi

/** Snippets for player.md. */
object PlayerKt {

  private class QueryPlaybackPosition(private val player: androidx.media3.common.Player) {
    private val handler = Handler(Looper.getMainLooper())

    // [START query_playback_position]
    fun checkPlaybackPosition(delayMs: Long): Boolean =
      handler.postDelayed(
        {
          val currentPosition = player.currentPosition
          // Update UI based on currentPosition
          checkPlaybackPosition(delayMs)
        },
        delayMs,
      )
    // [END query_playback_position]
  }

  @OptIn(UnstableApi::class)
  // [START simple_base_player]
  class CustomPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    override fun getState(): State {
      return State.Builder()
        .setAvailableCommands(Commands.EMPTY) // Set which playback commands the player can handle
        // Configure additional playback properties
        .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setCurrentMediaItemIndex(0)
        .setContentPositionMs(0)
        .build()
    }
  }
  // [END simple_base_player]
}
