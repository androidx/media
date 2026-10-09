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

package androidx.media3.demo.compose.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.media3.cast.MediaRouteButton
import androidx.media3.cast.rememberMediaRouteButtonState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.demo.compose.R
import androidx.media3.demo.compose.buttons.CcButton
import androidx.media3.demo.compose.buttons.LabeledProgressSlider
import androidx.media3.demo.compose.buttons.SettingsBottomSheet
import androidx.media3.demo.compose.buttons.SettingsButton
import androidx.media3.demo.compose.text.CastingOverlay
import androidx.media3.demo.compose.text.CurrentMediaItemCard
import androidx.media3.demo.compose.text.FastForwardOverlay
import androidx.media3.demo.compose.text.PlaylistInfoBottomSheet
import androidx.media3.demo.compose.text.SeekOverlay
import androidx.media3.demo.compose.text.SeekOverlayState
import androidx.media3.demo.compose.text.rememberCastState
import androidx.media3.demo.compose.viewmodel.PlayerLifecycleViewModel
import androidx.media3.demo.compose.viewmodel.rememberPlayerWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.Artwork
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LongFormPlayerScreen(
  playlistName: String,
  mediaItems: List<MediaItem>,
  playerViewModel: PlayerLifecycleViewModel,
  modifier: Modifier = Modifier,
) {
  val player by
    rememberPlayerWithLifecycle(playerViewModel, mediaItems, playlistName, useCast = true)
  val localPlayer by playerViewModel.localPlayer.collectAsState()
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
    LongFormPlayerScreen(player, localPlayer, modifier = modifier.fillMaxSize())
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun LongFormPlayerScreen(
  player: Player?,
  localPlayer: ExoPlayer?,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var currentContentScaleIndex by rememberSaveable { mutableIntStateOf(0) }
  var showPlaylist by rememberSaveable { mutableStateOf(false) }
  var showMiniController by rememberSaveable { mutableStateOf(false) }
  var showSettings by rememberSaveable { mutableStateOf(false) }
  val castState = rememberCastState(player)
  val isRemotePlayback = castState.isRemotePlayback
  val mediaRouteButtonState = rememberMediaRouteButtonState()

  var showControls by rememberSaveable { mutableStateOf(true) }
  var anyPointerDown by remember { mutableStateOf(false) }
  val playPauseButtonState = rememberPlayPauseButtonState(player)

  val hideJobHolder = remember { JobHolder() }
  fun scheduleHideControls() {
    hideJobHolder.job?.cancel()
    if (!anyPointerDown) {
      hideJobHolder.job = scope.launch {
        delay(CONTROLS_VISIBILITY_TIMEOUT)
        showControls = false
      }
    }
  }

  LaunchedEffect(
    showControls,
    anyPointerDown,
    showSettings,
    mediaRouteButtonState.isPickerVisible,
    isRemotePlayback,
  ) {
    if (
      showControls &&
        !anyPointerDown &&
        !showSettings &&
        !mediaRouteButtonState.isPickerVisible &&
        !isRemotePlayback
    ) {
      scheduleHideControls()
    } else {
      hideJobHolder.job?.cancel()
    }
  }

  var size by remember { mutableStateOf(IntSize.Zero) }
  val seekOverlayState = remember { SeekOverlayState(scope) }
  var showFastForward by remember { mutableStateOf(false) }

  val playbackSpeedState = rememberPlaybackSpeedState(player)
  val presentationState = rememberPresentationState(player)
  val context = LocalContext.current
  val bitmapLoader = remember(context) { DataSourceBitmapLoader.Builder(context).build() }

  val errorPainter =
    rememberTintedPainter(
      painterResource(R.drawable.media3_icon_broken_image),
      MaterialTheme.colorScheme.primary,
    )
  val fallbackPainter =
    rememberTintedPainter(
      painterResource(R.drawable.media3_icon_default_album_image),
      MaterialTheme.colorScheme.primary,
    )

  BoxWithConstraints(
    modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()
  ) {
    val playerContainerModifier =
      Modifier.fillMaxWidth()
        .height(playerHeight(maxWidth, maxHeight, presentationState.videoAspectRatio))
    Column(Modifier.fillMaxSize()) {
      Box(
        playerContainerModifier.pointerHoverIcon(
          if (showControls) PointerIcon.Default else PointerIcon(0)
        )
      ) {
        val controlsVisible = isRemotePlayback || showControls
        Player(
          player = player,
          artwork = {
            Artwork(
              player = player,
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              bitmapLoader = bitmapLoader,
              error = errorPainter,
              fallback = fallbackPainter,
            )
          },
          modifier =
            Modifier.fillMaxSize()
              .onGloballyPositioned { coordinates -> size = coordinates.size }
              .playerGestures(
                onPointerDownChange = { anyPointerDown = it },
                onPointerMove = {
                  showControls = true
                  scheduleHideControls()
                },
                onToggleControls = { if (!isRemotePlayback) showControls = !showControls },
                playbackSpeedState = playbackSpeedState,
                seekBackButtonState = rememberSeekBackButtonState(player),
                seekForwardButtonState = rememberSeekForwardButtonState(player),
                seekBackActionArea = { offset -> offset.x < size.width / 2 },
                seekForwardActionArea = { offset -> offset.x >= size.width / 2 },
                onSeek = {
                  showControls = false
                  seekOverlayState.show(it)
                },
                // Only allow fast-forwarding if we are NOT showing the play button (i.e., we are
                // playing)
                // and the press is on the right half of the screen.
                fastForwardActionArea = { offset ->
                  !playPauseButtonState.showPlay && offset.x >= size.width / 2
                },
                onFastForward = {
                  showControls = false
                  showFastForward = it
                },
                onSpacebarRelease = {
                  if (!playPauseButtonState.showPlay) {
                    // Bring up the controls if we are about to pause
                    showControls = true
                  }
                  playPauseButtonState.onClick()
                },
              ),
          contentScale = CONTENT_SCALES[currentContentScaleIndex].second,
          shutter = {
            Box(Modifier.fillMaxSize().background(Color.Black))
            CastingOverlay(castState, Modifier.fillMaxSize())
          },
          topControls = {
            PlayerDefaults.TopControls(
              player,
              controlsVisible,
              Modifier.fillMaxWidth().padding(horizontal = 15.dp),
            ) {
              Row(Modifier.align(Alignment.CenterEnd)) {
                CcButton(player = player)
                MediaRouteButton(state = mediaRouteButtonState)
                SettingsButton(onSettingsClick = { showSettings = true })
              }
            }
          },
          centerControls = {
            if (!isRemotePlayback) {
              PlayerDefaults.CenterControls(player, controlsVisible, Modifier.fillMaxWidth())
            }
          },
          bottomControls = {
            PlayerDefaults.BottomControls(
              player,
              controlsVisible,
              modifier = Modifier.fillMaxWidth(),
              above = {
                if (isRemotePlayback) {
                  PlayerDefaults.CenterControls(player, controlsVisible, Modifier.fillMaxWidth())
                } else {
                  Box(Modifier.fillMaxWidth()) {
                    MuteButton(player, Modifier.align(Alignment.CenterEnd))
                  }
                }
              },
              progressSlider = {
                val sliderPlayer = if (isRemotePlayback) player else localPlayer
                LabeledProgressSlider(sliderPlayer)
              },
            )
          },
        )
        SeekOverlay(
          state = seekOverlayState,
          modifier =
            Modifier.align(
                if (seekOverlayState.seekAmountMs < 0) Alignment.CenterStart
                else Alignment.CenterEnd
              )
              .padding(horizontal = 20.dp),
        )
        if (showFastForward) {
          FastForwardOverlay(
            speed = playbackSpeedState.playbackSpeed,
            Modifier.align(Alignment.BottomCenter)
              .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
              ),
          )
        }
      }
      Box(Modifier.fillMaxWidth().weight(1f)) {
        Column(
          Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          CurrentMediaItemCard(player, Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaylistButton(onClick = { showPlaylist = true })
            PlayingNowButton(
              showMiniController,
              onClick = { showMiniController = !showMiniController },
            )
          }
        }
        if (showMiniController) {
          MiniController(
            player = player,
            modifier =
              Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            bitmapLoader = bitmapLoader,
            defaultArtwork = fallbackPainter,
          )
        }
      }
    }
    if (showPlaylist) {
      PlaylistInfoBottomSheet(
        player = player,
        onDismissRequest = { showPlaylist = false },
        modifier = Modifier.fillMaxWidth(),
        bitmapLoader = bitmapLoader,
      )
    }
    if (showSettings) {
      SettingsBottomSheet(
        player = player,
        onDismissRequest = { showSettings = false },
        contentScale = CONTENT_SCALES[currentContentScaleIndex].first,
        onContentScaleChange = {
          currentContentScaleIndex = currentContentScaleIndex.inc() % CONTENT_SCALES.size
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PlaylistButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
  Button(onClick, modifier) { Text("Playlist") }
}

@Composable
private fun PlayingNowButton(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  ElevatedButton(
    onClick = onClick,
    modifier = modifier,
    elevation =
      ButtonDefaults.elevatedButtonElevation(defaultElevation = if (visible) 0.dp else 8.dp),
  ) {
    Text("Playing Now")
  }
}

/**
 * Returns the height of the player: the top third of the screen, or more if needed to fit a
 * landscape video to the width of the screen, while leaving at least a quarter of the screen for
 * the content below the player, which scrolls if it doesn't fit.
 */
private fun playerHeight(screenWidth: Dp, screenHeight: Dp, videoAspectRatio: Float?): Dp {
  val videoHeightAtFullWidth =
    if (videoAspectRatio != null && videoAspectRatio > 1f) screenWidth / videoAspectRatio else 0.dp
  return min(max(screenHeight / 3, videoHeightAtFullWidth), screenHeight * 3 / 4)
}

@Composable
private fun rememberTintedPainter(painter: Painter, tint: Color): Painter {
  return remember(painter, tint) {
    object : Painter() {
      override val intrinsicSize
        get() = painter.intrinsicSize

      override fun DrawScope.onDraw() {
        with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) }
      }
    }
  }
}

private val CONTROLS_VISIBILITY_TIMEOUT = 3000.milliseconds

private class JobHolder(var job: Job? = null)
