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
package androidx.media3.effect;

import static androidx.media3.common.util.GlUtil.createTexture;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoFrameProcessor.Listener;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.GlShaderProgram.InputListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link FinalShaderProgramWrapper}.
 *
 * <p>The thread the test runs on is the VideoFrameProcessing and Gl thread, to make assertions
 * easier and avoid race conditions.
 */
@RunWith(AndroidJUnit4.class)
public final class FinalShaderProgramWrapperTest {
  private @MonotonicNonNull FinalShaderProgramWrapper finalShaderProgramWrapper;

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private @MonotonicNonNull VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;
  @Nullable private VideoFrameProcessingException videoFrameProcessingException;
  @Nullable private VideoFrameProcessor.Listener videoFrameProcessorListener;
  @Nullable private InputListener inputListener;
  @Nullable private FinalShaderProgramWrapper.Listener listener;
  private @MonotonicNonNull SurfaceInfo outputSurfaceInfo;
  private @MonotonicNonNull List<GlTextureInfo> inputTextureInfos;

  /**
   * All presentationTimeUs of frames sent to {@link
   * VideoFrameProcessor.Listener#onOutputFrameAvailableForRendering}
   */
  private @MonotonicNonNull List<Long> presentationTimesUsAvailableForRendering;

  /**
   * All {@link GlTextureInfo} sent to {@link GlShaderProgram.InputListener#onInputFrameProcessed}.
   */
  private @MonotonicNonNull List<GlTextureInfo> processedTextures;

  /**
   * All presentationTimeUs of frames sent to {@link
   * FinalShaderProgramWrapper.Listener#onFrameRendered}.
   */
  private @MonotonicNonNull List<Long> renderedPresentationTimesUs;

  /**
   * A list of the last presentationTimeUs sent to {@link
   * FinalShaderProgramWrapper.Listener#onFrameRendered} when {@link
   * FinalShaderProgramWrapper.Listener#onInputStreamProcessed} is called.
   */
  private @MonotonicNonNull List<Long> endOfCurrentStreamPresentationTimesUs;

  @Before
  public void setupResultLists() {
    presentationTimesUsAvailableForRendering = new ArrayList<>();
    processedTextures = new ArrayList<>();
    renderedPresentationTimesUs = new ArrayList<>();
    endOfCurrentStreamPresentationTimesUs = new ArrayList<>();
  }

  @Before
  public void createOutputSurface() {
    ImageReader outputImageReader =
        ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, /* maxImages= */ 10);
    outputSurfaceInfo = new SurfaceInfo(outputImageReader.getSurface(), 1, 1);
  }

  @Before
  public void createGlObjects() throws Exception {
    VideoFrameProcessingTaskExecutor.ErrorListener errorListener =
        exception -> videoFrameProcessingException = exception;
    videoFrameProcessingTaskExecutor =
        new VideoFrameProcessingTaskExecutor(
            newDirectExecutorService(), /* shouldShutdownExecutorService= */ true, errorListener);

    // Ensure the Gl thread is the same as the VideoFrameProcessor thread and the test thread.
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext = GlUtil.createEglContext(eglDisplay);
    glObjectsProvider = new DefaultGlObjectsProvider(eglContext);
    placeholderEglSurface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);

    inputTextureInfos = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Bitmap bitmap = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
      int texId = createTexture(bitmap);
      inputTextureInfos.add(new GlTextureInfo(texId, C.INDEX_UNSET, C.INDEX_UNSET, 1, 1));
    }
  }

  @Before
  public void createListeners() {
    videoFrameProcessorListener =
        new Listener() {
          @Override
          public void onOutputFrameAvailableForRendering(
              long presentationTimeUs, boolean isRedrawnFrame) {
            presentationTimesUsAvailableForRendering.add(presentationTimeUs);
          }
        };
    inputListener =
        new InputListener() {
          @Override
          public void onInputFrameProcessed(GlTextureInfo inputTexture) {
            processedTextures.add(inputTexture);
          }
        };
    listener =
        new FinalShaderProgramWrapper.Listener() {
          @Override
          public void onInputStreamProcessed() {
            int numFramesRendered = renderedPresentationTimesUs.size();
            if (numFramesRendered == 0) {
              endOfCurrentStreamPresentationTimesUs.add(C.TIME_UNSET);
            } else {
              endOfCurrentStreamPresentationTimesUs.add(
                  renderedPresentationTimesUs.get(numFramesRendered - 1));
            }
          }

          @Override
          public void onFrameRendered(long presentationTimeUs) {
            renderedPresentationTimesUs.add(presentationTimeUs);
          }
        };
  }

  @After
  public void checkExceptionsAndRelease() throws Exception {
    if (videoFrameProcessingException != null) {
      throw videoFrameProcessingException;
    }
    // Call release on a new thread as the current thread is the VideoFrameProcessingThread.
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                videoFrameProcessingTaskExecutor.release(
                    /* releaseTask= */ () -> {
                      if (finalShaderProgramWrapper != null) {
                        finalShaderProgramWrapper.release();
                      }
                      for (GlTextureInfo textureInfo : inputTextureInfos) {
                        GlUtil.deleteTexture(textureInfo.texId);
                      }
                      if (eglContext != null && eglDisplay != null) {
                        GlUtil.destroyEglContext(eglDisplay, eglContext);
                      }
                    });
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });
  }

  @Test
  public void queueInputFrame_surfaceOutputAutomaticFrameRendering_rendersFramesImmediately()
      throws Exception {
    buildFinalShaderProgramWrapper(/* renderFramesAutomatically= */ true);

    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(0), 1000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(1), 2000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(2), 3000);

    assertThat(presentationTimesUsAvailableForRendering).containsExactly(1000L, 2000L, 3000L);
    assertThat(processedTextures).hasSize(3);
    assertThat(renderedPresentationTimesUs).containsExactly(1000L, 2000L, 3000L);
    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();
  }

  @Test
  public void queueInputFrame_surfaceOutputManualFrameRendering_rendersFramesOnRender()
      throws Exception {
    buildFinalShaderProgramWrapper(/* renderFramesAutomatically= */ false);

    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(0), 1000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(1), 2000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(2), 3000);

    assertThat(presentationTimesUsAvailableForRendering).containsExactly(1000L, 2000L, 3000L);
    assertThat(processedTextures).isEmpty();
    assertThat(renderedPresentationTimesUs).isEmpty();
    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();

    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10001000);

    assertThat(processedTextures).containsExactly(inputTextureInfos.get(0));
    assertThat(renderedPresentationTimesUs).containsExactly(1000L);
    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();

    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10002000);

    assertThat(processedTextures)
        .containsExactly(inputTextureInfos.get(0), inputTextureInfos.get(1));
    assertThat(renderedPresentationTimesUs).containsExactly(1000L, 2000L);
    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();

    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10003000);

    assertThat(processedTextures)
        .containsExactly(
            inputTextureInfos.get(0), inputTextureInfos.get(1), inputTextureInfos.get(2));
    assertThat(renderedPresentationTimesUs).containsExactly(1000L, 2000L, 3000L);
    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();
  }

  @Test
  public void
      signalEndOfCurrentInputStream_surfaceOutputAutomaticFrameRendering_notifiesImmediately()
          throws Exception {
    buildFinalShaderProgramWrapper(/* renderFramesAutomatically= */ true);

    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(0), 1000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(1), 2000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(2), 3000);

    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();

    finalShaderProgramWrapper.signalEndOfCurrentInputStream();

    assertThat(endOfCurrentStreamPresentationTimesUs).containsExactly(3000L);
  }

  @Test
  public void
      signalEndOfCurrentInputStream_surfaceOutputManualFrameRendering_notifiesOnceAllFramesRendered()
          throws Exception {
    buildFinalShaderProgramWrapper(/* renderFramesAutomatically= */ false);

    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(0), 1000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(1), 2000);
    finalShaderProgramWrapper.queueInputFrame(glObjectsProvider, inputTextureInfos.get(2), 3000);
    finalShaderProgramWrapper.signalEndOfCurrentInputStream();
    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10001000);
    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10002000);

    assertThat(endOfCurrentStreamPresentationTimesUs).isEmpty();

    finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, 10003000);

    assertThat(endOfCurrentStreamPresentationTimesUs).containsExactly(3000L);
  }

  private void buildFinalShaderProgramWrapper(boolean renderFramesAutomatically) throws Exception {
    finalShaderProgramWrapper =
        new FinalShaderProgramWrapper(
            getApplicationContext(),
            eglDisplay,
            eglContext,
            placeholderEglSurface,
            ColorInfo.SDR_BT709_LIMITED,
            videoFrameProcessingTaskExecutor,
            directExecutor(),
            checkNotNull(videoFrameProcessorListener),
            null,
            2,
            DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
            renderFramesAutomatically);
    videoFrameProcessingTaskExecutor.invoke(
        () -> {
          checkNotNull(inputListener);
          checkNotNull(listener);
          finalShaderProgramWrapper.setInputListener(inputListener);
          finalShaderProgramWrapper.setListener(listener);
        });
    finalShaderProgramWrapper.setOutputSurfaceInfo(outputSurfaceInfo);
  }
}
