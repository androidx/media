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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Utility class containing fakes for GlFrameProcessor testing. */
public final class GlFrameProcessorTestUtil {

  private GlFrameProcessorTestUtil() {}

  /** A no-op {@link FrameWriter} that records the number of input frames. */
  public static final class NoOpFrameWriter implements FrameWriter {
    int queuedFrames;
    boolean signalEndOfStreamCalled;

    @Override
    public Info getInfo() {
      return (format, usage) -> true;
    }

    @Override
    public void configure(Format format, long usage) {}

    @Nullable
    @Override
    public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
      return null;
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
      queuedFrames++;
    }

    @Override
    public void signalEndOfStream() {
      signalEndOfStreamCalled = true;
    }

    @Override
    public void close() {}
  }

  /** Fake implementation of {@link GlTextureFrameConsumer} for testing. */
  public static final class FakeGlTextureFrameConsumer implements GlTextureFrameConsumer {

    public boolean shouldAcceptIncomingFrames;
    public int framesReceived;
    public boolean signalEndOfStreamCalled;
    @Nullable public Runnable wakeupListener;
    @Nullable public GlTextureFrame lastReceivedFrame;
    @Nullable public FrameWriter frameWriter;
    @Nullable public VideoFrameProcessingException exceptionToThrowOnQueueing;
    @Nullable public RuntimeException runtimeExceptionToThrow;

    public FakeGlTextureFrameConsumer(@Nullable FrameWriter frameWriter) {
      this.frameWriter = frameWriter;
      shouldAcceptIncomingFrames = true;
    }

    @Override
    public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
        throws VideoFrameProcessingException {
      if (exceptionToThrowOnQueueing != null) {
        throw exceptionToThrowOnQueueing;
      }
      if (runtimeExceptionToThrow != null) {
        throw runtimeExceptionToThrow;
      }
      if (!shouldAcceptIncomingFrames) {
        this.wakeupListener = wakeupListener;
        return false;
      }
      lastReceivedFrame = frame;
      framesReceived++;
      if (frameWriter != null) {
        frameWriter.queueInputFrame(null, null);
      }
      frame.release(/* releaseFence= */ null);
      return true;
    }

    @Override
    public void signalEndOfStream() {
      signalEndOfStreamCalled = true;
      if (frameWriter != null) {
        frameWriter.signalEndOfStream();
      }
    }

    @Override
    public void close() {}
  }

  /** Fake {@link GlShaderProgram} for testing. */
  public static final class FakeGlShaderProgram implements GlShaderProgram {
    public int framesReceived;
    public boolean signalEndOfCurrentInputStreamCalled;
    public boolean delayReadyToAcceptInputFrame;
    public boolean isReleased;
    private InputListener inputListener;
    private OutputListener outputListener;

    FakeGlShaderProgram() {
      inputListener = new InputListener() {};
      outputListener = new OutputListener() {};
    }

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
    public void setErrorListener(Executor executor, ErrorListener errorListener) {}

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      framesReceived++;
      outputListener.onOutputFrameAvailable(
          new GlTextureInfo(
              inputTexture.texId + 1,
              inputTexture.fboId + 1,
              C.INDEX_UNSET,
              inputTexture.width,
              inputTexture.height),
          presentationTimeUs);
      inputListener.onInputFrameProcessed(inputTexture);
      if (!delayReadyToAcceptInputFrame) {
        inputListener.onReadyToAcceptInputFrame();
      }
    }

    public void signalReadyToAcceptInputFrame() {
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {}

    @Override
    public void signalEndOfCurrentInputStream() {
      signalEndOfCurrentInputStreamCalled = true;
      outputListener.onCurrentOutputStreamEnded();
    }

    @Override
    public void flush() {
      inputListener.onFlush();
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void release() {
      isReleased = true;
    }
  }

  /** Fake {@link HardwareBufferFrame} for testing. */
  public static final class FakeHardwareBufferFrame implements HardwareBufferFrame {
    private final ImmutableMap<String, Object> metadata;

    public FakeHardwareBufferFrame(Map<String, Object> metadata) {
      this.metadata = ImmutableMap.copyOf(metadata);
    }

    @Override
    public HardwareBuffer getHardwareBuffer() {
      return null;
    }

    @Override
    public long getContentTimeUs() {
      return 0;
    }

    @Override
    public Format getFormat() {
      return new Format.Builder().setWidth(100).setHeight(100).build();
    }

    @Override
    public ImmutableMap<String, Object> getMetadata() {
      return metadata;
    }

    @Override
    public HardwareBufferFrame.Builder buildUpon() {
      return null;
    }
  }

  /** Fake {@link DefaultGlFrameProcessor.HardwareBufferConverter} for testing. */
  public static final class FakeHardwareBufferConverter
      implements DefaultGlFrameProcessor.HardwareBufferConverter {
    public int framesReceived;
    public int framesWithGlResourceReleased;

    @Override
    public GlTextureFrame convert(
        HardwareBufferFrame hardwareBufferFrame,
        Executor glExecutor,
        Executor listenerExecutor,
        FrameProcessor.Listener listener) {
      framesReceived++;
      return new GlTextureFrame.Builder(
              new GlTextureInfo(
                  /* texId= */ 1,
                  /* fboId= */ -1,
                  /* rboId= */ -1,
                  /* width= */ 100,
                  /* height= */ 100),
              /* releaseTextureExecutor= */ directExecutor(),
              /* releaseTextureCallback= */ info -> {
                if (listener != null) {
                  listenerExecutor.execute(
                      () ->
                          listener.onFrameProcessed(
                              hardwareBufferFrame, /* onCompleteFence= */ null));
                }
              })
          .setPresentationTimeUs(hardwareBufferFrame.getContentTimeUs())
          .setFormat(hardwareBufferFrame.getFormat())
          .setMetadata(hardwareBufferFrame.getMetadata())
          .build();
    }

    @Override
    public void releaseGlResources(HardwareBufferFrame hardwareBufferFrame) {
      framesWithGlResourceReleased++;
    }

    @Override
    public void close() {}
  }
}
