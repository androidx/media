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

package androidx.media3.ui.compose.material3

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.Artwork
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType
import androidx.media3.ui.compose.material3.PlayerTokens.ErrorVerticalOffsetFromControls
import androidx.media3.ui.compose.material3.text.Subtitles

/**
 * A composable that provides a basic player UI layout with a shutter and hidden default controls.
 *
 * This composable consists of a [ContentFrame] that handles the rendering of the player's video,
 * overlaid with a shutter and the standard Material3 layouts provided by [PlayerDefaults]. This
 * includes [PlayerDefaults.TopControls], [PlayerDefaults.CenterControls], and
 * [PlayerDefaults.BottomControls].
 *
 * The controls are always hidden, because showing them permanently would occlude the video. To show
 * and hide them, for example when the user taps the player, use the overload that accepts
 * `showControls`. That overload also allows customizing the UI components.
 *
 * @param player The [Player] instance whose content is displayed.
 * @param modifier The [Modifier] to be applied to the outer [Box].
 */
@UnstableApi
@Composable
fun Player(player: Player?, modifier: Modifier = Modifier) {
  PlayerImpl(player, modifier)
}

/**
 * A composable that provides a basic player UI layout, combining a [ContentFrame] for displaying
 * player content with customizable controls and a shutter.
 *
 * This composable is designed to be a flexible container for building player interfaces. It
 * consists of a [ContentFrame] that handles the rendering of the player's video, overlaid with
 * optional controls and a shutter.
 *
 * By default, this component uses the standard Material3 layouts provided by [PlayerDefaults]. This
 * includes [PlayerDefaults.TopControls], [PlayerDefaults.CenterControls], and
 * [PlayerDefaults.BottomControls]. You can deeply customize the player UI by providing your own
 * composables to the slots of this composable, or by utilizing the [PlayerDefaults] layouts and
 * overriding only specific slots within them.
 *
 * The [Player] natively manages keyboard focus traversal between its control slots. Each provided
 * control slot ([topControls], [centerControls], and [bottomControls]) is wrapped in a focus group.
 * 1-dimensional focus traversal (e.g., using the Tab key) is configured to move sequentially from
 * the top controls, to the center controls, and down to the bottom controls. Backward traversal
 * moves in the reverse order.
 *
 * @param player The [Player] instance to be controlled and whose content is displayed.
 * @param modifier The [Modifier] to be applied to the outer [Box].
 * @param surfaceType The type of surface to use for video rendering. See [SurfaceType].
 * @param contentScale The scaling mode to apply to the content within the [ContentFrame].
 * @param keepContentOnReset Whether to keep the content visible when the player is reset.
 * @param subtitleOverlay A composable for rendering subtitles.
 * @param shutter A composable to be displayed as a shutter over the content. The default shutter is
 *   a black [Box].
 * @param artwork Optional composable slot to render artwork for the current media item.
 * @param showControls Whether the default controls should be visible. False by default, so that the
 *   video isn't occluded. Toggle it, for example, when the user taps the player. Custom control
 *   slots should manage their own visibility.
 * @param topControls A composable aligned with [Alignment.TopCenter].
 * @param centerControls A composable aligned with [Alignment.Center].
 * @param bottomControls A composable aligned with [Alignment.BottomCenter].
 * @param errorOverlay Slot for the error message overlay. Defaults to
 *   [PlayerDefaults.ErrorOverlay].
 */
@ExperimentalApi // TODO: b/490015547 - Move to stable/unstable
@Composable
fun Player(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  subtitleOverlay: @Composable () -> Unit = { Subtitles(player) },
  shutter: @Composable () -> Unit = PlayerDefaults::Shutter,
  artwork: (@Composable () -> Unit)? = {
    Artwork(player, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
  },
  showControls: Boolean = false,
  topControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.TopControls(player, showControls, Modifier.fillMaxWidth())
  },
  centerControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.CenterControls(player, showControls, Modifier.fillMaxWidth())
  },
  bottomControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.BottomControls(player, showControls)
  },
  errorOverlay: (@Composable BoxScope.() -> Unit)? = { PlayerDefaults.ErrorOverlay(player) },
) {
  PlayerImpl(
    player,
    modifier,
    surfaceType,
    contentScale,
    keepContentOnReset,
    subtitleOverlay,
    shutter,
    artwork,
    showControls,
    topControls,
    centerControls,
    bottomControls,
    errorOverlay,
  )
}

@Composable
private fun PlayerImpl(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  subtitleOverlay: @Composable () -> Unit = { Subtitles(player) },
  shutter: @Composable () -> Unit = PlayerDefaults::Shutter,
  artwork: (@Composable () -> Unit)? = {
    Artwork(player, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
  },
  showControls: Boolean = false,
  topControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.TopControls(player, showControls, Modifier.fillMaxWidth())
  },
  centerControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.CenterControls(player, showControls, Modifier.fillMaxWidth())
  },
  bottomControls: (@Composable BoxScope.() -> Unit)? = {
    PlayerDefaults.BottomControls(player, showControls)
  },
  errorOverlay: (@Composable BoxScope.() -> Unit)? = { PlayerDefaults.ErrorOverlay(player) },
) {
  val topControlsFocus = remember { FocusRequester() }
  val centerControlsFocus = remember { FocusRequester() }
  val bottomControlsFocus = remember { FocusRequester() }

  Box(modifier) {
    ContentFrame(
      player = player,
      surfaceType = surfaceType,
      contentScale = contentScale,
      keepContentOnReset = keepContentOnReset,
      artwork = artwork,
      overlay = subtitleOverlay,
      shutter = shutter,
    )

    // Error overlay should not cover the controls that can be used to recover from the error
    Box(
      Modifier.align(Alignment.Center).offset(y = ErrorVerticalOffsetFromControls).fillMaxWidth(),
      contentAlignment = Alignment.Center,
    ) {
      errorOverlay?.invoke(this)
    }

    // this = BoxScope of a container-Box
    Box(
      Modifier.align(Alignment.TopCenter)
        .focusRequester(topControlsFocus)
        .focusProperties { next = centerControlsFocus }
        .focusGroup()
    ) {
      topControls?.invoke(this)
    }
    Box(
      Modifier.align(Alignment.Center)
        .focusRequester(centerControlsFocus)
        .focusProperties {
          previous = topControlsFocus // Enables 'Shift + Tab' traversal
          next = bottomControlsFocus
        }
        .focusGroup()
    ) {
      centerControls?.invoke(this)
    }
    Box(
      Modifier.align(Alignment.BottomCenter)
        .focusRequester(bottomControlsFocus)
        .focusProperties { previous = centerControlsFocus }
        .focusGroup()
    ) {
      bottomControls?.invoke(this)
    }
  }
}
