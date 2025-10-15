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

package androidx.media3.ui.compose.testutils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope

/**
 * Moves the virtual clock of this dispatcher forward by [the specified amount][delayTime], running
 * the scheduled tasks in the meantime.
 *
 * Compared to a single [kotlinx.coroutines.test.advanceTimeBy], it does run the tasks that are
 * scheduled at exactly currentTime + [delayTime]. There is often a need for another run of the
 * scheduled tasks because they contain exactly the update needed for a correct assertion of the
 * test. If we will stop just before executing any task starting at the next millisecond, we might
 * be off by one iteration/task and there would be a need to awkwardly advance by delayTime+1.
 */
internal fun TestScope.advanceTimeByInclusive(delayTime: Duration) {
  testScheduler.advanceTimeBy(delayTime)
  testScheduler.runCurrent()
}

// A scope that
// 1. inherits the job from backgroundScope for cancellation after test assertions
// 2. uses Composable's FrameClock to its context for animation, e.g. withFrameMillis
@Composable
internal fun TestScope.rememberCoroutineScopeWithBackgroundCancellation(): CoroutineScope =
  rememberCoroutineScope().plus(backgroundScope.coroutineContext)
