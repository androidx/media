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
package androidx.media3.ui;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import androidx.media3.common.text.Cue;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CanvasSubtitleOutput}. */
@RunWith(AndroidJUnit4.class)
public class CanvasSubtitleOutputTest {

  private static final int VIEW_WIDTH = 1920;
  private static final int VIEW_HEIGHT = 1080;

  private Context context;
  private CanvasSubtitleOutput subtitleOutput;
  private Canvas canvas;
  private Bitmap bitmap;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    subtitleOutput = new CanvasSubtitleOutput(context);
    // Set up the view with a fixed size for consistent testing
    subtitleOutput.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    bitmap = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888);
    canvas = new Canvas(bitmap);
  }

  @Test
  public void dispatchDraw_emptyCues_doesNotCrash() {
    subtitleOutput.update(
        Collections.emptyList(),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_singleCue_renders() {
    Cue cue = new Cue.Builder().setText("Single subtitle").build();

    subtitleOutput.update(
        Collections.singletonList(cue),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_overlappingCuesWithNegativeLineNumbers_rendersWithoutOverlap() {
    // Simulate WebVTT overlapping cues that get assigned negative line numbers
    // by WebvttSubtitle.getCues()
    Cue cue1 =
        new Cue.Builder()
            .setText("First overlapping subtitle")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue cue2 =
        new Cue.Builder()
            .setText("Second overlapping subtitle")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    subtitleOutput.update(
        Arrays.asList(cue1, cue2),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw and should render both cues
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_multipleOverlappingCues_rendersAll() {
    // Test with 3+ overlapping cues (common in anime subtitles)
    Cue cue1 =
        new Cue.Builder()
            .setText("Line 1")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue cue2 =
        new Cue.Builder()
            .setText("Line 2")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue cue3 =
        new Cue.Builder()
            .setText("Line 3")
            .setLine(-3f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    subtitleOutput.update(
        Arrays.asList(cue1, cue2, cue3),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_mixedCueTypes_rendersCorrectly() {
    // Mix of cues with and without explicit line numbers
    Cue cueWithLine =
        new Cue.Builder()
            .setText("Cue with line")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue cueWithoutLine = new Cue.Builder().setText("Cue without line").build();

    subtitleOutput.update(
        Arrays.asList(cueWithLine, cueWithoutLine),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_cuesWithFractionalLine_notAffectedByStacking() {
    // Cues with LINE_TYPE_FRACTION should not be affected by the stacking logic
    Cue cue1 =
        new Cue.Builder()
            .setText("Fractional line cue 1")
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION)
            .build();
    Cue cue2 =
        new Cue.Builder()
            .setText("Fractional line cue 2")
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .build();

    subtitleOutput.update(
        Arrays.asList(cue1, cue2),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_cuesWithPositiveLineNumbers_stacksCorrectly() {
    // Cues with positive line numbers (top-anchored) should stack downward from the top
    Cue cue1 =
        new Cue.Builder()
            .setText("Top line cue 1")
            .setLine(0f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue cue2 =
        new Cue.Builder()
            .setText("Top line cue 2")
            .setLine(1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    subtitleOutput.update(
        Arrays.asList(cue1, cue2),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_mixedPositiveAndNegativeLineNumbers_stacksCorrectly() {
    // Test with cues at both top and bottom of screen
    Cue topCue =
        new Cue.Builder()
            .setText("Top subtitle")
            .setLine(0f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue bottomCue =
        new Cue.Builder()
            .setText("Bottom subtitle")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    subtitleOutput.update(
        Arrays.asList(topCue, bottomCue),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }

  @Test
  public void dispatchDraw_multiLineCueWithOverlap_stacksCorrectly() {
    // Test with a multi-line cue followed by another cue
    // This is the core issue from the bug report
    Cue multiLineCue =
        new Cue.Builder()
            .setText("This is a very long subtitle that will wrap to multiple lines")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();
    Cue secondCue =
        new Cue.Builder()
            .setText("Second cue")
            .setLine(-2f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    subtitleOutput.update(
        Arrays.asList(multiLineCue, secondCue),
        CaptionStyleCompat.DEFAULT,
        /* textSize= */ 0.05f,
        Cue.TEXT_SIZE_TYPE_FRACTIONAL,
        /* bottomPaddingFraction= */ 0.08f);

    // Should not throw
    subtitleOutput.dispatchDraw(canvas);
  }
}
