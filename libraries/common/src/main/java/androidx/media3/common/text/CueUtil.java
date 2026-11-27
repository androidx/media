/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common.text;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.graphics.Rect;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/** Utility methods and constants for {@link Cue} layout and styling. */
@UnstableApi
public final class CueUtil {

  /** The default fractional text size, as a fraction of the viewport height. */
  public static final float DEFAULT_TEXT_SIZE_FRACTION = 0.0533f;

  /**
   * The default bottom padding to apply when {@link Cue#line} is {@link Cue#DIMEN_UNSET}, as a
   * fraction of the viewport height.
   */
  public static final float DEFAULT_BOTTOM_PADDING_FRACTION = 0.08f;

  /**
   * Returns the text size in px, derived from {@code textSize} and {@code textSizeType}.
   *
   * <p>Returns {@link Cue#DIMEN_UNSET} if {@code textSize == Cue.DIMEN_UNSET} or {@code
   * textSizeType == Cue.TYPE_UNSET}.
   */
  public static float resolveTextSize(
      @Cue.TextSizeType int textSizeType,
      float textSize,
      int rawViewHeight,
      int viewHeightMinusPadding) {
    if (textSize == Cue.DIMEN_UNSET) {
      return Cue.DIMEN_UNSET;
    }
    switch (textSizeType) {
      case Cue.TEXT_SIZE_TYPE_ABSOLUTE:
        return textSize;
      case Cue.TEXT_SIZE_TYPE_FRACTIONAL:
        return textSize * viewHeightMinusPadding;
      case Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING:
        return textSize * rawViewHeight;
      case Cue.TYPE_UNSET:
      default:
        return Cue.DIMEN_UNSET;
    }
  }

  /**
   * Calculates the pixel offset for placing a cue along a given axis (X or Y) relative to the
   * viewport.
   *
   * <p>If {@code fractionalPosition} is {@link Cue#DIMEN_UNSET}, the cue is centered within the
   * viewport. Otherwise, the offset is determined by aligning the specified {@code anchorType} of
   * the cue with the {@code fractionalPosition} of the viewport.
   *
   * @param fractionalPosition The position of the cue's anchor, expressed as a fraction (between 0
   *     and 1) of {@code viewDimension}, or {@link Cue#DIMEN_UNSET}.
   * @param anchorType The anchor type (e.g., {@link Cue#ANCHOR_TYPE_START}, {@link
   *     Cue#ANCHOR_TYPE_MIDDLE}, {@link Cue#ANCHOR_TYPE_END}).
   * @param cueDimension The pixel dimension (width or height) of the cue.
   * @param viewDimension The pixel dimension (width or height) of the viewport.
   * @return The pixel offset of the start (top or left) of the cue, relative to the start (top or
   *     left) of the viewport.
   */
  public static float resolveAnchorOffset(
      float fractionalPosition,
      @Cue.AnchorType int anchorType,
      int cueDimension,
      int viewDimension) {
    if (fractionalPosition == Cue.DIMEN_UNSET) {
      return (viewDimension - cueDimension) / 2f; // Fallback to center
    }

    float anchorPoint = viewDimension * fractionalPosition;
    switch (anchorType) {
      case Cue.ANCHOR_TYPE_END:
        return anchorPoint - cueDimension;
      case Cue.ANCHOR_TYPE_MIDDLE:
        return anchorPoint - (cueDimension / 2f);
      case Cue.ANCHOR_TYPE_START:
      case Cue.TYPE_UNSET:
      default:
        return anchorPoint;
    }
  }

  /**
   * Resolves vertical overlap between a candidate cue box and already-placed cue boxes within a
   * viewport.
   *
   * <p>If {@code cueRect} is empty or does not intersect any box in {@code placedRects}, {@code
   * cueRect.top} is returned unchanged.
   *
   * <p>Otherwise, the candidate box is shifted vertically in the preferred direction (upward if
   * {@code preferUpward} is {@code true}, downward otherwise) past each intersecting box until a
   * non-overlapping position within {@code [viewportTop, viewportBottom]} is found. If shifting in
   * the preferred direction reaches the viewport boundary without finding a free position, the
   * search restarts from {@code cueRect.top} in the opposite direction. If neither direction fits
   * within the viewport without overlap, {@code cueRect.top} is returned unchanged.
   *
   * @param cueRect The initial bounding box of the candidate cue, in pixels. Not modified.
   * @param placedRects The bounding boxes of cues already placed in the viewport, in pixels.
   * @param viewportTop The top boundary of the viewport, in pixels.
   * @param viewportBottom The bottom boundary of the viewport, in pixels.
   * @param preferUpward Whether to shift upward first ({@code true}) or downward first ({@code
   *     false}) when resolving overlap.
   * @return The resolved top coordinate for the candidate cue, in pixels.
   */
  @RestrictTo(LIBRARY_GROUP)
  public static int resolveVerticalOverlap(
      Rect cueRect,
      List<Rect> placedRects,
      int viewportTop,
      int viewportBottom,
      boolean preferUpward) {
    if (placedRects.isEmpty() || cueRect.isEmpty()) {
      return cueRect.top;
    }
    int height = cueRect.height();
    for (int attempt = 0; attempt < 2; attempt++) {
      boolean moveUp = (attempt == 0) == preferUpward;
      int candidateTop = cueRect.top;
      while (true) {
        int nextTop = candidateTop;
        for (int i = 0; i < placedRects.size(); i++) {
          Rect placed = placedRects.get(i);
          if (intersects(
              cueRect.left, candidateTop, cueRect.right, candidateTop + height, placed)) {
            nextTop =
                moveUp ? Math.min(nextTop, placed.top - height) : Math.max(nextTop, placed.bottom);
          }
        }
        if (nextTop == candidateTop) {
          if (candidateTop == cueRect.top
              || (candidateTop >= viewportTop && candidateTop + height <= viewportBottom)) {
            return candidateTop;
          }
          break;
        }
        candidateTop = nextTop;
        if (moveUp ? candidateTop < viewportTop : candidateTop + height > viewportBottom) {
          break;
        }
      }
    }
    return cueRect.top;
  }

  private static boolean intersects(int left, int top, int right, int bottom, Rect placed) {
    return !placed.isEmpty()
        && left < placed.right
        && right > placed.left
        && top < placed.bottom
        && bottom > placed.top;
  }

  private CueUtil() {}
}
