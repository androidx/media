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
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.CueUtil
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

// TODO(b/572878706): Use the ShadowCaptioningManager setters once Robolectric releases them.
// Hidden Settings.Secure keys that CaptioningManager reads the user's caption preferences from.
private const val CAPTIONING_PRESET = "accessibility_captioning_preset"
private const val CAPTIONING_FONT_SCALE = "accessibility_captioning_font_scale"

// The index of the yellow text on black background preset in CaptionStyle.PRESETS.
private const val PRESET_YELLOW_ON_BLACK = 2

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

  @Test
  fun subtitles_whenCuesChange_createsNewNode() = runComposeUiTest {
    val player = FakePlayer()
    val firstCue = Cue.Builder().setText("first subtitle").build()
    val secondCue = Cue.Builder().setText("second subtitle").build()

    player.setCurrentCues(CueGroup(listOf(firstCue), 0))
    setContent { Subtitles(player = player) }

    val firstNodeId = onNodeWithText("first subtitle").fetchSemanticsNode().id

    player.setCurrentCues(CueGroup(listOf(secondCue), 0))
    waitForIdle()

    val secondNodeId = onNodeWithText("second subtitle").fetchSemanticsNode().id

    // Subtitles are properly keyed on 'cue' (rather than on loop index)
    assertNotEquals(firstNodeId, secondNodeId)
  }

  @Test
  fun subtitles_withUserStyle_usesUserStyle() = runComposeUiTest {
    Settings.Secure.putInt(context.contentResolver, CAPTIONING_PRESET, PRESET_YELLOW_ON_BLACK)
    val cues = listOf(Cue.Builder().setText("Hello").build())
    val player = FakePlayer().apply { setCurrentCues(CueGroup(cues, 0)) }

    setContent { Subtitles(player = player) }

    val style = onNodeWithText("Hello").fetchTextLayoutResult().layoutInput.style
    assertThat(style.color).isEqualTo(Color.Yellow)
  }

  @Test
  fun subtitles_withFontScale_scalesDefaultTextSize() = runComposeUiTest {
    Settings.Secure.putFloat(context.contentResolver, CAPTIONING_FONT_SCALE, 2f)
    val cues = listOf(Cue.Builder().setText("Hello").build())
    val player = FakePlayer().apply { setCurrentCues(CueGroup(cues, 0)) }

    setContent { Subtitles(player = player) }

    val fontSize = onNodeWithText("Hello").fetchTextLayoutResult().layoutInput.style.fontSize
    val viewHeight = onRoot().fetchSemanticsNode().size.height
    val expectedFontSize =
      with(density) { (CueUtil.DEFAULT_TEXT_SIZE_FRACTION * 2f * viewHeight).toSp() }
    assertThat(fontSize.value).isWithin(0.01f).of(expectedFontSize.value)
  }

  @Test
  fun subtitles_withUserStyleChangedWhileNoCuesShown_usesUserStyleForNextCues() = runComposeUiTest {
    val player = FakePlayer()
    player.setCurrentCues(CueGroup(listOf(Cue.Builder().setText("first").build()), 0))
    setContent { Subtitles(player = player) }
    player.setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
    waitForIdle()
    Settings.Secure.putInt(context.contentResolver, CAPTIONING_PRESET, PRESET_YELLOW_ON_BLACK)

    player.setCurrentCues(CueGroup(listOf(Cue.Builder().setText("second").build()), 0))
    waitForIdle()

    val style = onNodeWithText("second").fetchTextLayoutResult().layoutInput.style
    assertThat(style.color).isEqualTo(Color.Yellow)
  }

  private fun SemanticsNodeInteraction.fetchTextLayoutResult(): TextLayoutResult {
    val textLayoutResults = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[GetTextLayoutResult].action?.invoke(textLayoutResults)
    return textLayoutResults.first()
  }
}
