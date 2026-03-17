/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.demo.shortform.lazycolumn.composable

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.lazycolumn.LazyColumnPlayerManager


@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun ShortFormLazyColumn(
    playerManager: LazyColumnPlayerManager
) {
    val lazyListState = rememberLazyListState()
    var currentPlayingIndex by remember { mutableIntStateOf(0) }
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { currentIndex ->
                currentPlayingIndex = currentIndex
                playerManager.updateCurrentIndex(currentIndex)
            }
    }

    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState)

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        flingBehavior = snapFlingBehavior
    ) {
        items(
            count = playerManager.getMediaCount(),
            key = { index -> index }
        ) { index ->
            val shouldPlay = index == currentPlayingIndex

            LazyColumnMediaItem(
                playerManager = playerManager,
                index = index,
                shouldPlay = shouldPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight)
                    .background(Color.Black)
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberSnapFlingBehavior(listState: LazyListState): FlingBehavior {
    return remember(listState) {
        snapFlingBehavior(
            snapLayoutInfoProvider = SnapLayoutInfoProvider(
                lazyListState = listState,
                snapPosition = SnapPosition.Start
            ),
            decayAnimationSpec = exponentialDecay(1.5f),
            snapAnimationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        )
    }
}