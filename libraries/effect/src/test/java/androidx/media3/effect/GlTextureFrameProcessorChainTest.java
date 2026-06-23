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
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlShaderProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
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
            downstreamFrameConsumer);
  }

  @After
  public void tearDown() throws VideoFrameProcessingException {
    glTextureFrameProcessorChain.close();
    FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
  }

  @Test
  public void configure_withEmptyEffects_setsDownstreamAsFirstConsumer() throws Exception {
    glTextureFrameProcessorChain.configure(ImmutableList.of());

    GlTextureFrame frame = createGlTextureFrame();
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();

    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(downstreamFrameConsumer.lastReceivedFrame).isSameInstanceAs(frame);
  }

  @Test
  public void configure_withEffects_createsProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram1 = new FakeGlShaderProgram();
    FakeGlShaderProgram fakeGlShaderProgram2 = new FakeGlShaderProgram();
    GlEffect fakeEffect1 = (context, useHdr) -> fakeGlShaderProgram1;
    GlEffect fakeEffect2 = (context, useHdr) -> fakeGlShaderProgram2;

    glTextureFrameProcessorChain.configure(ImmutableList.of(fakeEffect1, fakeEffect2));
    GlTextureFrame frame = createGlTextureFrame();

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
  public void configure_withDifferentEffects_replacesProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram1 = new FakeGlShaderProgram();
    GlEffect fakeEffect1 = (context, useHdr) -> fakeGlShaderProgram1;
    glTextureFrameProcessorChain.configure(ImmutableList.of(fakeEffect1));

    FakeGlShaderProgram fakeGlShaderProgram2 = new FakeGlShaderProgram();
    GlEffect fakeEffect2 = (context, useHdr) -> fakeGlShaderProgram2;

    // This should close the first chain and create a new one.
    glTextureFrameProcessorChain.configure(ImmutableList.of(fakeEffect2));

    GlTextureFrame frame = createGlTextureFrame();
    assertThat(
            glTextureFrameProcessorChain.queue(
                frame, glExecutorService, /* wakeupListener= */ () -> {}))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram1.framesReceived).isEqualTo(0);
    assertThat(fakeGlShaderProgram2.framesReceived).isEqualTo(1);
    assertThat(downstreamFrameConsumer.framesReceived).isEqualTo(1);
  }

  @Test
  public void configure_withException_closesPartiallyCreatedProcessors() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeEffect = (context, useHdr) -> fakeGlShaderProgram;

    GlEffect failingEffect =
        (context, useHdr) -> {
          throw new VideoFrameProcessingException("Test error");
        };

    assertThrows(
        VideoFrameProcessingException.class,
        () -> glTextureFrameProcessorChain.configure(ImmutableList.of(fakeEffect, failingEffect)));
    assertThat(fakeGlShaderProgram.isReleased).isTrue();
  }

  @Test
  public void signalEndOfStream_delegatesToFirstConsumer() throws Exception {
    FakeGlShaderProgram fakeGlShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeEffect = (context, useHdr) -> fakeGlShaderProgram;
    glTextureFrameProcessorChain.configure(ImmutableList.of(fakeEffect));

    glTextureFrameProcessorChain.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(downstreamFrameConsumer.signalEndOfStreamCalled).isTrue();
  }

  private void waitUntilGlThreadFinishes() throws ExecutionException, InterruptedException {
    glExecutorService.submit(() -> {}).get();
  }

  private static GlTextureFrame createGlTextureFrame() {
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
        .build();
  }
}
