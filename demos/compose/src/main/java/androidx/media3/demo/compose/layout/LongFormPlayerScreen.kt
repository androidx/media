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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import androidx.media3.demo.compose.text.FastForwardOverlay
import androidx.media3.demo.compose.text.PlaylistInfoBottomSheet
import androidx.media3.demo.compose.text.SeekOverlay
import androidx.media3.demo.compose.text.SeekOverlayState
import androidx.media3.demo.compose.text.rememberCastState
import androidx.media3.demo.compose.viewmodel.PlayerLifecycleViewModel
import androidx.media3.demo.compose.viewmodel.rememberPlayerWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
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
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  var currentContentScaleIndex by rememberSaveable { mutableIntStateOf(0) }
  var showPlaylist by rememberSaveable { mutableStateOf(false) }
  var showMiniController by rememberSaveable { mutableStateOf(false) }
  var showSettings by rememberSaveable { mutableStateOf(false) }
  var bottomControlsHeight by remember { mutableStateOf(0.dp) }
  val castState = rememberCastState(player)
  val isRemotePlayback = castState.isRemotePlayback
  val mediaRouteButtonState = rememberMediaRouteButtonState()

  var showControls by rememberSaveable { mutableStateOf(true) }
  var anyPointerDown by remember { mutableStateOf(false) }
  val playPauseButtonState = rememberPlayPauseButtonState(player)

  val hideJob = remember { mutableStateOf<Job?>(null) }
  fun scheduleHideControls() {
    hideJob.value?.cancel()
    if (!anyPointerDown) {
      hideJob.value = scope.launch {
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
      hideJob.value?.cancel()
    }
  }

  var size by remember { mutableStateOf(IntSize.Zero) }
  val seekOverlayState = remember { SeekOverlayState(scope) }
  var showFastForward by remember { mutableStateOf(false) }

  val playbackSpeedState = rememberPlaybackSpeedState(player)
  Box(
    modifier
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .pointerHoverIcon(if (showControls) PointerIcon.Default else PointerIcon(0))
  ) {
    Player(
      player = player,
      showControls = if (isRemotePlayback) true else showControls,
      modifier =
        Modifier.onGloballyPositioned { coordinates -> size = coordinates.size }
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
            fastForwardActionArea = { offset -> offset.x >= size.width / 2 },
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
      topControls = { player, showControls ->
        PlayerDefaults.TopControls(
          player,
          showControls,
          Modifier.fillMaxWidth().padding(horizontal = 15.dp),
        ) {
          Row(Modifier.align(Alignment.CenterEnd)) {
            CcButton(player = player)
            MediaRouteButton(state = mediaRouteButtonState)
            SettingsButton(onSettingsClick = { showSettings = true })
          }
        }
      },
      centerControls = { player, showControls ->
        if (!isRemotePlayback) {
          PlayerDefaults.CenterControls(player, showControls, Modifier.fillMaxWidth())
        }
      },
      bottomControls = { player, showControls ->
        PlayerDefaults.BottomControls(
          player,
          showControls,
          modifier =
            Modifier.fillMaxWidth().navigationBarsPadding().onSizeChanged {
              bottomControlsHeight = with(density) { it.height.toDp() }
            },
          above = { player ->
            if (isRemotePlayback) {
              PlayerDefaults.CenterControls(player, showControls, Modifier.fillMaxWidth())
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
    Column(Modifier.align(Alignment.TopStart)) {
      PlaylistButton(onClick = { showPlaylist = true })
      PlayingNowButton(showMiniController, onClick = { showMiniController = !showMiniController })
    }
    SeekOverlay(
      state = seekOverlayState,
      modifier =
        Modifier.align(
            if (seekOverlayState.seekAmountMs < 0) Alignment.CenterStart else Alignment.CenterEnd
          )
          .padding(horizontal = 20.dp),
    )
    if (showFastForward) {
      FastForwardOverlay(
        speed = playbackSpeedState.playbackSpeed,
        Modifier.navigationBarsPadding()
          .align(Alignment.BottomCenter)
          .background(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
          ),
      )
    }
    if (showMiniController) {
      val context = LocalContext.current
      val bitmapLoader = remember(context) { DataSourceBitmapLoader.Builder(context).build() }
      MiniController(
        player = player,
        modifier =
          Modifier.fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomControlsHeight + 10.dp),
        bitmapLoader = bitmapLoader,
        defaultArtwork = painterResource(R.drawable.media3_icon_default_album_image),
      )
    }
    if (showPlaylist) {
      PlaylistInfoBottomSheet(
        player = player,
        onDismissRequest = { showPlaylist = false },
        modifier = Modifier.fillMaxWidth(),
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

private val CONTROLS_VISIBILITY_TIMEOUT = 3000.milliseconds
