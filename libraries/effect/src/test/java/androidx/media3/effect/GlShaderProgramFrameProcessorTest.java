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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.fail;

import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.annotation.Nullable;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link GlShaderProgramFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class GlShaderProgramFrameProcessorTest {

  private static final int TEST_TIMEOUT = 1000;
  private static final int ITEM_ID = 1;
  private GlShaderProgramFrameProcessor processor;
  private ListeningExecutorService glThreadExecutorService;
  private GlObjectsProvider glObjectsProvider;
  private FakeGlShaderProgram fakeGlShaderProgram;
  private FakeCapacityListener fakeCapacityListener;
  private FakeFrameConsumer<GlTextureFrame> fakeFrameConsumer;

  @Before
  public void setUp() throws Exception {
    glThreadExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider =
        new GlObjectsProvider() {
          @Override
          public EGLContext createEglContext(
              EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
            throw new UnsupportedOperationException(
                "OpenGL should not be called in Robolectric tests.");
          }

          @Override
          public EGLSurface createEglSurface(
              EGLDisplay eglDisplay,
              Object surface,
              @ColorTransfer int colorTransfer,
              boolean isEncoderInputSurface)
              throws GlException {
            throw new UnsupportedOperationException(
                "OpenGL should not be called in Robolectric tests.");
          }

          @Override
          public EGLSurface createFocusedPlaceholderEglSurface(
              EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
            throw new UnsupportedOperationException(
                "OpenGL should not be called in Robolectric tests.");
          }

          @Override
          public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
              throws GlException {
            throw new UnsupportedOperationException(
                "OpenGL should not be called in Robolectric tests.");
          }

          @Override
          public void release(EGLDisplay eglDisplay) throws GlException {
            throw new UnsupportedOperationException(
                "OpenGL should not be called in Robolectric tests.");
          }
        };

    Consumer<VideoFrameProcessingException> errorListener =
        exception -> {
          fail("OpenGL error during test: " + exception);
        };
    fakeGlShaderProgram = new FakeGlShaderProgram();
    processor =
        glThreadExecutorService
            .submit(
                () ->
                    GlShaderProgramFrameProcessor.create(
                        glThreadExecutorService, fakeGlShaderProgram, glObjectsProvider))
            .get(TEST_TIMEOUT, MILLISECONDS);
    processor.setOnErrorCallback(glThreadExecutorService, errorListener);

    fakeCapacityListener = new FakeCapacityListener();
    processor
        .getInput()
        .setOnCapacityAvailableCallback(
            glThreadExecutorService, fakeCapacityListener::onCapacityAvailable);

    fakeFrameConsumer = new FakeFrameConsumer<>(/* expectedNumberOfFrames= */ 1);
    processor.setOutput(fakeFrameConsumer);
  }

  @After
  public void tearDown() {
    processor.release();
    glThreadExecutorService.shutdownNow();
  }

  @Test
  public void queueFrame_callsShaderProgramQueueInputFrame() throws Exception {
    TestGlTextureFrame inputFrame = createTestFrame(ITEM_ID);

    processor.getInput().queueFrame(inputFrame);
    // Force pending tasks on the GL thread to complete.
    glThreadExecutorService
        .invokeAll(ImmutableList.of(() -> null))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);

    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    FakeGlShaderProgram.QueuedFrameInfo info = fakeGlShaderProgram.queuedFrames.get(0);
    assertThat(info.textureInfo).isEqualTo(inputFrame.getGlTextureInfo());
    assertThat(info.presentationTimeUs).isEqualTo(inputFrame.getMetadata().getPresentationTimeUs());
  }

  @Test
  public void queueFrame_atCapacity_doesNotAcceptFrame() throws Exception {
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    TestGlTextureFrame inputFrame1 = createTestFrame(ITEM_ID);
    TestGlTextureFrame inputFrame2 = createTestFrame(ITEM_ID + 1);

    assertThat(processor.getInput().queueFrame(inputFrame1)).isTrue();
    assertThat(processor.getInput().queueFrame(inputFrame2)).isFalse();

    assertThat(queuedFramesLatch.await(TEST_TIMEOUT, MILLISECONDS)).isTrue();
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    FakeGlShaderProgram.QueuedFrameInfo info = fakeGlShaderProgram.queuedFrames.get(0);
    assertThat(info.textureInfo).isEqualTo(inputFrame1.getGlTextureInfo());
    assertThat(info.presentationTimeUs)
        .isEqualTo(inputFrame1.getMetadata().getPresentationTimeUs());
  }

  @Test
  public void shaderProgram_onReadyToAcceptInputFrame_notifiesCapacityListener()
      throws InterruptedException, ExecutionException, TimeoutException {
    processor.getInput().queueFrame(createTestFrame(ITEM_ID));

    glThreadExecutorService
        .submit(() -> fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame())
        .get(TEST_TIMEOUT, MILLISECONDS);
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);

    assertThat(fakeCapacityListener.onCapacityAvailableCount.get()).isEqualTo(1);
  }

  @Test
  public void shaderProgram_onReadyToAcceptInputFrame_releasesInputFrame() throws Exception {
    TestGlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    processor.getInput().queueFrame(inputFrame);

    glThreadExecutorService
        .submit(() -> fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame())
        .get(TEST_TIMEOUT, MILLISECONDS);
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);

    assertThat(inputFrame.isReleased()).isTrue();
  }

  @Test
  public void shaderProgram_withOutputProcessor_queuesToOutputProcessor() throws Exception {
    TestGlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    GlTextureInfo outputTexture = new GlTextureInfo(99, -1, -1, 100, 100);
    long outputTimeUs = 12345L;

    processor.getInput().queueFrame(inputFrame);
    assertThat(queuedFramesLatch.await(TEST_TIMEOUT, MILLISECONDS)).isTrue();

    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
                      outputTexture, outputTimeUs);
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);

    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.getGlTextureInfo().texId).isEqualTo(outputTexture.texId);
    assertThat(outputFrame.getMetadata().getPresentationTimeUs()).isEqualTo(outputTimeUs);
  }

  @Test
  public void shaderProgram_withOutputProcessor_queuesToOutputProcessorAfterCapacityCallback()
      throws Exception {
    fakeFrameConsumer.acceptFrames = false;
    TestGlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    GlTextureInfo outputTexture = new GlTextureInfo(99, -1, -1, 100, 100);
    long outputTimeUs = 12345L;

    processor.getInput().queueFrame(inputFrame);
    assertThat(queuedFramesLatch.await(TEST_TIMEOUT, MILLISECONDS)).isTrue();
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
                      outputTexture, outputTimeUs);
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);
    assertThat(fakeFrameConsumer.receivedFrames).isEmpty();

    fakeFrameConsumer.acceptFrames = true;
    fakeFrameConsumer.notifyCallbackListener();

    fakeFrameConsumer.awaitFrame(TEST_TIMEOUT);
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.getGlTextureInfo().texId).isEqualTo(outputTexture.texId);
    assertThat(outputFrame.getMetadata().getPresentationTimeUs()).isEqualTo(outputTimeUs);
  }

  @Test
  public void shaderProgram_onError_propagatesToListener()
      throws ExecutionException, InterruptedException, TimeoutException {
    VideoFrameProcessingException testException = new VideoFrameProcessingException("Shader error");
    AtomicReference<VideoFrameProcessingException> caughtException = new AtomicReference<>();
    processor.setOnErrorCallback(glThreadExecutorService, caughtException::set);

    fakeGlShaderProgram.errorListener.onError(testException);
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT, MILLISECONDS);

    assertThat(caughtException.get()).isEqualTo(testException);
  }

  private TestGlTextureFrame createTestFrame(int id) {
    return new TestGlTextureFrame(
        new GlTextureInfo(id, -1, -1, 100, 100),
        new GlTextureFrame.Metadata(id * 1000L, new Format.Builder().build()),
        directExecutor(),
        (texInfo) -> {});
  }

  private static class FakeGlShaderProgram implements GlShaderProgram {

    public static class QueuedFrameInfo {
      final GlTextureInfo textureInfo;
      final long presentationTimeUs;

      QueuedFrameInfo(GlTextureInfo textureInfo, long presentationTimeUs) {
        this.textureInfo = textureInfo;
        this.presentationTimeUs = presentationTimeUs;
      }
    }

    public InputListener inputListener = new InputListener() {};
    public OutputListener outputListener = new OutputListener() {};
    public ErrorListener errorListener = e -> {};
    public final List<QueuedFrameInfo> queuedFrames = new ArrayList<>();
    @Nullable public CountDownLatch queuedFramesLatch;

    @Override
    public void setInputListener(InputListener inputListener) {
      this.inputListener = inputListener;
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void setOutputListener(OutputListener outputListener) {
      this.outputListener = outputListener;
    }

    @Override
    public void setErrorListener(Executor executor, ErrorListener errorListener) {
      this.errorListener = errorListener;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      queuedFrames.add(new QueuedFrameInfo(inputTexture, presentationTimeUs));
      if (queuedFramesLatch != null) {
        queuedFramesLatch.countDown();
      }
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {}

    @Override
    public void signalEndOfCurrentInputStream() {
      outputListener.onCurrentOutputStreamEnded();
    }

    @Override
    public void flush() {
      inputListener.onFlush();
    }

    @Override
    public void release() {}
  }

  /** A fake {@link FrameProcessor} for capturing output frames. */
  public static final class FakeFrameConsumer<I extends Frame> implements FrameConsumer<I> {
    public boolean acceptFrames;
    private final CountDownLatch queueFrameLatch;
    public final List<I> receivedFrames;
    @Nullable private Runnable onCapacityAvailableCallback;
    @Nullable private Executor onCapacityAvailableExecutor;

    public FakeFrameConsumer(int expectedNumberOfFrames) {
      this.acceptFrames = true;
      this.queueFrameLatch = new CountDownLatch(expectedNumberOfFrames);
      this.receivedFrames = new ArrayList<>();
    }

    @Override
    public boolean queueFrame(I frame) {
      if (!acceptFrames) {
        return false;
      }
      receivedFrames.add(frame);
      queueFrameLatch.countDown();
      return true;
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor executor, Runnable onCapacityAvailableCallback) {
      this.onCapacityAvailableCallback = onCapacityAvailableCallback;
      this.onCapacityAvailableExecutor = executor;
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      this.onCapacityAvailableCallback = null;
      this.onCapacityAvailableExecutor = null;
    }

    public void notifyCallbackListener() {
      checkNotNull(onCapacityAvailableExecutor).execute(checkNotNull(onCapacityAvailableCallback));
    }

    public void awaitFrame(long timeoutMs) throws InterruptedException {
      if (!queueFrameLatch.await(timeoutMs, MILLISECONDS)) {
        fail("Timeout waiting for output frame");
      }
    }
  }

  private static final class FakeCapacityListener {
    public final AtomicInteger onCapacityAvailableCount = new AtomicInteger(0);

    public void onCapacityAvailable() {
      onCapacityAvailableCount.incrementAndGet();
    }
  }

  static class TestGlTextureFrame extends GlTextureFrame {
    private boolean released = false;

    public TestGlTextureFrame(
        GlTextureInfo glTextureInfo,
        Metadata metadata,
        Executor onReleaseExecutor,
        Consumer<GlTextureInfo> onRelease) {
      super(glTextureInfo, metadata, onReleaseExecutor, onRelease);
    }

    @Override
    public void release() {
      super.release();
      released = true;
    }

    public boolean isReleased() {
      return released;
    }
  }
}
