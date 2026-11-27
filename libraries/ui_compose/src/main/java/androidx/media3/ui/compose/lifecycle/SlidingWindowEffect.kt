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
package androidx.media3.ui.compose.lifecycle

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
 * A side effect that calculates a sliding window of managed item indices based on a dynamic current
 * index.
 *
 * It invokes [onRangeEnterWindow] when items should be prepared/loaded, and [onRangeLeaveWindow]
 * when they fall outside the managed window boundaries and should be evicted.
 *
 * The effect guarantees that all indices passed to [onRangeEnterWindow] and [onRangeLeaveWindow]
 * will always be within the valid range of `0 until itemCountProvider()`.
 *
 * @param itemCountProvider A lambda returning the total number of items available to scroll
 *   through.
 * @param currentItemProvider A lambda returning the currently focused item index.
 * @param maxLookbehind The maximum number of items to keep actively managed in memory before the
 *   current item.
 * @param maxLookahead The maximum number of items to keep actively managed in memory after the
 *   current item.
 * @param batchSize The number of items to load/evict at once when crossing the prefetchDistance
 *   threshold.
 * @param prefetchDistance The distance from the edge of the window that triggers the next batch of
 *   preloads.
 * @param onRangeEnterWindow Callback invoked with the contiguous range of indices that have entered
 *   the window. These indices are guaranteed to be within `0 until itemCountProvider()`.
 * @param onRangeLeaveWindow Callback invoked with the contiguous range of indices that have left
 *   the window. This is also called to evict indices that have become invalid/out-of-bounds due to
 *   a decrease in [itemCountProvider].
 */
@UnstableApi
@Composable
fun SlidingWindowEffect(
  itemCountProvider: () -> Int,
  currentItemProvider: () -> Int,
  maxLookbehind: Int,
  maxLookahead: Int,
  batchSize: Int,
  prefetchDistance: Int = 2,
  onRangeEnterWindow: (IntRange) -> Unit,
  onRangeLeaveWindow: (IntRange) -> Unit,
) {
  val currentOnEnter by rememberUpdatedState(onRangeEnterWindow)
  val currentOnLeave by rememberUpdatedState(onRangeLeaveWindow)
  val currentProvider by rememberUpdatedState(currentItemProvider)
  val currentItemCount by rememberUpdatedState(itemCountProvider)

  var windowStart by remember { mutableIntStateOf(0) }
  var windowEnd by remember { mutableIntStateOf(0) } // Exclusive bound

  fun setWindowBoundsFromRange(range: IntRange) {
    if (range.isEmpty()) {
      windowStart = 0
      windowEnd = 0
    } else {
      windowStart = range.first
      windowEnd = range.last + 1
    }
  }

  LaunchedEffect(maxLookbehind, maxLookahead, batchSize, prefetchDistance) {
    val currentIndex = currentProvider()
    val itemCountVal = currentItemCount()
    val lastValidIndex = maxOf(0, itemCountVal - 1)
    val clampedIndex = currentIndex.coerceIn(0, lastValidIndex)
    val newStart = maxOf(0, clampedIndex - maxLookbehind)
    val newEnd = minOf(itemCountVal, clampedIndex + maxLookahead + 1)

    val newRange = newStart until newEnd
    reconcile(windowStart until windowEnd, newRange, currentOnEnter, currentOnLeave)
    setWindowBoundsFromRange(newRange)

    snapshotFlow { Pair(currentProvider(), currentItemCount()) }
      .collect { (currentIndex, currentCount) ->
        val lastValidIndex = maxOf(0, currentCount - 1)
        val clampedIndex = currentIndex.coerceIn(0, lastValidIndex)

        val backEdge = windowStart
        val frontEdge = windowEnd - 1

        val approachingFront =
          windowStart != windowEnd &&
            currentCount > 0 &&
            frontEdge - clampedIndex in 0..prefetchDistance
        val approachingBack =
          windowStart != windowEnd &&
            currentCount > 0 &&
            clampedIndex - backEdge in 0..prefetchDistance

        if (approachingFront && !approachingBack) {
          val start = windowEnd
          val end = minOf(currentCount, start + batchSize)
          if (start < end) {
            currentOnEnter(start until end)
            windowEnd = end
            // Evict from behind based on maxLookbehind
            val targetStart = maxOf(0, clampedIndex - maxLookbehind)
            if (windowStart < targetStart) {
              currentOnLeave(windowStart until targetStart)
              windowStart = targetStart
            }
          }
        } else if (approachingBack && !approachingFront) {
          val end = windowStart
          val start = maxOf(0, end - batchSize)
          if (start < end) {
            currentOnEnter(start until end)
            windowStart = start
            // Evict from ahead based on maxLookahead
            val targetEnd = minOf(currentCount, clampedIndex + maxLookahead + 1)
            if (windowEnd > targetEnd) {
              currentOnLeave(targetEnd until windowEnd)
              windowEnd = targetEnd
            }
          }
        } else {
          // Empty window, or both approaching, or jump/out-of-bounds
          val targetStart = maxOf(0, clampedIndex - maxLookbehind)
          val targetEnd = minOf(currentCount, clampedIndex + maxLookahead + 1)
          val oldRange = windowStart until windowEnd
          val newRange = targetStart until targetEnd
          reconcile(oldRange, newRange, currentOnEnter, currentOnLeave)
          setWindowBoundsFromRange(newRange)
        }
      }
  }
}

/**
 * Reconciles the transition between [oldRange] and [newRange], invoking [onEnter] for newly
 * appearing indices and [onLeave] for disappearing indices.
 */
private fun reconcile(
  oldRange: IntRange,
  newRange: IntRange,
  onEnter: (IntRange) -> Unit,
  onLeave: (IntRange) -> Unit,
) {
  if (oldRange.isEmpty()) {
    if (!newRange.isEmpty()) {
      onEnter(newRange)
    }
  } else if (newRange.isEmpty()) {
    onLeave(oldRange)
  } else {
    if (oldRange.last < newRange.first || newRange.last < oldRange.first) {
      onLeave(oldRange)
      onEnter(newRange)
    } else {
      if (oldRange.first < newRange.first) {
        onLeave(oldRange.first until newRange.first)
      } else if (newRange.first < oldRange.first) {
        onEnter(newRange.first until oldRange.first)
      }
      if (newRange.last < oldRange.last) {
        onLeave((newRange.last + 1)..oldRange.last)
      } else if (oldRange.last < newRange.last) {
        onEnter((oldRange.last + 1)..newRange.last)
      }
    }
  }
}
