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

import androidx.media3.common.util.UnstableApi;

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

  private CueUtil() {}
}
