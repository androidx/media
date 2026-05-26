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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import android.content.Context;
import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor.FrameCompletionListener;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultGlFrameProcessor} using pluggable non-OpenGL fakes. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class DefaultGlFrameProcessorTest {

  private final Context context = getApplicationContext();

  private FakeHardwareBufferConverter fakeHardwareBufferConverter;
  private FakeGlShaderProgram fakeGlShaderProgram;
  private FakeGlTextureFrameConsumer fakeGlTextureFrameConsumer;
  private GlEffect fakeEffect;
  private DefaultGlFrameProcessor processor;
  private ListeningExecutorService glExecutorService;
  private NoOpFrameWriter frameWriter;

  @Before
  public void setUp() {
    frameWriter = new NoOpFrameWriter();
    fakeHardwareBufferConverter = new FakeHardwareBufferConverter();
    fakeGlShaderProgram = new FakeGlShaderProgram();
    fakeGlTextureFrameConsumer = new FakeGlTextureFrameConsumer(frameWriter);
    fakeEffect = (context, useHdr) -> fakeGlShaderProgram;
    glExecutorService = listeningDecorator(Util.newSingleThreadExecutor("Effect:GlThread"));

    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter);
    assertThat(processor).isNotNull();
  }

  @After
  public void tearDown() {
    if (processor != null) {
      processor.close();
    }
    if (glExecutorService != null) {
      FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
    }
  }

  @Test
  public void queue_hardwareBufferFrame_propagatesToAllPipelineComponents() throws Exception {
    Frame frame =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));

    boolean frameQueued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frameQueued).isTrue();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFramesWithAndWithoutEffects_propagatesToAllPipelineComponents()
      throws Exception {
    // 1. First sends a frame with the FakeEffect, verify the frame reaches the end.
    Frame frameWithEffect1 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));

    boolean frame1Queued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frameWithEffect1, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frame1Queued).isTrue();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    // 2. Send another frame that has no effect, verify the frame reaches the end.
    Frame frameWithoutEffect = new FakeHardwareBufferFrame(ImmutableMap.of());

    boolean frame2Queued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frameWithoutEffect, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frame2Queued).isTrue();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(2);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(2);
    assertThat(frameWriter.queuedFrames).isEqualTo(2);

    // 3. Send another frame with the fakeEffect, verify the frame reaches the end.
    Frame frameWithEffect2 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));

    boolean frame3Queued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frameWithEffect2, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frame3Queued).isTrue();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(3);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(2);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(3);
    assertThat(frameWriter.queuedFrames).isEqualTo(3);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_multipleInputFrames_processesFirstFrameAndInstantlyCompletesRemainingFrames()
      throws Exception {
    // TODO: b/505721737 - Remove when multi-sequence is supported.
    Frame frame0 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));
    Frame frame1 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame2 = new FakeHardwareBufferFrame(ImmutableMap.of());

    List<Frame> completedFrames = new ArrayList<>();
    boolean framesQueued =
        processor.queue(
            ImmutableList.of(
                new AsyncFrame(frame0, /* acquireFence= */ null),
                new AsyncFrame(frame1, /* acquireFence= */ null),
                new AsyncFrame(frame2, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> completedFrames.add(f));

    assertThat(framesQueued).isTrue();
    // frame0 comes last because frames 1 and 2 are released before processing frame0
    assertThat(completedFrames).containsExactly(frame1, frame2, frame0).inOrder();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrame_notifiesCompletionListenerWhenFrameIsProcessed()
      throws Exception {
    Frame frame =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));

    List<Frame> completedFrames = new ArrayList<>();
    boolean frameQueued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> completedFrames.add(f));

    assertThat(frameQueued).isTrue();
    assertThat(completedFrames).containsExactly(frame).inOrder();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrame_notifiesWakeupListener() throws Exception {
    fakeGlShaderProgram.delayReadyToAcceptInputFrame = true;
    Frame frame1 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));
    Frame frame2 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(DefaultGlFrameProcessor.KEY_EFFECTS, ImmutableList.of(fakeEffect)));

    boolean frame1Queued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frame1Queued).isTrue();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    ArrayList<Boolean> wakeupNotified = new ArrayList<>();
    boolean frame2Queued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> wakeupNotified.add(true),
            /* completionListener= */ (f, fence) -> {});

    assertThat(frame2Queued).isFalse();
    assertThat(wakeupNotified).isEmpty();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    glExecutorService.submit(() -> fakeGlShaderProgram.signalReadyToAcceptInputFrame()).get();
    waitUntilGlThreadFinishes();

    assertThat(wakeupNotified).containsExactly(true);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_whenDownstreamConsumerReturnsFalse_releasesGlResourcesOnHardwareBufferFrame() {
    fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
    Frame frame = new FakeHardwareBufferFrame(ImmutableMap.of());

    boolean frameQueued =
        processor.queue(
            ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null)),
            /* listenerExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (f, fence) -> {});

    assertThat(frameQueued).isFalse();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeHardwareBufferConverter.framesWithGlResourceReleased).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(0);
  }

  private void waitUntilGlThreadFinishes() throws ExecutionException, InterruptedException {
    glExecutorService.submit(() -> {}).get();
  }

  // TODO: b/505721737 - Consider moving fakes into a utility class.
  private static final class NoOpFrameWriter implements FrameWriter {
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

  private static final class FakeHardwareBufferConverter
      implements DefaultGlFrameProcessor.HardwareBufferConverter {
    int framesReceived;
    int framesWithGlResourceReleased;

    @Override
    public GlTextureFrame convert(
        HardwareBufferFrame hardwareBufferFrame,
        Executor glExecutor,
        Executor listenerExecutor,
        FrameCompletionListener completionListener) {
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
                if (completionListener != null) {
                  listenerExecutor.execute(
                      () ->
                          completionListener.onFrameProcessed(
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

  static final class FakeGlTextureFrameConsumer implements GlTextureFrameConsumer {
    private final FrameWriter frameWriter;
    int framesReceived;
    boolean signalEndOfStreamCalled;
    boolean shouldAcceptIncomingFrames;

    FakeGlTextureFrameConsumer(FrameWriter frameWriter) {
      this.frameWriter = frameWriter;
      shouldAcceptIncomingFrames = true;
    }

    @Override
    public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
      if (!shouldAcceptIncomingFrames) {
        return false;
      }
      framesReceived++;
      frameWriter.queueInputFrame(null, null);
      frame.release(/* releaseFence= */ null);
      return true;
    }

    @Override
    public void signalEndOfStream() {
      signalEndOfStreamCalled = true;
      frameWriter.signalEndOfStream();
    }

    @Override
    public void close() {}
  }

  private static final class FakeGlShaderProgram implements GlShaderProgram {
    int framesReceived;
    boolean signalEndOfCurrentInputStreamCalled;
    boolean delayReadyToAcceptInputFrame;
    private InputListener inputListener = new InputListener() {};
    private OutputListener outputListener = new OutputListener() {};

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

    void signalReadyToAcceptInputFrame() {
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
    public void release() {}
  }

  private static final class FakeHardwareBufferFrame implements HardwareBufferFrame {
    private final ImmutableMap<String, Object> metadata;

    FakeHardwareBufferFrame(Map<String, Object> metadata) {
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
}
