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
package androidx.media3.effect;

import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FrameProcessorUtils}. */
@RunWith(AndroidJUnit4.class)
public final class FrameProcessorUtilsTest {

  @Test
  public void runAllAndAccumulateExceptions_noExceptions_doesNotThrow() throws Exception {
    runAllAndAccumulateExceptions(() -> {}, () -> {});
  }

  @Test
  public void runAllAndAccumulateExceptions_singleException_throwsAndWrapsException() {
    Exception exception = new Exception("Test exception");

    VideoFrameProcessingException thrown =
        assertThrows(
            VideoFrameProcessingException.class,
            () ->
                runAllAndAccumulateExceptions(
                    () -> {
                      throw exception;
                    }));

    assertThat(thrown).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void runAllAndAccumulateExceptions_multipleExceptions_throwsWithSuppressedExceptions() {
    Exception firstException = new Exception("First exception");
    Exception secondException = new Exception("Second exception");
    Exception thirdException = new Exception("Third exception");

    VideoFrameProcessingException thrown =
        assertThrows(
            VideoFrameProcessingException.class,
            () ->
                runAllAndAccumulateExceptions(
                    () -> {
                      throw firstException;
                    },
                    () -> {
                      throw secondException;
                    },
                    () -> {
                      throw thirdException;
                    }));

    assertThat(thrown).hasCauseThat().isEqualTo(firstException);
    assertThat(thrown.getSuppressed()).hasLength(2);
    assertThat(thrown.getSuppressed()[0]).hasCauseThat().isEqualTo(secondException);
    assertThat(thrown.getSuppressed()[1]).hasCauseThat().isEqualTo(thirdException);
  }

  @Test
  public void runAllAndAccumulateExceptions_exceptionInMiddle_runsSubsequentActions() {
    AtomicBoolean firstActionExecuted = new AtomicBoolean();
    AtomicBoolean secondActionExecuted = new AtomicBoolean();
    AtomicBoolean thirdActionExecuted = new AtomicBoolean();
    Exception exception = new Exception("Test exception");

    assertThrows(
        VideoFrameProcessingException.class,
        () ->
            runAllAndAccumulateExceptions(
                () -> firstActionExecuted.set(true),
                () -> {
                  secondActionExecuted.set(true);
                  throw exception;
                },
                () -> thirdActionExecuted.set(true)));

    assertThat(firstActionExecuted.get()).isTrue();
    assertThat(secondActionExecuted.get()).isTrue();
    assertThat(thirdActionExecuted.get()).isTrue();
  }

  @Test
  public void setupOpenGl_surfacelessSupported_successfulVersion3_returnsVersion3()
      throws Exception {
    TestGlObjectsProvider glObjectsProvider =
        new TestGlObjectsProvider(
            /* failVersion3= */ false, /* failVersion2= */ false, /* failSurfaceCreation= */ false);

    int result =
        FrameProcessorUtils.setupOpenGl(
            glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ true);

    assertThat(result).isEqualTo(FrameProcessorUtils.OPEN_GL_VERSION_3);
    assertThat(glObjectsProvider.requestedVersions).containsExactly(3);
  }

  @Test
  public void
      setupOpenGl_surfacelessSupported_version3Throws_fallsBackToVersion2AndReturnsVersion2()
          throws Exception {
    TestGlObjectsProvider glObjectsProvider =
        new TestGlObjectsProvider(
            /* failVersion3= */ true, /* failVersion2= */ false, /* failSurfaceCreation= */ false);

    int result =
        FrameProcessorUtils.setupOpenGl(
            glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ true);

    assertThat(result).isEqualTo(FrameProcessorUtils.OPEN_GL_VERSION_2);
    assertThat(glObjectsProvider.requestedVersions).containsExactly(3, 2).inOrder();
  }

  @Test
  public void setupOpenGl_surfacelessSupported_surfaceCreationThrows_throwsGlException() {
    TestGlObjectsProvider glObjectsProvider =
        new TestGlObjectsProvider(
            /* failVersion3= */ false, /* failVersion2= */ false, /* failSurfaceCreation= */ true);

    assertThrows(
        GlException.class,
        () ->
            FrameProcessorUtils.setupOpenGl(
                glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ true));
    assertThat(glObjectsProvider.requestedVersions).containsExactly(3, 2).inOrder();
  }

  @Test
  public void setupOpenGl_surfacelessUnsupported_createsVersion2AndReturnsVersion2()
      throws Exception {
    TestGlObjectsProvider glObjectsProvider =
        new TestGlObjectsProvider(
            /* failVersion3= */ false, /* failVersion2= */ false, /* failSurfaceCreation= */ false);

    int result =
        FrameProcessorUtils.setupOpenGl(
            glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ false);

    assertThat(result).isEqualTo(FrameProcessorUtils.OPEN_GL_VERSION_2);
    assertThat(glObjectsProvider.requestedVersions).containsExactly(2);
  }

  @Test
  public void setupOpenGl_surfacelessUnsupported_version2Throws_throwsGlException() {
    TestGlObjectsProvider glObjectsProvider =
        new TestGlObjectsProvider(
            /* failVersion3= */ false, /* failVersion2= */ true, /* failSurfaceCreation= */ false);

    assertThrows(
        GlException.class,
        () ->
            FrameProcessorUtils.setupOpenGl(
                glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ false));
    assertThat(glObjectsProvider.requestedVersions).containsExactly(2);
  }

  private static class TestGlObjectsProvider implements GlObjectsProvider {
    private final boolean failVersion3;
    private final boolean failVersion2;
    private final boolean failSurfaceCreation;
    final List<Integer> requestedVersions = new ArrayList<>();

    TestGlObjectsProvider(boolean failVersion3, boolean failVersion2, boolean failSurfaceCreation) {
      this.failVersion3 = failVersion3;
      this.failVersion2 = failVersion2;
      this.failSurfaceCreation = failSurfaceCreation;
    }

    @Override
    public EGLContext createEglContext(
        EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
      requestedVersions.add(openGlVersion);
      if (openGlVersion == 3 && failVersion3) {
        throw new GlException("Test Version 3 unsupported");
      }
      if (openGlVersion == 2 && failVersion2) {
        throw new GlException("Test Version 2 unsupported");
      }
      return EGL14.EGL_NO_CONTEXT;
    }

    @Override
    public EGLSurface createEglSurface(
        EGLDisplay eglDisplay, Object surface, int colorTransfer, boolean isEncoderInputSurface) {
      return EGL14.EGL_NO_SURFACE;
    }

    @Override
    public EGLSurface createFocusedPlaceholderEglSurface(
        EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
      if (failSurfaceCreation) {
        throw new GlException("Test Surface Creation failed");
      }
      return EGL14.EGL_NO_SURFACE;
    }

    @Override
    public GlTextureInfo createBuffersForTexture(int texId, int width, int height) {
      return new GlTextureInfo(texId, -1, -1, width, height);
    }

    @Override
    public void release(EGLDisplay eglDisplay) {}
  }
}
