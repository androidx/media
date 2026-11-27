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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeCompositorGlProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlObjectsProvider;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultGlTextureFrameCompositingProcessor}. */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4.class)
public final class DefaultGlTextureFrameCompositingProcessorTest {

  private static final int WIDTH = 100;
  private static final int HEIGHT = 200;
  private static final int FAKE_GL_TEXTURE_ID = 999;
  private static final long TIMEOUT_MS = 1_000;

  private DefaultGlTextureFrameCompositingProcessor compositingProcessor;
  private FakeGlTextureFrameConsumer downstreamConsumer;
  private AtomicReference<VideoFrameProcessingException> errorReference;
  private FakeCompositorGlProgram compositorGlProgram;

  @Before
  public void setUp() {
    downstreamConsumer = new FakeGlTextureFrameConsumer(/* frameWriter= */ null);
    errorReference = new AtomicReference<>();
    compositorGlProgram = new FakeCompositorGlProgram();

    compositingProcessor =
        new DefaultGlTextureFrameCompositingProcessor(
            new FakeGlObjectsProvider(),
            new TexturePool(
                /* textureAllocator= */ (width, height, useHighPrecisionColorComponents) ->
                    FAKE_GL_TEXTURE_ID,
                /* useHighPrecisionColorComponents= */ false,
                /* capacity= */ 1),
            errorReference::set,
            compositorGlProgram,
            directExecutor(),
            downstreamConsumer);
  }

  @After
  public void tearDown() throws Exception {
    compositingProcessor.close();
  }

  @Test
  public void queue_singleInputFrame_passesThroughUnmodified() throws Exception {
    GlTextureFrame frame = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);

    assertThat(compositingProcessor.queue(ImmutableList.of(frame), directExecutor(), () -> {}))
        .isTrue();

    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);
  }

  @Test
  public void queue_multipleFrames_compositesAndPassesToDownstream() throws Exception {
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame1, frame2), directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);
  }

  @Test
  public void queue_multipleFramesWhenDownstreamRejectsInput_retriesOnGlThread() throws Exception {
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    ExecutorService glExecutorService = Util.newSingleThreadExecutor("Test:GlThread");
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    try (DefaultGlTextureFrameCompositingProcessor processor =
        new DefaultGlTextureFrameCompositingProcessor(
            new FakeGlObjectsProvider(),
            new TexturePool(
                /* textureAllocator= */ (width, height, useHighPrecisionColorComponents) ->
                    FAKE_GL_TEXTURE_ID,
                /* useHighPrecisionColorComponents= */ false,
                /* capacity= */ 1),
            errorReference::set,
            compositorGlProgram,
            glExecutorService,
            downstreamConsumer)) {

      boolean queued =
          glExecutorService
              .submit(
                  () ->
                      processor.queue(ImmutableList.of(frame1, frame2), directExecutor(), () -> {}))
              .get(TIMEOUT_MS, MILLISECONDS);

      assertThat(queued).isTrue();
      assertThat(downstreamConsumer.framesReceived).isEqualTo(0);
      assertThat(downstreamConsumer.listenerExecutor).isEqualTo(glExecutorService);

      downstreamConsumer.shouldAcceptIncomingFrames = true;
      downstreamConsumer.triggerWakeup();
      glExecutorService.submit(() -> {}).get(); // Blocks until wakeup runs on glExecutorService
      assertThat(downstreamConsumer.framesReceived).isEqualTo(1);
    } finally {
      FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
    }
  }

  @Test
  public void signalEndOfStream_whenDownstreamRejects_defersEosUntilQueued() throws Exception {
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame1, frame2), directExecutor(), () -> {}))
        .isTrue();

    compositingProcessor.signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse(); // Deferred

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_whenDownstreamThrowsVideoFrameProcessingException_routesToErrorConsumer()
      throws Exception {
    VideoFrameProcessingException exception = new VideoFrameProcessingException("test");
    downstreamConsumer.exceptionToThrowOnQueueing = exception;
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame1, frame2), directExecutor(), () -> {}))
        .isTrue();
    assertThat(errorReference.get()).isSameInstanceAs(exception);
  }

  @Test
  public void queue_whenDownstreamThrowsRuntimeExceptionAndWakeup_routesToErrorConsumer()
      throws Exception {
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    RuntimeException exception = new RuntimeException("test");
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    boolean unused =
        compositingProcessor.queue(ImmutableList.of(frame1, frame2), directExecutor(), () -> {});

    // Queueing while buffer is full returns false
    GlTextureFrame frame3 = createGlTextureFrame(/* texId= */ 2, /* sequenceIndex= */ 0);
    GlTextureFrame frame4 = createGlTextureFrame(/* texId= */ 3, /* sequenceIndex= */ 1);
    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame3, frame4), directExecutor(), () -> {}))
        .isFalse();

    downstreamConsumer.runtimeExceptionToThrowOnQueueing = exception;
    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(errorReference.get()).hasCauseThat().isSameInstanceAs(exception);
  }

  @Test
  public void queue_whenDownstreamThrowsRuntimeException_routesToErrorConsumer() throws Exception {
    RuntimeException exception = new RuntimeException("test");
    downstreamConsumer.runtimeExceptionToThrowOnQueueing = exception;
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    boolean queued =
        compositingProcessor.queue(ImmutableList.of(frame1, frame2), directExecutor(), () -> {});

    assertThat(queued).isTrue();
    assertThat(errorReference.get()).hasCauseThat().isSameInstanceAs(exception);
  }

  @Test
  public void queue_multipleFrames_preservesPrimaryFrameMetadata() throws Exception {
    GlTextureFrame frame1 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    /* texId= */ 0,
                    /* fboId= */ C.INDEX_UNSET,
                    /* rboId= */ C.INDEX_UNSET,
                    /* width= */ WIDTH,
                    /* height= */ HEIGHT),
                directExecutor(),
                /* releaseTextureCallback= */ info -> {})
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITOR_SETTINGS,
                    VideoCompositorSettings.DEFAULT,
                    DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX,
                    0,
                    "CUSTOM_KEY",
                    "CUSTOM_VALUE"))
            .build();

    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);

    boolean queued =
        compositingProcessor.queue(ImmutableList.of(frame1, frame2), directExecutor(), () -> {});

    assertThat(queued).isTrue();
    assertThat(downstreamConsumer.lastReceivedFrame).isNotNull();
    assertThat(downstreamConsumer.lastReceivedFrame.getMetadata().get("CUSTOM_KEY"))
        .isEqualTo("CUSTOM_VALUE");
  }

  @Test
  public void queue_afterSignalEndOfStream_doesNotPrematurelySignalEos() throws Exception {
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 1);
    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame1, frame2), directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);

    compositingProcessor.signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();

    downstreamConsumer.signalEndOfStreamCalled = false;

    GlTextureFrame frame3 = createGlTextureFrame(/* texId= */ 2, /* sequenceIndex= */ 0);
    GlTextureFrame frame4 = createGlTextureFrame(/* texId= */ 3, /* sequenceIndex= */ 1);
    assertThat(
            compositingProcessor.queue(
                ImmutableList.of(frame3, frame4), directExecutor(), () -> {}))
        .isTrue();

    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse();

    compositingProcessor.signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_singleFrameAfterSignalEndOfStream_doesNotPrematurelySignalEos()
      throws Exception {
    GlTextureFrame frame1 = createGlTextureFrame(/* texId= */ 0, /* sequenceIndex= */ 0);
    assertThat(compositingProcessor.queue(ImmutableList.of(frame1), directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);

    compositingProcessor.signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();

    downstreamConsumer.signalEndOfStreamCalled = false;

    GlTextureFrame frame2 = createGlTextureFrame(/* texId= */ 1, /* sequenceIndex= */ 0);
    assertThat(compositingProcessor.queue(ImmutableList.of(frame2), directExecutor(), () -> {}))
        .isTrue();

    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse();

    compositingProcessor.signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  private static GlTextureFrame createGlTextureFrame(int texId, int sequenceIndex) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ texId,
                /* fboId= */ C.INDEX_UNSET,
                /* rboId= */ C.INDEX_UNSET,
                /* width= */ WIDTH,
                /* height= */ HEIGHT),
            directExecutor(),
            /* releaseTextureCallback= */ info -> {})
        .setMetadata(
            ImmutableMap.of(
                KEY_COMPOSITOR_SETTINGS,
                VideoCompositorSettings.DEFAULT,
                DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX,
                sequenceIndex))
        .build();
  }
}
