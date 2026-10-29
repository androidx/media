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
package androidx.media3.effect;

import static com.google.common.base.Preconditions.checkNotNull;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit rule that creates an {@link EGLContext} and a focused placeholder {@link EGLSurface}
 * before each test, and destroys them after each test.
 *
 * <p>The rule creates an OpenGL ES 3.0 context, falling back to OpenGL ES 2.0 if that fails. It
 * runs around the {@code @Before} and {@code @After} methods of the test class, so they can use the
 * context.
 *
 * <p>Textures and framebuffers created through this rule are deleted after each test, before the
 * context is destroyed.
 *
 * <p>All methods must be called on the test thread.
 */
/* package */ final class GlTestRule implements TestRule {

  private final List<Integer> texIds;
  private final List<Integer> fboIds;

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;

  /** Creates an instance. */
  public GlTestRule() {
    texIds = new ArrayList<>();
    fboIds = new ArrayList<>();
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        createEglObjects();
        List<Throwable> errors = new ArrayList<>();
        try {
          base.evaluate();
        } catch (Throwable t) {
          errors.add(t);
        }
        release(errors);
        if (!errors.isEmpty()) {
          Throwable firstError = errors.get(0);
          for (int i = 1; i < errors.size(); i++) {
            firstError.addSuppressed(errors.get(i));
          }
          throw firstError;
        }
      }
    };
  }

  /**
   * Returns a texture containing the pixels of {@code bitmap}, which is deleted after the test.
   *
   * @see BitmapPixelTestUtil#createGlTextureFromBitmap(Bitmap)
   */
  public int createGlTextureFromBitmap(Bitmap bitmap) throws GlUtil.GlException {
    int texId = BitmapPixelTestUtil.createGlTextureFromBitmap(bitmap);
    texIds.add(texId);
    return texId;
  }

  /**
   * Creates a texture with the given size, makes it the render target with a matching viewport, and
   * clears it.
   *
   * <p>The texture and its framebuffer are deleted after the test.
   */
  public void createAndFocusTexture(int width, int height, boolean useHighPrecisionColorComponents)
      throws GlUtil.GlException {
    int texId = GlUtil.createTexture(width, height, useHighPrecisionColorComponents);
    texIds.add(texId);
    createAndFocusFramebuffer(texId, width, height);
    GlUtil.clearFocusedBuffers();
  }

  /**
   * Creates a framebuffer for the texture {@code texId}, and makes it the render target with a
   * viewport of {@code width} by {@code height} pixels.
   *
   * <p>The framebuffer is deleted after the test.
   */
  public void createAndFocusFramebuffer(int texId, int width, int height)
      throws GlUtil.GlException {
    int fboId = GlUtil.createFboForTexture(texId);
    fboIds.add(fboId);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        fboId,
        width,
        height);
  }

  private void createEglObjects() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    try {
      createFocusedEglContextAndPlaceholderSurface(/* openGlVersion= */ 3);
    } catch (GlUtil.GlException openGl3Exception) {
      try {
        createFocusedEglContextAndPlaceholderSurface(/* openGlVersion= */ 2);
      } catch (GlUtil.GlException openGl2Exception) {
        openGl2Exception.addSuppressed(openGl3Exception);
        throw openGl2Exception;
      }
    }
  }

  private void createFocusedEglContextAndPlaceholderSurface(int openGlVersion)
      throws GlUtil.GlException {
    EGLContext newEglContext =
        GlUtil.createEglContext(
            EGL14.EGL_NO_CONTEXT,
            checkNotNull(eglDisplay),
            openGlVersion,
            GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    try {
      placeholderEglSurface = GlUtil.createFocusedPlaceholderEglSurface(newEglContext, eglDisplay);
    } catch (GlUtil.GlException e) {
      GlUtil.destroyEglContext(eglDisplay, newEglContext);
      throw e;
    }
    eglContext = newEglContext;
  }

  /** Releases all GL objects, adding any exceptions thrown to {@code errors}. */
  private void release(List<Throwable> errors) {
    for (int fboId : fboIds) {
      runAndCollectException(() -> GlUtil.deleteFbo(fboId), errors);
    }
    for (int texId : texIds) {
      runAndCollectException(() -> GlUtil.deleteTexture(texId), errors);
    }
    runAndCollectException(
        () -> GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface), errors);
    runAndCollectException(() -> GlUtil.destroyEglContext(eglDisplay, eglContext), errors);
  }

  private static void runAndCollectException(
      ThrowingRunnable<GlUtil.GlException> action, List<Throwable> errors) {
    try {
      action.run();
    } catch (GlUtil.GlException e) {
      errors.add(e);
    }
  }
}
