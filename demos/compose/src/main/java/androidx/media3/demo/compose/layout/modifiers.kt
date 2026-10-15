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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.media3.demo.compose.R
import androidx.media3.ui.compose.material3.R as Material3R
import androidx.media3.ui.compose.state.PlaybackSpeedState
import androidx.media3.ui.compose.state.SeekBackButtonState
import androidx.media3.ui.compose.state.SeekForwardButtonState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
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
 * If a [zoomState] is provided, pinching zooms into the video, and dragging with a single pointer
 * pans the video while it is zoomed in freely. Zoom and pan gestures cancel taps and long presses,
 * and accessibility actions are provided for zooming in to fill the player and resetting the zoom.
 *
 * @param onPointerDownChange Lambda invoked with `true` when any pointer goes down, and `false`
 *   when the last pointer goes up.
 * @param onPointerMove Lambda invoked when a pointer hovers/moves within the Composable without
 *   being pressed.
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
 * @param zoomState State for zooming into and panning the video, or `null` to disable zoom and pan
 *   gestures.
 * @param onZoomStart Lambda invoked when a zoom or pan gesture starts.
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
  zoomState: ZoomState? = null,
  onZoomStart: () -> Unit = {},
): Modifier {
  val seekBackButtonDescription = stringResource(Material3R.string.seek_back_button)
  val seekForwardButtonDescription = stringResource(Material3R.string.seek_forward_button)
  val zoomToFillDescription = stringResource(R.string.zoom_to_fill)
  val resetZoomDescription = stringResource(R.string.reset_zoom)
  var isFastForwarding by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val currentOnZoomStart by rememberUpdatedState(onZoomStart)

  return this.focusRequester(focusRequester)
    .focusable()
    .onPreviewKeyEvent {
      onPointerMove?.invoke()
      false
    }
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
              if (event.type == PointerEventType.Move && !isAnyPressed) {
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
                seekBackButtonState
                  ?.takeIf { it.isEnabled }
                  ?.run {
                    onSeek(-seekBackAmountMs)
                    onClick()
                  }
              }
              seekForwardActionArea(offset) -> {
                seekForwardButtonState
                  ?.takeIf { it.isEnabled }
                  ?.run {
                    onSeek(seekForwardAmountMs)
                    onClick()
                  }
              }
            }
          },
          onLongPress = { offset ->
            if (
              fastForwardActionArea(offset) &&
                !isFastForwarding &&
                playbackSpeedState?.isEnabled == true
            ) {
              isFastForwarding = true
              onFastForward(true)
              playbackSpeedState.temporarilyOverrideSpeedWith(fastForwardSpeed)
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
    .then(
      if (zoomState != null) {
        // Comes after the tap gestures to receive pointer events before them, so that consuming the
        // events of zoom and pan gestures cancels the tap gestures
        Modifier.pointerInput(zoomState) {
          detectZoomGestures(
            canZoom = { zoomState.isEnabled && !isFastForwarding },
            canPan = { zoomState.canPan && !isFastForwarding },
            onGestureStart = {
              zoomState.onGestureStart()
              currentOnZoomStart()
            },
            onGesture = zoomState::onGesture,
            onGestureEnd = zoomState::onGestureEnd,
          )
        }
      } else {
        Modifier
      }
    )
    .semantics {
      customActions = buildList {
        if (seekBackButtonState?.isEnabled == true) {
          add(
            CustomAccessibilityAction(seekBackButtonDescription) {
              seekBackButtonState.onClick()
              true
            }
          )
        }
        if (seekForwardButtonState?.isEnabled == true) {
          add(
            CustomAccessibilityAction(seekForwardButtonDescription) {
              seekForwardButtonState.onClick()
              true
            }
          )
        }
        if (zoomState?.isEnabled == true) {
          if (zoomState.canZoomToFill && zoomState.mode != ZoomMode.FILL) {
            add(
              CustomAccessibilityAction(zoomToFillDescription) {
                zoomState.zoomToFill()
                true
              }
            )
          }
          if (zoomState.mode != ZoomMode.ORIGINAL) {
            add(
              CustomAccessibilityAction(resetZoomDescription) {
                zoomState.resetZoom()
                true
              }
            )
          }
        }
      }
    }
}

/**
 * Detects zoom gestures with multiple pointers, and pan gestures with a single pointer if [canPan].
 *
 * All pointer events are consumed as soon as a second pointer goes down while [canZoom], which
 * cancels tap gestures such as long presses even before the pointers move. Gestures that start on a
 * child that consumes the initial pointer event, such as a button, are ignored.
 *
 * This differs from [androidx.compose.foundation.gestures.transformable] and
 * [androidx.compose.foundation.gestures.detectTransformGestures], which only consume pointer events
 * once the pointers move beyond the touch slop, so a long press can trigger while the pointers stay
 * still (which in turn cancels `transformable`). They also don't provide callbacks for when a
 * gesture starts and ends, which are needed to snap the zoom when a gesture ends.
 *
 * @param canZoom Whether a gesture with multiple pointers can zoom. Otherwise, the gesture is
 *   ignored.
 * @param canPan Whether a gesture with a single pointer can pan. Otherwise, it is ignored until a
 *   second pointer goes down.
 * @param onGestureStart Lambda invoked when the pointers move beyond the touch slop.
 * @param onGesture Lambda invoked with the centroid of the pointers before the change, the pan and
 *   the zoom change, for each change after the gesture started.
 * @param onGestureEnd Lambda invoked when a started gesture ends.
 */
// TODO: b/473765632 - Delete custom detectZoomGestures once Compose Foundation provides a
// high-level zoomable API with gesture lifecycle callbacks.
private suspend fun PointerInputScope.detectZoomGestures(
  canZoom: () -> Boolean,
  canPan: () -> Boolean,
  onGestureStart: () -> Unit,
  onGesture: (centroid: Offset, pan: Offset, zoomChange: Float) -> Unit,
  onGestureEnd: () -> Unit,
) {
  awaitEachGesture {
    if (awaitFirstDown(requireUnconsumed = false).isConsumed) {
      return@awaitEachGesture
    }
    var isMultiTouch = false
    var isTransforming = false
    var accumulatedZoom = 1f
    var accumulatedPan = Offset.Zero
    while (true) {
      val event = awaitPointerEvent()
      if (event.changes.any { it.isConsumed } || event.changes.none { it.pressed }) {
        break
      }
      if (!isMultiTouch && event.changes.count { it.pressed } > 1) {
        if (!canZoom()) {
          break
        }
        isMultiTouch = true
      }
      val zoomChange = event.calculateZoom()
      val panChange = event.calculatePan()
      if (!isTransforming) {
        accumulatedZoom *= zoomChange
        accumulatedPan += panChange
        val zoomMotion = abs(1 - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
        val touchSlop = viewConfiguration.touchSlop
        if (
          (isMultiTouch || canPan()) &&
            (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop)
        ) {
          isTransforming = true
          onGestureStart()
        }
      }
      if (isTransforming && (zoomChange != 1f || panChange != Offset.Zero)) {
        onGesture(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
      }
      if (isMultiTouch || isTransforming) {
        event.changes.forEach { it.consume() }
      }
    }
    if (isTransforming) {
      onGestureEnd()
    }
  }
}
