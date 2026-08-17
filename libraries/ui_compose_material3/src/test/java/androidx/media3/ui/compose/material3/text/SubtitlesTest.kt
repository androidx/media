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

package androidx.media3.ui.compose.material3.text

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [Subtitles]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubtitlesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun subtitles_withImageCue_rendersImage() = runComposeUiTest {
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    val cues = listOf(Cue.Builder().setBitmap(bitmap).build())
    val player = FakePlayer().apply { setCurrentCues(CueGroup(cues, 0)) }

    setContent { Subtitles(player = player) }

    onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue)).assertExists()
  }
}
