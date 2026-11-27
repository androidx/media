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
package androidx.media3.ui;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Layout.Alignment;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.style.UnderlineSpan;
import android.view.View.MeasureSpec;
import androidx.media3.common.Flags;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.SubtitleStyle;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.GcFinalization;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link SubtitleView}, {@link CanvasSubtitleOutput}, and {@link SubtitlePainter}.
 */
@RunWith(AndroidJUnit4.class)
// Robolectric Canvas/Bitmap pixel rendering requires API 26+.
@Config(minSdk = 26)
public final class CanvasSubtitleOutputTest {

  private static final int VIEW_WIDTH = 400;
  private static final int VIEW_HEIGHT = 200;
  private static final SubtitleStyle WINDOW_ONLY_STYLE =
      new SubtitleStyle.Builder()
          .setForegroundColor(Color.TRANSPARENT)
          .setBackgroundColor(Color.TRANSPARENT)
          .build();

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  // Heap reachability is SDK-independent; run full-GC leak checks only on NEWEST_SDK.
  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void setCues_withNullAfterBitmapCue_releasesCachedBitmap() {
    SubtitleView subtitleView = createLaidOutSubtitleView();
    WeakReference<Bitmap> bitmapRef = drawNewBitmapCueOnSubtitleView(subtitleView);

    subtitleView.setCues(null);

    GcFinalization.awaitClear(bitmapRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void update_withEmptyCuesAfterBitmapCue_releasesCachedBitmap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<Bitmap> bitmapRef = drawNewBitmapCue(output);

    updateCues(output, ImmutableList.of());

    GcFinalization.awaitClear(bitmapRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void update_withEmptyCuesAfterSpannedTextCue_releasesCachedTextLayouts() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<UnderlineSpan> spanRef = drawNewSpannedTextCue(output);

    updateCues(output, ImmutableList.of());

    GcFinalization.awaitClear(spanRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void update_withFewerCues_releasesUnusedPainterLayouts() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Cue firstCue =
        createTopLeftBitmapCue(
            createSolidBitmap(/* width= */ 20, /* height= */ 20, /* color= */ Color.RED));
    WeakReference<UnderlineSpan> secondCueSpanRef = drawWithSecondSpannedTextCue(output, firstCue);

    updateCues(output, ImmutableList.of(firstCue));

    GcFinalization.awaitClear(secondCueSpanRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void update_withEmptyCuesAfterCustomTextDirectionHeuristic_releasesCachedHeuristic() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<TextDirectionHeuristic> heuristicRef =
        drawRtlTextCueWithCustomTextDirectionHeuristic(output);

    updateCues(output, ImmutableList.of());

    GcFinalization.awaitClear(heuristicRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void dispatchDraw_withEmptyTextCueAfterBitmapCue_releasesCachedBitmap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<Bitmap> bitmapRef = drawNewBitmapCue(output);
    updateCues(output, ImmutableList.of(new Cue.Builder().setText("").build()));

    output.dispatchDraw(new Canvas(createTargetBitmap()));

    GcFinalization.awaitClear(bitmapRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void dispatchDraw_withCollapsedBoundsAfterCueChange_releasesPreviousPainterBitmap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<Bitmap> firstBitmapRef = drawNewBitmapCue(output);
    Cue replacementCue = new Cue.Builder().setText("Replacement subtitle").build();
    updateCues(output, ImmutableList.of(replacementCue));
    output.layout(0, 0, 0, 0);

    output.dispatchDraw(new Canvas(createTargetBitmap()));

    GcFinalization.awaitClear(firstBitmapRef);
  }

  @Config(sdk = Config.NEWEST_SDK)
  @Test
  public void dispatchDraw_withZeroTextSizeAfterCueChange_releasesPreviousPainterBitmap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    WeakReference<Bitmap> firstBitmapRef = drawNewBitmapCue(output);
    Cue replacementCue = new Cue.Builder().setText("Replacement subtitle").build();
    output.update(
        ImmutableList.of(replacementCue),
        SubtitleStyle.DEFAULT,
        /* textSize= */ 0f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);

    output.dispatchDraw(new Canvas(createTargetBitmap()));

    GcFinalization.awaitClear(firstBitmapRef);
  }

  @Test
  public void dispatchDraw_withSameBitmapCueAfterEmptyCues_rendersCueAgain() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap secondTarget = createTargetBitmap();
    Cue bitmapCue =
        createTopLeftBitmapCue(
            createSolidBitmap(/* width= */ 20, /* height= */ 20, /* color= */ Color.RED));
    updateCues(output, ImmutableList.of(bitmapCue));
    output.dispatchDraw(new Canvas(firstTarget));
    updateCues(output, ImmutableList.of());
    updateCues(output, ImmutableList.of(bitmapCue));

    output.dispatchDraw(new Canvas(secondTarget));

    assertThat(firstTarget.getPixel(10, 10)).isEqualTo(Color.RED);
    assertThat(secondTarget.getPixel(10, 10)).isEqualTo(Color.RED);
  }

  @Test
  public void dispatchDraw_withSameTextCueAfterEmptyCues_rendersCueAgain() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap secondTarget = createTargetBitmap();
    Cue textCue = createTopLeftTextCue("Subtitle", /* windowColor= */ Color.BLUE);
    updateCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(firstTarget));
    updateCues(output, ImmutableList.of());
    updateCues(output, ImmutableList.of(textCue));

    output.dispatchDraw(new Canvas(secondTarget));

    assertThat(firstTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
    assertThat(secondTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
  }

  @Test
  public void dispatchDraw_withSameTextCueAfterCollapsedBounds_rendersCueWhenExpanded() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap expandedTarget = createTargetBitmap();
    Cue textCue = createTopLeftTextCue("Subtitle", /* windowColor= */ Color.BLUE);
    updateCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(firstTarget));
    output.layout(0, 0, 0, 0);
    output.dispatchDraw(new Canvas(createTargetBitmap()));
    output.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);

    output.dispatchDraw(new Canvas(expandedTarget));

    assertThat(firstTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
    assertThat(expandedTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
  }

  @Test
  public void dispatchDraw_withZeroSizeSameTextCueAfterTextCue_clearsPreviousTextLayout() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap zeroSizeTarget = createTargetBitmap();
    Cue textCue = createTopLeftTextCue("Subtitle", /* windowColor= */ Color.BLUE);
    Cue zeroSizeCue = textCue.buildUpon().setSize(0f).build();
    updateCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(firstTarget));
    updateCues(output, ImmutableList.of(zeroSizeCue));

    output.dispatchDraw(new Canvas(zeroSizeTarget));

    assertThat(firstTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
    assertThat(zeroSizeTarget.getPixel(10, 8)).isEqualTo(Color.TRANSPARENT);
  }

  @Test
  public void dispatchDraw_withZeroHorizontalWidthCueAfterTextCue_clearsPreviousTextLayout() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap zeroWidthTarget = createTargetBitmap();
    Cue textCue = createTopLeftTextCue("Subtitle", /* windowColor= */ Color.BLUE);
    Cue offRightEdgeCue =
        textCue.buildUpon().setPosition(1f).setPositionAnchor(Cue.ANCHOR_TYPE_START).build();
    updateCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(firstTarget));
    updateCues(output, ImmutableList.of(offRightEdgeCue));

    output.dispatchDraw(new Canvas(zeroWidthTarget));

    assertThat(firstTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
    assertThat(zeroWidthTarget.getPixel(10, 8)).isEqualTo(Color.TRANSPARENT);
  }

  @Test
  public void dispatchDraw_withRepeatedUnchangedVerticalCueUpdate_reusesCachedTextLayout() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap secondTarget = createTargetBitmap();
    AtomicInteger heuristicCallCount = new AtomicInteger();
    TextDirectionHeuristic countingHeuristic = createCountingHeuristic(heuristicCallCount);
    Cue verticalRtlCue =
        new Cue.Builder()
            .setText("שלום")
            .setTextAlignment(Alignment.ALIGN_NORMAL)
            .setVerticalType(Cue.VERTICAL_TYPE_RL)
            .setWindowColor(Color.BLUE)
            .setLine(1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(1f)
            .build();
    updateCuesWithHeuristic(output, ImmutableList.of(verticalRtlCue), countingHeuristic);
    output.dispatchDraw(new Canvas(firstTarget));
    int callCountAfterFirstDraw = heuristicCallCount.get();
    updateCuesWithHeuristic(output, ImmutableList.of(verticalRtlCue), countingHeuristic);

    output.dispatchDraw(new Canvas(secondTarget));

    assertThat(callCountAfterFirstDraw).isGreaterThan(0);
    assertThat(heuristicCallCount.get()).isEqualTo(callCountAfterFirstDraw);
    assertThat(firstTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
    assertThat(secondTarget.getPixel(10, 8)).isEqualTo(Color.BLUE);
  }

  @Test
  public void dispatchDraw_multiLineNegativeLines_stacksWithoutOverlap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap target = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder()
            .setText("Line 1\nLine 2")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second cue")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(target));

    Rect firstBounds = findColorBounds(target, Color.RED);
    Rect secondBounds = findColorBounds(target, Color.BLUE);
    assertThat(firstBounds.isEmpty()).isFalse();
    assertThat(secondBounds.isEmpty()).isFalse();
    assertThat(firstBounds.bottom).isEqualTo(VIEW_HEIGHT);
    assertThat(secondBounds.bottom).isEqualTo(firstBounds.top);
  }

  @Test
  public void dispatchDraw_multiLinePositiveLines_stacksWithoutOverlap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap target = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder()
            .setText("Line 1\nLine 2")
            .setLine(0f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second cue")
            .setLine(1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(target));

    Rect firstBounds = findColorBounds(target, Color.RED);
    Rect secondBounds = findColorBounds(target, Color.BLUE);
    assertThat(firstBounds.isEmpty()).isFalse();
    assertThat(secondBounds.isEmpty()).isFalse();
    assertThat(firstBounds.top).isEqualTo(0);
    assertThat(secondBounds.top).isEqualTo(firstBounds.bottom);
  }

  @Test
  public void dispatchDraw_unsetLineCuesWithBottomPadding_stacksWithoutOverlap() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder().setText("Line 1\nLine 2\nLine 3").setWindowColor(Color.RED).build();
    Cue secondCue = new Cue.Builder().setText("Second cue").setWindowColor(Color.BLUE).build();
    output.update(
        ImmutableList.of(firstCue),
        WINDOW_ONLY_STYLE,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.2f,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);
    output.dispatchDraw(new Canvas(soloTarget));
    output.update(
        ImmutableList.of(firstCue, secondCue),
        WINDOW_ONLY_STYLE,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.2f,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloFirstBounds = findColorBounds(soloTarget, Color.RED);
    Rect firstBounds = findColorBounds(combinedTarget, Color.RED);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloFirstBounds.isEmpty()).isFalse();
    assertThat(firstBounds).isEqualTo(soloFirstBounds);
    assertThat(secondBounds.isEmpty()).isFalse();
    assertThat(secondBounds.bottom).isEqualTo(firstBounds.top);
  }

  @Test
  public void dispatchDraw_sameLineSideBySideCues_staysOnSameLine() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap target = createTargetBitmap();
    Cue leftCue =
        new Cue.Builder()
            .setText("Left")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.25f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.4f)
            .setWindowColor(Color.RED)
            .build();
    Cue rightCue =
        new Cue.Builder()
            .setText("Right")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.75f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.4f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(leftCue, rightCue));

    output.dispatchDraw(new Canvas(target));

    Rect leftBounds = findColorBounds(target, Color.RED);
    Rect rightBounds = findColorBounds(target, Color.BLUE);
    assertThat(leftBounds.isEmpty()).isFalse();
    assertThat(rightBounds.isEmpty()).isFalse();
    assertThat(rightBounds.top).isEqualTo(leftBounds.top);
    assertThat(rightBounds.bottom).isEqualTo(leftBounds.bottom);
    assertThat(leftBounds.right).isAtMost(rightBounds.left);
  }

  @Test
  public void dispatchDraw_reverseOrderedCollidingNegativeLines_shiftsLaterCueAbove() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloFirstTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder()
            .setText("First cue at line -3")
            .setLine(-3f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second cue top\nSecond cue bottom")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(firstCue));
    output.dispatchDraw(new Canvas(soloFirstTarget));
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloFirstBounds = findColorBounds(soloFirstTarget, Color.RED);
    Rect firstBounds = findColorBounds(combinedTarget, Color.RED);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloFirstBounds.isEmpty()).isFalse();
    assertThat(firstBounds).isEqualTo(soloFirstBounds);
    assertThat(secondBounds.isEmpty()).isFalse();
    assertThat(secondBounds.bottom).isEqualTo(firstBounds.top);
  }

  @Test
  public void dispatchDraw_lineNumberOverlappingFractionalCue_shiftsAboveFixedCue() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloFixedTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue fixedCue =
        new Cue.Builder()
            .setText("Fixed")
            .setLine(0.98f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue autoCue =
        new Cue.Builder()
            .setText("Auto")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(fixedCue));
    output.dispatchDraw(new Canvas(soloFixedTarget));
    updateWindowOnlyCues(output, ImmutableList.of(fixedCue, autoCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloFixedBounds = findColorBounds(soloFixedTarget, Color.RED);
    Rect fixedBounds = findColorBounds(combinedTarget, Color.RED);
    Rect autoBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloFixedBounds.isEmpty()).isFalse();
    assertThat(fixedBounds).isEqualTo(soloFixedBounds);
    assertThat(autoBounds.isEmpty()).isFalse();
    assertThat(autoBounds.bottom).isEqualTo(fixedBounds.top);
  }

  @Test
  public void dispatchDraw_unsetLineBeforeFractionalCue_shiftsAboveFixedCue() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloFixedTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue movableCue = new Cue.Builder().setText("Movable").setWindowColor(Color.BLUE).build();
    Cue fixedCue =
        new Cue.Builder()
            .setText("Fixed")
            .setLine(0.92f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(fixedCue));
    output.dispatchDraw(new Canvas(soloFixedTarget));
    updateWindowOnlyCues(output, ImmutableList.of(movableCue, fixedCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloFixedBounds = findColorBounds(soloFixedTarget, Color.RED);
    Rect fixedBounds = findColorBounds(combinedTarget, Color.RED);
    Rect movableBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloFixedBounds.isEmpty()).isFalse();
    assertThat(fixedBounds).isEqualTo(soloFixedBounds);
    assertThat(movableBounds.isEmpty()).isFalse();
    assertThat(movableBounds.bottom).isEqualTo(fixedBounds.top);
  }

  @Test
  public void dispatchDraw_overlappingFractionalCues_keepsFixedPositions() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloFirstTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder()
            .setText("First")
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second")
            .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(firstCue));
    output.dispatchDraw(new Canvas(soloFirstTarget));
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloFirstBounds = findColorBounds(soloFirstTarget, Color.RED);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloFirstBounds.isEmpty()).isFalse();
    assertThat(secondBounds).isEqualTo(soloFirstBounds);
  }

  @Test
  public void dispatchDraw_unsetLineTextOverlappingBitmap_neitherCueMoves() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloTextTarget = createTargetBitmap();
    Bitmap soloBitmapTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue textCue = new Cue.Builder().setText("Bottom text").setWindowColor(Color.BLUE).build();
    Cue bitmapCue =
        new Cue.Builder()
            .setBitmap(createSolidBitmap(/* width= */ 100, /* height= */ 60, Color.RED))
            .setLine(0.98f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(0.5f)
            .setBitmapHeight(0.3f)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(soloTextTarget));
    updateWindowOnlyCues(output, ImmutableList.of(bitmapCue));
    output.dispatchDraw(new Canvas(soloBitmapTarget));
    // Draw bitmap first so the overlapping text paints on top and both bounding boxes stay visible.
    updateWindowOnlyCues(output, ImmutableList.of(bitmapCue, textCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloTextBounds = findColorBounds(soloTextTarget, Color.BLUE);
    Rect soloBitmapBounds = findColorBounds(soloBitmapTarget, Color.RED);
    Rect combinedTextBounds = findColorBounds(combinedTarget, Color.BLUE);
    Rect combinedBitmapBounds = findColorBounds(combinedTarget, Color.RED);
    assertThat(soloTextBounds.isEmpty()).isFalse();
    assertThat(soloBitmapBounds.isEmpty()).isFalse();
    assertThat(Rect.intersects(soloTextBounds, soloBitmapBounds)).isTrue();
    assertThat(combinedTextBounds).isEqualTo(soloTextBounds);
    assertThat(combinedBitmapBounds).isEqualTo(soloBitmapBounds);
  }

  @Test
  public void dispatchDraw_shiftedCueFromEarlierCueChange_reusesCachedTextLayout() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap firstTarget = createTargetBitmap();
    Bitmap secondTarget = createTargetBitmap();
    AtomicInteger heuristicCallCount = new AtomicInteger();
    TextDirectionHeuristic countingHeuristic = createCountingHeuristic(heuristicCallCount);
    Cue shortFirstCue =
        new Cue.Builder()
            .setText("One line")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue tallFirstCue = shortFirstCue.buildUpon().setText("Line 1\nLine 2\nLine 3").build();
    Cue secondRtlCue =
        new Cue.Builder()
            .setText("שלום")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    output.update(
        ImmutableList.of(shortFirstCue, secondRtlCue),
        WINDOW_ONLY_STYLE,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        countingHeuristic);
    output.dispatchDraw(new Canvas(firstTarget));
    int callCountAfterFirstDraw = heuristicCallCount.get();
    output.update(
        ImmutableList.of(tallFirstCue, secondRtlCue),
        WINDOW_ONLY_STYLE,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        countingHeuristic);

    output.dispatchDraw(new Canvas(secondTarget));

    Rect firstDrawSecondBounds = findColorBounds(firstTarget, Color.BLUE);
    Rect secondDrawSecondBounds = findColorBounds(secondTarget, Color.BLUE);
    Rect secondDrawFirstBounds = findColorBounds(secondTarget, Color.RED);
    assertThat(callCountAfterFirstDraw).isGreaterThan(0);
    assertThat(heuristicCallCount.get()).isEqualTo(callCountAfterFirstDraw);
    assertThat(secondDrawSecondBounds.top).isLessThan(firstDrawSecondBounds.top);
    assertThat(secondDrawSecondBounds.bottom).isEqualTo(secondDrawFirstBounds.top);
  }

  @Test
  public void dispatchDraw_cuesExceedingViewportHeight_keepsOriginalPosition() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloSecondTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    // Each 12-line cue exceeds half of VIEW_HEIGHT (200px), so neither shift direction fits.
    String tallText = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12";
    Cue tallCue1 =
        new Cue.Builder()
            .setText(tallText)
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue tallCue2 =
        new Cue.Builder()
            .setText(tallText)
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(tallCue2));
    output.dispatchDraw(new Canvas(soloSecondTarget));
    updateWindowOnlyCues(output, ImmutableList.of(tallCue1, tallCue2));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloSecondBounds = findColorBounds(soloSecondTarget, Color.BLUE);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloSecondBounds.isEmpty()).isFalse();
    assertThat(soloSecondBounds.height() * 2).isGreaterThan(VIEW_HEIGHT);
    assertThat(secondBounds).isEqualTo(soloSecondBounds);
  }

  @Test
  public void dispatchDraw_withViewPadding_fallsBackWithinPaddedBounds() {
    CanvasSubtitleOutput output = createLaidOutOutput();
    int paddingTop = 60;
    int paddingBottom = 40;
    output.setPadding(/* left= */ 0, paddingTop, /* right= */ 0, paddingBottom);
    Bitmap topFallbackTarget = createTargetBitmap();
    Bitmap bottomFallbackTarget = createTargetBitmap();
    Cue fixedTopCue =
        new Cue.Builder()
            .setText("Fixed top")
            .setLine(0f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue negativeCueNearTop =
        new Cue.Builder()
            .setText("Negative cue")
            .setLine(-4f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    Cue fixedBottomCue =
        new Cue.Builder()
            .setText("Fixed bottom")
            .setLine(1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue positiveCueNearBottom =
        new Cue.Builder()
            .setText("Positive cue")
            .setLine(3f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    output.update(
        ImmutableList.of(fixedTopCue, negativeCueNearTop),
        WINDOW_ONLY_STYLE,
        /* textSize= */ 20f,
        Cue.TEXT_SIZE_TYPE_ABSOLUTE,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);
    output.dispatchDraw(new Canvas(topFallbackTarget));
    output.update(
        ImmutableList.of(fixedBottomCue, positiveCueNearBottom),
        WINDOW_ONLY_STYLE,
        /* textSize= */ 20f,
        Cue.TEXT_SIZE_TYPE_ABSOLUTE,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);

    output.dispatchDraw(new Canvas(bottomFallbackTarget));

    Rect topFixedBounds = findColorBounds(topFallbackTarget, Color.RED);
    Rect topMovableBounds = findColorBounds(topFallbackTarget, Color.BLUE);
    Rect bottomFixedBounds = findColorBounds(bottomFallbackTarget, Color.RED);
    Rect bottomMovableBounds = findColorBounds(bottomFallbackTarget, Color.BLUE);
    assertThat(topFixedBounds.top).isEqualTo(paddingTop);
    assertThat(topMovableBounds.top).isEqualTo(topFixedBounds.bottom);
    assertThat(bottomFixedBounds.bottom).isEqualTo(VIEW_HEIGHT - paddingBottom);
    assertThat(bottomMovableBounds.bottom).isEqualTo(bottomFixedBounds.top);
  }

  @Test
  public void dispatchDraw_overlapAvoidanceFlagDisabled_doesNotShiftOverlappingCues() {
    Flags.disableFlag(Flags.FLAG_AVOID_OVERLAPPING_SUBTITLE_CUES);
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloSecondTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue firstCue =
        new Cue.Builder()
            .setText("Line 1\nLine 2")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.RED)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second cue")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setSize(1f)
            .setWindowColor(Color.BLUE)
            .build();
    updateWindowOnlyCues(output, ImmutableList.of(secondCue));
    output.dispatchDraw(new Canvas(soloSecondTarget));
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloSecondBounds = findColorBounds(soloSecondTarget, Color.BLUE);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloSecondBounds.isEmpty()).isFalse();
    assertThat(secondBounds).isEqualTo(soloSecondBounds);
  }

  @Test
  public void dispatchDraw_canaryModeDisabled_doesNotShiftOverlappingCues() {
    Flags.setCanaryModeEnabled(false);
    CanvasSubtitleOutput output = createLaidOutOutput();
    Bitmap soloSecondTarget = createTargetBitmap();
    Bitmap combinedTarget = createTargetBitmap();
    Cue firstCue = new Cue.Builder().setText("First").setWindowColor(Color.RED).build();
    Cue secondCue = new Cue.Builder().setText("Second").setWindowColor(Color.BLUE).build();
    updateWindowOnlyCues(output, ImmutableList.of(secondCue));
    output.dispatchDraw(new Canvas(soloSecondTarget));
    updateWindowOnlyCues(output, ImmutableList.of(firstCue, secondCue));

    output.dispatchDraw(new Canvas(combinedTarget));

    Rect soloSecondBounds = findColorBounds(soloSecondTarget, Color.BLUE);
    Rect secondBounds = findColorBounds(combinedTarget, Color.BLUE);
    assertThat(soloSecondBounds.isEmpty()).isFalse();
    assertThat(secondBounds).isEqualTo(soloSecondBounds);
  }

  private static SubtitleView createLaidOutSubtitleView() {
    SubtitleView subtitleView = new SubtitleView(ApplicationProvider.getApplicationContext());
    subtitleView.measure(
        MeasureSpec.makeMeasureSpec(VIEW_WIDTH, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, MeasureSpec.EXACTLY));
    subtitleView.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    return subtitleView;
  }

  private static CanvasSubtitleOutput createLaidOutOutput() {
    CanvasSubtitleOutput output =
        new CanvasSubtitleOutput(ApplicationProvider.getApplicationContext());
    output.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    return output;
  }

  private static void updateCues(CanvasSubtitleOutput output, List<Cue> cues) {
    updateCuesWithHeuristic(output, cues, TextDirectionHeuristics.FIRSTSTRONG_LTR);
  }

  private static void updateWindowOnlyCues(CanvasSubtitleOutput output, List<Cue> cues) {
    output.update(
        cues,
        WINDOW_ONLY_STYLE,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        TextDirectionHeuristics.FIRSTSTRONG_LTR);
  }

  private static void updateCuesWithHeuristic(
      CanvasSubtitleOutput output, List<Cue> cues, TextDirectionHeuristic heuristic) {
    output.update(
        cues,
        SubtitleStyle.DEFAULT,
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION,
        heuristic);
  }

  // Helper methods below create and draw victim objects in their own stack frame so local slots do
  // not retain strong roots when GcFinalization.awaitClear runs in the caller.

  private static WeakReference<Bitmap> drawNewBitmapCueOnSubtitleView(SubtitleView subtitleView) {
    Bitmap cueBitmap = createSolidBitmap(/* width= */ 20, /* height= */ 20, /* color= */ Color.RED);
    WeakReference<Bitmap> bitmapRef = new WeakReference<>(cueBitmap);
    subtitleView.setCues(ImmutableList.of(createTopLeftBitmapCue(cueBitmap)));
    subtitleView.draw(new Canvas(createTargetBitmap()));
    cueBitmap = null;
    return bitmapRef;
  }

  private static WeakReference<Bitmap> drawNewBitmapCue(CanvasSubtitleOutput output) {
    Bitmap cueBitmap = createSolidBitmap(/* width= */ 20, /* height= */ 20, /* color= */ Color.RED);
    WeakReference<Bitmap> bitmapRef = new WeakReference<>(cueBitmap);
    updateCues(output, ImmutableList.of(createTopLeftBitmapCue(cueBitmap)));
    output.dispatchDraw(new Canvas(createTargetBitmap()));
    cueBitmap = null;
    return bitmapRef;
  }

  private static WeakReference<UnderlineSpan> drawNewSpannedTextCue(CanvasSubtitleOutput output) {
    UnderlineSpan span = new UnderlineSpan();
    WeakReference<UnderlineSpan> spanRef = new WeakReference<>(span);
    Cue textCue =
        createSpannedTextCue(
            "Subtitle", span, /* lineFraction= */ 0f, /* windowColor= */ Color.BLUE);
    updateCues(output, ImmutableList.of(textCue));
    output.dispatchDraw(new Canvas(createTargetBitmap()));
    span = null;
    return spanRef;
  }

  private static WeakReference<UnderlineSpan> drawWithSecondSpannedTextCue(
      CanvasSubtitleOutput output, Cue firstCue) {
    UnderlineSpan span = new UnderlineSpan();
    WeakReference<UnderlineSpan> spanRef = new WeakReference<>(span);
    Cue secondCue =
        createSpannedTextCue(
            "Second subtitle", span, /* lineFraction= */ 0.5f, /* windowColor= */ Color.BLUE);
    updateCues(output, ImmutableList.of(firstCue, secondCue));
    output.dispatchDraw(new Canvas(createTargetBitmap()));
    span = null;
    return spanRef;
  }

  private static WeakReference<TextDirectionHeuristic>
      drawRtlTextCueWithCustomTextDirectionHeuristic(CanvasSubtitleOutput output) {
    TextDirectionHeuristic customHeuristic =
        new TextDirectionHeuristic() {
          @Override
          public boolean isRtl(char[] array, int start, int count) {
            return true;
          }

          @Override
          public boolean isRtl(CharSequence cs, int start, int count) {
            return true;
          }
        };
    WeakReference<TextDirectionHeuristic> heuristicRef = new WeakReference<>(customHeuristic);
    Cue rtlCue = createTopLeftTextCue("שלום", /* windowColor= */ Color.BLUE);
    updateCuesWithHeuristic(output, ImmutableList.of(rtlCue), customHeuristic);
    output.dispatchDraw(new Canvas(createTargetBitmap()));
    customHeuristic = null;
    return heuristicRef;
  }

  private static TextDirectionHeuristic createCountingHeuristic(AtomicInteger callCount) {
    return new TextDirectionHeuristic() {
      @Override
      public boolean isRtl(char[] array, int start, int count) {
        callCount.incrementAndGet();
        return TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(array, start, count);
      }

      @Override
      public boolean isRtl(CharSequence cs, int start, int count) {
        callCount.incrementAndGet();
        return TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(cs, start, count);
      }
    };
  }

  private static Cue createTopLeftTextCue(CharSequence text, int windowColor) {
    return new Cue.Builder()
        .setText(text)
        .setWindowColor(windowColor)
        .setPosition(0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(1f)
        .build();
  }

  private static Cue createSpannedTextCue(
      String text, Object span, float lineFraction, int windowColor) {
    SpannableString spannableText = new SpannableString(text);
    spannableText.setSpan(span, 0, spannableText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return new Cue.Builder()
        .setText(spannableText)
        .setWindowColor(windowColor)
        .setPosition(0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(lineFraction, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(1f)
        .build();
  }

  private static Cue createTopLeftBitmapCue(Bitmap bitmap) {
    return new Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(0.5f)
        .setBitmapHeight(0.5f)
        .build();
  }

  private static Bitmap createTargetBitmap() {
    return Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888);
  }

  private static Bitmap createSolidBitmap(int width, int height, int color) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    return bitmap;
  }

  private static Rect findColorBounds(Bitmap bitmap, int color) {
    int left = Integer.MAX_VALUE;
    int top = Integer.MAX_VALUE;
    int right = Integer.MIN_VALUE;
    int bottom = Integer.MIN_VALUE;
    for (int y = 0; y < bitmap.getHeight(); y++) {
      for (int x = 0; x < bitmap.getWidth(); x++) {
        if (bitmap.getPixel(x, y) == color) {
          left = Math.min(left, x);
          top = Math.min(top, y);
          right = Math.max(right, x + 1);
          bottom = Math.max(bottom, y + 1);
        }
      }
    }
    return left < right ? new Rect(left, top, right, bottom) : new Rect();
  }
}
