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
package androidx.media3.ui.compose.state

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi

/**
 * A side effect that observes a [PagerState] and calculates a sliding window of managed items.
 *
 * It invokes [onRangeEnterWindow] when items should be prepared/loaded, and [onRangeLeaveWindow]
 * when they fall outside the managed window boundaries and should be evicted.
 *
 * @param pagerState The [PagerState] to observe.
 * @param maxLookbehind The maximum number of items to keep actively managed in memory before the
 *   current page.
 * @param maxLookahead The maximum number of items to keep actively managed in memory after the
 *   current page.
 * @param batchSize The number of items to load/evict at once when crossing the prefetchDistance
 *   threshold.
 * @param prefetchDistance The distance from the edge of the window that triggers the next batch of
 *   preloads.
 * @param onRangeEnterWindow Callback invoked with the contiguous range of indices that have entered
 *   the window.
 * @param onRangeLeaveWindow Callback invoked with the contiguous range of indices that have left
 *   the window.
 */
@UnstableApi
@Composable
fun SlidingWindowEffect(
  pagerState: PagerState,
  maxLookbehind: Int,
  maxLookahead: Int,
  batchSize: Int,
  prefetchDistance: Int = 2,
  onRangeEnterWindow: (IntRange) -> Unit,
  onRangeLeaveWindow: (IntRange) -> Unit,
) {
  val currentOnEnter by rememberUpdatedState(onRangeEnterWindow)
  val currentOnLeave by rememberUpdatedState(onRangeLeaveWindow)

  var windowStart by remember { mutableIntStateOf(0) }
  var windowEnd by remember { mutableIntStateOf(0) } // Exclusive bound

  LaunchedEffect(Unit) {
    val initialCount = minOf(pagerState.pageCount, maxLookahead + 1) // current page included
    if (initialCount > 0) {
      windowStart = 0
      windowEnd = initialCount
      currentOnEnter(0 until initialCount)
    }
  }

  LaunchedEffect(pagerState, maxLookbehind, maxLookahead, batchSize, prefetchDistance) {
    snapshotFlow { pagerState.settledPage }
      .collect { page ->
        if (windowStart == windowEnd) return@collect

        val backEdge = windowStart
        val frontEdge = windowEnd - 1

        // Approaching the front edge (scrolling forward/down)
        if (frontEdge - page <= prefetchDistance) {
          val start = windowEnd
          val end = minOf(pagerState.pageCount, start + batchSize)

          if (start < end) {
            currentOnEnter(start until end)
            windowEnd = end
            // Evict from behind based on maxLookbehind
            val targetStart = maxOf(0, page - maxLookbehind)
            if (windowStart < targetStart) {
              currentOnLeave(windowStart until targetStart)
              windowStart = targetStart
            }
          }
        }
        // Approaching the back edge (scrolling backward/up)
        else if (page - backEdge <= prefetchDistance) {
          val end = windowStart
          val start = maxOf(0, end - batchSize)
          if (start < end) {
            currentOnEnter(start until end)
            windowStart = start
            // Evict from ahead based on maxLookahead
            val targetEnd = minOf(pagerState.pageCount, page + maxLookahead + 1)
            if (windowEnd > targetEnd) {
              currentOnLeave(targetEnd until windowEnd)
              windowEnd = targetEnd
            }
          }
        }
      }
  }
}
