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
package androidx.media3.decoder.av1;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.EGLSurfaceTexture;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link Dav1dCodecAdapter}. */
@RunWith(AndroidJUnit4.class)
public final class Dav1dCodecAdapterAndroidTest {

  private static final String SDR_8BIT_AV1_ASSET = "media/mp4/sample_av1.mp4";
  private static final String HDR_10BIT_AV1_ASSET = "media/mp4/hdr10-av1.mp4";
  private static final int INITIAL_INPUT_BUFFER_SIZE = 4 * 1024;
  private static final int THREADS = 2;
  private static final int MAX_FRAME_DELAY = 2;

  /**
   * Minimum API level for tests that render to a {@link Surface}.
   *
   * <p>On the legacy x86 emulator images below API 30, locking a {@code HAL_PIXEL_FORMAT_YV12}
   * buffer via {@code ANativeWindow_lock} triggers a heap buffer overflow in {@code
   * gralloc.ranchu.so} ({@code gralloc_lock} allocates {@code width * height * 3} bytes for a
   * temporary buffer when {@code ANDROID_EMU_yuv_cache} is unavailable, but reads {@code GL_RGBA}
   * {@code width * height * 4} bytes into it). This corrupts the native heap and causes subsequent
   * crashes in libdav1d (such as {@code dav1d_mem_pool_pop} or {@code dav1d_ref_dec}). It does not
   * occur on API 30+ emulators or on real devices.
   */
  private static final int MIN_API_FOR_SURFACE_RENDERING = 30;

  private static final int WIDTH = 32;
  private static final int HEIGHT = 16;
  private static final int UV_WIDTH = WIDTH / 2;
  private static final int UV_HEIGHT = HEIGHT / 2;

  // 32x16 8-bit I420 AV1 keyframe OBU (Y=41, U=240, V=110).
  private static final byte[] av18bitI420Sample =
      new byte[] {
        18, 0, 10, 10, 0, 0, 0, 2, 31, -4, -38, -7, 0, 64, 50, 20, 16, 0, -128, 0, 0, 31, 124, -115,
        -27, 23, -103, 0, -44, -55, 65, -107, -13, 100, 19, 12
      };

  // 32x16 8-bit monochrome (I400) AV1 keyframe OBU (Y=128).
  private static final byte[] av18bitMonochromeSample =
      new byte[] {18, 0, 10, 9, 0, 0, 0, 2, 31, -4, -38, -7, 42, 50, 6, 16, 0, -128, 0, 31, -128};

  // Corrupt OBU header (obu_forbidden_bit = 1) rejected immediately by dav1d_send_data.
  private static final byte[] corruptObuSample =
      new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private @Nullable Surface surface;
  private @Nullable SurfaceTexture surfaceTexture;

  @Before
  public void setUp() {
    assertWithMessage("Dav1d library is not available").that(Dav1dLibrary.isAvailable()).isTrue();
  }

  @After
  public void tearDown() {
    try {
      if (surface != null) {
        surface.release();
        surface = null;
      }
    } finally {
      if (surfaceTexture != null) {
        surfaceTexture.release();
        surfaceTexture = null;
      }
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void createAdapter_withDefaultSettingsAndSurface_decodesAndRendersFrame()
      throws Exception {
    Format format = createAv1Format(WIDTH, HEIGHT);
    try (SurfaceFrameReceiver receiver = new SurfaceFrameReceiver()) {
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, receiver.getSurface());
      Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

      MediaCodecAdapter adapter = factory.createAdapter(configuration);
      try {
        int outputIndex =
            decodeAndDequeueFrameIndex(
                adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);
        MediaFormat outputFormat = adapter.getOutputFormat();
        adapter.releaseOutputBuffer(outputIndex, /* renderTimeStampNs= */ 10_000_000L);

        receiver.awaitFrameAvailable();
        assertThat(receiver.getReceivedFrameCount()).isEqualTo(1);
        assertThat(outputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(WIDTH);
        assertThat(outputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(HEIGHT);
      } finally {
        adapter.release();
      }
    }
  }

  @Test(timeout = 10_000)
  public void createAdapter_withCustomFactorySettings_appliesSettingsAndThreadPriority()
      throws Exception {
    Dav1dCodecAdapter adapter = null;
    AtomicReference<VideoDecoderOutputBuffer> renderedBuffer = new AtomicReference<>();
    try {
      Format format = createAv1Format(WIDTH, HEIGHT);
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, /* surface= */ null);
      Dav1dCodecAdapter.Factory factory =
          new Dav1dCodecAdapter.Factory()
              .setNumInputBuffers(2)
              .setNumOutputBuffers(2)
              .setInitialInputBufferSize(1024)
              .setThreads(Dav1dCodecAdapter.THREAD_COUNT_PERFORMANCE_CORES)
              .setMaxFrameDelay(1)
              .setUseCustomAllocator(true)
              .setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      CountDownLatch twoInputsReleasedLatch = new CountDownLatch(2);
      CountDownLatch twoOutputsAvailableLatch = new CountDownLatch(2);
      AtomicInteger callbackThreadPriority = new AtomicInteger(Integer.MIN_VALUE);

      adapter = (Dav1dCodecAdapter) factory.createAdapter(configuration);
      adapter.setOutput((VideoDecoderOutputBufferRenderer) renderedBuffer::set);
      boolean registered =
          adapter.registerOnBufferAvailableListener(
              new MediaCodecAdapter.OnBufferAvailableListener() {
                @Override
                public void onInputBufferAvailable() {
                  twoInputsReleasedLatch.countDown();
                }

                @Override
                public void onOutputBufferAvailable() {
                  callbackThreadPriority.set(Process.getThreadPriority(Process.myTid()));
                  twoOutputsAvailableLatch.countDown();
                }
              });
      int firstInputIndex = adapter.dequeueInputBufferIndex();
      ByteBuffer inputBuffer = checkNotNull(adapter.getInputBuffer(firstInputIndex));
      int inputBufferCapacity = inputBuffer.capacity();
      inputBuffer.put(av18bitI420Sample);
      adapter.queueInputBuffer(
          firstInputIndex,
          /* offset= */ 0,
          av18bitI420Sample.length,
          /* presentationTimeUs= */ 10_000,
          /* flags= */ 0);
      queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 20_000);
      boolean twoInputsReleased = twoInputsReleasedLatch.await(5, SECONDS);
      boolean twoOutputsAvailable = twoOutputsAvailableLatch.await(5, SECONDS);
      // Both output buffers are now queued, parking the decode thread so queuing 2 more samples
      // exhausts the 2-buffer input pool.
      queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 30_000);
      queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 40_000);
      int thirdInputIndex = adapter.dequeueInputBufferIndex();
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
      int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
      adapter.releaseOutputBuffer(outputIndex, /* render= */ true);

      assertThat(registered).isTrue();
      assertThat(inputBufferCapacity).isEqualTo(1024);
      assertThat(twoInputsReleased).isTrue();
      assertThat(twoOutputsAvailable).isTrue();
      assertThat(thirdInputIndex).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
      assertThat(callbackThreadPriority.get()).isEqualTo(Process.THREAD_PRIORITY_BACKGROUND);
      assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      assertThat(renderedBuffer.get()).isNotNull();
      VideoDecoderOutputBuffer outputBuffer = checkNotNull(renderedBuffer.get());
      int[] yuvStrides = checkNotNull(outputBuffer.yuvStrides);
      assertThat(outputBuffer.timeUs).isEqualTo(10_000L);
      assertThat(checkNotNull(outputBuffer.data).capacity())
          .isGreaterThan(yuvStrides[0] * HEIGHT + 2 * yuvStrides[1] * UV_HEIGHT);
      assertYuv8BitPlanes(
          outputBuffer,
          /* expectedY= */ (byte) 41,
          /* expectedU= */ (byte) 240,
          /* expectedV= */ (byte) 110);
    } finally {
      try {
        @Nullable VideoDecoderOutputBuffer outputBuffer = renderedBuffer.get();
        if (outputBuffer != null) {
          outputBuffer.release();
        }
      } finally {
        if (adapter != null) {
          adapter.release();
        }
      }
    }
  }

  @Test(timeout = 10_000)
  public void createAdapter_withExperimentalThreadCount_decodesFrame() throws Exception {
    MediaCodecAdapter adapter = null;
    try {
      Format format = createAv1Format(/* width= */ 320, /* height= */ 240);
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, /* surface= */ null);
      Dav1dCodecAdapter.Factory factory =
          new Dav1dCodecAdapter.Factory().setThreads(Dav1dCodecAdapter.THREAD_COUNT_EXPERIMENTAL);

      adapter = factory.createAdapter(configuration);
      int outputIndex =
          decodeAndDequeueFrameIndex(adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);
      MediaFormat outputFormat = adapter.getOutputFormat();

      assertThat(outputIndex).isAtLeast(0);
      assertThat(outputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(WIDTH);
      assertThat(outputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(HEIGHT);
    } finally {
      if (adapter != null) {
        adapter.release();
      }
    }
  }

  @Test(timeout = 10_000)
  public void
      createAdapter_withMaxFrameDelayOneAndMultipleThreads_emitsSingleFrameWithoutEndOfStream()
          throws Exception {
    Format format = createAv1Format(WIDTH, HEIGHT);
    MediaCodecAdapter.Configuration configuration =
        createAdapterConfiguration(format, /* surface= */ null);
    Dav1dCodecAdapter.Factory factory =
        new Dav1dCodecAdapter.Factory().setNumInputBuffers(4).setThreads(2).setMaxFrameDelay(1);
    CountDownLatch outputAvailableLatch = new CountDownLatch(1);
    MediaCodecAdapter adapter = factory.createAdapter(configuration);
    try {
      registerOutputAvailableLatches(adapter, outputAvailableLatch);

      queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);
      boolean outputAvailable = outputAvailableLatch.await(5, SECONDS);
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
      int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
      adapter.releaseOutputBuffer(outputIndex, /* render= */ false);

      assertThat(outputAvailable).isTrue();
      assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      assertThat(outputIndex).isAtLeast(0);
      assertThat(bufferInfo.presentationTimeUs).isEqualTo(10_000L);
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  public void
      dequeueOutputBufferIndex_whenDecodeFails_notifiesListenerAndThrowsIllegalStateException()
          throws Exception {
    CountDownLatch outputAvailableLatch = new CountDownLatch(1);
    Format format = createAv1Format(WIDTH, HEIGHT);
    Dav1dCodecAdapter adapter = createAdapter(format, /* surface= */ null);
    try {
      registerOutputAvailableLatches(adapter, outputAvailableLatch);
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

      queueSampleToAdapter(adapter, corruptObuSample, /* presentationTimeUs= */ 10_000);
      boolean outputAvailable = outputAvailableLatch.await(5, SECONDS);
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));

      assertThat(outputAvailable).isTrue();
      assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void setOutputSurface_afterFrameDecodedWithoutInitialSurface_rendersFrameToSurface()
      throws Exception {
    Format format = createAv1Format(WIDTH, HEIGHT);
    try (SurfaceFrameReceiver receiver = new SurfaceFrameReceiver()) {
      Dav1dCodecAdapter adapter = createAdapter(format, /* surface= */ null);
      try {
        int outputIndex =
            decodeAndDequeueFrameIndex(
                adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);

        adapter.setOutputSurface(receiver.getSurface());
        boolean canRender = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(outputIndex, /* render= */ true);

        assertThat(canRender).isTrue();
        receiver.awaitFrameAvailable();
        assertThat(receiver.getReceivedFrameCount()).isEqualTo(1);
      } finally {
        adapter.release();
      }
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void detachOutputSurface_andSetNewOutputSurface_dropsWhileDetachedAndRendersToNewSurface()
      throws Exception {
    Format format = createAv1Format(WIDTH, HEIGHT);
    try (SurfaceFrameReceiver firstReceiver = new SurfaceFrameReceiver();
        SurfaceFrameReceiver secondReceiver = new SurfaceFrameReceiver()) {
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, firstReceiver.getSurface());
      Dav1dCodecAdapter adapter =
          (Dav1dCodecAdapter)
              new Dav1dCodecAdapter.Factory()
                  .setThreads(1)
                  .setMaxFrameDelay(1)
                  .createAdapter(configuration);
      try {
        CountDownLatch firstOutputLatch = new CountDownLatch(1);
        CountDownLatch secondOutputLatch = new CountDownLatch(2);
        CountDownLatch thirdOutputLatch = new CountDownLatch(3);
        registerOutputAvailableLatches(
            adapter, firstOutputLatch, secondOutputLatch, thirdOutputLatch);
        queueSampleToAdapter(adapter, av18bitMonochromeSample, /* presentationTimeUs= */ 10_000);
        checkState(firstOutputLatch.await(5, SECONDS));
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        checkState(
            adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
        int firstOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        checkState(firstOutputIndex >= 0);
        adapter.releaseOutputBuffer(firstOutputIndex, /* render= */ true);
        firstReceiver.awaitFrameAvailable();
        queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 20_000);
        checkState(secondOutputLatch.await(5, SECONDS));
        int secondOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        checkState(secondOutputIndex >= 0);
        checkState(adapter.canRenderOutputBuffer());

        adapter.detachOutputSurface();
        boolean canRenderWhileDetached = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(secondOutputIndex, /* render= */ true);
        adapter.setOutputSurface(secondReceiver.getSurface());
        queueSampleAndEndOfStreamToAdapter(
            adapter, av18bitI420Sample, /* presentationTimeUs= */ 30_000);
        boolean thirdOutputAvailable = thirdOutputLatch.await(5, SECONDS);
        int thirdOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        adapter.releaseOutputBuffer(thirdOutputIndex, /* render= */ true);

        assertThat(canRenderWhileDetached).isFalse();
        assertThat(thirdOutputAvailable).isTrue();
        secondReceiver.awaitFrameAvailable();
        assertThat(firstReceiver.getReceivedFrameCount()).isEqualTo(1);
        assertThat(secondReceiver.getReceivedFrameCount()).isEqualTo(1);
      } finally {
        adapter.release();
      }
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void setOutput_withVideoDecoderOutputBufferRendererAfterSurface_switchesModeAndRendersYuv()
      throws Exception {
    try (SurfaceFrameReceiver receiver = new SurfaceFrameReceiver()) {
      Format format = createAv1Format(WIDTH, HEIGHT);
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, receiver.getSurface());
      Dav1dCodecAdapter.Factory factory =
          new Dav1dCodecAdapter.Factory().setNumOutputBuffers(1).setThreads(1).setMaxFrameDelay(1);
      CountDownLatch firstOutputLatch = new CountDownLatch(1);
      CountDownLatch secondOutputLatch = new CountDownLatch(2);
      AtomicReference<VideoDecoderOutputBuffer> renderedYuvBuffer = new AtomicReference<>();
      Dav1dCodecAdapter adapter = (Dav1dCodecAdapter) factory.createAdapter(configuration);
      try {
        registerOutputAvailableLatches(adapter, firstOutputLatch, secondOutputLatch);
        queueSampleToAdapter(adapter, av18bitMonochromeSample, /* presentationTimeUs= */ 10_000);
        checkState(firstOutputLatch.await(5, SECONDS));
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        checkState(
            adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
        int staleSurfaceOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        checkState(staleSurfaceOutputIndex >= 0);
        checkState(adapter.canRenderOutputBuffer());

        adapter.setOutput((VideoDecoderOutputBufferRenderer) renderedYuvBuffer::set);
        boolean canRenderStaleSurfaceBuffer = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(staleSurfaceOutputIndex, /* render= */ true);
        boolean renderedAfterStaleRelease = renderedYuvBuffer.get() != null;
        queueSampleAndEndOfStreamToAdapter(
            adapter, av18bitI420Sample, /* presentationTimeUs= */ 20_000);
        boolean secondOutputAvailable = secondOutputLatch.await(5, SECONDS);
        int secondOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        boolean canRenderYuvBuffer = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(secondOutputIndex, /* render= */ true);

        assertThat(canRenderStaleSurfaceBuffer).isFalse();
        assertThat(renderedAfterStaleRelease).isFalse();
        assertThat(receiver.hasReceivedFrame()).isFalse();
        assertThat(secondOutputAvailable).isTrue();
        assertThat(secondOutputIndex).isAtLeast(0);
        assertThat(canRenderYuvBuffer).isTrue();
        assertThat(renderedYuvBuffer.get()).isNotNull();
        VideoDecoderOutputBuffer outputBuffer = checkNotNull(renderedYuvBuffer.get());
        assertThat(outputBuffer.timeUs).isEqualTo(20_000L);
        assertYuv8BitPlanes(
            outputBuffer,
            /* expectedY= */ (byte) 41,
            /* expectedU= */ (byte) 240,
            /* expectedV= */ (byte) 110);
      } finally {
        try {
          @Nullable VideoDecoderOutputBuffer outputBuffer = renderedYuvBuffer.get();
          if (outputBuffer != null) {
            outputBuffer.release();
          }
        } finally {
          adapter.release();
        }
      }
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void
      setOutputSurface_afterVideoDecoderOutputBufferRenderer_switchesModeAndRendersToSurface()
          throws Exception {
    try (SurfaceFrameReceiver receiver = new SurfaceFrameReceiver()) {
      Format format = createAv1Format(WIDTH, HEIGHT);
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, /* surface= */ null);
      Dav1dCodecAdapter.Factory factory =
          new Dav1dCodecAdapter.Factory().setThreads(1).setMaxFrameDelay(1);
      CountDownLatch firstOutputLatch = new CountDownLatch(1);
      CountDownLatch secondOutputLatch = new CountDownLatch(2);
      AtomicReference<VideoDecoderOutputBuffer> renderedYuvBuffer = new AtomicReference<>();
      Dav1dCodecAdapter adapter = (Dav1dCodecAdapter) factory.createAdapter(configuration);
      try {
        registerOutputAvailableLatches(adapter, firstOutputLatch, secondOutputLatch);
        adapter.setOutput((VideoDecoderOutputBufferRenderer) renderedYuvBuffer::set);
        queueSampleToAdapter(adapter, av18bitMonochromeSample, /* presentationTimeUs= */ 10_000);
        checkState(firstOutputLatch.await(5, SECONDS));
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        checkState(
            adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
        int staleYuvOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        checkState(staleYuvOutputIndex >= 0);
        checkState(adapter.canRenderOutputBuffer());

        adapter.setOutputSurface(receiver.getSurface());
        boolean canRenderStaleYuvBuffer = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(staleYuvOutputIndex, /* render= */ true);
        queueSampleAndEndOfStreamToAdapter(
            adapter, av18bitI420Sample, /* presentationTimeUs= */ 20_000);
        boolean secondOutputAvailable = secondOutputLatch.await(5, SECONDS);
        int secondOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        boolean canRenderSurfaceBuffer = adapter.canRenderOutputBuffer();
        adapter.releaseOutputBuffer(secondOutputIndex, /* render= */ true);

        assertThat(canRenderStaleYuvBuffer).isFalse();
        assertThat(renderedYuvBuffer.get()).isNull();
        assertThat(secondOutputAvailable).isTrue();
        assertThat(secondOutputIndex).isAtLeast(0);
        assertThat(canRenderSurfaceBuffer).isTrue();
        receiver.awaitFrameAvailable();
        assertThat(receiver.getReceivedFrameCount()).isEqualTo(1);
      } finally {
        try {
          @Nullable VideoDecoderOutputBuffer outputBuffer = renderedYuvBuffer.get();
          if (outputBuffer != null) {
            outputBuffer.release();
          }
        } finally {
          adapter.release();
        }
      }
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void
      releaseOutputBuffer_whenRenderToSurfaceFails_releasesOutputBufferAndThrowsIllegalStateException()
          throws Exception {
    CountDownLatch firstOutputAvailableLatch = new CountDownLatch(1);
    CountDownLatch eosOutputAvailableLatch = new CountDownLatch(2);
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surfaceTexture.setDefaultBufferSize(WIDTH, HEIGHT);
    surface = new Surface(surfaceTexture);
    Format format = createAv1Format(WIDTH, HEIGHT);
    MediaCodecAdapter adapter =
        new Dav1dCodecAdapter.Factory()
            .setNumOutputBuffers(1)
            .setThreads(1)
            .setMaxFrameDelay(1)
            .createAdapter(createAdapterConfiguration(format, surface));
    try {
      registerOutputAvailableLatches(adapter, firstOutputAvailableLatch, eosOutputAvailableLatch);
      queueSampleAndEndOfStreamToAdapter(
          adapter, av18bitI420Sample, /* presentationTimeUs= */ 20_000);
      checkState(firstOutputAvailableLatch.await(5, SECONDS));
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      checkState(
          adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
      checkState(outputIndex >= 0);
      // Release the surface so renderToSurface throws Dav1dDecoderException.
      surface.release();

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> adapter.releaseOutputBuffer(outputIndex, /* render= */ true));
      boolean eosOutputAvailable = eosOutputAvailableLatch.await(5, SECONDS);
      int eosIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

      assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
      assertThat(eosOutputAvailable).isTrue();
      assertThat(eosIndex).isAtLeast(0);
      assertThat(bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM).isNotEqualTo(0);
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void flush_withDequeuedOutputBuffer_releasesPictureAndDecodesSubsequentFrame()
      throws Exception {
    try (SurfaceFrameReceiver receiver = new SurfaceFrameReceiver()) {
      Format format = createAv1Format(WIDTH, HEIGHT);
      MediaCodecAdapter.Configuration configuration =
          createAdapterConfiguration(format, receiver.getSurface());
      Dav1dCodecAdapter.Factory factory =
          new Dav1dCodecAdapter.Factory()
              .setNumInputBuffers(2)
              .setNumOutputBuffers(1)
              .setThreads(1)
              .setMaxFrameDelay(1);
      CountDownLatch firstInputReleasedLatch = new CountDownLatch(1);
      CountDownLatch firstOutputLatch = new CountDownLatch(1);
      MediaCodecAdapter adapter = factory.createAdapter(configuration);
      try {
        checkState(
            adapter.registerOnBufferAvailableListener(
                new MediaCodecAdapter.OnBufferAvailableListener() {
                  @Override
                  public void onInputBufferAvailable() {
                    firstInputReleasedLatch.countDown();
                  }

                  @Override
                  public void onOutputBufferAvailable() {
                    firstOutputLatch.countDown();
                  }
                }));
        queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);
        checkState(firstOutputLatch.await(5, SECONDS));
        checkState(firstInputReleasedLatch.await(5, SECONDS));
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        checkState(
            adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
        int firstOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        checkState(firstOutputIndex >= 0);
        // Queue a pre-flush frame while the sole output buffer is held so flush() must discard it
        // before releasing firstOutputIndex wakes the decode thread.
        queueSampleToAdapter(adapter, av18bitMonochromeSample, /* presentationTimeUs= */ 20_000);
        CountDownLatch postFlushInputLatch = new CountDownLatch(1);
        CountDownLatch postFlushOutputLatch = new CountDownLatch(1);
        checkState(
            adapter.registerOnBufferAvailableListener(
                new MediaCodecAdapter.OnBufferAvailableListener() {
                  @Override
                  public void onInputBufferAvailable() {
                    postFlushInputLatch.countDown();
                  }

                  @Override
                  public void onOutputBufferAvailable() {
                    postFlushOutputLatch.countDown();
                  }
                }));

        adapter.flush();
        boolean postFlushInputAvailable = postFlushInputLatch.await(5, SECONDS);
        queueSampleAndEndOfStreamToAdapter(
            adapter, av18bitI420Sample, /* presentationTimeUs= */ 50_000);
        boolean postFlushOutputAvailable = postFlushOutputLatch.await(5, SECONDS);
        int secondOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
        long secondFrameTimeUs = bufferInfo.presentationTimeUs;
        adapter.releaseOutputBuffer(secondOutputIndex, /* render= */ true);

        assertThat(postFlushInputAvailable).isTrue();
        assertThat(postFlushOutputAvailable).isTrue();
        assertThat(secondFrameTimeUs).isEqualTo(50_000);
        receiver.awaitFrameAvailable();
        assertThat(receiver.getReceivedFrameCount()).isEqualTo(1);
      } finally {
        adapter.release();
      }
    }
  }

  @Test(timeout = 10_000)
  public void setOutput_afterRelease_doesNotThrow() throws Exception {
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surface = new Surface(surfaceTexture);
    Format format = createAv1Format(WIDTH, HEIGHT);
    Dav1dCodecAdapter adapter = createAdapter(format, /* surface= */ null);
    adapter.release();

    adapter.setOutputSurface(surface);
    adapter.setOutput((VideoDecoderOutputBufferRenderer) outputBuffer -> {});

    assertThat(adapter.canRenderOutputBuffer()).isFalse();
  }

  @Test(timeout = 10_000)
  public void
      renderOutputBufferToSurface_afterRelease_releasesOutputBufferAndThrowsDav1dDecoderException()
          throws Exception {
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surface = new Surface(surfaceTexture);
    Format format = createAv1Format(WIDTH, HEIGHT);
    AtomicBoolean outputBufferReleased = new AtomicBoolean();
    VideoDecoderOutputBuffer outputBuffer =
        new VideoDecoderOutputBuffer(unused -> outputBufferReleased.set(true));
    Dav1dCodecAdapter adapter = createAdapter(format, surface);
    adapter.release();

    Dav1dDecoderException thrown =
        assertThrows(
            Dav1dDecoderException.class,
            () -> adapter.renderOutputBufferToSurface(outputBuffer, surface));

    assertThat(thrown).hasMessageThat().contains("decoder is not initialized");
    assertThat(outputBufferReleased.get()).isTrue();
  }

  @Test(timeout = 10_000)
  public void
      dequeueOutputBufferIndex_with10BitSampleWithoutSurface_decodesFrameAndUpdatesOutputFormat()
          throws Exception {
    FakeTrackOutput hdr10Track = extractVideoTrack(HDR_10BIT_AV1_ASSET);
    Format format = checkNotNull(hdr10Track.lastFormat);
    MediaCodecAdapter adapter =
        new Dav1dCodecAdapter.Factory()
            .setThreads(1)
            .setMaxFrameDelay(1)
            .createAdapter(createAdapterConfiguration(format, /* surface= */ null));
    try {
      int outputIndex =
          decodeAndDequeueFrameIndex(
              adapter, hdr10Track.getSampleData(0), /* presentationTimeUs= */ 10_000);
      MediaFormat outputFormat = adapter.getOutputFormat();
      adapter.releaseOutputBuffer(outputIndex, /* render= */ false);

      assertThat(outputIndex).isAtLeast(0);
      assertThat(outputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(format.width);
      assertThat(outputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(format.height);
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  public void
      dequeueOutputBufferIndex_with10BitSampleAndOutputBufferRenderer_throwsIllegalStateException()
          throws Exception {
    FakeTrackOutput hdr10Track = extractVideoTrack(HDR_10BIT_AV1_ASSET);
    Format format = checkNotNull(hdr10Track.lastFormat);
    CountDownLatch outputAvailableLatch = new CountDownLatch(1);
    Dav1dCodecAdapter adapter =
        (Dav1dCodecAdapter)
            new Dav1dCodecAdapter.Factory()
                .setThreads(1)
                .setMaxFrameDelay(1)
                .createAdapter(createAdapterConfiguration(format, /* surface= */ null));
    try {
      adapter.setOutput((VideoDecoderOutputBufferRenderer) VideoDecoderOutputBuffer::release);
      registerOutputAvailableLatches(adapter, outputAvailableLatch);
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

      queueSampleAndEndOfStreamToAdapter(
          adapter, hdr10Track.getSampleData(0), /* presentationTimeUs= */ 10_000);
      boolean outputAvailable = outputAvailableLatch.await(5, SECONDS);
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));

      assertThat(outputAvailable).isTrue();
      assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
      assertThat(thrown)
          .hasCauseThat()
          .hasMessageThat()
          .contains("High bit depth (10 or 12 bits per pixel) output format is not supported");
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  public void
      dequeueOutputBufferIndex_onMidStreamResolutionChange_emitsFormatChangedAndUpdatesDimensions()
          throws Exception {
    FakeTrackOutput sdr8BitTrack = extractVideoTrack(SDR_8BIT_AV1_ASSET);
    Format highResFormat = checkNotNull(sdr8BitTrack.lastFormat);
    Format initialFormat = createAv1Format(WIDTH, HEIGHT);
    CountDownLatch firstOutputLatch = new CountDownLatch(1);
    CountDownLatch secondOutputLatch = new CountDownLatch(2);
    MediaCodecAdapter adapter =
        new Dav1dCodecAdapter.Factory()
            .setThreads(1)
            .setMaxFrameDelay(1)
            .createAdapter(createAdapterConfiguration(initialFormat, /* surface= */ null));
    try {
      registerOutputAvailableLatches(adapter, firstOutputLatch, secondOutputLatch);
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

      queueSampleToAdapter(adapter, av18bitI420Sample, /* presentationTimeUs= */ 10_000);
      boolean firstOutputAvailable = firstOutputLatch.await(5, SECONDS);
      int firstFormatChanged = adapter.dequeueOutputBufferIndex(bufferInfo);
      MediaFormat firstOutputFormat = adapter.getOutputFormat();
      int firstOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
      adapter.releaseOutputBuffer(firstOutputIndex, /* render= */ false);
      queueSampleAndEndOfStreamToAdapter(
          adapter, sdr8BitTrack.getSampleData(0), /* presentationTimeUs= */ 20_000);
      boolean secondOutputAvailable = secondOutputLatch.await(5, SECONDS);
      int secondFormatChanged = adapter.dequeueOutputBufferIndex(bufferInfo);
      MediaFormat secondOutputFormat = adapter.getOutputFormat();
      int secondOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
      adapter.releaseOutputBuffer(secondOutputIndex, /* render= */ false);

      assertThat(firstOutputAvailable).isTrue();
      assertThat(firstFormatChanged).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      assertThat(firstOutputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(WIDTH);
      assertThat(firstOutputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(HEIGHT);
      assertThat(secondOutputAvailable).isTrue();
      assertThat(secondFormatChanged).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
      assertThat(secondOutputFormat.getInteger(MediaFormat.KEY_WIDTH))
          .isEqualTo(highResFormat.width);
      assertThat(secondOutputFormat.getInteger(MediaFormat.KEY_HEIGHT))
          .isEqualTo(highResFormat.height);
      assertThat(secondOutputIndex).isAtLeast(0);
    } finally {
      adapter.release();
    }
  }

  @Test(timeout = 10_000)
  public void
      render_viaMediaCodecVideoRendererWithSmallInitialInputBufferSize_reallocatesAndRendersFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Context context = ApplicationProvider.getApplicationContext();
    Format format = createAv1Format(WIDTH, HEIGHT);
    Handler mainHandler = new Handler(Looper.getMainLooper());
    CountDownLatch renderedLatch = new CountDownLatch(1);
    AtomicReference<Throwable> renderError = new AtomicReference<>();
    AtomicReference<MediaCodecVideoRenderer> rendererRef = new AtomicReference<>();
    CountDownLatch startedLatch = new CountDownLatch(1);
    Runnable[] renderStep = new Runnable[1];
    VideoDecoderOutputBufferRenderer outputBufferRenderer =
        outputBuffer -> {
          outputBuffer.release();
          renderedLatch.countDown();
        };

    boolean started = false;
    boolean rendered = false;
    boolean released = false;
    checkState(
        mainHandler.post(() -> {
          try {
            MediaCodecVideoRenderer renderer =
                new MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(
                        new Dav1dCodecAdapter.Factory()
                            .setInitialInputBufferSize(8)
                            .setThreads(1)
                            .setMaxFrameDelay(1))
                    .setMediaCodecSelector(
                        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                            mimeType.equals(MimeTypes.VIDEO_AV1)
                                ? ImmutableList.of(new Dav1dCodecInfo())
                                : ImmutableList.of())
                    .build();
            rendererRef.set(renderer);
            renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
            renderStep[0] =
                () -> {
                  if (renderer.getState() != Renderer.STATE_STARTED
                      || renderedLatch.getCount() == 0
                      || renderError.get() != null) {
                    return;
                  }
                  try {
                    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
                  } catch (Throwable t) {
                    renderError.compareAndSet(null, t);
                    renderedLatch.countDown();
                  }
                };
            renderer.handleMessage(
                Renderer.MSG_SET_WAKEUP_LISTENER,
                new Renderer.WakeupListener() {
                  @Override
                  public void onSleep() {}

                  @Override
                  public void onWakeup() {
                    boolean unused = mainHandler.post(renderStep[0]);
                  }
                });
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, outputBufferRenderer);
            FakeSampleStream sampleStream =
                new FakeSampleStream(
                    new DefaultAllocator(
                        /* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
                    /* mediaSourceEventDispatcher= */ null,
                    DrmSessionManager.DRM_UNSUPPORTED,
                    new DrmSessionEventListener.EventDispatcher(),
                    /* initialFormat= */ format,
                    ImmutableList.of(
                        FakeSampleStream.FakeSampleStreamItem.sample(
                            /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, av18bitI420Sample),
                        FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM));
            sampleStream.writeData(/* startPositionUs= */ 0);
            renderer.enable(
                RendererConfiguration.DEFAULT,
                new Format[] {format},
                sampleStream,
                /* positionUs= */ 0,
                /* joining= */ false,
                /* mayRenderStartOfStream= */ true,
                /* startPositionUs= */ 0,
                /* offsetUs= */ 0,
                new MediaSource.MediaPeriodId(new Object()));
            renderer.start();
            renderStep[0].run();
          } catch (Throwable t) {
            renderError.compareAndSet(null, t);
            renderedLatch.countDown();
          } finally {
            startedLatch.countDown();
          }
        }));
    try {
      started = startedLatch.await(5, SECONDS);
      rendered = renderedLatch.await(5, SECONDS);
    } finally {
      CountDownLatch releasedLatch = new CountDownLatch(1);
      boolean posted =
          mainHandler.post(() -> {
            try {
              if (renderStep[0] != null) {
                mainHandler.removeCallbacks(renderStep[0]);
              }
              @Nullable MediaCodecVideoRenderer renderer = rendererRef.get();
              if (renderer != null) {
                try {
                  try {
                    if (renderer.getState() == Renderer.STATE_STARTED) {
                      renderer.stop();
                    }
                  } finally {
                    try {
                      if (renderer.getState() == Renderer.STATE_ENABLED) {
                        renderer.disable();
                      }
                    } finally {
                      renderer.reset();
                    }
                  }
                } finally {
                  renderer.release();
                }
              }
            } catch (Throwable t) {
              renderError.compareAndSet(null, t);
            } finally {
              releasedLatch.countDown();
            }
          });
      if (posted) {
        boolean interrupted = Thread.interrupted();
        try {
          released = releasedLatch.await(5, SECONDS);
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }

    assertThat(started).isTrue();
    assertThat(renderError.get()).isNull();
    assertThat(rendered).isTrue();
    assertThat(released).isTrue();
  }

  private static FakeTrackOutput extractVideoTrack(String assetPath) throws IOException {
    Context context = ApplicationProvider.getApplicationContext();
    FakeExtractorOutput extractorOutput =
        TestUtil.extractAllSamplesFromFile(
            new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), context, assetPath);
    for (int i = 0; i < extractorOutput.trackOutputs.size(); i++) {
      FakeTrackOutput trackOutput = extractorOutput.trackOutputs.valueAt(i);
      if (trackOutput.getType() == C.TRACK_TYPE_VIDEO) {
        return trackOutput;
      }
    }
    throw new IllegalStateException("No video track found in " + assetPath);
  }

  private static Format createAv1Format(int width, int height) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(width)
        .setHeight(height)
        .build();
  }

  private static MediaCodecAdapter.Configuration createAdapterConfiguration(
      Format format, @Nullable Surface surface) {
    return MediaCodecAdapter.Configuration.createForVideoDecoding(
        new Dav1dCodecInfo(), new MediaFormat(), format, surface, /* crypto= */ null);
  }

  private static Dav1dCodecAdapter createAdapter(Format format, @Nullable Surface surface)
      throws IOException {
    return (Dav1dCodecAdapter)
        new Dav1dCodecAdapter.Factory()
            .setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
            .setThreads(THREADS)
            .setMaxFrameDelay(MAX_FRAME_DELAY)
            .createAdapter(createAdapterConfiguration(format, surface));
  }

  private static void registerOutputAvailableLatches(
      MediaCodecAdapter adapter, CountDownLatch... outputAvailableLatches) {
    checkState(
        adapter.registerOnBufferAvailableListener(
            new MediaCodecAdapter.OnBufferAvailableListener() {
              @Override
              public void onOutputBufferAvailable() {
                for (CountDownLatch latch : outputAvailableLatches) {
                  latch.countDown();
                }
              }
            }));
  }

  private static int decodeAndDequeueFrameIndex(
      MediaCodecAdapter adapter, byte[] sample, long presentationTimeUs) throws Exception {
    CountDownLatch outputAvailableLatch = new CountDownLatch(1);
    registerOutputAvailableLatches(adapter, outputAvailableLatch);
    queueSampleAndEndOfStreamToAdapter(adapter, sample, presentationTimeUs);
    checkState(
        outputAvailableLatch.await(5, SECONDS), "Timed out waiting for adapter output buffer");
    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    checkState(
        adapter.dequeueOutputBufferIndex(bufferInfo) == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    checkState(outputIndex >= 0);
    return outputIndex;
  }

  private static void queueSampleToAdapter(
      MediaCodecAdapter adapter, byte[] sample, long presentationTimeUs) {
    int inputIndex = adapter.dequeueInputBufferIndex();
    checkNotNull(adapter.getInputBuffer(inputIndex)).put(sample);
    adapter.queueInputBuffer(
        inputIndex, /* offset= */ 0, sample.length, presentationTimeUs, /* flags= */ 0);
  }

  private static void queueSampleAndEndOfStreamToAdapter(
      MediaCodecAdapter adapter, byte[] sample, long presentationTimeUs) {
    queueSampleToAdapter(adapter, sample, presentationTimeUs);
    int eosIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        eosIndex,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 0,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
  }

  private static void assertYuv8BitPlanes(
      VideoDecoderOutputBuffer outputBuffer, byte expectedY, byte expectedU, byte expectedV) {
    ByteBuffer[] yuvPlanes = checkNotNull(outputBuffer.yuvPlanes);
    int[] yuvStrides = checkNotNull(outputBuffer.yuvStrides);
    assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_YUV);
    assertByteBufferPlane8Bit(yuvPlanes[0], yuvStrides[0], WIDTH, HEIGHT, expectedY);
    assertByteBufferPlane8Bit(yuvPlanes[1], yuvStrides[1], UV_WIDTH, UV_HEIGHT, expectedU);
    assertByteBufferPlane8Bit(yuvPlanes[2], yuvStrides[2], UV_WIDTH, UV_HEIGHT, expectedV);
  }

  private static void assertByteBufferPlane8Bit(
      ByteBuffer plane, int stride, int width, int height, byte expectedValue) {
    ByteBuffer duplicate = plane.duplicate();
    for (int h = 0; h < height; h++) {
      for (int w = 0; w < width; w++) {
        assertWithMessage("Sample at (%s, %s)", w, h)
            .that(duplicate.get(h * stride + w))
            .isEqualTo(expectedValue);
      }
    }
  }

  private static final class SurfaceFrameReceiver implements AutoCloseable {
    private final HandlerThread handlerThread;
    private final Handler handler;
    private final EGLSurfaceTexture eglSurfaceTexture;
    private final Surface surface;
    private final CountDownLatch frameAvailableLatch;
    private final AtomicInteger receivedFrameCount;

    private SurfaceFrameReceiver() throws Exception {
      handlerThread = new HandlerThread("Dav1dTestSurfaceReceiver");
      handlerThread.start();
      boolean initialized = false;
      AtomicBoolean eglInitialized = new AtomicBoolean();
      Handler constructedHandler = null;
      EGLSurfaceTexture constructedEglSurfaceTexture = null;
      try {
        handler = new Handler(handlerThread.getLooper());
        constructedHandler = handler;
        frameAvailableLatch = new CountDownLatch(1);
        receivedFrameCount = new AtomicInteger();
        eglSurfaceTexture =
            new EGLSurfaceTexture(
                handler,
                () -> {
                  receivedFrameCount.incrementAndGet();
                  frameAvailableLatch.countDown();
                });
        constructedEglSurfaceTexture = eglSurfaceTexture;
        AtomicReference<Throwable> initError = new AtomicReference<>();
        CountDownLatch initLatch = new CountDownLatch(1);
        checkState(
            handler.post(() -> {
              try {
                eglSurfaceTexture.init(EGLSurfaceTexture.SECURE_MODE_NONE);
                eglInitialized.set(true);
              } catch (Throwable t) {
                initError.set(t);
                try {
                  eglSurfaceTexture.release();
                } catch (Throwable releaseException) {
                  t.addSuppressed(releaseException);
                }
              } finally {
                initLatch.countDown();
              }
            }));
        checkState(initLatch.await(5, SECONDS));
        Throwable error = initError.get();
        if (error instanceof Exception) {
          throw (Exception) error;
        } else if (error instanceof Error) {
          throw (Error) error;
        } else if (error != null) {
          throw new RuntimeException(error);
        }
        SurfaceTexture surfaceTexture = eglSurfaceTexture.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(WIDTH, HEIGHT);
        surface = new Surface(surfaceTexture);
        initialized = true;
      } finally {
        if (!initialized) {
          boolean interrupted = Thread.interrupted();
          try {
            if (eglInitialized.get()
                && constructedHandler != null
                && constructedEglSurfaceTexture != null) {
              EGLSurfaceTexture textureToRelease = constructedEglSurfaceTexture;
              CountDownLatch releaseLatch = new CountDownLatch(1);
              if (constructedHandler.post(
                  () -> {
                    try {
                      textureToRelease.release();
                    } finally {
                      releaseLatch.countDown();
                    }
                  })) {
                boolean unused = releaseLatch.await(5, SECONDS);
              }
            }
            handlerThread.quitSafely();
            handlerThread.join(5_000);
          } finally {
            handlerThread.quitSafely();
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    }

    private Surface getSurface() {
      return surface;
    }

    private void awaitFrameAvailable() throws InterruptedException {
      assertWithMessage("Timed out waiting for rendered Surface frame")
          .that(frameAvailableLatch.await(5, SECONDS))
          .isTrue();
    }

    private int getReceivedFrameCount() throws InterruptedException {
      // EGLSurfaceTexture.onFrameAvailable posts a Runnable back onto handler, so flush twice to
      // drain both any queued onFrameAvailable callback and its posted TextureImageListener call.
      flushHandler();
      flushHandler();
      return receivedFrameCount.get();
    }

    private boolean hasReceivedFrame() throws InterruptedException {
      return getReceivedFrameCount() > 0;
    }

    private void flushHandler() throws InterruptedException {
      CountDownLatch handlerFlushedLatch = new CountDownLatch(1);
      checkState(handler.post(handlerFlushedLatch::countDown));
      checkState(handlerFlushedLatch.await(5, SECONDS));
    }

    @Override
    public void close() throws InterruptedException {
      try {
        surface.release();
      } finally {
        boolean interrupted = Thread.interrupted();
        try {
          CountDownLatch releaseLatch = new CountDownLatch(1);
          if (handler.post(
              () -> {
                try {
                  eglSurfaceTexture.release();
                } finally {
                  releaseLatch.countDown();
                }
              })) {
            checkState(releaseLatch.await(5, SECONDS));
          }
          handlerThread.quitSafely();
          handlerThread.join(5_000);
        } finally {
          handlerThread.quitSafely();
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }
}
