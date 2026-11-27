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

import static androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION;
import static androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.util.AttributeSet;
import android.view.View;
import androidx.media3.common.Flags;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueUtil;
import androidx.media3.common.text.SubtitleStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A {@link SubtitleView.Output} that uses Android's native layout framework via {@link
 * SubtitlePainter}.
 */
/* package */ final class CanvasSubtitleOutput extends View implements SubtitleView.Output {

  private static final int SHIFT_DIRECTION_NONE = 0;
  private static final int SHIFT_DIRECTION_UP = 1;
  private static final int SHIFT_DIRECTION_DOWN = 2;

  private final List<SubtitlePainter> painters;
  private final List<Rect> cueRectPool;
  private final List<Rect> placedTextCueRects;

  private int[] cueShiftDirections;
  private List<Cue> cues;
  private @Cue.TextSizeType int textSizeType;
  private float textSize;
  private SubtitleStyle style;
  private float bottomPaddingFraction;
  private TextDirectionHeuristic textDirectionHeuristic;

  public CanvasSubtitleOutput(Context context) {
    this(context, /* attrs= */ null);
  }

  public CanvasSubtitleOutput(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    painters = new ArrayList<>();
    cueRectPool = new ArrayList<>();
    placedTextCueRects = new ArrayList<>();
    cueShiftDirections = new int[0];
    cues = Collections.emptyList();
    textSizeType = Cue.TEXT_SIZE_TYPE_FRACTIONAL;
    textSize = DEFAULT_TEXT_SIZE_FRACTION;
    style = SubtitleStyle.DEFAULT;
    bottomPaddingFraction = DEFAULT_BOTTOM_PADDING_FRACTION;
    textDirectionHeuristic = TextDirectionHeuristics.FIRSTSTRONG_LTR;
  }

  @Override
  public void update(
      List<Cue> cues,
      SubtitleStyle style,
      float textSize,
      @Cue.TextSizeType int textSizeType,
      float bottomPaddingFraction,
      TextDirectionHeuristic textDirectionHeuristic) {
    this.cues = cues;
    this.style = style;
    this.textSize = textSize;
    this.textSizeType = textSizeType;
    this.bottomPaddingFraction = bottomPaddingFraction;
    this.textDirectionHeuristic = textDirectionHeuristic;
    // Ensure we have sufficient painters and reusable scratch storage, and reset unused painters.
    while (painters.size() < cues.size()) {
      painters.add(new SubtitlePainter(getContext()));
      cueRectPool.add(new Rect());
    }
    if (cueShiftDirections.length < cues.size()) {
      cueShiftDirections = new int[cues.size()];
    }
    for (int i = cues.size(); i < painters.size(); i++) {
      painters.get(i).reset();
    }
    // Invalidate to trigger drawing.
    invalidate();
  }

  @Override
  public void dispatchDraw(Canvas canvas) {
    @Nullable List<Cue> cues = this.cues;
    if (cues.isEmpty()) {
      return;
    }

    int rawViewHeight = getHeight();

    // Calculate the cue box bounds relative to the canvas after padding is taken into account.
    int left = getPaddingLeft();
    int top = getPaddingTop();
    int right = getWidth() - getPaddingRight();
    int bottom = rawViewHeight - getPaddingBottom();
    if (bottom <= top || right <= left) {
      // No space to draw subtitles.
      resetAllPainters();
      return;
    }
    int viewHeightMinusPadding = bottom - top;

    float defaultViewTextSizePx =
        CueUtil.resolveTextSize(textSizeType, textSize, rawViewHeight, viewHeightMinusPadding);
    if (defaultViewTextSizePx <= 0) {
      // Text has no height.
      resetAllPainters();
      return;
    }

    boolean avoidOverlappingCues = Flags.isEnabled(Flags.FLAG_AVOID_OVERLAPPING_SUBTITLE_CUES);
    placedTextCueRects.clear();
    int cueCount = cues.size();
    // Lay out all cues first and record fixed text cues as obstacles so movable cues earlier in
    // the list still avoid overlapping fixed cues later in the list.
    for (int i = 0; i < cueCount; i++) {
      Cue cue = cues.get(i);
      if (cue.verticalType != Cue.TYPE_UNSET) {
        cue = repositionVerticalCue(cue);
      }
      float cueTextSizePx =
          CueUtil.resolveTextSize(
              cue.textSizeType, cue.textSize, rawViewHeight, viewHeightMinusPadding);
      SubtitlePainter painter = painters.get(i);
      painter.layout(
          cue,
          style,
          defaultViewTextSizePx,
          cueTextSizePx,
          bottomPaddingFraction,
          textDirectionHeuristic,
          left,
          top,
          right,
          bottom);
      if (!avoidOverlappingCues) {
        painter.draw(canvas, /* textVerticalOffsetPx= */ 0);
        continue;
      }
      Rect cueRect = cueRectPool.get(i);
      if (painter.getTextBounds(cueRect)) {
        if (cue.line == Cue.DIMEN_UNSET || cue.lineType == Cue.LINE_TYPE_NUMBER) {
          cueShiftDirections[i] =
              (cue.line == Cue.DIMEN_UNSET || cue.line < 0)
                  ? SHIFT_DIRECTION_UP
                  : SHIFT_DIRECTION_DOWN;
        } else {
          cueShiftDirections[i] = SHIFT_DIRECTION_NONE;
          placedTextCueRects.add(cueRect);
        }
      } else {
        cueShiftDirections[i] = SHIFT_DIRECTION_NONE;
      }
    }
    if (!avoidOverlappingCues) {
      return;
    }
    for (int i = 0; i < cueCount; i++) {
      int verticalOffsetPx = 0;
      int shiftDirection = cueShiftDirections[i];
      if (shiftDirection != SHIFT_DIRECTION_NONE) {
        Rect cueRect = cueRectPool.get(i);
        int resolvedTop =
            CueUtil.resolveVerticalOverlap(
                cueRect,
                placedTextCueRects,
                top,
                bottom,
                /* preferUpward= */ shiftDirection == SHIFT_DIRECTION_UP);
        verticalOffsetPx = resolvedTop - cueRect.top;
        cueRect.offset(/* dx= */ 0, verticalOffsetPx);
        placedTextCueRects.add(cueRect);
      }
      painters.get(i).draw(canvas, verticalOffsetPx);
    }
  }

  /**
   * Reposition a vertical cue for horizontal display.
   *
   * <p>This class doesn't support rendering vertical text, but if we naively interpret vertical
   * {@link Cue#position} and{@link Cue#line} values for horizontal display then the cues will often
   * be displayed in unexpected positions. For example, the 'default' position for vertical-rl
   * subtitles is the right-hand edge of the viewport, so cues that would appear vertically in this
   * position should appear horizontally at the bottom of the viewport (generally the default
   * position). Similarly left-edge vertical-rl cues should be shown at the top of a horizontal
   * viewport.
   *
   * <p>There isn't a meaningful way to transform {@link Cue#position} and related values (e.g.
   * alignment), so we clear these and allow {@link SubtitlePainter} to do the default behaviour of
   * centering the cue.
   */
  private static Cue repositionVerticalCue(Cue cue) {
    Cue.Builder cueBuilder =
        cue.buildUpon()
            .setPosition(Cue.DIMEN_UNSET)
            .setPositionAnchor(Cue.TYPE_UNSET)
            .setTextAlignment(null);

    if (cue.lineType == Cue.LINE_TYPE_FRACTION) {
      cueBuilder.setLine(1.0f - cue.line, Cue.LINE_TYPE_FRACTION);
    } else {
      cueBuilder.setLine(-cue.line - 1f, Cue.LINE_TYPE_NUMBER);
    }
    switch (cue.lineAnchor) {
      case Cue.ANCHOR_TYPE_END:
        cueBuilder.setLineAnchor(Cue.ANCHOR_TYPE_START);
        break;
      case Cue.ANCHOR_TYPE_START:
        cueBuilder.setLineAnchor(Cue.ANCHOR_TYPE_END);
        break;
      case Cue.ANCHOR_TYPE_MIDDLE:
      case Cue.TYPE_UNSET:
      default:
        // Fall through
    }
    return cueBuilder.build();
  }

  private void resetAllPainters() {
    for (int i = 0; i < painters.size(); i++) {
      painters.get(i).reset();
    }
  }
}
