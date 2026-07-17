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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_ITEM_EFFECTS;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlShaderProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlTextureFrameProcessorChain}. */
@RunWith(AndroidJUnit4.class)
public final class GlTextureFrameProcessorChainTest {

  private final Context context = getApplicationContext();

  private FakeGlTextureFrameConsumer downstreamFrameConsumer;
  private ListeningExecutorService glExecutorService;
  private GlTextureFrameProcessorChain glTextureFrameProcessorChain;

  @Before
  public void setUp() {
    downstreamFrameConsumer = new FakeGlTextureFrameConsumer(/* frameWriter= */ null);
    glExecutorService = listeningDecorator(Util.newSingleThreadExecutor("Effect:GlThread"));

    glTextureFrameProcessorChain =
        new GlTextureFrameProcessorChain(
            context,
            new DefaultGlObjectsProvider(),
            glExecutorService,
            /* errorConsumer= */ e -> {},
            downstreamFrameConsumer,
            KEY_ITEM_EFFECTS);
  }

  @After
  public void tearDown() throws VideoFrameProcessingException {
    glTextureFrameProcessorChain.close();
    FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
  }

  @Test
  public void queue_withEmptyEffects_setsDownstreamAsFirstConsumer() throws Exception {
    GlTextureFrame frame = createGlTextureFrameWithEffects(ImmutableList.of());
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();

    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(downstreamFrameConsumer.lastReceivedFrame).isSameInstanceAs(frame);
  }

  @Test
  public void queue_withEffects_createsProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram1 = new FakeGlShaderProgram();
    FakeGlShaderProgram fakeGlShaderProgram2 = new FakeGlShaderProgram();
    GlEffect fakeEffect1 = (context, useHdr) -> fakeGlShaderProgram1;
    GlEffect fakeEffect2 = (context, useHdr) -> fakeGlShaderProgram2;

    GlTextureFrame frame =
        createGlTextureFrameWithEffects(ImmutableList.of(fakeEffect1, fakeEffect2));

    // Process the frame.
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram1.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram2.framesReceived).isEqualTo(1);
    // Finally goes to downstream
    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(1);
  }

  @Test
  public void queue_withDifferentEffects_replacesProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram1 = new FakeGlShaderProgram();
    GlEffect fakeEffect1 = (context, useHdr) -> fakeGlShaderProgram1;
    GlTextureFrame frame1 = createGlTextureFrameWithEffects(ImmutableList.of(fakeEffect1));

    FakeGlShaderProgram fakeGlShaderProgram2 = new FakeGlShaderProgram();
    GlEffect fakeEffect2 = (context, useHdr) -> fakeGlShaderProgram2;
    GlTextureFrame frame2 = createGlTextureFrameWithEffects(ImmutableList.of(fakeEffect2));

    // Queue frame1. It configures with fakeEffect1 and processes.
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame1, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    // Queue frame2. It should reconfigure with fakeEffect2 (closing fakeEffect1) and process.
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame2, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram1.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram2.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram1.isReleased).isTrue(); // Replaced program is closed
    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(2);
  }

  @Test
  public void queue_withException_closesPartiallyCreatedProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeEffect = (context, useHdr) -> fakeGlShaderProgram;

    GlEffect failingEffect =
        (context, useHdr) -> {
          throw new VideoFrameProcessingException("Test error");
        };

    GlTextureFrame frame =
        createGlTextureFrameWithEffects(ImmutableList.of(fakeEffect, failingEffect));

    assertThrows(
        VideoFrameProcessingException.class,
        () ->
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}));
    assertThat(fakeGlShaderProgram.isReleased).isTrue();
  }

  @Test
  public void signalEndOfStream_delegatesToFirstConsumer() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeEffect = (context, useHdr) -> fakeGlShaderProgram;

    // We must queue a frame to trigger the initial configuration of the chain.
    GlTextureFrame frame = createGlTextureFrameWithEffects(ImmutableList.of(fakeEffect));
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    glTextureFrameProcessorChain.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(downstreamFrameConsumer.signalEndOfStreamCalled).isTrue();
  }

  private void waitUntilGlThreadFinishes() throws ExecutionException, InterruptedException {
    glExecutorService.submit(() -> {}).get();
  }

  private static GlTextureFrame createGlTextureFrameWithEffects(List<Effect> effects) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ 1,
                /* fboId= */ -1,
                /* rboId= */ -1,
                /* width= */ 100,
                /* height= */ 100),
            directExecutor(),
            /* releaseTextureCallback= */ info -> {})
        .setPresentationTimeUs(0)
        .setMetadata(ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.copyOf(effects)))
        .build();
  }

  @Test
  public void queue_withSizeChangingEffect_propagatesNewSizeDownstream() throws Exception {
    int newWidth = 200;
    int newHeight = 300;
    SizeChangingGlShaderProgram sizeChangingShaderProgram =
        new SizeChangingGlShaderProgram(newWidth, newHeight);
    GlEffect sizeChangingEffect = (context, useHdr) -> sizeChangingShaderProgram;

    GlTextureFrame frame = createGlTextureFrameWithEffects(ImmutableList.of(sizeChangingEffect));

    // Process the frame.
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(downstreamFrameConsumer.lastReceivedFrame.format.width).isEqualTo(newWidth);
    assertThat(downstreamFrameConsumer.lastReceivedFrame.format.height).isEqualTo(newHeight);
  }

  private static final class SizeChangingGlShaderProgram extends FakeGlShaderProgram {
    private final int outputWidth;
    private final int outputHeight;

    SizeChangingGlShaderProgram(int outputWidth, int outputHeight) {
      this.outputWidth = outputWidth;
      this.outputHeight = outputHeight;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      GlTextureInfo sizeChangedInputTexture =
          new GlTextureInfo(
              /* texId= */ inputTexture.texId,
              /* fboId= */ inputTexture.fboId,
              /* rboId= */ inputTexture.rboId,
              /* width= */ outputWidth,
              /* height= */ outputHeight);
      super.queueInputFrame(glObjectsProvider, sizeChangedInputTexture, presentationTimeUs);
    }
  }
}
