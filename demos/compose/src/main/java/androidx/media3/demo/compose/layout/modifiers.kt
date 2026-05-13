/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.compose.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.PlaybackSpeedState
import androidx.media3.ui.compose.state.SeekBackButtonState
import androidx.media3.ui.compose.state.SeekForwardButtonState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
  clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null, // to prevent the ripple from the tap
  ) {
    onClick()
  }

@Composable
internal fun Modifier.reportPointerDown(onPointerDownChange: (Boolean) -> Unit): Modifier =
  this.pointerInput(onPointerDownChange) {
    awaitPointerEventScope {
      var isDown = false
      while (true) {
        val event = awaitPointerEvent()
        val currentlyPressed = event.changes.any { it.pressed }
        if (isDown != currentlyPressed) {
          isDown = currentlyPressed
          onPointerDownChange(isDown)
        }
      }
    }
  }

/**
 * Adds common media player gestures to a Composable.
 *
 * This modifier detects single taps to toggle controls and double taps to seek. Double tapping on
 * the area defined by [seekBackActionArea] will seek backward using the [seekBackButtonState],
 * while double tapping on the area defined by [seekForwardActionArea] will seek forward using the
 * [seekForwardButtonState].
 *
 * It also provides accessibility actions for seeking back and forward.
 *
 * @param onPointerDownChange Lambda invoked with `true` when any pointer goes down, and `false`
 *   when the last pointer goes up.
 * @param onPointerMove Lambda invoked when the pointer moves within the Composable.
 * @param onToggleControls Lambda invoked to show/hide player controls, typically used on a single
 *   tap.
 * @param seekBackButtonState State object for handling seek back actions and providing seek
 *   increment.
 * @param seekBackActionArea A function that takes the [Offset] of the tap and returns `true` if the
 *   tap is within the seek back region.
 * @param seekForwardButtonState State object for handling seek forward actions and providing seek
 *   increment.
 * @param seekForwardActionArea A function that takes the [Offset] of the tap and returns `true` if
 *   the tap is within the seek forward region.
 * @param onSeek Lambda invoked when a seek is performed, providing the seek amount in milliseconds
 *   (negative for seek back, positive for seek forward).
 * @param playbackSpeedState State for setting the playback speed.
 * @param fastForwardActionArea A function that takes the [Offset] of the press and returns `true`
 *   if the press is within the fast-forward long press region.
 * @param onFastForward Lambda invoked with whether fast-forwarding is performed or not.
 */
@Composable
internal fun Modifier.playerGestures(
  onPointerDownChange: ((Boolean) -> Unit)?,
  onPointerMove: (() -> Unit)?,
  onToggleControls: () -> Unit,
  seekBackButtonState: SeekBackButtonState?,
  seekBackActionArea: (Offset) -> Boolean = { false },
  seekForwardButtonState: SeekForwardButtonState?,
  seekForwardActionArea: (Offset) -> Boolean = { false },
  onSeek: (seekIncrementMillis: Long) -> Unit = {},
  playbackSpeedState: PlaybackSpeedState? = null,
  fastForwardActionArea: (Offset) -> Boolean = { false },
  onFastForward: (Boolean) -> Unit = {},
  fastForwardSpeed: Float = 2f,
  onSpacebarRelease: (() -> Unit)? = null,
): Modifier {
  val seekBackButtonDescription = stringResource(R.string.seek_back_button)
  val seekForwardButtonDescription = stringResource(R.string.seek_forward_button)
  var isFastForwarding by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }

  return this.focusRequester(focusRequester)
    .focusable()
    .onKeyEvent { event ->
      // Holding the spacebar will trigger multiple KeyDown events due to auto-repeat
      // Avoid multifiring by handling KeyUp instead
      if (event.type == KeyEventType.KeyUp && event.key == Key.Spacebar) {
        onSpacebarRelease?.invoke()
        true
      } else {
        false
      }
    }
    .pointerInput(
      onPointerDownChange,
      onPointerMove,
      seekBackButtonState,
      seekForwardButtonState,
      playbackSpeedState,
    ) {
      coroutineScope {
        launch {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.changes.any { it.pressed }) {
                focusRequester.requestFocus()
              }
              val isAnyPressed = event.changes.any { it.pressed }
              onPointerDownChange?.invoke(isAnyPressed)
              if (event.type == PointerEventType.Move) {
                onPointerMove?.invoke()
              }
            }
          }
        }

        detectTapGestures(
          onTap = { onToggleControls() },
          onDoubleTap = { offset ->
            when {
              seekBackActionArea(offset) -> {
                seekBackButtonState?.let {
                  onSeek(-it.seekBackAmountMs)
                  it.onClick()
                }
              }
              seekForwardActionArea(offset) -> {
                seekForwardButtonState?.let {
                  onSeek(it.seekForwardAmountMs)
                  it.onClick()
                }
              }
            }
          },
          onLongPress = { offset ->
            if (fastForwardActionArea(offset) && !isFastForwarding) {
              isFastForwarding = true
              onFastForward(true)
              playbackSpeedState?.temporarilyOverrideSpeedWith(fastForwardSpeed)
            }
          },
          onPress = {
            try {
              // Waits for the pointer to be released or the gesture to be canceled.
              tryAwaitRelease()
            } catch (c: CancellationException) {} finally {
              // Pointer is up
              if (isFastForwarding) {
                playbackSpeedState?.restoreOverriddenSpeed()
                onFastForward(false)
                isFastForwarding = false
              }
            }
          },
        )
      }
    }
    .semantics {
      customActions =
        listOf(
          CustomAccessibilityAction(seekBackButtonDescription) {
            seekBackButtonState?.onClick()
            true
          },
          CustomAccessibilityAction(seekForwardButtonDescription) {
            seekForwardButtonState?.onClick()
            true
          },
        )
    }
}
