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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.effect.EffectsTestUtil.FakeFrameConsumer;
import androidx.media3.effect.EffectsTestUtil.FakeGlShaderProgram;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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

  private static final int TEST_TIMEOUT_MS = 1000;
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
            .get(TEST_TIMEOUT_MS, MILLISECONDS);
    processor.setOnErrorCallback(glThreadExecutorService, errorListener);

    fakeCapacityListener = new FakeCapacityListener();
    processor
        .getInput()
        .setOnCapacityAvailableCallback(
            glThreadExecutorService, fakeCapacityListener::onCapacityAvailable);

    fakeFrameConsumer = new FakeFrameConsumer<>(/* expectedNumberOfFrames= */ 1);
    processor.setOutputAsync(fakeFrameConsumer).get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @After
  public void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
    processor.releaseAsync().get(TEST_TIMEOUT_MS, MILLISECONDS);
    glThreadExecutorService.shutdownNow();
  }

  @Test
  public void queueFrame_callsShaderProgramQueueInputFrame() throws Exception {
    GlTextureFrame inputFrame = createTestFrame(ITEM_ID);

    processor.getInput().queueFrame(inputFrame);
    // Force pending tasks on the GL thread to complete.
    glThreadExecutorService
        .invokeAll(ImmutableList.of(() -> null))
        .get(0)
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    FakeGlShaderProgram.QueuedFrameInfo info = fakeGlShaderProgram.queuedFrames.get(0);
    assertThat(info.textureInfo).isEqualTo(inputFrame.glTextureInfo);
    assertThat(info.presentationTimeUs).isEqualTo(inputFrame.presentationTimeUs);
  }

  @Test
  public void queueFrame_atCapacity_doesNotAcceptFrame() throws Exception {
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    GlTextureFrame inputFrame1 = createTestFrame(ITEM_ID);
    GlTextureFrame inputFrame2 = createTestFrame(ITEM_ID + 1);

    assertThat(processor.getInput().queueFrame(inputFrame1)).isTrue();
    assertThat(processor.getInput().queueFrame(inputFrame2)).isFalse();

    assertThat(queuedFramesLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    FakeGlShaderProgram.QueuedFrameInfo info = fakeGlShaderProgram.queuedFrames.get(0);
    assertThat(info.textureInfo).isEqualTo(inputFrame1.glTextureInfo);
    assertThat(info.presentationTimeUs).isEqualTo(inputFrame1.presentationTimeUs);
  }

  @Test
  public void shaderProgram_onReadyToAcceptInputFrame_notifiesCapacityListener()
      throws InterruptedException, ExecutionException, TimeoutException {
    processor.getInput().queueFrame(createTestFrame(ITEM_ID));

    glThreadExecutorService
        .submit(() -> fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame())
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(fakeCapacityListener.onCapacityAvailableCount.get()).isEqualTo(1);
  }

  @Test
  public void shaderProgram_onReadyToAcceptInputFrame_releasesInputFrame() throws Exception {
    GlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    processor.getInput().queueFrame(inputFrame);

    glThreadExecutorService
        .submit(() -> fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame())
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(((TestMetadata) inputFrame.getMetadata()).released).isTrue();
  }

  @Test
  public void shaderProgram_withOutputProcessor_queuesToOutputProcessor() throws Exception {
    GlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    GlTextureInfo outputTexture = new GlTextureInfo(99, -1, -1, 100, 100);
    long outputTimeUs = 12345L;

    processor.getInput().queueFrame(inputFrame);
    assertThat(queuedFramesLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
                      outputTexture, outputTimeUs);
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.glTextureInfo.texId).isEqualTo(outputTexture.texId);
    assertThat(outputFrame.presentationTimeUs).isEqualTo(outputTimeUs);
  }

  @Test
  public void shaderProgram_withOutputProcessor_queuesToOutputProcessorAfterCapacityCallback()
      throws Exception {
    fakeFrameConsumer.acceptFrames = false;
    GlTextureFrame inputFrame = createTestFrame(ITEM_ID);
    CountDownLatch queuedFramesLatch = new CountDownLatch(1);
    fakeGlShaderProgram.queuedFramesLatch = queuedFramesLatch;
    GlTextureInfo outputTexture = new GlTextureInfo(99, -1, -1, 100, 100);
    long outputTimeUs = 12345L;

    processor.getInput().queueFrame(inputFrame);
    assertThat(queuedFramesLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    glThreadExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
                      outputTexture, outputTimeUs);
                  return null;
                }))
        .get(0)
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(fakeFrameConsumer.receivedFrames).isEmpty();

    fakeFrameConsumer.acceptFrames = true;
    fakeFrameConsumer.notifyCallbackListener();

    fakeFrameConsumer.awaitFrame(TEST_TIMEOUT_MS);
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.glTextureInfo.texId).isEqualTo(outputTexture.texId);
    assertThat(outputFrame.presentationTimeUs).isEqualTo(outputTimeUs);
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
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(caughtException.get()).isEqualTo(testException);
  }

  @Test
  public void getInput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(IllegalStateException.class, () -> processor.getInput());
  }

  @Test
  public void queueFrame_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    FrameConsumer<GlTextureFrame> consumer = processor.getInput();
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(IllegalStateException.class, () -> consumer.queueFrame(createTestFrame(ITEM_ID)));
  }

  @Test
  public void setOutput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(IllegalStateException.class, () -> processor.setOutputAsync(fakeFrameConsumer));
  }

  private GlTextureFrame createTestFrame(int id) {
    TestMetadata metadata = new TestMetadata();
    return new GlTextureFrame.Builder(
            new GlTextureInfo(id, -1, -1, 100, 100),
            directExecutor(),
            (texInfo) -> {
              metadata.released = true;
            })
        .setPresentationTimeUs(id * 1000L)
        .setMetadata(metadata)
        .build();
  }

  private static final class TestMetadata implements Frame.Metadata {
    boolean released;
  }

  private static final class FakeCapacityListener {
    public final AtomicInteger onCapacityAvailableCount = new AtomicInteger(0);

    public void onCapacityAvailable() {
      onCapacityAvailableCount.incrementAndGet();
    }
  }
}
