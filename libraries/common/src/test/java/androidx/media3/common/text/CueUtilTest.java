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

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Rect;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Tests for {@link CueUtil}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class CueUtilTest {

  @Test
  public void resolveTextSize_withAbsoluteType_returnsTextSizeDirectly() {
    float resolvedSize =
        CueUtil.resolveTextSize(
            Cue.TEXT_SIZE_TYPE_ABSOLUTE,
            /* textSize= */ 24f,
            /* rawViewHeight= */ 1000,
            /* viewHeightMinusPadding= */ 800);

    assertThat(resolvedSize).isEqualTo(24f);
  }

  @Test
  public void resolveTextSize_withFractionalType_usesViewHeightMinusPadding() {
    float resolvedSize =
        CueUtil.resolveTextSize(
            Cue.TEXT_SIZE_TYPE_FRACTIONAL,
            /* textSize= */ 0.25f,
            /* rawViewHeight= */ 1000,
            /* viewHeightMinusPadding= */ 800);

    assertThat(resolvedSize).isEqualTo(200f);
  }

  @Test
  public void resolveTextSize_withFractionalIgnorePaddingType_usesRawViewHeight() {
    float resolvedSize =
        CueUtil.resolveTextSize(
            Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING,
            /* textSize= */ 0.25f,
            /* rawViewHeight= */ 1000,
            /* viewHeightMinusPadding= */ 800);

    assertThat(resolvedSize).isEqualTo(250f);
  }

  @Test
  public void resolveTextSize_withDimenUnset_returnsDimenUnset() {
    float resolvedSize =
        CueUtil.resolveTextSize(
            Cue.TEXT_SIZE_TYPE_FRACTIONAL,
            /* textSize= */ Cue.DIMEN_UNSET,
            /* rawViewHeight= */ 1000,
            /* viewHeightMinusPadding= */ 800);

    assertThat(resolvedSize).isEqualTo(Cue.DIMEN_UNSET);
  }

  @Test
  public void resolveTextSize_withTypeUnset_returnsDimenUnset() {
    float resolvedSize =
        CueUtil.resolveTextSize(
            Cue.TYPE_UNSET,
            /* textSize= */ 24f,
            /* rawViewHeight= */ 1000,
            /* viewHeightMinusPadding= */ 800);

    assertThat(resolvedSize).isEqualTo(Cue.DIMEN_UNSET);
  }

  @Test
  public void resolveAnchorOffset_withDimenUnset_centersInViewport() {
    float offset =
        CueUtil.resolveAnchorOffset(
            /* fractionalPosition= */ Cue.DIMEN_UNSET,
            Cue.TYPE_UNSET,
            /* cueDimension= */ 51,
            /* viewDimension= */ 200);

    assertThat(offset).isEqualTo(74.5f);
  }

  @Test
  public void resolveAnchorOffset_withAnchorTypeStart_returnsAnchorPoint() {
    float offset =
        CueUtil.resolveAnchorOffset(
            /* fractionalPosition= */ 0.25f,
            Cue.ANCHOR_TYPE_START,
            /* cueDimension= */ 50,
            /* viewDimension= */ 200);

    assertThat(offset).isEqualTo(50f);
  }

  @Test
  public void resolveAnchorOffset_withAnchorTypeMiddle_subtractsHalfCueDimension() {
    float offset =
        CueUtil.resolveAnchorOffset(
            /* fractionalPosition= */ 0.5f,
            Cue.ANCHOR_TYPE_MIDDLE,
            /* cueDimension= */ 51,
            /* viewDimension= */ 200);

    assertThat(offset).isEqualTo(74.5f);
  }

  @Test
  public void resolveAnchorOffset_withAnchorTypeEnd_subtractsFullCueDimension() {
    float offset =
        CueUtil.resolveAnchorOffset(
            /* fractionalPosition= */ 1.0f,
            Cue.ANCHOR_TYPE_END,
            /* cueDimension= */ 50,
            /* viewDimension= */ 200);

    assertThat(offset).isEqualTo(150f);
  }

  @Test
  public void resolveVerticalOverlap_variousCases_returnsExpectedTop(
      @TestParameter ResolveVerticalOverlapCase testCase) {
    Rect cueRect = toRect(testCase.cueRect);
    Rect originalCueRect = new Rect(cueRect);
    List<Rect> placedRects = new ArrayList<>(testCase.placedRects.size());
    for (ImmutableList<Integer> placedRect : testCase.placedRects) {
      placedRects.add(toRect(placedRect));
    }

    int resolvedTop =
        CueUtil.resolveVerticalOverlap(
            cueRect,
            placedRects,
            testCase.viewportTop,
            testCase.viewportBottom,
            testCase.preferUpward);

    assertThat(resolvedTop).isEqualTo(testCase.expectedTop);
    assertThat(cueRect).isEqualTo(originalCueRect);
  }

  private enum ResolveVerticalOverlapCase {
    EMPTY_PLACED_RECTS(
        /* cueRect= */ rect(0, 900, 400, 960),
        /* placedRects= */ ImmutableList.of(),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 900),
    EMPTY_CUE_RECT(
        /* cueRect= */ rect(0, 900, 400, 900),
        /* placedRects= */ ImmutableList.of(rect(0, 880, 400, 960)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 900),
    NO_INTERSECTION(
        /* cueRect= */ rect(0, 800, 400, 860),
        /* placedRects= */ ImmutableList.of(rect(0, 900, 400, 960)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 800),
    OUT_OF_VIEWPORT_NO_INTERSECTION(
        /* cueRect= */ rect(0, 50, 400, 150),
        /* placedRects= */ ImmutableList.of(rect(0, 300, 400, 400)),
        /* viewportTop= */ 100,
        /* viewportBottom= */ 600,
        /* preferUpward= */ true,
        /* expectedTop= */ 50),
    TOUCHING_EDGES(
        /* cueRect= */ rect(0, 840, 400, 900),
        /* placedRects= */ ImmutableList.of(rect(0, 900, 400, 960)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 840),
    DISJOINT_HORIZONTALLY(
        /* cueRect= */ rect(220, 900, 400, 960),
        /* placedRects= */ ImmutableList.of(rect(0, 900, 200, 960)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 900),
    PREFER_UPWARD_SHIFTS_ABOVE_PLACED_RECT(
        /* cueRect= */ rect(0, 880, 400, 940),
        /* placedRects= */ ImmutableList.of(rect(0, 880, 400, 1000)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ true,
        /* expectedTop= */ 820),
    PREFER_DOWNWARD_SHIFTS_BELOW_PLACED_RECT(
        /* cueRect= */ rect(0, 60, 400, 120),
        /* placedRects= */ ImmutableList.of(rect(0, 0, 400, 120)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 1000,
        /* preferUpward= */ false,
        /* expectedTop= */ 120),
    // Placed rects are ordered away from the initial collision ([100, 200] before [250, 350]) so
    // the 50px gap between them exercises multi-pass re-checking for the 100px cue.
    CASCADING_COLLISIONS_UPWARD_SKIPS_MULTIPLE_RECTS(
        /* cueRect= */ rect(0, 300, 400, 400),
        /* placedRects= */ ImmutableList.of(rect(0, 100, 400, 200), rect(0, 250, 400, 350)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 500,
        /* preferUpward= */ true,
        /* expectedTop= */ 0),
    // Placed rects are ordered away from the initial collision ([300, 400] before [150, 250]) so
    // the 50px gap between them exercises downward multi-pass re-checking for the 100px cue.
    CASCADING_COLLISIONS_DOWNWARD_SKIPS_MULTIPLE_RECTS(
        /* cueRect= */ rect(0, 100, 400, 200),
        /* placedRects= */ ImmutableList.of(rect(0, 300, 400, 400), rect(0, 150, 400, 250)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 500,
        /* preferUpward= */ false,
        /* expectedTop= */ 400),
    SUFFICIENT_GAP_SLOTS_INTO_GAP(
        /* cueRect= */ rect(0, 300, 400, 400),
        /* placedRects= */ ImmutableList.of(rect(0, 300, 400, 400), rect(0, 100, 400, 200)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 500,
        /* preferUpward= */ true,
        /* expectedTop= */ 200),
    // Uses a non-zero viewportTop so hardcoding 0 instead of viewportTop fails this case.
    PREFER_UPWARD_HITS_TOP_FALLS_BACK_DOWNWARD(
        /* cueRect= */ rect(0, 120, 400, 220),
        /* placedRects= */ ImmutableList.of(rect(0, 100, 400, 200)),
        /* viewportTop= */ 100,
        /* viewportBottom= */ 600,
        /* preferUpward= */ true,
        /* expectedTop= */ 200),
    PREFER_DOWNWARD_HITS_BOTTOM_FALLS_BACK_UPWARD(
        /* cueRect= */ rect(0, 400, 400, 500),
        /* placedRects= */ ImmutableList.of(rect(0, 400, 400, 500)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 500,
        /* preferUpward= */ false,
        /* expectedTop= */ 300),
    NEITHER_DIRECTION_FITS_RETURNS_ORIGINAL_TOP(
        /* cueRect= */ rect(0, 100, 400, 200),
        /* placedRects= */ ImmutableList.of(rect(0, 0, 400, 150), rect(0, 150, 400, 300)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 300,
        /* preferUpward= */ true,
        /* expectedTop= */ 100),
    EMPTY_PLACED_RECT_IGNORED(
        /* cueRect= */ rect(0, 100, 400, 200),
        /* placedRects= */ ImmutableList.of(rect(100, 150, 100, 180)),
        /* viewportTop= */ 0,
        /* viewportBottom= */ 300,
        /* preferUpward= */ true,
        /* expectedTop= */ 100);

    final ImmutableList<Integer> cueRect;
    final ImmutableList<ImmutableList<Integer>> placedRects;
    final int viewportTop;
    final int viewportBottom;
    final boolean preferUpward;
    final int expectedTop;

    ResolveVerticalOverlapCase(
        ImmutableList<Integer> cueRect,
        ImmutableList<ImmutableList<Integer>> placedRects,
        int viewportTop,
        int viewportBottom,
        boolean preferUpward,
        int expectedTop) {
      this.cueRect = cueRect;
      this.placedRects = placedRects;
      this.viewportTop = viewportTop;
      this.viewportBottom = viewportBottom;
      this.preferUpward = preferUpward;
      this.expectedTop = expectedTop;
    }
  }

  private static ImmutableList<Integer> rect(int left, int top, int right, int bottom) {
    return ImmutableList.of(left, top, right, bottom);
  }

  private static Rect toRect(ImmutableList<Integer> bounds) {
    return new Rect(bounds.get(0), bounds.get(1), bounds.get(2), bounds.get(3));
  }
}
