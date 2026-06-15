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
import androidx.compose.runtime.rememberCoroutineScope
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
}
