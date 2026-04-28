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

import androidx.compose.ui.test.MainTestClock

/**
 * Moves the virtual clock of this MainTestClock's dispatcher forward by [milliseconds], running the
 * scheduled tasks in the meantime.
 *
 * This helper addresses two critical differences in standard testing APIs:
 *
 * 1. **Difference to [MainTestClock.advanceTimeBy]**: Standard Compose clock advancement rounds the
 *    requested duration up to the nearest multiple of the frame duration (typically 16ms) by
 *    default to align with UI cycles. This often leads to discrepancies (e.g., advancing by 1000ms
 *    results in 1008ms). This function defaults [ignoreFrameDuration] to `true` to allow precise
 *    millisecond control, which is essential for verifying Media3 position-based logic. It also
 *    runs tasks scheduled at exactly currentTime + [milliseconds]. This function includes an
 *    explicit [kotlinx.coroutines.test.runCurrent] call on the underlying scheduler to drain any
 *    tasks scheduled for the final millisecond, ensuring state assertions at that boundary reflect
 *    the intended update.
 *
 * 2. **Difference to [kotlinx.coroutines.test.advanceTimeBy]**: Standard coroutine advancement only
 *    moves virtual time for coroutines. In the v2 environment, this helper uses the [MainTestClock]
 *    API to drive the **unified scheduler**. Unlike pure coroutine advancement, this call **pumps
 *    Compose frames**, which is required for recomposition and for UI-state components to observe
 *    the time change. Without this, the Kotlin clock would advance (moving the `FakePlayer`
 *    position) but the Compose UI would remain frozen.
 */
internal fun MainTestClock.advancePrecisely(
  milliseconds: Long,
  ignoreFrameDuration: Boolean = true,
) {
  this.advanceTimeBy(milliseconds, ignoreFrameDuration)
  this.scheduler.runCurrent()
}
