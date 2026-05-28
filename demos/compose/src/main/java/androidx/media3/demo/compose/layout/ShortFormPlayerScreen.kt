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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.compose.shortform.ShortFormState
import androidx.media3.demo.compose.shortform.rememberShortFormState
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.state.SlidingWindowEffect
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import kotlinx.coroutines.launch

@Composable
internal fun ShortFormPlayerScreen(
  playlistName: String,
  mediaItems: List<MediaItem>,
  modifier: Modifier = Modifier,
) {
  val state = rememberShortFormState(mediaItems)

  if (!state.isReady) {
    // PreloadManager and PlayerPool are still initializing
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }
  val pagerState = rememberPagerState { mediaItems.size }

  SlidingWindowEffect(
    pagerState = pagerState,
    maxLookbehind = 3,
    maxLookahead = 6,
    batchSize = 4,
    prefetchDistance = 2,
    onRangeEnterWindow = state::addMediaItems,
    onRangeLeaveWindow = state::removeMediaItems,
  )

  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect(state::updateCurrentPage)
  }

  VerticalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
    ShortFormPlayer(
      mediaItem = state.getMediaItem(page),
      pageIndex = page,
      state = state,
      isPageActive = page == pagerState.settledPage,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun ShortFormPlayer(
  mediaItem: MediaItem,
  pageIndex: Int,
  state: ShortFormState,
  isPageActive: Boolean,
  modifier: Modifier = Modifier,
) {
  // Why we need 3 player variables:
  // -- player (The UI State): The Compose MutableState that is required for the UI to
  // recompose when the player is loaded. Kotlin prevents smart casting it to a non-null type, as
  // the compiler cannot guarantee the getter will consistently return a non-null value. We only use
  // it in the Player composable.
  // -- acquiredPlayer (The Lifecycle Net): A mutable var declared **outside** the coroutine. This
  // reference prevents memory leaks during asynchronous cancellation. It guarantees onDispose has a
  // hard reference to the exact player we acquired.
  // -- p (The Local Execution val): This is a short-lived, immutable local variable inside the
  // launch block that can never change or be null. It is safe for p.setMediaSource(...) calls.
  var player: ExoPlayer? by remember { mutableStateOf(null) }
  val scope = rememberCoroutineScope()

  DisposableEffect(state) {
    var acquiredPlayer: ExoPlayer? = null
    val job = scope.launch {
      val p = state.playerPool.acquirePlayer()
      acquiredPlayer = p
      player = p

      val mediaSource =
        state.preloadManager.getMediaSource(mediaItem)
          ?: run {
            state.preloadManager.add(mediaItem, pageIndex)
            state.preloadManager.getMediaSource(mediaItem)!!
          }
      p.setMediaSource(mediaSource)
      p.prepare()
      if (isPageActive) state.playerPool.play(p)
    }
    onDispose {
      job.cancel()
      state.playerPool.returnToPool(acquiredPlayer)
      player = null
    }
  }

  LifecycleStartEffect(isPageActive, player) {
    if (isPageActive) {
      player?.let(state.playerPool::play)
    } else {
      player?.pause()
    }
    onStopOrDispose { player?.pause() }
  }

  val playPauseButtonState = rememberPlayPauseButtonState(player)
  Player(
    player = player,
    showControls = false,
    contentScale = ContentScale.Crop,
    modifier = modifier.noRippleClickable(playPauseButtonState::onClick),
  )
}
