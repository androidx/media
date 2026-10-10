/*
 * Copyright 2025 The Android Open Source Project
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
import androidx.media3.common.text.Cue;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SubtitlePainter}. */
@RunWith(AndroidJUnit4.class)
public class SubtitlePainterTest {

  private static final int CANVAS_WIDTH = 1920;
  private static final int CANVAS_HEIGHT = 1080;
  private static final float DEFAULT_TEXT_SIZE_PX = 50f;

  private Context context;
  private SubtitlePainter painter;
  private Canvas canvas;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    painter = new SubtitlePainter(context);
    Bitmap bitmap = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
    canvas = new Canvas(bitmap);
  }

  @Test
  public void getLastDrawnCueHeight_beforeDraw_returnsZero() {
    assertThat(painter.getLastDrawnCueHeight()).isEqualTo(0);
  }

  @Test
  public void getLastDrawnCueHeight_afterDrawingTextCue_returnsPositiveValue() {
    Cue cue = new Cue.Builder().setText("Test subtitle").build();

    painter.draw(
        cue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);

    assertThat(painter.getLastDrawnCueHeight()).isGreaterThan(0);
  }

  @Test
  public void getLastDrawnCueHeight_multiLineText_returnsLargerValue() {
    Cue singleLineCue = new Cue.Builder().setText("Single line").build();
    painter.draw(
        singleLineCue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);
    int singleLineHeight = painter.getLastDrawnCueHeight();

    Cue multiLineCue = new Cue.Builder().setText("Line 1\nLine 2\nLine 3").build();
    painter.draw(
        multiLineCue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);
    int multiLineHeight = painter.getLastDrawnCueHeight();

    assertThat(multiLineHeight).isGreaterThan(singleLineHeight);
  }

  @Test
  public void getLastDrawnCueHeight_emptyText_returnsZero() {
    Cue cue = new Cue.Builder().setText("").build();

    painter.draw(
        cue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);

    assertThat(painter.getLastDrawnCueHeight()).isEqualTo(0);
  }

  @Test
  public void getLastDrawnCueHeight_bitmapCue_returnsPositiveValue() {
    Bitmap cueBitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
    Cue cue =
        new Cue.Builder()
            .setBitmap(cueBitmap)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(0.5f)
            .build();

    painter.draw(
        cue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);

    assertThat(painter.getLastDrawnCueHeight()).isGreaterThan(0);
  }

  @Test
  public void getLastDrawnCueHeight_cueWithNegativeLine_returnsPositiveValue() {
    Cue cue =
        new Cue.Builder()
            .setText("Bottom-stacked subtitle")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    painter.draw(
        cue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);

    assertThat(painter.getLastDrawnCueHeight()).isGreaterThan(0);
  }

  @Test
  public void getLastDrawnCueHeight_cueWithPositiveLine_returnsPositiveValue() {
    Cue cue =
        new Cue.Builder()
            .setText("Top-stacked subtitle")
            .setLine(0f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build();

    painter.draw(
        cue,
        CaptionStyleCompat.DEFAULT,
        DEFAULT_TEXT_SIZE_PX,
        /* cueTextSizePx= */ 0,
        /* bottomPaddingFraction= */ 0.08f,
        canvas,
        /* cueBoxLeft= */ 0,
        /* cueBoxTop= */ 0,
        /* cueBoxRight= */ CANVAS_WIDTH,
        /* cueBoxBottom= */ CANVAS_HEIGHT);

    assertThat(painter.getLastDrawnCueHeight()).isGreaterThan(0);
  }
}
