/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.EffectsTestUtil.FakeFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link BitmapToGlTextureFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class BitmapToGlTextureFrameProcessorTest {

  private static final int TEST_TIMEOUT_MS = 10_000;
  private static final int WIDTH = 20;
  private static final int HEIGHT = 10;
  private ListeningExecutorService glThreadExecutorService;
  private GlObjectsProvider glObjectsProvider;
  private EGLDisplay eglDisplay;
  private Consumer<VideoFrameProcessingException> errorListener;
  private BitmapToGlTextureFrameProcessor processor;
  private GlTextureFrameProcessorFactory factory;

  @Before
  public void setUp() throws Exception {
    glThreadExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    errorListener =
        exception -> {
          throw new AssertionError(exception);
        };
    glThreadExecutorService
        .submit(
            () -> {
              eglDisplay = GlUtil.getDefaultEglDisplay();
              EGLContext eglContext = GlUtil.createEglContext(eglDisplay);
              GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
              return null;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    factory =
        new GlTextureFrameProcessorFactory(
            getApplicationContext(), glThreadExecutorService, glObjectsProvider);
  }

  @After
  public void tearDown() throws Exception {
    processor.releaseAsync().get(TEST_TIMEOUT_MS, MILLISECONDS);
    glThreadExecutorService
        .submit(
            () -> {
              glObjectsProvider.release(eglDisplay);
              return null;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    glThreadExecutorService.shutdownNow();
  }

  @Test
  public void queueFrame_sdrBitmap_outputsGlTextureFrame() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<GlTextureFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpSdrProcessor(fakeFrameConsumer);
    Format srgbFormat =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();
    BitmapFrame inputFrame =
        new BitmapFrame(
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888),
            new BitmapFrame.Metadata(/* presentationTimeUs= */ 1000, srgbFormat));

    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.glTextureInfo.width).isEqualTo(WIDTH);
    assertThat(outputFrame.glTextureInfo.height).isEqualTo(HEIGHT);
    assertThat(outputFrame.presentationTimeUs).isEqualTo(1000);
    assertThat(outputFrame.format.height).isEqualTo(HEIGHT);
    assertThat(outputFrame.format.width).isEqualTo(WIDTH);
    assertThat(outputFrame.format.colorInfo).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
  }

  private void setUpSdrProcessor(FakeFrameConsumer<GlTextureFrame> fakeFrameConsumer)
      throws ExecutionException, InterruptedException, TimeoutException {
    processor =
        glThreadExecutorService
            .submit(
                () ->
                    factory.buildBitmapToGlTextureFrameProcessor(
                        /* inputColorInfo= */ ColorInfo.SRGB_BT709_FULL,
                        /* outputColorInfo= */ ColorInfo.SDR_BT709_LIMITED,
                        errorListener))
            .get(TEST_TIMEOUT_MS, MILLISECONDS);
    processor.setOutputAsync(fakeFrameConsumer).get(TEST_TIMEOUT_MS, MILLISECONDS);
    processor.setOnErrorCallback(glThreadExecutorService, errorListener);
  }
}
