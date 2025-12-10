/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link CanvasOverlay}.
 *
 * <p>Verifies the lifecycle of the underlying {@link android.graphics.Bitmap}, ensuring it is
 * correctly recycled and re-instantiated to prevent use-after-recycle errors.
 */
@RunWith(AndroidJUnit4.class)
public final class CanvasOverlayTest {

  private TestCanvasOverlay canvasOverlay;

  @After
  public void tearDown() throws VideoFrameProcessingException {
    if (canvasOverlay != null) {
      canvasOverlay.release();
    }
  }

  @Test
  public void getBitmapAndRelease_thenGetBitmapAgain_succeeds() throws Exception {
    canvasOverlay = new TestCanvasOverlay(/* useInputFrameSize= */ true);
    Size videoSize = new Size(1920, 1080);
    canvasOverlay.configure(videoSize);
    Bitmap firstBitmap = canvasOverlay.getBitmap(/* presentationTimeUs= */ 0);
    assertThat(canvasOverlay.drawCount).isEqualTo(1);
    assertThat(firstBitmap.isRecycled()).isFalse();
    assertThat(firstBitmap.getWidth()).isEqualTo(videoSize.getWidth());
    assertThat(firstBitmap.getHeight()).isEqualTo(videoSize.getHeight());

    canvasOverlay.release();
    assertThat(firstBitmap.isRecycled()).isTrue();

    Bitmap secondBitmap = canvasOverlay.getBitmap(/* presentationTimeUs= */ 1_000_000);

    assertThat(canvasOverlay.drawCount).isEqualTo(2);
    assertThat(secondBitmap).isNotNull();
    assertThat(secondBitmap.isRecycled()).isFalse();
    assertThat(secondBitmap).isNotSameInstanceAs(firstBitmap);
    assertThat(secondBitmap.getWidth()).isEqualTo(videoSize.getWidth());
  }

  @Test
  public void getBitmap_withManuallySetSize_createsCorrectlySizedBitmap() {
    canvasOverlay = new TestCanvasOverlay(/* useInputFrameSize= */ false);
    int width = 100;
    int height = 200;

    canvasOverlay.setCanvasSize(width, height);
    Bitmap bitmap = canvasOverlay.getBitmap(/* presentationTimeUs= */ 0);

    assertThat(bitmap.getWidth()).isEqualTo(width);
    assertThat(bitmap.getHeight()).isEqualTo(height);
  }

  @Test
  public void getBitmap_withoutAnySizeSet_throwsException() {
    canvasOverlay = new TestCanvasOverlay(/* useInputFrameSize= */ false);

    assertThrows(
        IllegalArgumentException.class, () -> canvasOverlay.getBitmap(/* presentationTimeUs= */ 0));
  }

  private static class TestCanvasOverlay extends CanvasOverlay {
    int drawCount = 0;

    public TestCanvasOverlay(boolean useInputFrameSize) {
      super(useInputFrameSize);
    }

    @Override
    public void onDraw(Canvas canvas, long presentationTimeUs) {
      drawCount++;
    }
  }
}
