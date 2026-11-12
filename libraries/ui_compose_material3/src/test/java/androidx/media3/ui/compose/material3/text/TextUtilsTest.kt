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

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.text.set
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Unit test for the [Spanned][android.text.Spanned] to [AnnotatedString] conversion utilities. */
@RunWith(AndroidJUnit4::class)
class TextUtilsTest {

  @Test
  fun toAnnotatedString_withNoSpans_returnsPlainString() {
    val spanned = SpannableString("Hello")

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("Hello")
    assertThat(annotatedString.spanStyles).isEmpty()
    assertThat(annotatedString.paragraphStyles).isEmpty()
  }

  @Test
  fun toAnnotatedString_withForegroundColorSpan_convertsToColor() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = ForegroundColorSpan(android.graphics.Color.RED)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(AnnotatedString.Range(SpanStyle(color = Color.Red), start = 1, end = 4))
  }

  @Test
  fun toAnnotatedString_withBackgroundColorSpan_convertsToBackground() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = BackgroundColorSpan(android.graphics.Color.BLUE)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(background = Color.Blue), start = 1, end = 4)
      )
  }

  @Test
  fun toAnnotatedString_withUnderlineSpan_convertsToUnderline() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = UnderlineSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withStrikethroughSpan_convertsToLineThrough() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StrikethroughSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.LineThrough),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withUnderlineAndStrikethroughSpansOnSameRange_combinesTextDecorations() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = UnderlineSpan()
    spanned[1, 4] = StrikethroughSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline + TextDecoration.LineThrough),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withOverlappingUnderlineAndStrikethroughSpans_combinesTextDecorations() {
    val spanned = SpannableString("Hello World")
    spanned[0, 8] = UnderlineSpan()
    spanned[3, 11] = StrikethroughSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline),
          start = 0,
          end = 3,
        ),
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline + TextDecoration.LineThrough),
          start = 3,
          end = 8,
        ),
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.LineThrough),
          start = 8,
          end = 11,
        ),
      )
  }

  @Test
  fun toAnnotatedString_withSeparateUnderlineSpans_keepsGapUndecorated() {
    val spanned = SpannableString("Hello")
    spanned[0, 2] = UnderlineSpan()
    spanned[3, 5] = UnderlineSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline),
          start = 0,
          end = 2,
        ),
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline),
          start = 3,
          end = 5,
        ),
      )
  }

  @Test
  fun toAnnotatedString_withBoldStyleSpan_convertsToBoldFontWeight() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StyleSpan(Typeface.BOLD)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), start = 1, end = 4)
      )
  }

  @Test
  fun toAnnotatedString_withItalicStyleSpan_convertsToItalicFontStyle() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StyleSpan(Typeface.ITALIC)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(fontStyle = FontStyle.Italic), start = 1, end = 4)
      )
  }

  @Test
  fun toAnnotatedString_withBoldItalicStyleSpan_convertsToBoldAndItalic() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StyleSpan(Typeface.BOLD_ITALIC)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withNormalStyleSpan_ignoresSpan() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StyleSpan(Typeface.NORMAL)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles).isEmpty()
  }

  @Test
  fun toAnnotatedString_withAbsoluteSizeSpanInPixels_convertsToSp() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = AbsoluteSizeSpan(/* size= */ 30)

    val annotatedString = spanned.toAnnotatedString(Density(density = 2f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(AnnotatedString.Range(SpanStyle(fontSize = 15.sp), start = 1, end = 4))
  }

  @Test
  fun toAnnotatedString_withAbsoluteSizeSpanInDip_convertsToSp() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = AbsoluteSizeSpan(/* size= */ 15, /* dip= */ true)

    val annotatedString = spanned.toAnnotatedString(Density(density = 2f, fontScale = 1.5f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(AnnotatedString.Range(SpanStyle(fontSize = 10.sp), start = 1, end = 4))
  }

  @Test
  fun toAnnotatedString_withRelativeSizeSpan_convertsToEm() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = RelativeSizeSpan(1.5f)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(AnnotatedString.Range(SpanStyle(fontSize = 1.5f.em), start = 1, end = 4))
  }

  @Test
  fun toAnnotatedString_withScaleXSpan_convertsToTextGeometricTransform() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = ScaleXSpan(0.5f)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = 0.5f)),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withSubscriptAndSuperscriptSpans_convertsToBaselineShifts() {
    val spanned = SpannableString("H2O x2")
    spanned[1, 2] = SubscriptSpan()
    spanned[5, 6] = SuperscriptSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(baselineShift = BaselineShift.Subscript),
          start = 1,
          end = 2,
        ),
        AnnotatedString.Range(
          SpanStyle(baselineShift = BaselineShift.Superscript),
          start = 5,
          end = 6,
        ),
      )
  }

  @Test
  fun toAnnotatedString_withTypefaceSpan_convertsToFontFamily() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = TypefaceSpan("monospace")

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(fontFamily = FontFamily.Monospace), start = 1, end = 4)
      )
  }

  @Test
  @Config(minSdk = 28)
  fun toAnnotatedString_withTypefaceSpanFromTypeface_convertsToFontFamilyWithStyleVariants() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = TypefaceSpan(Typeface.SERIF)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    val range = annotatedString.spanStyles.single()
    assertThat(range.start).isEqualTo(1)
    assertThat(range.end).isEqualTo(4)
    val fonts = (range.item.fontFamily as FontListFontFamily).fonts
    assertThat(fonts.map { it.weight to it.style })
      .containsExactly(
        FontWeight.Normal to FontStyle.Normal,
        FontWeight.Bold to FontStyle.Normal,
        FontWeight.Normal to FontStyle.Italic,
        FontWeight.Bold to FontStyle.Italic,
      )
  }

  @Test
  fun toAnnotatedString_withTextAppearanceSpan_convertsColorSizeStyleAndFamily() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] =
      TextAppearanceSpan(
        /* family= */ "serif",
        Typeface.BOLD_ITALIC,
        /* size= */ 30,
        ColorStateList.valueOf(android.graphics.Color.RED),
        /* linkColor= */ null,
      )

    val annotatedString = spanned.toAnnotatedString(Density(density = 2f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(
            color = Color.Red,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif,
          ),
          start = 1,
          end = 4,
        )
      )
  }

  @Test
  fun toAnnotatedString_withAlignmentSpan_convertsToParagraphStyleForWholeLine() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles).isEmpty()
    assertThat(annotatedString.paragraphStyles)
      .containsExactly(
        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Center), start = 0, end = 5)
      )
  }

  @Test
  fun toAnnotatedString_withAlignmentSpanOnSecondLineOnly_omitsNewlineAndAlignsSecondLine() {
    val spanned = SpannableString("Short\nLonger")
    spanned["Short\n".length, spanned.length] =
      AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("ShortLonger")
    assertThat(annotatedString.paragraphStyles)
      .containsExactly(
        AnnotatedString.Range(
          ParagraphStyle(textAlign = TextAlign.Center),
          start = "Short".length,
          end = "ShortLonger".length,
        )
      )
  }

  @Test
  fun toAnnotatedString_withSpanAcrossOmittedNewline_shiftsSpanEnd() {
    val spanned = SpannableString("Short\nLonger")
    spanned["Short\n".length, spanned.length] =
      AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
    // Covers "rt\nLon".
    spanned["Sho".length, "Short\nLon".length] = StyleSpan(Typeface.BOLD)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("ShortLonger")
    // The omitted '\n' shifts the span end back by one, so it covers "rtLon".
    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(
          SpanStyle(fontWeight = FontWeight.Bold),
          start = "Sho".length,
          end = "ShortLon".length,
        )
      )
  }

  @Test
  fun toAnnotatedString_withAlignmentSpanAndTrailingNewline_alignsWholeText() {
    val spanned = SpannableString("Hello\n")
    spanned[0, spanned.length] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("Hello\n")
    assertThat(annotatedString.paragraphStyles)
      .containsExactly(
        AnnotatedString.Range(
          ParagraphStyle(textAlign = TextAlign.Center),
          start = 0,
          end = "Hello\n".length,
        )
      )
  }

  @Test
  fun toAnnotatedString_withAlignmentSpanOnlyCoveringNewline_ignoresSpan() {
    val spanned = SpannableString("A\nB")
    spanned[1, 2] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("A\nB")
    assertThat(annotatedString.paragraphStyles).isEmpty()
  }

  @Test
  fun toAnnotatedString_withEmptyAlignmentSpan_ignoresSpan() {
    val spanned = SpannableString("A\nB")
    spanned[1, 1] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("A\nB")
    assertThat(annotatedString.paragraphStyles).isEmpty()
  }

  @Test
  fun toAnnotatedString_withPartiallyOverlappingAlignmentSpans_usesLastSpanPerLine() {
    val spanned = SpannableString("Line 1\nLine 2\nLine 3")
    spanned[0, 14] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)
    spanned[7, 20] = AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("Line 1Line 2\nLine 3")
    assertThat(annotatedString.paragraphStyles)
      .containsExactly(
        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Start), start = 0, end = 6),
        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.End), start = 6, end = 19),
      )
  }

  @Test
  fun toAnnotatedString_withMultipleSpansOnSameText_convertsEachSpan() {
    val spanned = SpannableString("Hello")
    spanned[1, 4] = StyleSpan(Typeface.BOLD)
    spanned[1, 4] = UnderlineSpan()

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), start = 1, end = 4),
        AnnotatedString.Range(
          SpanStyle(textDecoration = TextDecoration.Underline),
          start = 1,
          end = 4,
        ),
      )
  }

  @Test
  fun toAnnotatedString_withUnsupportedSpans_ignoresThemAndKeepsSupportedSpans() {
    val spanned = SpannableString("Hello")
    spanned[0, 5] = RubySpan("ruby", TextAnnotation.POSITION_BEFORE)
    spanned[0, 5] = URLSpan("https://example.com")
    spanned[1, 4] = ForegroundColorSpan(android.graphics.Color.RED)

    val annotatedString = spanned.toAnnotatedString(Density(1f))

    assertThat(annotatedString.text).isEqualTo("Hello")
    assertThat(annotatedString.spanStyles)
      .containsExactly(AnnotatedString.Range(SpanStyle(color = Color.Red), start = 1, end = 4))
    assertThat(annotatedString.paragraphStyles).isEmpty()
  }

  @Test
  fun withoutSpanColors_withColoredSpans_removesColorsAndKeepsOtherStyles() {
    val annotatedString = buildAnnotatedString {
      append("Hello World")
      addStyle(SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold), start = 0, end = 5)
      addStyle(SpanStyle(color = Color.Yellow), start = 6, end = 11)
      addStyle(ParagraphStyle(textAlign = TextAlign.Center), start = 0, end = 11)
    }

    val withoutColors = annotatedString.withoutSpanColors()

    assertThat(withoutColors.text).isEqualTo("Hello World")
    assertThat(withoutColors.spanStyles)
      .containsExactly(
        AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), start = 0, end = 5),
        AnnotatedString.Range(SpanStyle(), start = 6, end = 11),
      )
    assertThat(withoutColors.paragraphStyles).isEqualTo(annotatedString.paragraphStyles)
  }

  @Test
  fun withoutSpanColors_withoutColoredSpans_returnsSameInstance() {
    val annotatedString = buildAnnotatedString {
      append("Hello")
      addStyle(SpanStyle(fontWeight = FontWeight.Bold), start = 0, end = 5)
    }

    val withoutColors = annotatedString.withoutSpanColors()

    assertThat(withoutColors).isSameInstanceAs(annotatedString)
  }
}
