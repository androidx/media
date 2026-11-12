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
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Converts this [Spanned] text (e.g. [Cue.text][androidx.media3.common.text.Cue.text]) to an
 * [AnnotatedString].
 *
 * The following spans are supported:
 * - [ForegroundColorSpan]
 * - [BackgroundColorSpan]
 * - [StyleSpan]
 * - [UnderlineSpan]
 * - [StrikethroughSpan]
 * - [AbsoluteSizeSpan]
 * - [RelativeSizeSpan]
 * - [ScaleXSpan]
 * - [SubscriptSpan]
 * - [SuperscriptSpan]
 * - [TypefaceSpan]
 * - [TextAppearanceSpan] (color, size, style and family)
 * - [AlignmentSpan]
 *
 * Other spans, including [RubySpan][androidx.media3.common.text.RubySpan],
 * [TextEmphasisSpan][androidx.media3.common.text.TextEmphasisSpan] and
 * [HorizontalTextInVerticalContextSpan][androidx.media3.common.text.HorizontalTextInVerticalContextSpan],
 * are ignored.
 *
 * @param density The [Density] used to convert pixel sizes to [TextUnit]s.
 */
internal fun Spanned.toAnnotatedString(density: Density): AnnotatedString {
  return buildAnnotatedString {
    val spanned = this@toAnnotatedString
    val omittedNewlineIndices = appendTextAndParagraphStyles(spanned)

    val underlineRanges = mutableListOf<TextRange>()
    val strikethroughRanges = mutableListOf<TextRange>()
    for (span in spanned.getSpans(0, spanned.length, CharacterStyle::class.java)) {
      val start = mapIndex(spanned.getSpanStart(span), omittedNewlineIndices)
      val end = mapIndex(spanned.getSpanEnd(span), omittedNewlineIndices)
      if (start >= end) continue
      when (span) {
        is UnderlineSpan -> underlineRanges.add(TextRange(start, end))
        is StrikethroughSpan -> strikethroughRanges.add(TextRange(start, end))
        else -> span.toSpanStyle(density)?.let { addStyle(it, start, end) }
      }
    }
    addTextDecorationStyles(underlineRanges, strikethroughRanges)
  }
}

/**
 * Appends [spanned]'s text and [AlignmentSpan] paragraph styles to this builder.
 *
 * @return The sorted indices in [spanned] of `\n` characters that weren't appended, because they're
 *   at a boundary between differently-aligned paragraphs (where the [ParagraphStyle] boundary
 *   already breaks the line).
 */
private fun AnnotatedString.Builder.appendTextAndParagraphStyles(spanned: Spanned): List<Int> {
  val text = spanned.toString()
  val alignmentSpans =
    spanned.getSpans(0, spanned.length, AlignmentSpan::class.java).filter {
      it.alignment != null && spanned.getSpanStart(it) < spanned.getSpanEnd(it)
    }
  if (alignmentSpans.isEmpty()) {
    append(text)
    return emptyList()
  }

  // The [start, end) range of each line, excluding its trailing '\n'.
  val lines = mutableListOf<TextRange>()
  var lineStart = 0
  for (i in text.indices) {
    if (text[i] == '\n') {
      lines.add(TextRange(lineStart, i))
      lineStart = i + 1
    }
  }
  lines.add(TextRange(lineStart, text.length))

  val lineAligns = lines.map { line ->
    alignmentSpans
      .lastOrNull { span ->
        val spanStart = spanned.getSpanStart(span)
        val spanEnd = spanned.getSpanEnd(span)
        if (line.collapsed) {
          spanStart <= line.start && spanEnd >= line.end
        } else {
          spanStart < line.end && spanEnd > line.start
        }
      }
      ?.alignment
      ?.toTextAlign()
  }

  // A non-empty AlignmentSpan can still overlap no line, e.g. if it only covers a '\n'.
  if (lineAligns.all { it == null }) {
    append(text)
    return emptyList()
  }
  if (lineAligns.distinct().size == 1) {
    // All lines share the same alignment, which can't be null because of the check above.
    append(text)
    addStyle(ParagraphStyle(textAlign = checkNotNull(lineAligns[0])), start = 0, end = text.length)
    return emptyList()
  }

  val omittedNewlineIndices = mutableListOf<Int>()
  var runStartLine = 0
  while (runStartLine < lines.size) {
    val runAlign = lineAligns[runStartLine]
    var runEndLine = runStartLine
    while (runEndLine + 1 < lines.size && lineAligns[runEndLine + 1] == runAlign) {
      runEndLine++
    }
    val runEnd = lines[runEndLine].end
    val outStart = length
    append(text, lines[runStartLine].start, runEnd)
    // Unaligned runs don't need a ParagraphStyle, because AnnotatedString puts the text between
    // ParagraphStyles in separate default paragraphs.
    runAlign?.let { addStyle(ParagraphStyle(textAlign = it), outStart, length) }
    if (runEndLine + 1 < lines.size) {
      // The '\n' at runEnd is replaced by the paragraph boundary.
      omittedNewlineIndices.add(runEnd)
    }
    runStartLine = runEndLine + 1
  }
  return omittedNewlineIndices
}

/** Maps an [index] in the original text to the text appended without [omittedNewlineIndices]. */
private fun mapIndex(index: Int, omittedNewlineIndices: List<Int>): Int {
  return index - omittedNewlineIndices.count { it < index }
}

// TODO(b/575734702): Add separate underline and strikethrough styles once Compose combines
//  overlapping TextDecoration styles.
/**
 * Adds a single [TextDecoration] style per segment where [underlineRanges] and
 * [strikethroughRanges] overlap, because separate overlapping [TextDecoration] styles don't
 * combine.
 */
private fun AnnotatedString.Builder.addTextDecorationStyles(
  underlineRanges: List<TextRange>,
  strikethroughRanges: List<TextRange>,
) {
  val boundaries =
    (underlineRanges + strikethroughRanges).flatMap { listOf(it.start, it.end) }.distinct().sorted()
  var segmentStart = 0
  var segmentDecoration: TextDecoration? = null
  for (i in 0 until boundaries.size - 1) {
    val start = boundaries[i]
    val end = boundaries[i + 1]
    val decorations = buildList {
      if (underlineRanges.any { it.start <= start && end <= it.end }) add(TextDecoration.Underline)
      if (strikethroughRanges.any { it.start <= start && end <= it.end }) {
        add(TextDecoration.LineThrough)
      }
    }
    val decoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
    if (decoration != segmentDecoration) {
      segmentDecoration?.let { addStyle(SpanStyle(textDecoration = it), segmentStart, start) }
      segmentStart = start
      segmentDecoration = decoration
    }
  }
  segmentDecoration?.let {
    addStyle(SpanStyle(textDecoration = it), segmentStart, boundaries.last())
  }
}

private fun CharacterStyle.toSpanStyle(density: Density): SpanStyle? {
  return when (this) {
    is ForegroundColorSpan -> SpanStyle(color = Color(foregroundColor))
    is BackgroundColorSpan -> SpanStyle(background = Color(backgroundColor))
    is StyleSpan ->
      when (style) {
        Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Typeface.BOLD_ITALIC ->
          SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        else -> null
      }
    is AbsoluteSizeSpan -> {
      val sizeSp = with(density) { if (dip) size.dp.toSp() else size.toSp() }
      SpanStyle(fontSize = sizeSp)
    }
    is RelativeSizeSpan -> SpanStyle(fontSize = sizeChange.em)
    is ScaleXSpan -> SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = scaleX))
    is SubscriptSpan -> SpanStyle(baselineShift = BaselineShift.Subscript)
    is SuperscriptSpan -> SpanStyle(baselineShift = BaselineShift.Superscript)
    is TypefaceSpan ->
      ((if (Build.VERSION.SDK_INT >= 28) typeface?.toFontFamily() else null)
          ?: mapFontFamily(family))
        ?.let { SpanStyle(fontFamily = it) }
    is TextAppearanceSpan ->
      SpanStyle(
        color = textColor?.let { Color(it.defaultColor) } ?: Color.Unspecified,
        fontSize = if (textSize > 0) with(density) { textSize.toSp() } else TextUnit.Unspecified,
        fontWeight = if ((textStyle and Typeface.BOLD) != 0) FontWeight.Bold else null,
        fontStyle = if ((textStyle and Typeface.ITALIC) != 0) FontStyle.Italic else null,
        fontFamily = mapFontFamily(family),
      )
    // Other CharacterStyle spans (e.g. URLSpan) aren't supported.
    else -> null
  }
}

private fun mapFontFamily(fontFamilyName: String?): FontFamily? {
  return when (fontFamilyName) {
    null -> null
    "sans-serif" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else ->
      // Unknown family names resolve to the default typeface, which shouldn't override the base
      // font (as in AnnotatedString.fromHtml).
      Typeface.create(fontFamilyName, Typeface.NORMAL)
        .takeIf {
          it != Typeface.DEFAULT && it != Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        ?.toFontFamily()
  }
}

/**
 * Returns a copy of this text without span colors.
 *
 * Used to draw text outlines, because span colors would otherwise override the outline color.
 */
internal fun AnnotatedString.withoutSpanColors(): AnnotatedString {
  if (spanStyles.none { it.item.color.isSpecified }) {
    return this
  }
  return AnnotatedString(
    text,
    spanStyles.map { it.copy(item = it.item.copy(color = Color.Unspecified)) },
    paragraphStyles,
  )
}

internal fun Layout.Alignment.toTextAlign(): TextAlign {
  return when (this) {
    Layout.Alignment.ALIGN_NORMAL -> TextAlign.Start
    Layout.Alignment.ALIGN_OPPOSITE -> TextAlign.End
    Layout.Alignment.ALIGN_CENTER -> TextAlign.Center
  }
}

// TODO(b/241158858): Use FontFamily(typeface) instead once it applies the requested FontWeight and
//  FontStyle.
/**
 * Returns a [FontFamily] with normal, bold, italic and bold italic variants of this [Typeface].
 *
 * Unlike `FontFamily(typeface)`, which ignores the requested [FontWeight] and [FontStyle], this
 * lets bold and italic spans apply on top of the typeface. The variants are derived with
 * [Typeface.create], as `StyleSpan` does in the View system.
 */
internal fun Typeface.toFontFamily(): FontFamily {
  return FontFamily(
    TypefaceVariantFont(this, FontWeight.Normal, FontStyle.Normal),
    TypefaceVariantFont(this, FontWeight.Bold, FontStyle.Normal),
    TypefaceVariantFont(this, FontWeight.Normal, FontStyle.Italic),
    TypefaceVariantFont(this, FontWeight.Bold, FontStyle.Italic),
  )
}

/** An [AndroidFont] for the [weight] and [style] variant of [baseTypeface]. */
private class TypefaceVariantFont(
  baseTypeface: Typeface,
  override val weight: FontWeight,
  override val style: FontStyle,
) :
  AndroidFont(
    FontLoadingStrategy.Blocking,
    TypefaceVariantFontLoader,
    FontVariation.Settings(weight, style),
  ) {

  val typeface: Typeface =
    if (weight == FontWeight.Normal && style == FontStyle.Normal) {
      baseTypeface
    } else {
      // Add bold and italic to the base style, as StyleSpan does in the View system.
      val bold = weight == FontWeight.Bold || baseTypeface.isBold
      val italic = style == FontStyle.Italic || baseTypeface.isItalic
      val typefaceStyle =
        when {
          bold && italic -> Typeface.BOLD_ITALIC
          bold -> Typeface.BOLD
          italic -> Typeface.ITALIC
          else -> Typeface.NORMAL
        }
      Typeface.create(baseTypeface, typefaceStyle)
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TypefaceVariantFont) return false
    return typeface == other.typeface && weight == other.weight && style == other.style
  }

  override fun hashCode(): Int {
    var result = typeface.hashCode()
    result = 31 * result + weight.hashCode()
    result = 31 * result + style.hashCode()
    return result
  }
}

private object TypefaceVariantFontLoader : AndroidFont.TypefaceLoader {
  override fun loadBlocking(context: Context, font: AndroidFont): Typeface? =
    (font as? TypefaceVariantFont)?.typeface

  override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
    (font as? TypefaceVariantFont)?.typeface
}
