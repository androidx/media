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

import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer

internal fun createReadyPlayerWithTwoItems(): TestSimpleBasePlayer =
  TestSimpleBasePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist =
      listOf(
        MediaItemData.Builder("First").setDurationUs(1_000_000L).setIsSeekable(true).build(),
        MediaItemData.Builder("Second").setDurationUs(2_000_000L).setIsSeekable(true).build(),
      ),
  )

internal fun createReadyPlayerWithSingleItem(durationUs: Long = 10_000_000L): TestSimpleBasePlayer =
  TestSimpleBasePlayer(
    playbackState = STATE_READY,
    playWhenReady = true,
    playlist = listOf(MediaItemData.Builder("SingleItem").setDurationUs(durationUs).build()),
  )
