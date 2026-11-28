/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.ui.compose

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlayerSubtitleView]. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleViewTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerSubtitleView_withTextCues_displaysText() {
    val cue1 = Cue.Builder().setText("Hello").build()
    val cue2 = Cue.Builder().setText("World").build()
    val cueGroup = CueGroup(listOf(cue1, cue2), 0)

    composeTestRule.setContent {
      PlayerSubtitleView(
        cueGroup = cueGroup,
        videoSizeDp = IntSize(1920, 1080),
        sourceVideoWidth = 1920,
        sourceVideoHeight = 1080
      )
    }

    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    composeTestRule.onNodeWithText("World").assertIsDisplayed()
  }

  @Test
  fun playerSubtitleView_withEmptyCueGroup_doesNotCrash() {
    composeTestRule.setContent {
      PlayerSubtitleView(
        cueGroup = CueGroup(emptyList(), 0),
        videoSizeDp = IntSize(1920, 1080),
        sourceVideoWidth = 1920,
        sourceVideoHeight = 1080
      )
    }
  }

  @Test
  fun playerSubtitleView_withNullCueGroup_doesNotCrash() {
    composeTestRule.setContent {
      PlayerSubtitleView(
        cueGroup = null,
        videoSizeDp = IntSize(1920, 1080),
        sourceVideoWidth = 1920,
        sourceVideoHeight = 1080
      )
    }
  }

  @Test
  fun playerSubtitleView_withBitmapCues_doesNotCrash() {
    val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
    val cue = Cue.Builder()
      .setBitmap(bitmap)
      .setPosition(0.5f)
      .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
      .setSize(0.2f)
      .build()
    val cueGroup = CueGroup(listOf(cue), 0)

    composeTestRule.setContent {
      PlayerSubtitleView(
        cueGroup = cueGroup,
        videoSizeDp = IntSize(400, 300),
        sourceVideoWidth = 1920,
        sourceVideoHeight = 1080
      )
    }
    
    composeTestRule.waitForIdle()
  }
}
