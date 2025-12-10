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

import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.os.Build;
import androidx.annotation.ColorInt;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.EffectsTestUtil.FakeFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link GlTextureToBitmapFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class GlTextureToBitmapFrameProcessorTest {

  private static final int TEST_TIMEOUT_MS = 1000;
  private static final int WIDTH = 20;
  private static final int HEIGHT = 10;

  private @MonotonicNonNull ListeningExecutorService glThreadExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull Consumer<VideoFrameProcessingException> errorListener;
  private @MonotonicNonNull GlTextureToBitmapFrameProcessor processor;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull GlTextureFrameProcessorFactory factory;

  @Before
  public void setUp() throws Exception {
    glThreadExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    glThreadExecutorService
        .submit(
            () -> {
              eglDisplay = GlUtil.getDefaultEglDisplay();
              EGLContext eglContext = GlUtil.createEglContext(eglDisplay);
              GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
              return null;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    errorListener =
        exception -> {
          throw new AssertionError(exception);
        };
    factory =
        new GlTextureFrameProcessorFactory(
            getApplicationContext(), glThreadExecutorService, glObjectsProvider);
  }

  @After
  public void tearDown() throws Exception {
    if (processor != null) {
      processor.releaseAsync().get(TEST_TIMEOUT_MS, MILLISECONDS);
    }
    if (glObjectsProvider != null) {
      glObjectsProvider.release(eglDisplay);
    }
    if (glThreadExecutorService != null) {
      glThreadExecutorService.shutdownNow();
    }
  }

  @Test
  public void queueFrame_sdrTexture_outputsBitmapFrame() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    GlTextureFrame inputFrame =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 2000);

    boolean didQueueFrame = processor.getInput().queueFrame(inputFrame);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    BitmapFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.getMetadata().getPresentationTimeUs()).isEqualTo(2000);
    float bitmapDiff =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(
            createBitmap(Color.RED),
            outputFrame.getBitmap(),
            /* testId= */ null,
            /* differencesBitmapPath= */ null);
    assertThat(bitmapDiff).isEqualTo(0);
  }

  @Test
  public void queueFrame_hdrTexture_outputsBitmapFrame() throws Exception {
    assumeTrue(Build.VERSION.SDK_INT >= 34);
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ true);
    GlTextureFrame inputFrame =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 2000);

    boolean didQueueFrame = processor.getInput().queueFrame(inputFrame);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    BitmapFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.getMetadata().getPresentationTimeUs()).isEqualTo(2000);
    Bitmap outputBitmap = outputFrame.getBitmap();
    assertThat(outputBitmap).isNotNull();
    assertThat(outputBitmap.getWidth()).isEqualTo(WIDTH);
    assertThat(outputBitmap.getHeight()).isEqualTo(HEIGHT);
    // The output color is device specific for HDR, so do not assert on it.
  }

  @Test
  public void queueFrame_releasesInputFrame() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    CountDownLatch releaseLatch = new CountDownLatch(1);
    GlTextureInfo inputTextureInfo = createGlTextureWithColor(Color.RED);
    GlTextureFrame inputFrame =
        new GlTextureFrame.Builder(
                inputTextureInfo,
                glThreadExecutorService,
                /* releaseTextureCallback= */ (texInfo) -> {
                  assertThat(texInfo).isSameInstanceAs(inputTextureInfo);
                  releaseLatch.countDown();
                })
            .setPresentationTimeUs(2000)
            .build();

    boolean didQueueFrame = processor.getInput().queueFrame(inputFrame);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(releaseLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  @Test
  public void queueFrame_atCapacity_rejectsFrameThenAccepts() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    // Used to allow a frame to be queued and then simulate processing blocking.
    CountDownLatch processingStartedLatch = new CountDownLatch(1);
    CountDownLatch processingCanContinueLatch = new CountDownLatch(1);
    GlTextureFrame inputFrame1 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 1000);
    GlTextureFrame inputFrame2 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.GREEN), /* presentationTimeUs= */ 2000);
    // Block the GL thread to simulate a full processor.
    ListenableFuture<Void> unused =
        glThreadExecutorService.submit(
            () -> {
              processingStartedLatch.countDown();
              assertThat(processingCanContinueLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
              return null;
            });
    assertThat(processingStartedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    // Queue first frame. It will be accepted but will block inside the executor.
    boolean didQueueFrame = processor.getInput().queueFrame(inputFrame1);

    assertThat(didQueueFrame).isTrue();

    // Queue second frame. It should be rejected because the processor is busy.
    didQueueFrame = processor.getInput().queueFrame(inputFrame2);

    assertThat(didQueueFrame).isFalse();

    // Unblock GL thread and wait for first frame to be processed.
    processingCanContinueLatch.countDown();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    assertThat(fakeFrameConsumer.receivedFrames.get(0).getMetadata().getPresentationTimeUs())
        .isEqualTo(1000);

    // Queue second frame again. It should now be accepted.
    queueFrameLatch = new CountDownLatch(1);
    fakeFrameConsumer.setOnQueueFrame(queueFrameLatch::countDown);

    didQueueFrame = processor.getInput().queueFrame(inputFrame2);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(2);
    assertThat(fakeFrameConsumer.receivedFrames.get(1).getMetadata().getPresentationTimeUs())
        .isEqualTo(2000);
  }

  @Test
  public void queueFrame_notifiesCapacityListenerWhenProcessed() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    CountDownLatch callbackLatch = new CountDownLatch(1);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    ListeningExecutorService queueFrameExecutor =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    GlTextureFrame inputFrame1 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 1000);
    queueFrameExecutor
        .submit(
            () ->
                processor
                    .getInput()
                    .setOnCapacityAvailableCallback(queueFrameExecutor, callbackLatch::countDown))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    queueFrameExecutor
        .submit(() -> assertThat(processor.getInput().queueFrame(inputFrame1)).isTrue())
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  @Test
  public void queueFrame_multipleSdrFrames_processedInOrder() throws Exception {
    CountDownLatch queueFrameLatch = new CountDownLatch(1);
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer =
        new FakeFrameConsumer<>(queueFrameLatch::countDown);
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    GlTextureFrame inputFrame1 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 1000);
    GlTextureFrame inputFrame2 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.GREEN), /* presentationTimeUs= */ 2000);
    GlTextureFrame inputFrame3 =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.BLUE), /* presentationTimeUs= */ 3000);

    boolean didQueueFrame = processor.getInput().queueFrame(inputFrame1);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    queueFrameLatch = new CountDownLatch(1);
    fakeFrameConsumer.setOnQueueFrame(queueFrameLatch::countDown);

    didQueueFrame = processor.getInput().queueFrame(inputFrame2);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    queueFrameLatch = new CountDownLatch(1);
    fakeFrameConsumer.setOnQueueFrame(queueFrameLatch::countDown);

    didQueueFrame = processor.getInput().queueFrame(inputFrame3);

    assertThat(didQueueFrame).isTrue();
    assertThat(queueFrameLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(3);
    assertThat(fakeFrameConsumer.receivedFrames.get(0).getMetadata().getPresentationTimeUs())
        .isEqualTo(1000);
    assertThat(fakeFrameConsumer.receivedFrames.get(1).getMetadata().getPresentationTimeUs())
        .isEqualTo(2000);
    assertThat(fakeFrameConsumer.receivedFrames.get(2).getMetadata().getPresentationTimeUs())
        .isEqualTo(3000);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(Color.RED),
                fakeFrameConsumer.receivedFrames.get(0).getBitmap(),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(Color.GREEN),
                fakeFrameConsumer.receivedFrames.get(1).getBitmap(),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(Color.BLUE),
                fakeFrameConsumer.receivedFrames.get(2).getBitmap(),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void getInput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer = new FakeFrameConsumer<>(() -> {});
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);

    ListenableFuture<Void> unused = processor.releaseAsync();
    assertThrows(IllegalStateException.class, () -> processor.getInput());
  }

  @Test
  public void queueFrame_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer = new FakeFrameConsumer<>(() -> {});
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);
    FrameConsumer<GlTextureFrame> consumer = processor.getInput();
    GlTextureFrame frame =
        createTestGlTextureFrame(
            createGlTextureWithColor(Color.RED), /* presentationTimeUs= */ 1000);

    ListenableFuture<Void> unused = processor.releaseAsync();
    assertThrows(IllegalStateException.class, () -> consumer.queueFrame(frame));
  }

  @Test
  public void setOutput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    FakeFrameConsumer<BitmapFrame> fakeFrameConsumer = new FakeFrameConsumer<>(() -> {});
    setUpProcessor(fakeFrameConsumer, /* useHdr= */ false);

    ListenableFuture<Void> unused = processor.releaseAsync();
    assertThrows(IllegalStateException.class, () -> processor.setOutputAsync(fakeFrameConsumer));
  }

  private void setUpProcessor(FrameConsumer<BitmapFrame> frameConsumer, boolean useHdr)
      throws ExecutionException, InterruptedException, TimeoutException {
    processor =
        Futures.submit(
                () -> factory.buildGlTextureToBitmapFrameProcessor(useHdr), glThreadExecutorService)
            .get(TEST_TIMEOUT_MS, MILLISECONDS);
    processor.setOutputAsync(frameConsumer).get(TEST_TIMEOUT_MS, MILLISECONDS);
    processor.setOnErrorCallback(glThreadExecutorService, errorListener);
  }

  /** Creates a {@link GlTextureInfo} with the given color drawn on it. */
  private GlTextureInfo createGlTextureWithColor(int color) throws Exception {
    return glThreadExecutorService
        .submit(
            () -> {
              int texId =
                  GlUtil.createTexture(WIDTH, HEIGHT, /* useHighPrecisionColorComponents= */ false);
              GlTextureInfo textureInfo =
                  glObjectsProvider.createBuffersForTexture(texId, WIDTH, HEIGHT);
              GlUtil.focusFramebufferUsingCurrentContext(
                  textureInfo.fboId, textureInfo.width, textureInfo.height);
              GLES20.glClearColor(
                  Color.red(color) / 255f,
                  Color.green(color) / 255f,
                  Color.blue(color) / 255f,
                  Color.alpha(color) / 255f);
              GlUtil.checkGlError();
              GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
              return textureInfo;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  /** Creates a {@link GlTextureFrame} with a no-op release listener. */
  private GlTextureFrame createTestGlTextureFrame(
      GlTextureInfo glTextureInfo, long presentationTimeUs) {
    return new GlTextureFrame.Builder(
            glTextureInfo, glThreadExecutorService, /* releaseTextureCallback= */ (unused) -> {})
        .setPresentationTimeUs(presentationTimeUs)
        .build();
  }

  private Bitmap createBitmap(@ColorInt int color) {
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Config.ARGB_8888);
    bitmap.eraseColor(color);
    return bitmap;
  }
}
