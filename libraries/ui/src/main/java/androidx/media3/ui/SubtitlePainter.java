/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.ui;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.text.Layout.Alignment;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import androidx.annotation.Nullable;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Paints subtitle {@link Cue}s. */
/* package */ final class SubtitlePainter {

  private static final String TAG = "SubtitlePainter";

  /** Ratio of inner padding to font size. */
  private static final float INNER_PADDING_RATIO = 0.125f;

  // Styled dimensions.
  private final float outlineWidth;
  private final float shadowRadius;
  private final float shadowOffset;
  private final float spacingMult;
  private final float spacingAdd;

  private final TextPaint textPaint;
  private final Paint windowPaint;
  private final Paint bitmapPaint;

  // Previous input variables.
  @Nullable private CharSequence cueText;
  @Nullable private Alignment cueTextAlignment;
  @Nullable private Bitmap cueBitmap;
  private float cueLine;
  private @Cue.LineType int cueLineType;
  private @Cue.AnchorType int cueLineAnchor;
  private float cuePosition;
  private @Cue.AnchorType int cuePositionAnchor;
  private float cueSize;
  private float cueBitmapHeight;
  private int foregroundColor;
  private int backgroundColor;
  private int windowColor;
  private int edgeColor;
  private @CaptionStyleCompat.EdgeType int edgeType;
  private float defaultTextSizePx;
  private float cueTextSizePx;
  private float bottomPaddingFraction;
  private int parentLeft;
  private int parentTop;
  private int parentRight;
  private int parentBottom;

  // Derived drawing variables.
  private @MonotonicNonNull StaticLayout textLayout;
  private @MonotonicNonNull StaticLayout edgeLayout;
  private int textLeft;
  private int textTop;
  private int textPaddingX;
  private @MonotonicNonNull Rect bitmapRect;

  @SuppressWarnings("ResourceType")
  public SubtitlePainter(Context context) {
    int[] viewAttr = {android.R.attr.lineSpacingExtra, android.R.attr.lineSpacingMultiplier};
    TypedArray styledAttributes = context.obtainStyledAttributes(null, viewAttr, 0, 0);
    spacingAdd = styledAttributes.getDimensionPixelSize(0, 0);
    spacingMult = styledAttributes.getFloat(1, 1);
    styledAttributes.recycle();

    Resources resources = context.getResources();
    DisplayMetrics displayMetrics = resources.getDisplayMetrics();
    int twoDpInPx = Math.round((2f * displayMetrics.densityDpi) / DisplayMetrics.DENSITY_DEFAULT);
    outlineWidth = twoDpInPx;
    shadowRadius = twoDpInPx;
    shadowOffset = twoDpInPx;

    textPaint = new TextPaint();
    textPaint.setAntiAlias(true);
    textPaint.setSubpixelText(true);

    windowPaint = new Paint();
    windowPaint.setAntiAlias(true);
    windowPaint.setStyle(Style.FILL);

    bitmapPaint = new Paint();
    bitmapPaint.setAntiAlias(true);
    bitmapPaint.setFilterBitmap(true);
  }

  /**
   * Draws the provided {@link Cue} into a canvas with the specified styling.
   *
   * <p>A call to this method is able to use cached results of calculations made during the previous
   * call, and so an instance of this class is able to optimize repeated calls to this method in
   * which the same parameters are passed.
   *
   * @param cue The cue to draw. sizes embedded within the cue should be applied. Otherwise, it is
   *     ignored.
   * @param style The style to use when drawing the cue text.
   * @param defaultTextSizePx The default text size to use when drawing the text, in pixels.
   * @param cueTextSizePx The embedded text size of this cue, in pixels.
   * @param bottomPaddingFraction The bottom padding fraction to apply when {@link Cue#line} is
   *     {@link Cue#DIMEN_UNSET}, as a fraction of the viewport height
   * @param canvas The canvas into which to draw.
   * @param cueBoxLeft The left position of the enclosing cue box.
   * @param cueBoxTop The top position of the enclosing cue box.
   * @param cueBoxRight The right position of the enclosing cue box.
   * @param cueBoxBottom The bottom position of the enclosing cue box.
   */
  public void draw(
      Cue cue,
      CaptionStyleCompat style,
      float defaultTextSizePx,
      float cueTextSizePx,
      float bottomPaddingFraction,
      Canvas canvas,
      int cueBoxLeft,
      int cueBoxTop,
      int cueBoxRight,
      int cueBoxBottom) {
    boolean isTextCue = cue.bitmap == null;
    int windowColor = Color.BLACK;
    if (isTextCue) {
      if (TextUtils.isEmpty(cue.text)) {
        // Nothing to draw.
        return;
      }
      windowColor = cue.windowColorSet ? cue.windowColor : style.windowColor;
    }
    if (areCharSequencesEqual(this.cueText, cue.text)
        && Objects.equals(this.cueTextAlignment, cue.textAlignment)
        && this.cueBitmap == cue.bitmap
        && this.cueLine == cue.line
        && this.cueLineType == cue.lineType
        && Objects.equals(this.cueLineAnchor, cue.lineAnchor)
        && this.cuePosition == cue.position
        && Objects.equals(this.cuePositionAnchor, cue.positionAnchor)
        && this.cueSize == cue.size
        && this.cueBitmapHeight == cue.bitmapHeight
        && this.foregroundColor == style.foregroundColor
        && this.backgroundColor == style.backgroundColor
        && this.windowColor == windowColor
        && this.edgeType == style.edgeType
        && this.edgeColor == style.edgeColor
        && Objects.equals(this.textPaint.getTypeface(), style.typeface)
        && this.defaultTextSizePx == defaultTextSizePx
        && this.cueTextSizePx == cueTextSizePx
        && this.bottomPaddingFraction == bottomPaddingFraction
        && this.parentLeft == cueBoxLeft
        && this.parentTop == cueBoxTop
        && this.parentRight == cueBoxRight
        && this.parentBottom == cueBoxBottom) {
      // We can use the cached layout.
      drawLayout(canvas, isTextCue);
      return;
    }

    this.cueText = cue.text;
    this.cueTextAlignment = cue.textAlignment;
    this.cueBitmap = cue.bitmap;
    this.cueLine = cue.line;
    this.cueLineType = cue.lineType;
    this.cueLineAnchor = cue.lineAnchor;
    this.cuePosition = cue.position;
    this.cuePositionAnchor = cue.positionAnchor;
    this.cueSize = cue.size;
    this.cueBitmapHeight = cue.bitmapHeight;
    this.foregroundColor = style.foregroundColor;
    this.backgroundColor = style.backgroundColor;
    this.windowColor = windowColor;
    this.edgeType = style.edgeType;
    this.edgeColor = style.edgeColor;
    this.textPaint.setTypeface(style.typeface);
    this.defaultTextSizePx = defaultTextSizePx;
    this.cueTextSizePx = cueTextSizePx;
    this.bottomPaddingFraction = bottomPaddingFraction;
    this.parentLeft = cueBoxLeft;
    this.parentTop = cueBoxTop;
    this.parentRight = cueBoxRight;
    this.parentBottom = cueBoxBottom;

    if (isTextCue) {
      Assertions.checkNotNull(cueText);
      setupTextLayout();
    } else {
      Assertions.checkNotNull(cueBitmap);
      setupBitmapLayout();
    }
    drawLayout(canvas, isTextCue);
  }

  @RequiresNonNull("cueText")
  private void setupTextLayout() {
    SpannableStringBuilder cueText =
        this.cueText instanceof SpannableStringBuilder
            ? (SpannableStringBuilder) this.cueText
            : new SpannableStringBuilder(this.cueText);
    int parentWidth = parentRight - parentLeft;
    int parentHeight = parentBottom - parentTop;

    textPaint.setTextSize(defaultTextSizePx);
    int textPaddingX = (int) (defaultTextSizePx * INNER_PADDING_RATIO + 0.5f);

    int availableWidth = parentWidth - textPaddingX * 2;
    if (cueSize != Cue.DIMEN_UNSET) {
      availableWidth = (int) (availableWidth * cueSize);
    }
    if (availableWidth <= 0) {
      Log.w(TAG, "Skipped drawing subtitle cue (insufficient space)");
      return;
    }

    if (cueTextSizePx > 0) {
      // Use an AbsoluteSizeSpan encompassing the whole text to apply the default cueTextSizePx.
      cueText.setSpan(
          new AbsoluteSizeSpan((int) cueTextSizePx),
          /* start= */ 0,
          /* end= */ cueText.length(),
          Spanned.SPAN_PRIORITY);
    }

    // Remove embedded font color to not destroy edges, otherwise it overrides edge color.
    SpannableStringBuilder cueTextEdge = new SpannableStringBuilder(cueText);
    if (edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
      ForegroundColorSpan[] foregroundColorSpans =
          cueTextEdge.getSpans(0, cueTextEdge.length(), ForegroundColorSpan.class);
      for (ForegroundColorSpan foregroundColorSpan : foregroundColorSpans) {
        cueTextEdge.removeSpan(foregroundColorSpan);
      }
    }

    // EDGE_TYPE_NONE & EDGE_TYPE_DROP_SHADOW both paint in one pass, they ignore cueTextEdge.
    // In other cases we use two painters and we need to apply the background in the first one only,
    // otherwise the background color gets drawn in front of the edge color
    // (https://github.com/google/ExoPlayer/pull/6724#issuecomment-564650572).
    if (Color.alpha(backgroundColor) > 0) {
      if (edgeType == CaptionStyleCompat.EDGE_TYPE_NONE
          || edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
        cueText.setSpan(
            new BackgroundColorSpan(backgroundColor), 0, cueText.length(), Spanned.SPAN_PRIORITY);
      } else {
        cueTextEdge.setSpan(
            new BackgroundColorSpan(backgroundColor),
            0,
            cueTextEdge.length(),
            Spanned.SPAN_PRIORITY);
      }
    }

    Alignment textAlignment = cueTextAlignment == null ? Alignment.ALIGN_CENTER : cueTextAlignment;
    textLayout =
        new StaticLayout(
            cueText, textPaint, availableWidth, textAlignment, spacingMult, spacingAdd, true);
    int textHeight = textLayout.getHeight();
    int textWidth = 0;
    int lineCount = textLayout.getLineCount();
    for (int i = 0; i < lineCount; i++) {
      textWidth = Math.max((int) Math.ceil(textLayout.getLineWidth(i)), textWidth);
    }
    if (cueSize != Cue.DIMEN_UNSET && textWidth < availableWidth) {
      textWidth = availableWidth;
    }
    textWidth += textPaddingX * 2;

    int textLeft;
    int textRight;
    if (cuePosition != Cue.DIMEN_UNSET) {
      int anchorPosition = Math.round(parentWidth * cuePosition) + parentLeft;
      switch (cuePositionAnchor) {
        case Cue.ANCHOR_TYPE_END:
          textLeft = anchorPosition - textWidth;
          break;
        case Cue.ANCHOR_TYPE_MIDDLE:
          textLeft = (anchorPosition * 2 - textWidth) / 2;
          break;
        case Cue.ANCHOR_TYPE_START:
        case Cue.TYPE_UNSET:
        default:
          textLeft = anchorPosition;
      }

      textLeft = Math.max(textLeft, parentLeft);
      textRight = Math.min(textLeft + textWidth, parentRight);
    } else {
      textLeft = (parentWidth - textWidth) / 2 + parentLeft;
      textRight = textLeft + textWidth;
    }

    textWidth = textRight - textLeft;
    if (textWidth <= 0) {
      Log.w(TAG, "Skipped drawing subtitle cue (invalid horizontal positioning)");
      return;
    }

    int textTop;
    if (cueLine != Cue.DIMEN_UNSET) {
      if (cueLineType == Cue.LINE_TYPE_FRACTION) {
        int anchorPosition = Math.round(parentHeight * cueLine) + parentTop;
        textTop =
            cueLineAnchor == Cue.ANCHOR_TYPE_END
                ? anchorPosition - textHeight
                : cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE
                    ? (anchorPosition * 2 - textHeight) / 2
                    : anchorPosition;
      } else {
        // cueLineType == Cue.LINE_TYPE_NUMBER
        int firstLineHeight = textLayout.getLineBottom(0) - textLayout.getLineTop(0);
        if (cueLine >= 0) {
          textTop = Math.round(cueLine * firstLineHeight) + parentTop;
        } else {
          textTop = Math.round((cueLine + 1) * firstLineHeight) + parentBottom - textHeight;
        }
      }

      if (textTop + textHeight > parentBottom) {
        textTop = parentBottom - textHeight;
      } else if (textTop < parentTop) {
        textTop = parentTop;
      }
    } else {
      textTop = parentBottom - textHeight - (int) (parentHeight * bottomPaddingFraction);
    }

    // Update the derived drawing variables.
    this.textLayout =
        new StaticLayout(
            cueText, textPaint, textWidth, textAlignment, spacingMult, spacingAdd, true);
    this.edgeLayout =
        new StaticLayout(
            cueTextEdge, textPaint, textWidth, textAlignment, spacingMult, spacingAdd, true);
    this.textLeft = textLeft;
    this.textTop = textTop;
    this.textPaddingX = textPaddingX;
  }

  @RequiresNonNull("cueBitmap")
  private void setupBitmapLayout() {
    Bitmap cueBitmap = this.cueBitmap;
    int parentWidth = parentRight - parentLeft;
    int parentHeight = parentBottom - parentTop;
    float anchorX = parentLeft + (parentWidth * cuePosition);
    float anchorY = parentTop + (parentHeight * cueLine);
    int width = Math.round(parentWidth * cueSize);
    int height =
        cueBitmapHeight != Cue.DIMEN_UNSET
            ? Math.round(parentHeight * cueBitmapHeight)
            : Math.round(width * ((float) cueBitmap.getHeight() / cueBitmap.getWidth()));
    int x =
        Math.round(
            cuePositionAnchor == Cue.ANCHOR_TYPE_END
                ? (anchorX - width)
                : cuePositionAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorX - (width / 2)) : anchorX);
    int y =
        Math.round(
            cueLineAnchor == Cue.ANCHOR_TYPE_END
                ? (anchorY - height)
                : cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE ? (anchorY - (height / 2)) : anchorY);
    bitmapRect = new Rect(x, y, x + width, y + height);
  }

  private void drawLayout(Canvas canvas, boolean isTextCue) {
    if (isTextCue) {
      drawTextLayout(canvas);
    } else {
      Assertions.checkNotNull(bitmapRect);
      Assertions.checkNotNull(cueBitmap);
      drawBitmapLayout(canvas);
    }
  }

  private void drawTextLayout(Canvas canvas) {
    StaticLayout textLayout = this.textLayout;
    StaticLayout edgeLayout = this.edgeLayout;
    if (textLayout == null || edgeLayout == null) {
      // Nothing to draw.
      return;
    }

    int saveCount = canvas.save();
    canvas.translate(textLeft, textTop);

    if (Color.alpha(windowColor) > 0) {
      windowPaint.setColor(windowColor);
      canvas.drawRect(
          -textPaddingX,
          0,
          textLayout.getWidth() + textPaddingX,
          textLayout.getHeight(),
          windowPaint);
    }

    if (edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
      textPaint.setStrokeJoin(Join.ROUND);
      textPaint.setStrokeWidth(outlineWidth);
      textPaint.setColor(edgeColor);
      textPaint.setStyle(Style.FILL_AND_STROKE);
      edgeLayout.draw(canvas);
    } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
      textPaint.setShadowLayer(shadowRadius, shadowOffset, shadowOffset, edgeColor);
    } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED
        || edgeType == CaptionStyleCompat.EDGE_TYPE_DEPRESSED) {
      boolean raised = edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED;
      int colorUp = raised ? Color.WHITE : edgeColor;
      int colorDown = raised ? edgeColor : Color.WHITE;
      float offset = shadowRadius / 2f;
      textPaint.setColor(foregroundColor);
      textPaint.setStyle(Style.FILL);
      textPaint.setShadowLayer(shadowRadius, -offset, -offset, colorUp);
      edgeLayout.draw(canvas);
      textPaint.setShadowLayer(shadowRadius, offset, offset, colorDown);
    }

    textPaint.setColor(foregroundColor);
    textPaint.setStyle(Style.FILL);
    textLayout.draw(canvas);
    textPaint.setShadowLayer(0, 0, 0, 0);

    canvas.restoreToCount(saveCount);
  }

  @RequiresNonNull({"cueBitmap", "bitmapRect"})
  private void drawBitmapLayout(Canvas canvas) {
    canvas.drawBitmap(cueBitmap, /* src= */ null, bitmapRect, bitmapPaint);
  }

  /**
   * This method is used instead of {@link TextUtils#equals(CharSequence, CharSequence)} because the
   * latter only checks the text of each sequence, and does not check for equality of styling that
   * may be embedded within the {@link CharSequence}s.
   */
  @SuppressWarnings("UndefinedEquals")
  private static boolean areCharSequencesEqual(
      @Nullable CharSequence first, @Nullable CharSequence second) {
    // Some CharSequence implementations don't perform a cheap referential equality check in their
    // equals methods, so we perform one explicitly here.
    return first == second || (first != null && first.equals(second));
  }
}
