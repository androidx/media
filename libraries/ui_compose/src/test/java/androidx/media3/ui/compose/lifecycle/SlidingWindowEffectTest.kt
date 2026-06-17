/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.ui.compose.lifecycle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [SlidingWindowEffect]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SlidingWindowEffectTest {

  @Test
  fun initialization_addsInitialItems() = runComposeUiTest {
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()

    setContent {
      val pagerState = rememberPagerState { 100 }
      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = pagerState.pageCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = { enteredRanges.add(it) },
        onRangeLeaveWindow = { leftRanges.add(it) },
      )
    }

    runOnIdle {
      // Upon initialization, the current page (0) + maxLookahead (4) should be loaded.
      assertThat(enteredRanges).containsExactly(0 until 5)
      assertThat(leftRanges).isEmpty()
    }
  }

  @Test
  fun scrollToForward_triggersPrefetchAndEviction() = runComposeUiTest {
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    lateinit var pagerState: PagerState
    lateinit var scope: CoroutineScope

    setContent {
      pagerState = rememberPagerState { 100 }
      scope = rememberCoroutineScope()

      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = pagerState.pageCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = { enteredRanges.add(it) },
        onRangeLeaveWindow = { leftRanges.add(it) },
      )
    }
    runOnIdle {
      enteredRanges.clear()
      leftRanges.clear()
    }
    // Current window is 0 until 5 (Front edge is 4).
    // Scroll to page 3. Distance from front edge is (4 - 3 = 1).
    // Since 1 <= prefetchDistance (2), this triggers a shift forward.
    scope.launch { pagerState.scrollToPage(3) }

    runOnIdle {
      // [!0] [1, 2] + [3] + [4, 5, 6, 7]
      assertThat(enteredRanges).containsExactly(5 until 8)
      assertThat(leftRanges).containsExactly(0..0)
    }
  }

  @Test
  fun scrollToBackward_triggersPrefetchAndEviction() = runComposeUiTest {
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    lateinit var pagerState: PagerState
    lateinit var scope: CoroutineScope

    setContent {
      pagerState = rememberPagerState { 100 }
      scope = rememberCoroutineScope()
      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = pagerState.pageCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = { enteredRanges.add(it) },
        onRangeLeaveWindow = { leftRanges.add(it) },
      )
    }
    scope.launch { pagerState.scrollToPage(3) }
    runOnIdle {
      enteredRanges.clear()
      leftRanges.clear()
    }
    // Window is now 1 until 8 (Back edge is 1).
    // Scroll backward to page 2. Distance from back edge is (2 - 1 = 1).
    // Since 1 <= prefetchDistance (2), this triggers a shift backward.
    scope.launch { pagerState.scrollToPage(2) }

    runOnIdle {
      // [0, 1] + [2] + [3, 4, 5, 6] [!7]
      assertThat(enteredRanges).containsExactly(0 until 1)
      assertThat(leftRanges).containsExactly(7..7)
    }
  }

  @Test
  fun scrollToForward_nearEndBoundary_clampsAdditions() = runComposeUiTest {
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    lateinit var pagerState: PagerState
    lateinit var scope: CoroutineScope

    setContent {
      pagerState = rememberPagerState { 7 }
      scope = rememberCoroutineScope()
      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = pagerState.pageCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = { enteredRanges.add(it) },
        onRangeLeaveWindow = { leftRanges.add(it) },
      )
    }
    runOnIdle {
      enteredRanges.clear()
      leftRanges.clear()
    }
    // Current window is 0 until 5. Front edge is 4. Scroll to page 4 to trigger prefetch.
    scope.launch { pagerState.scrollToPage(4) }

    runOnIdle {
      // [!0, !1] [2, 3] + [4] + [5, 6]
      assertThat(enteredRanges).containsExactly(5 until 7)
      assertThat(leftRanges).containsExactly(0 until 2)
    }
  }

  @Test
  fun itemCountIncreasesFromZero_currentlyDoesNotInitialize() = runComposeUiTest {
    // This test reproduces a bug where SlidingWindowEffect does not initialize when itemCount
    // changes from 0 to a positive value.
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    var itemCount by mutableIntStateOf(0)

    setContent {
      SlidingWindowEffect(
        itemCount = itemCount,
        currentItemProvider = { 0 },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = enteredRanges::add,
        onRangeLeaveWindow = leftRanges::add,
      )
    }

    runOnIdle {
      assertThat(enteredRanges).isEmpty()
      assertThat(leftRanges).isEmpty()
    }

    itemCount = 10

    runOnIdle {
      // BUG: The window should initialize to 0..4 when itemCount increases.
      assertThat(enteredRanges).isEmpty()
    }
  }

  @Test
  fun itemCountShrinks_currentlyLeavesOrphanedIndices() = runComposeUiTest {
    // This test reproduces a bug where SlidingWindowEffect does not evict items from the window
    // when the itemCount shrinks, leading to orphaned indices.
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    var itemCount by mutableIntStateOf(100)
    lateinit var pagerState: PagerState
    lateinit var scope: CoroutineScope

    setContent {
      pagerState = rememberPagerState { itemCount }
      scope = rememberCoroutineScope()
      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = itemCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = enteredRanges::add,
        onRangeLeaveWindow = leftRanges::add,
      )
    }

    // Scroll to 50, new window [48, 49] + 50 + [51, 52, 53, 54]
    scope.launch { pagerState.scrollToPage(50) }
    runOnIdle {
      enteredRanges.clear()
      leftRanges.clear()
    }

    // Shrink itemCount to 10
    itemCount = 10

    runOnIdle {
      // BUG: The orphaned indices (48..54) should be evicted.
      assertThat(leftRanges).isEmpty()
    }
  }

  @Test
  fun largeJump_currentlyCausesInvertedWindow() = runComposeUiTest {
    // This test reproduces a bug where a large jump in the current item causes an inverted
    // window state in SlidingWindowEffect.
    val enteredRanges = mutableListOf<IntRange>()
    val leftRanges = mutableListOf<IntRange>()
    lateinit var pagerState: PagerState
    lateinit var scope: CoroutineScope

    setContent {
      pagerState = rememberPagerState { 100 }
      scope = rememberCoroutineScope()
      VerticalPager(state = pagerState) { Box(Modifier.fillMaxSize()) }
      SlidingWindowEffect(
        itemCount = pagerState.pageCount,
        currentItemProvider = { pagerState.settledPage },
        maxLookbehind = 2,
        maxLookahead = 4,
        batchSize = 3,
        prefetchDistance = 2,
        onRangeEnterWindow = enteredRanges::add,
        onRangeLeaveWindow = leftRanges::add,
      )
    }
    runOnIdle {
      enteredRanges.clear()
      leftRanges.clear()
    }

    // Jump to 50, new window [48, 49] + 50 + [51, 52, 53, 54]
    scope.launch { pagerState.scrollToPage(50) }

    runOnIdle {
      // BUG: The previous window of 0 + [1, 2, 3, 4] gets a forward shift
      // The maths assumes a gentle scroll, not a big jump, so it loads [5, 6, 7] as a new batch
      // It evicts everything from the old page [0] to the new [50-maxLookbehind] = [48]
      // The window state internally becomes windowStart=48, windowEnd=8 (flipped!)
      // Correct behavior should load 48..54 and evict 0..4.
      assertThat(enteredRanges).containsExactly(5..7)
      assertThat(leftRanges).containsExactly(0..47)
    }
  }
}
