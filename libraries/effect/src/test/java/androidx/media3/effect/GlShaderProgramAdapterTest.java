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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_FRAME_DISCONTINUITY_NUMBER;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.annotation.Nullable;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
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

  @Test
  public void queue_withHigherDiscontinuityNumber_triggersFlush() {
    GlTextureFrame frame1 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 1,
            /* timestampUs= */ 1000L,
            /* discontinuityNumber= */ 0,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();

    // Reset flush flag, as transitioning from C.INDEX_UNSET to 0 already triggered the initial
    // flush.
    fakeGlShaderProgram.flushed = false;

    GlTextureFrame frame2 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 2,
            /* timestampUs= */ 2000L,
            /* discontinuityNumber= */ 1,
            /* released= */ null);

    // Queuing frame2 with higher discontinuity number (1 > 0) triggers a flush.
    assertThat(glShaderProgramAdapter.queue(frame2, directExecutor(), () -> {})).isTrue();

    assertThat(fakeGlShaderProgram.flushed).isTrue();
  }

  @Test
  public void queue_withLowerDiscontinuityNumber_ignoresAndReleasesFrame() {
    GlTextureFrame frame1 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 1,
            /* timestampUs= */ 1000L,
            /* discontinuityNumber= */ 1,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();

    // Current stream discontinuity number becomes 1.
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);

    AtomicBoolean frame2Released = new AtomicBoolean();
    GlTextureFrame frame2 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 2, /* timestampUs= */ 2000L, /* discontinuityNumber= */ 0, frame2Released);

    // Frame with lower discontinuity number is queued. It is ignored and immediately released.
    boolean frame2Queued = glShaderProgramAdapter.queue(frame2, directExecutor(), () -> {});

    assertThat(frame2Queued).isTrue();
    assertThat(fakeGlShaderProgram.flushed).isTrue();
    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(1);
    assertThat(frame2Released.get()).isTrue();
  }

  @Test
  public void queue_withHigherDiscontinuityNumberThanPendingFrame_doesNotQueuePendingFrame() {
    GlTextureFrame frame1 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 1,
            /* timestampUs= */ 1000L,
            /* discontinuityNumber= */ 0,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();

    // Downstream has no capacity. Queueing frame2 with same discontinuity will block and trigger
    // wakeup.
    GlTextureFrame frame2 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 2,
            /* timestampUs= */ 2000L,
            /* discontinuityNumber= */ 0,
            /* released= */ null);
    AtomicBoolean wakeupCalled = new AtomicBoolean();
    boolean queued2 =
        glShaderProgramAdapter.queue(frame2, directExecutor(), () -> wakeupCalled.set(true));
    assertThat(queued2).isFalse();

    // Now queue frame3 with higher discontinuity number, triggering a flush.
    GlTextureFrame frame3 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 3,
            /* timestampUs= */ 3000L,
            /* discontinuityNumber= */ 1,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame3, directExecutor(), () -> {})).isTrue();

    // Seeking should have reset the shader program input capacity. If not flushed, the wakeup
    // listener should have been invoked. But after a flush, the wakeup listener of the old
    // stream (bearing a lower discontinuity number) should have been cleared and not invoked.
    assertThat(wakeupCalled.get()).isFalse();
  }

  @Test
  public void queue_afterDiscontinuityNumberIncrement_propagatesNewFormatCorrectly() {
    AtomicBoolean frame1Released = new AtomicBoolean();
    Format format1 =
        new Format.Builder()
            .setWidth(TEXTURE_SIZE)
            .setHeight(TEXTURE_SIZE)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();
    GlTextureFrame frame1 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    /* texId= */ 1, /* fboId= */ -1, /* rboId= */ -1, TEXTURE_SIZE, TEXTURE_SIZE),
                directExecutor(),
                textureInfo -> frame1Released.set(true))
            .setPresentationTimeUs(1000L)
            .setFormat(format1)
            .setMetadata(ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 0))
            .build();
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();

    // Seek occurs, queueing a new frame with higher discontinuity number and new dimensions/format.
    int newWidth = TEXTURE_SIZE * 2;
    int newHeight = TEXTURE_SIZE * 2;
    Format format2 =
        new Format.Builder()
            .setWidth(newWidth)
            .setHeight(newHeight)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    AtomicBoolean frame2Released = new AtomicBoolean();
    GlTextureFrame frame2 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    /* texId= */ 2, /* fboId= */ -1, /* rboId= */ -1, newWidth, newHeight),
                directExecutor(),
                textureInfo -> frame2Released.set(true))
            .setPresentationTimeUs(2000L)
            .setFormat(format2)
            .setMetadata(ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1))
            .build();
    assertThat(glShaderProgramAdapter.queue(frame2, directExecutor(), () -> {})).isTrue();

    // New output frame becomes available.
    int outputWidth = newWidth * 2;
    int outputHeight = newHeight * 2;
    GlTextureInfo outputTexture =
        new GlTextureInfo(
            /* texId= */ 102,
            /* fboId= */ -1,
            /* rboId= */ -1,
            /* width= */ outputWidth,
            /* height= */ outputHeight);
    glShaderProgramAdapter.onOutputFrameAvailable(outputTexture, /* presentationTimeUs= */ 2000L);

    // Verify that the format forwarded downstream is based on the new format format2.
    assertThat(downstreamConsumer.queuedFrames).hasSize(1);
    GlTextureFrame forwardedFrame = downstreamConsumer.queuedFrames.get(0);
    assertThat(forwardedFrame.glTextureInfo).isEqualTo(outputTexture);
    assertThat(forwardedFrame.presentationTimeUs).isEqualTo(2000L);
    assertThat(forwardedFrame.format.width).isEqualTo(outputWidth);
    assertThat(forwardedFrame.format.height).isEqualTo(outputHeight);
    assertThat(forwardedFrame.format.colorInfo).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
  }

  @Test
  public void queue_withHigherDiscontinuityNumber_releasesInFlightFrames() {
    AtomicBoolean frame1Released = new AtomicBoolean();
    GlTextureFrame frame1 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 1, /* timestampUs= */ 1000L, /* discontinuityNumber= */ 0, frame1Released);
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();
    assertThat(frame1Released.get()).isFalse();

    AtomicBoolean frame2Released = new AtomicBoolean();
    GlTextureFrame frame2 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 2, /* timestampUs= */ 2000L, /* discontinuityNumber= */ 1, frame2Released);

    // Queuing frame2 with higher discontinuity number triggers a flush, which should release
    // frame1.
    assertThat(glShaderProgramAdapter.queue(frame2, directExecutor(), () -> {})).isTrue();

    assertThat(frame1Released.get()).isTrue();
    assertThat(frame2Released.get()).isFalse();
  }

  @Test
  public void queue_multipleConsecutiveDiscontinuityNumberIncrements_flushesEachTime() {
    GlTextureFrame frame1 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 1,
            /* timestampUs= */ 1000L,
            /* discontinuityNumber= */ 0,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame1, directExecutor(), () -> {})).isTrue();

    // 1st stream change.
    fakeGlShaderProgram.flushed = false;
    GlTextureFrame frame2 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 2,
            /* timestampUs= */ 2000L,
            /* discontinuityNumber= */ 1,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame2, directExecutor(), () -> {})).isTrue();
    assertThat(fakeGlShaderProgram.flushed).isTrue();

    // 2nd stream change.
    fakeGlShaderProgram.flushed = false;
    GlTextureFrame frame3 =
        createTestFrameWithDiscontinuityNumber(
            /* texId= */ 3,
            /* timestampUs= */ 3000L,
            /* discontinuityNumber= */ 2,
            /* released= */ null);
    assertThat(glShaderProgramAdapter.queue(frame3, directExecutor(), () -> {})).isTrue();
    assertThat(fakeGlShaderProgram.flushed).isTrue();
  }

  private static GlTextureFrame createTestFrameWithDiscontinuityNumber(
      int texId, long timestampUs, int discontinuityNumber, @Nullable AtomicBoolean released) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                texId,
                /* fboId= */ -1,
                /* rboId= */ -1,
                /* width= */ TEXTURE_SIZE,
                /* height= */ TEXTURE_SIZE),
            directExecutor(),
            /* releaseTextureCallback= */ textureInfo -> {
              if (released != null) {
                released.set(true);
              }
            })
        .setPresentationTimeUs(timestampUs)
        .setFormat(new Format.Builder().setWidth(TEXTURE_SIZE).setHeight(TEXTURE_SIZE).build())
        .setMetadata(ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, discontinuityNumber))
        .build();
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
    boolean flushed;

    FakeGlShaderProgram() {
      queuedFrames = new ArrayList<>();
      releasedFrames = new ArrayList<>();
      endOfInputStreamSignaled = false;
      flushed = false;
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
      flushed = true;
      inputListener.onFlush();
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void release() {}
  }
}
