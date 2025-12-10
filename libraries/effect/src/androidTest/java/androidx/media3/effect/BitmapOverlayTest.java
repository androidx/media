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

import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link BitmapOverlay}.
 *
 * <p>This test focuses on the lifecycle of OpenGL resources within {@link BitmapOverlay}, ensuring
 * that textures are correctly released and re-created.
 */
@RunWith(AndroidJUnit4.class)
public final class BitmapOverlayTest {

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull BitmapOverlay bitmapOverlay;
  private @MonotonicNonNull Bitmap testBitmap;

  @Before
  public void setUp() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext = GlUtil.createEglContext(eglDisplay);
    GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
  }

  @After
  public void tearDown() throws VideoFrameProcessingException, GlUtil.GlException {
    if (bitmapOverlay != null) {
      bitmapOverlay.release();
    }
    if (eglDisplay != null && eglContext != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
    if (testBitmap != null) {
      testBitmap.recycle();
    }
  }

  @Test
  public void releaseAndGetTextureIdAgain_succeeds() throws Exception {
    bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(testBitmap);

    int firstTextureId = bitmapOverlay.getTextureId(/* presentationTimeUs= */ 0);
    assertThat(firstTextureId).isNotEqualTo(C.INDEX_UNSET);

    bitmapOverlay.release();

    int secondTextureId = bitmapOverlay.getTextureId(/* presentationTimeUs= */ 1_000_000);
    assertThat(secondTextureId).isNotEqualTo(C.INDEX_UNSET);
    assertThat(secondTextureId).isNotEqualTo(firstTextureId);
    assertThat(testBitmap.isRecycled()).isFalse();
  }
}
