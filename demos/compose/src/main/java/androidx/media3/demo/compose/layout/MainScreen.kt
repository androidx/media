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

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.compose.buttons.LabeledProgressSlider
import androidx.media3.demo.compose.buttons.PlaybackSpeedBottomSheetButton
import androidx.media3.demo.compose.text.CurrentItemInfo
import androidx.media3.demo.compose.text.FastForwardOverlay
import androidx.media3.demo.compose.text.PlaylistInfoBottomSheet
import androidx.media3.demo.compose.text.SeekOverlay
import androidx.media3.demo.compose.text.SeekOverlayState
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import androidx.media3.ui.compose.text.CurrentMediaItemBox
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(playlistName: String, mediaItems: List<MediaItem>, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<Player?>(null) }

  // See the following resources
  // https://developer.android.com/topic/libraries/architecture/lifecycle#onStop-and-savedState
  // https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-window_mode_configuration
  // https://developer.android.com/develop/ui/compose/layouts/adaptive/support-multi-window-mode#android_9

  if (Build.VERSION.SDK_INT > 23) {
    // Initialize/release in onStart()/onStop() only because in a multi-window environment multiple
    // apps can be visible at the same time. The apps that are out-of-focus are paused, but video
    // playback should continue.
    LifecycleStartEffect(mediaItems, playlistName) {
      player = initializePlayer(context, playlistName, mediaItems)
      onStopOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  } else {
    // Call to onStop() is not guaranteed, hence we release the Player in onPause() instead
    LifecycleResumeEffect(mediaItems, playlistName) {
      player = initializePlayer(context, playlistName, mediaItems)
      onPauseOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  }

  MainScreen(player, modifier = modifier.fillMaxSize())
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun MainScreen(player: Player?, modifier: Modifier = Modifier) {
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  var currentContentScaleIndex by remember { mutableIntStateOf(0) }
  var showPlaylist by rememberSaveable { mutableStateOf(false) }
  var showCurrentMediaItemInfo by rememberSaveable { mutableStateOf(false) }
  var bottomControlsHeight by remember { mutableStateOf(0.dp) }

  var showControls by remember { mutableStateOf(true) }
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

  LaunchedEffect(showControls, anyPointerDown) {
    if (showControls && !anyPointerDown) {
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
      showControls = showControls,
      modifier =
        Modifier.onGloballyPositioned { coordinates -> size = coordinates.size }
          .playerGestures(
            onPointerDownChange = { anyPointerDown = it },
            onPointerMove = {
              showControls = true
              scheduleHideControls()
            },
            onToggleControls = { showControls = !showControls },
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
      bottomControls = { player, showControls ->
        BottomControlsWithLabeledProgress(
          player,
          showControls,
          modifier =
            Modifier.fillMaxWidth()
              .background(
                brush =
                  Brush.verticalGradient(
                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                  )
              )
              .padding(horizontal = 15.dp)
              .onSizeChanged { bottomControlsHeight = with(density) { it.height.toDp() } },
          interactionModifier = Modifier.reportPointerDown { anyPointerDown = it },
        )
      },
    )
    Column(Modifier.align(Alignment.TopStart)) {
      PlaylistButton(onClick = { showPlaylist = true })
      PlayingNowButton(
        showCurrentMediaItemInfo,
        onClick = { showCurrentMediaItemInfo = !showCurrentMediaItemInfo },
      )
    }
    ContentScaleButton(
      currentContentScaleIndex,
      Modifier.align(Alignment.TopCenter),
      onClick = { currentContentScaleIndex = currentContentScaleIndex.inc() % CONTENT_SCALES.size },
    )
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
          )
          .padding(4.dp),
      )
    }
    if (showCurrentMediaItemInfo) {
      CurrentMediaItemBox(player) {
        Box(
          Modifier.align(Alignment.BottomCenter)
            .padding(bottom = bottomControlsHeight + 10.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        ) {
          CurrentItemInfo(meta = mediaMetadata, Modifier.padding(8.dp))
        }
      }
    }
    if (showPlaylist) {
      PlaylistInfoBottomSheet(
        player = player,
        onDismissRequest = { showPlaylist = false },
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
  Button(onClick, modifier) { Text("Playing\nNow" + if (visible) " <<" else " >>") }
}

@Composable
private fun ContentScaleButton(
  currentContentScaleIndex: Int,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Button(onClick, modifier) {
    Text("ContentScale is ${CONTENT_SCALES[currentContentScaleIndex].first}")
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControlsWithLabeledProgress(
  player: Player?,
  showControls: Boolean,
  modifier: Modifier = Modifier,
  interactionModifier: Modifier = Modifier,
) {
  AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
    Column(modifier) {
      LabeledProgressSlider(player)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PositionAndDurationText(player, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        PlaybackSpeedBottomSheetButton(
          player,
          colors =
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
          sheetContentModifier = interactionModifier,
        )
        ShuffleButton(
          player,
          colors =
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        )
        RepeatButton(
          player,
          colors =
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        )
      }
    }
  }
}

private fun initializePlayer(
  context: Context,
  playlistName: String,
  mediaItems: List<MediaItem>,
): Player =
  ExoPlayer.Builder(context).build().apply {
    setMediaItems(mediaItems)
    setPlaylistMetadata(MediaMetadata.Builder().setTitle(playlistName).build())
    prepare()
  }

private val CONTROLS_VISIBILITY_TIMEOUT = 3000.milliseconds
