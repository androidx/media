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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.annotation.Nullable;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlShaderProgramAdapter}. */
@RunWith(AndroidJUnit4.class)
public class GlShaderProgramAdapterTest {

  private static final FakeGlObjectsProvider FAKE_GL_OBJECTS_PROVIDER = new FakeGlObjectsProvider();
  private static final int TEXTURE_SIZE = 16;

  private GlShaderProgramAdapter glShaderProgramAdapter;
  private FakeGlShaderProgram fakeGlShaderProgram;
  private FakeGlTextureFrameConsumer downstreamConsumer;
  private AtomicReference<VideoFrameProcessingException> errorReference;

  @Before
  public void setUp() {
    fakeGlShaderProgram = new FakeGlShaderProgram();
    downstreamConsumer = new FakeGlTextureFrameConsumer();
    errorReference = new AtomicReference<>();

    glShaderProgramAdapter =
        new GlShaderProgramAdapter(
            fakeGlShaderProgram, FAKE_GL_OBJECTS_PROVIDER, directExecutor(), errorReference::set);
    glShaderProgramAdapter.setOutput(downstreamConsumer);
  }

  @After
  public void tearDown() throws Exception {
    glShaderProgramAdapter.close();
  }

  @Test
  public void queue_withCapacity_queuesToShaderProgramAndReturnsTrue() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);

    // Fake shader program starts with capacity of 1 when setInputListener is called.
    boolean queued = glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {});

    assertThat(queued).isTrue();
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    assertThat(fakeGlShaderProgram.queuedFrames.get(0).textureInfo.texId).isEqualTo(1);
    assertThat(fakeGlShaderProgram.queuedFrames.get(0).presentationTimeUs).isEqualTo(1000L);
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void queue_withoutCapacity_returnsFalseAndRegistersWakeupListener() {
    AtomicBoolean frame1Released = new AtomicBoolean();
    GlTextureFrame inputFrame1 =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frame1Released);
    AtomicBoolean frame2Released = new AtomicBoolean();
    GlTextureFrame inputFrame2 =
        createTestFrame(/* texId= */ 2, /* timestampUs= */ 2000L, frame2Released);

    AtomicBoolean wakeupCalled = new AtomicBoolean();

    // First frame consumes the single capacity.
    assertThat(glShaderProgramAdapter.queue(inputFrame1, directExecutor(), () -> {})).isTrue();

    // Second frame should fail to queue.
    boolean queued =
        glShaderProgramAdapter.queue(inputFrame2, directExecutor(), () -> wakeupCalled.set(true));

    assertThat(queued).isFalse();
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);

    // Signal that a frame is processed to trigger the wakeup listener.
    glShaderProgramAdapter.onInputFrameProcessed(inputFrame1.glTextureInfo);
    glShaderProgramAdapter.onReadyToAcceptInputFrame();

    assertThat(wakeupCalled.get()).isTrue();
    assertThat(frame1Released.get()).isTrue();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void onOutputFrameAvailable_whenDownstreamHasCapacity_forwardsCorrectFormat() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    int outputSize = TEXTURE_SIZE * 4;
    GlTextureInfo outputTexture =
        new GlTextureInfo(
            /* texId= */ 101,
            /* fboId= */ -1,
            /* rboId= */ -1,
            /* width= */ outputSize,
            /* height= */ outputSize);

    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, /* presentationTimeUs= */ 1000L);

    assertThat(downstreamConsumer.queuedFrames).hasSize(1);
    GlTextureFrame forwardedFrame = downstreamConsumer.queuedFrames.get(0);
    assertThat(forwardedFrame.glTextureInfo).isEqualTo(outputTexture);
    assertThat(forwardedFrame.presentationTimeUs).isEqualTo(1000L);

    assertThat(forwardedFrame.format.width).isEqualTo(outputSize);
    assertThat(forwardedFrame.format.height).isEqualTo(outputSize);
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void onOutputFrameAvailable_whenDownstreamNoCapacity_queuesPendingAndForwardsOnWakeup() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    // Force the downstream capacity to block queueing
    downstreamConsumer.setCapacity(0);
    int outputSize = TEXTURE_SIZE * 4;
    GlTextureInfo outputTexture =
        new GlTextureInfo(
            /* texId= */ 101,
            /* fboId= */ -1,
            /* rboId= */ -1,
            /* width= */ outputSize,
            /* height= */ outputSize);
    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, 1000L);

    assertThat(downstreamConsumer.queuedFrames).isEmpty();

    // Sets a new capacity and this triggers onWakeupListener.
    downstreamConsumer.setCapacity(1);

    assertThat(downstreamConsumer.queuedFrames).hasSize(1);
    GlTextureFrame queuedFrame = downstreamConsumer.queuedFrames.get(0);
    assertThat(queuedFrame.glTextureInfo).isEqualTo(outputTexture);
    assertThat(queuedFrame.presentationTimeUs).isEqualTo(1000L);
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void onCurrentOutputStreamEnded_signalsEndOfStreamToDownstream() {
    glShaderProgramAdapter.onCurrentOutputStreamEnded();
    assertThat(downstreamConsumer.endOfStreamSignaled).isTrue();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void signalEndOfStream_signalsEndOfInputStreamToShaderProgram() {
    glShaderProgramAdapter.signalEndOfStream();
    assertThat(fakeGlShaderProgram.endOfInputStreamSignaled).isTrue();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void shaderProgramError_propagatedToErrorConsumer() {
    VideoFrameProcessingException exception = new VideoFrameProcessingException("test error");
    fakeGlShaderProgram.forceException(exception);
    assertThat(errorReference.get()).isEqualTo(exception);
  }

  @Test
  public void close_releasesInFlightAndPendingOutputFrames() throws Exception {
    AtomicBoolean inputFrameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, inputFrameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    // Force downstream capacity to 0 so output frame becomes pending in glshaderProgramAdapter.
    downstreamConsumer.setCapacity(0);
    int outputSize = TEXTURE_SIZE * 4;
    GlTextureInfo outputTexture =
        new GlTextureInfo(
            /* texId= */ 101,
            /* fboId= */ -1,
            /* rboId= */ -1,
            /* width= */ outputSize,
            /* height= */ outputSize);
    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, /* presentationTimeUs= */ 1000L);

    assertThat(inputFrameReleased.get()).isFalse();
    assertThat(fakeGlShaderProgram.releasedFrames).isEmpty();

    glShaderProgramAdapter.close();

    assertThat(inputFrameReleased.get()).isTrue();
    assertThat(fakeGlShaderProgram.releasedFrames).containsExactly(outputTexture);
  }

  @Test
  public void setOutput_whenFramesAreInFlight_throwsIllegalStateException() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    FakeGlTextureFrameConsumer newDownstreamConsumer = new FakeGlTextureFrameConsumer();

    assertThrows(
        IllegalStateException.class, () -> glShaderProgramAdapter.setOutput(newDownstreamConsumer));
  }

  @Test
  public void queue_repetitivelyWithoutCapacity_executesLastWakeupListener() {
    AtomicBoolean frame1Released = new AtomicBoolean();
    GlTextureFrame inputFrame1 =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frame1Released);
    AtomicBoolean frame2Released = new AtomicBoolean();
    GlTextureFrame inputFrame2 =
        createTestFrame(/* texId= */ 2, /* timestampUs= */ 2000L, frame2Released);
    AtomicBoolean frame3Released = new AtomicBoolean();
    GlTextureFrame inputFrame3 =
        createTestFrame(/* texId= */ 3, /* timestampUs= */ 3000L, frame3Released);

    AtomicBoolean wakeup2Called = new AtomicBoolean();
    AtomicBoolean wakeup3Called = new AtomicBoolean();

    // First frame consumes the single capacity.
    assertThat(glShaderProgramAdapter.queue(inputFrame1, directExecutor(), () -> {})).isTrue();
    // Queueing frame2 should fail
    assertThat(
            glShaderProgramAdapter.queue(
                inputFrame2, directExecutor(), /* wakeupListener= */ () -> wakeup2Called.set(true)))
        .isFalse();
    assertThat(
            glShaderProgramAdapter.queue(
                inputFrame3, directExecutor(), /* wakeupListener= */ () -> wakeup3Called.set(true)))
        .isFalse();

    // Signal that a frame is processed to trigger the pending wakeup listener.
    glShaderProgramAdapter.onInputFrameProcessed(inputFrame1.glTextureInfo);
    glShaderProgramAdapter.onReadyToAcceptInputFrame();

    assertThat(wakeup2Called.get()).isFalse();
    assertThat(wakeup3Called.get()).isTrue();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void onOutputFrameAvailable_whenDownstreamThrowsException_propagatesToErrorConsumer() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    VideoFrameProcessingException exception = new VideoFrameProcessingException("downstream error");
    downstreamConsumer.setExceptionToThrowOnQueue(exception);

    GlTextureInfo outputTexture =
        new GlTextureInfo(/* texId= */ 101, -1, -1, TEXTURE_SIZE, TEXTURE_SIZE);

    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, /* presentationTimeUs= */ 1000L);

    assertThat(errorReference.get()).isEqualTo(exception);
  }

  @Test
  public void queueDownstream_whenDownstreamThrowsException_propagatesToErrorConsumer() {
    AtomicBoolean frameReleased = new AtomicBoolean();
    GlTextureFrame inputFrame =
        createTestFrame(/* texId= */ 1, /* timestampUs= */ 1000L, frameReleased);
    assertThat(glShaderProgramAdapter.queue(inputFrame, directExecutor(), () -> {})).isTrue();

    // Force output frames become pending in glShaderProgramAdapter.
    downstreamConsumer.setCapacity(0);

    GlTextureInfo outputTexture =
        new GlTextureInfo(/* texId= */ 101, -1, -1, TEXTURE_SIZE, TEXTURE_SIZE);
    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, /* presentationTimeUs= */ 1000L);

    VideoFrameProcessingException exception =
        new VideoFrameProcessingException("downstream error on wakeup");
    downstreamConsumer.setExceptionToThrowOnQueue(exception);

    // Restore capacity, which triggers onWakeupListener
    downstreamConsumer.setCapacity(1);

    assertThat(errorReference.get()).isEqualTo(exception);
  }

  private static GlTextureFrame createTestFrame(
      int texId, long timestampUs, AtomicBoolean released) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                texId,
                /* fboId= */ -1,
                /* rboId= */ -1,
                /* width= */ TEXTURE_SIZE,
                /* height= */ TEXTURE_SIZE),
            directExecutor(),
            textureInfo -> released.set(true))
        .setPresentationTimeUs(timestampUs)
        .setFormat(new Format.Builder().setWidth(TEXTURE_SIZE).setHeight(TEXTURE_SIZE).build())
        .build();
  }

  private static final class FakeGlObjectsProvider implements GlObjectsProvider {
    @Override
    public EGLContext createEglContext(
        EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EGLSurface createEglSurface(
        EGLDisplay eglDisplay,
        Object surface,
        @ColorTransfer int colorTransfer,
        boolean isEncoderInputSurface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EGLSurface createFocusedPlaceholderEglSurface(
        EGLContext eglContext, EGLDisplay eglDisplay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GlTextureInfo createBuffersForTexture(int texId, int width, int height) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void release(EGLDisplay eglDisplay) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeGlTextureFrameConsumer implements GlTextureFrameConsumer {

    final List<GlTextureFrame> queuedFrames;

    @Nullable private Runnable pendingWakeupListener;
    private int capacity = 1;
    boolean endOfStreamSignaled;
    @Nullable private VideoFrameProcessingException exceptionToThrowOnQueue;

    FakeGlTextureFrameConsumer() {
      queuedFrames = new ArrayList<>();
    }

    void setCapacity(int capacity) {
      this.capacity = capacity;
      if (pendingWakeupListener != null) {
        pendingWakeupListener.run();
      }
    }

    void setExceptionToThrowOnQueue(VideoFrameProcessingException exception) {
      this.exceptionToThrowOnQueue = exception;
    }

    @Override
    public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
        throws VideoFrameProcessingException {
      if (exceptionToThrowOnQueue != null) {
        throw exceptionToThrowOnQueue;
      }
      if (capacity > 0) {
        capacity--;
        queuedFrames.add(frame);
        return true;
      }
      pendingWakeupListener = wakeupListener;
      return false;
    }

    @Override
    public void signalEndOfStream() {
      endOfStreamSignaled = true;
    }

    @Override
    public void close() {}
  }

  private static final class FakeGlShaderProgram implements GlShaderProgram {
    static class QueuedFrameInfo {
      final GlTextureInfo textureInfo;
      final long presentationTimeUs;

      QueuedFrameInfo(GlTextureInfo textureInfo, long presentationTimeUs) {
        this.textureInfo = textureInfo;
        this.presentationTimeUs = presentationTimeUs;
      }
    }

    final List<QueuedFrameInfo> queuedFrames;
    final List<GlTextureInfo> releasedFrames;
    private InputListener inputListener;
    @Nullable private ErrorListener errorListener;
    boolean endOfInputStreamSignaled;

    FakeGlShaderProgram() {
      queuedFrames = new ArrayList<>();
      releasedFrames = new ArrayList<>();
      endOfInputStreamSignaled = false;
      inputListener = new InputListener() {};
    }

    void forceException(VideoFrameProcessingException e) {
      if (errorListener != null) {
        errorListener.onError(e);
      }
    }

    @Override
    public void setInputListener(InputListener inputListener) {
      this.inputListener = inputListener;
      // Starts with capacity = 1
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void setOutputListener(OutputListener outputListener) {}

    @Override
    public void setErrorListener(Executor executor, ErrorListener errorListener) {
      this.errorListener = errorListener;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      queuedFrames.add(new QueuedFrameInfo(inputTexture, presentationTimeUs));
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {
      releasedFrames.add(outputTexture);
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      endOfInputStreamSignaled = true;
    }

    @Override
    public void flush() {
      inputListener.onFlush();
    }

    @Override
    public void release() {}
  }
}
