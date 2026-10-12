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

import android.text.Layout.Alignment.ALIGN_NORMAL
import android.text.Layout.Alignment.ALIGN_OPPOSITE
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueUtil
import androidx.media3.common.util.Log
import kotlin.math.roundToInt

private const val TAG = "TextCue"

private const val DEFAULT_Y_FRACTION = 1 - CueUtil.DEFAULT_BOTTOM_PADDING_FRACTION

/**
 * Renders a text subtitle cue (i.e., [Cue.text]) using a [Text] composable.
 *
 * This component sizes and places the text within the given [viewport] using a custom layout,
 * taking into account text alignment and text size.
 *
 * Note: The user's caption style (such as text backgrounds and edge types) isn't applied yet.
 *
 * @param cue The text [Cue] to render. If [Cue.text] is null, this composable renders nothing.
 * @param viewport The [IntRect] bounds representing the video frame surface. The cue's size and
 *   offsets are calculated relative to this rectangle.
 * @param modifier The [Modifier] to be applied to the component.
 */
@Composable
internal fun TextCue(cue: Cue, viewport: IntRect, modifier: Modifier = Modifier) {
  val text = cue.text ?: return

  if (cue.verticalType != Cue.TYPE_UNSET) {
    // TODO(b/384925883): Render vertical text, e.g. with androidx.text:text-vertical once stable.
    LaunchedEffect(cue) { Log.w(TAG, "Vertical text isn't supported yet, rendering horizontally") }
  }

  val density = LocalDensity.current
  var textLayoutResult by remember(cue) { mutableStateOf<TextLayoutResult?>(null) }

  Layout(
    modifier = Modifier.offset { IntOffset(viewport.left, viewport.top) },
    content = {
      Text(
        // TODO(b/374697754): Support conversion of styling spans to Compose AnnotatedString.
        text = text.toString(),
        modifier =
          modifier
            .resizeTextCueBox(cue, viewport.width, density)
            // Hide text until initial layout completes to prevent initial flash
            .alpha(if (textLayoutResult != null) 1f else 0f),
        // TODO(b/384925883): Apply the user's caption style (backgrounds, edge types).
        style = createTextStyle(cue, viewport.height, density),
        onTextLayout = { layoutResult -> textLayoutResult = layoutResult },
      )
    },
  ) { measurables, _ ->
    val placeable = measurables[0].measure(Constraints())

    val offset =
      getOffset(
        cue = cue,
        viewWidth = viewport.width,
        viewHeight = viewport.height,
        textWidth = placeable.width,
        textHeight = placeable.height,
        textLayoutResult = textLayoutResult,
      )

    layout(viewport.width, viewport.height) { placeable.place(offset) }
  }
}

private fun createTextStyle(cue: Cue, viewHeight: Int, density: Density): TextStyle {
  // TODO(b/550450834): Add padding support to Player and pass viewHeightMinusPadding.
  val resolvedSizePx =
    CueUtil.resolveTextSize(cue.textSizeType, cue.textSize, viewHeight, viewHeight)
  val fontSizePx =
    if (resolvedSizePx != Cue.DIMEN_UNSET) {
      resolvedSizePx
    } else {
      CueUtil.DEFAULT_TEXT_SIZE_FRACTION * viewHeight
    }

  val fontSizeSp = with(density) { fontSizePx.toSp() }

  return TextStyle(fontSize = fontSizeSp, textAlign = convertAlignment(cue))
}

private fun Modifier.resizeTextCueBox(cue: Cue, viewWidth: Int, density: Density): Modifier {
  // Cap the width so the cue stays inside the viewport without moving its anchor (as in WebVTT).
  // At position 0 or 1, some anchors would leave no room at all, so those cases fall back to the
  // full width and rely on the clamping in getOffset.
  val maxWidthFraction =
    if (cue.position > 0f && cue.position < 1f) {
      when (cue.positionAnchor) {
        Cue.ANCHOR_TYPE_END -> cue.position
        Cue.ANCHOR_TYPE_MIDDLE -> 2f * minOf(cue.position, 1f - cue.position)
        else -> 1f - cue.position // ANCHOR_TYPE_START or TYPE_UNSET
      }
    } else {
      1f
    }
  val maxWidthPx = viewWidth * maxWidthFraction
  return if (cue.size != Cue.DIMEN_UNSET) {
    width(with(density) { minOf(viewWidth * cue.size, maxWidthPx).toDp() })
  } else {
    widthIn(max = with(density) { maxWidthPx.toDp() })
  }
}

private fun getOffset(
  cue: Cue,
  viewWidth: Int,
  viewHeight: Int,
  textWidth: Int,
  textHeight: Int,
  textLayoutResult: TextLayoutResult?,
): IntOffset {
  val offsetX = CueUtil.resolveAnchorOffset(cue.position, cue.positionAnchor, textWidth, viewWidth)

  val offsetY =
    if (cue.line == Cue.DIMEN_UNSET) {
      viewHeight * DEFAULT_Y_FRACTION - textHeight
    } else if (cue.lineType == Cue.LINE_TYPE_NUMBER) {
      if (textLayoutResult == null || textHeight == 0) {
        viewHeight * DEFAULT_Y_FRACTION - textHeight
      } else {
        val firstLineHeight = textLayoutResult.getLineBottom(0) - textLayoutResult.getLineTop(0)
        if (cue.line >= 0) {
          cue.line * firstLineHeight
        } else {
          // Negative line numbers stack upwards from the bottom of the viewport.
          // For example, cue.line = -1 means the bottom-most line.
          (cue.line + 1) * firstLineHeight + viewHeight - textHeight
        }
      }
    } else {
      CueUtil.resolveAnchorOffset(cue.line, cue.lineAnchor, textHeight, viewHeight)
    }

  val maxOffsetX = maxOf(0, viewWidth - textWidth).toFloat()
  val maxOffsetY = maxOf(0, viewHeight - textHeight).toFloat()
  val clampedX = offsetX.coerceIn(0f, maxOffsetX)
  val clampedY = offsetY.coerceIn(0f, maxOffsetY)

  return IntOffset(clampedX.roundToInt(), clampedY.roundToInt())
}

private fun convertAlignment(cue: Cue): TextAlign {
  return when (cue.textAlignment) {
    ALIGN_NORMAL -> TextAlign.Start
    ALIGN_OPPOSITE -> TextAlign.End
    else -> TextAlign.Center
  }
}
