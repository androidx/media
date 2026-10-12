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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CueUtil}. */
@RunWith(AndroidJUnit4.class)
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
}
