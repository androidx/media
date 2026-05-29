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

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.compose.shortform.rememberPooledPlayer
import androidx.media3.demo.compose.shortform.rememberShortFormState
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.state.SlidingWindowEffect
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState

@OptIn(ExperimentalApi::class)
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
    itemCount = pagerState.pageCount,
    currentItemProvider = { pagerState.settledPage },
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
    val mediaItem = state.getMediaItem(page)
    val player =
      rememberPooledPlayer(
        mediaItem = mediaItem,
        playerPool = state.playerPool,
        playerSetup = { p: ExoPlayer ->
          val mediaSource =
            state.preloadManager.getMediaSource(mediaItem)
              ?: run {
                state.preloadManager.add(mediaItem, page)
                state.preloadManager.getMediaSource(mediaItem)!!
              }
          p.setMediaSource(mediaSource)
          p.prepare()
        },
        isActive = page == pagerState.settledPage,
      )

    val playPauseButtonState = rememberPlayPauseButtonState(player)

    Player(
      player = player,
      showControls = false,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize().noRippleClickable(playPauseButtonState::onClick),
    )
  }
}
