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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation unit tests for {@link Dav1dDecoder} and its JNI picture lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class Dav1dDecoderTest {

  private static final String SDR_8BIT_AV1_ASSET = "media/mp4/sample_av1.mp4";
  private static final String HDR_10BIT_AV1_ASSET = "media/mp4/hdr10-av1.mp4";
  private static final int NUM_INPUT_BUFFERS = 4;
  private static final int NUM_OUTPUT_BUFFERS = 4;
  private static final int INITIAL_INPUT_BUFFER_SIZE = 256 * 1024;
  private static final int THREADS = 2;
  private static final int MAX_FRAME_DELAY = 2;
  private static final long DECODE_TIMEOUT_MS = 5_000;
  private static final long STREAM_DECODE_TIMEOUT_MS = 20_000;

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

  // 32x16 8-bit I420 AV1 keyframe OBU (Y=41, U=240, V=110).
  private static final byte[] av18bitI420Sample =
      new byte[] {
        18, 0, 10, 10, 0, 0, 0, 2, 31, -4, -38, -7, 0, 64, 50, 20, 16, 0, -128, 0, 0, 31, 124, -115,
        -27, 23, -103, 0, -44, -55, 65, -107, -13, 100, 19, 12
      };

  private static FakeTrackOutput sdr8BitVideoTrack;
  private static FakeTrackOutput hdr10BitVideoTrack;

  private @Nullable Dav1dDecoder decoder;
  private @Nullable Surface surface;
  private @Nullable SurfaceTexture surfaceTexture;

  @BeforeClass
  public static void extractTestTracks() throws IOException {
    Context context = ApplicationProvider.getApplicationContext();
    sdr8BitVideoTrack = extractVideoTrack(context, SDR_8BIT_AV1_ASSET);
    hdr10BitVideoTrack = extractVideoTrack(context, HDR_10BIT_AV1_ASSET);
  }

  @Before
  public void setUp() throws Exception {
    assertWithMessage("Dav1d library is not available").that(Dav1dLibrary.isAvailable()).isTrue();
    decoder = createDecoder();
  }

  @After
  public void tearDown() {
    try {
      if (decoder != null) {
        decoder.release();
        decoder = null;
      }
    } finally {
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
  }

  /**
   * Tests software byte-buffer output mode ({@link C#VIDEO_OUTPUT_MODE_YUV}).
   *
   * <p>Ensures that frames are decoded and copied into planar YUV byte buffers without native
   * picture handles ({@code decoderPrivate == 0}), validating the zero-heap stack allocation path.
   */
  @Test(timeout = 10_000)
  public void decode_yuvOutputMode_populatesYuvPlanes() throws Exception {
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    try {
      assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_YUV);
      assertThat(outputBuffer.width).isEqualTo(1080);
      assertThat(outputBuffer.height).isEqualTo(720);
      assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
      assertThat(outputBuffer.data).isNotNull();
      assertThat(outputBuffer.data.remaining()).isGreaterThan(0);
      assertThat(outputBuffer.yuvPlanes).isNotNull();
      assertThat(outputBuffer.yuvPlanes).hasLength(3);
    } finally {
      outputBuffer.release();
    }
  }

  /**
   * Tests surface rendering mode ({@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV}) through render and
   * release.
   *
   * <p>Ensures that {@code dav1dGetFrame} promotes the stack picture to a native heap handle and
   * {@code dav1dRenderFrame} renders it, frees the native picture, and clears {@code
   * decoderPrivate} to 0 before release, preventing double-free.
   */
  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void decode_surfaceYuvOutputMode_renderToSurfaceClearsDecoderPrivateAndReleasesCleanly()
      throws Exception {
    // Given a decoder in VIDEO_OUTPUT_MODE_SURFACE_YUV and a valid output Surface.
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surfaceTexture.setDefaultBufferSize(1080, 720);
    surface = new Surface(surfaceTexture);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    long initialDecoderPrivate;
    long decoderPrivateAfterRender;
    try {
      initialDecoderPrivate = outputBuffer.decoderPrivate;

      // When rendering the output buffer to the surface and then releasing the buffer.
      decoder.renderToSurface(outputBuffer, surface);
      decoderPrivateAfterRender = outputBuffer.decoderPrivate;
    } finally {
      outputBuffer.release();
    }

    // Then dav1dGetFrame populated a heap Dav1dPicture handle and dav1dRenderFrame cleared it.
    assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    assertThat(outputBuffer.width).isEqualTo(1080);
    assertThat(outputBuffer.height).isEqualTo(720);
    assertThat(initialDecoderPrivate).isNotEqualTo(0);
    assertThat(decoderPrivateAfterRender).isEqualTo(0);
  }

  /**
   * Tests discarding a surface-mode output buffer without calling {@code renderToSurface}.
   *
   * <p>Ensures that {@code dav1dReleaseFrame} unreferences and deletes the native picture when a
   * frame is dropped before rendering, preventing native memory leaks.
   */
  @Test(timeout = 10_000)
  public void decode_surfaceYuvOutputMode_releaseWithoutRenderClearsDecoderPrivate()
      throws Exception {
    // Given a decoded surface-mode frame that will be dropped without calling renderToSurface.
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    long initialDecoderPrivate = outputBuffer.decoderPrivate;

    // When releasing the output buffer directly via dav1dReleaseFrame.
    outputBuffer.release();

    // Then the native picture handle was non-zero before release and cleared to 0 on release.
    assertThat(initialDecoderPrivate).isNotEqualTo(0);
    assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
  }

  /**
   * Tests releasing the decoder while the caller still holds a dequeued surface-mode output buffer.
   *
   * <p>Ensures that {@code Dav1dDecoder.release()} frees the native picture attached to the held
   * buffer, rather than leaking it once the decoder context is closed, and that releasing the
   * buffer afterwards is safe.
   */
  @Test(timeout = 10_000)
  public void release_whileHoldingSurfaceYuvOutputBuffer_freesNativePicture() throws Exception {
    // Given a decoded surface-mode frame that the caller has dequeued but not released.
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    long initialDecoderPrivate = outputBuffer.decoderPrivate;

    // When releasing the decoder before the output buffer.
    decoder.release();
    decoder = null;

    // Then the native picture was freed by the decoder, and releasing the buffer afterwards is
    // safe.
    assertThat(initialDecoderPrivate).isNotEqualTo(0);
    assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
    outputBuffer.release();
    assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
  }

  /**
   * Tests decode-only preroll handling when {@code outputStartTimeUs} is set.
   *
   * <p>Ensures that frames earlier than the target start time are marked as decode-only,
   * unreferenced immediately, and skipped without emitting output buffers.
   */
  @Test(timeout = 10_000)
  public void decode_framesBeforeOutputStartTimeUs_skipsDecodeOnlyFrames() throws Exception {
    long targetStartTimeUs = sdr8BitVideoTrack.getSampleTimeUs(3);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setOutputStartTimeUs(targetStartTimeUs);

    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    try {
      assertThat(outputBuffer.timeUs).isAtLeast(targetStartTimeUs);
      assertThat(outputBuffer.skippedOutputBufferCount).isEqualTo(3);
    } finally {
      outputBuffer.release();
    }
  }

  /**
   * Tests error handling when attempting 10-bit HDR decoding in software YUV output mode.
   *
   * <p>Ensures that bit-depth validation rejects 10-bit YUV output and unreferences the stack
   * picture before throwing {@link Dav1dDecoderException}.
   */
  @Test(timeout = 10_000)
  public void decode_10BitInYuvOutputMode_throwsDav1dDecoderException() throws Exception {
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    Dav1dDecoderException thrown =
        assertThrows(
            Dav1dDecoderException.class,
            () -> feedSamplesUntilOutputBuffer(decoder, hdr10BitVideoTrack));

    assertThat(thrown)
        .hasMessageThat()
        .contains("High bit depth (10 or 12 bits per pixel) output format is not supported");
  }

  /**
   * Tests defense against double-rendering the same surface output buffer.
   *
   * <p>Ensures that once a buffer's native picture is freed during the first render, subsequent
   * render attempts throw {@link Dav1dDecoderException} rather than dereferencing a dangling
   * pointer.
   */
  @Test(timeout = 10_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void renderToSurface_calledTwiceOnSameBuffer_throwsDav1dDecoderException()
      throws Exception {
    // Given a surface-mode frame that has already been rendered (and whose native picture was
    // therefore released by dav1dRenderFrame).
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surfaceTexture.setDefaultBufferSize(1080, 720);
    surface = new Surface(surfaceTexture);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    try {
      decoder.renderToSurface(outputBuffer, surface);

      // When rendering the same buffer a second time.
      Dav1dDecoderException thrown =
          assertThrows(
              Dav1dDecoderException.class, () -> decoder.renderToSurface(outputBuffer, surface));

      // Then the native layer reports the missing picture rather than reading freed memory.
      assertThat(thrown).hasMessageThat().contains("Failed to render output buffer to surface");
      assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
    } finally {
      outputBuffer.release();
    }
  }

  /**
   * Tests end-to-end stream decoding and surface presentation on a {@link PlaceholderSurface}.
   *
   * <p>Ensures that all frames in an AV1 stream cycle through allocation, decode, surface render,
   * and release repeatedly without buffer pool starvation, deadlocks, or leaks.
   */
  @Test(timeout = 30_000)
  @SdkSuppress(minSdkVersion = MIN_API_FOR_SURFACE_RENDERING)
  public void decodeEntireStream_surfaceYuvOutputMode_rendersAndReleasesEveryFrame()
      throws Exception {
    // Given a self-consuming placeholder surface and a surface-mode decoder.
    surface = PlaceholderSurface.newInstance(ApplicationProvider.getApplicationContext(), false);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);

    // When decoding the whole stream, rendering and releasing every output buffer so that each
    // of the NUM_OUTPUT_BUFFERS buffers is recycled multiple times.
    int renderedFrameCount = feedAndDrainEntireStream(decoder, sdr8BitVideoTrack, surface);

    // Then every emitted frame was rendered without native errors or double frees.
    assertThat(renderedFrameCount).isEqualTo(sdr8BitVideoTrack.getSampleCount());
  }

  /**
   * Tests end-to-end stream decoding in software YUV mode.
   *
   * <p>Ensures sustained multi-frame decoding stability for software YUV playback, verifying every
   * frame delivers valid planar data and reaches end-of-stream without leaking frame buffers.
   */
  @Test(timeout = 30_000)
  public void decodeEntireStream_yuvOutputMode_outputsEveryFrame() throws Exception {
    // Given a YUV-mode decoder.
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    // When decoding the whole stream and releasing every output buffer.
    int outputFrameCount =
        feedAndDrainEntireStream(decoder, sdr8BitVideoTrack, /* surface= */ null);

    // Then every emitted frame carried YUV planes and no native picture handle.
    assertThat(outputFrameCount).isEqualTo(sdr8BitVideoTrack.getSampleCount());
  }

  @Test(timeout = 10_000)
  public void decode_withDelayedFramesBeforeOutputStartTime_skipsNonLastAndEmitsLastSample()
      throws Exception {
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setOutputStartTimeUs(100_000);
    CountDownLatch outputAvailableLatch = new CountDownLatch(2);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {}

          @Override
          public void onOutputBufferAvailable() {
            outputAvailableLatch.countDown();
          }
        },
        Runnable::run);
    DecoderInputBuffer firstInput = checkNotNull(decoder.dequeueInputBuffer());
    firstInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(firstInput.data).put(av18bitI420Sample).flip();
    firstInput.timeUs = 10_000;
    decoder.queueInputBuffer(firstInput);
    DecoderInputBuffer lastSampleInput = checkNotNull(decoder.dequeueInputBuffer());
    lastSampleInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(lastSampleInput.data).put(av18bitI420Sample).flip();
    lastSampleInput.timeUs = 20_000;
    lastSampleInput.addFlag(C.BUFFER_FLAG_LAST_SAMPLE);
    decoder.queueInputBuffer(lastSampleInput);
    DecoderInputBuffer eosInput = checkNotNull(decoder.dequeueInputBuffer());
    eosInput.setFlags(C.BUFFER_FLAG_END_OF_STREAM);

    decoder.queueInputBuffer(eosInput);
    boolean outputsAvailable = outputAvailableLatch.await(5, SECONDS);
    int skippedCount = decoder.getAndResetSkippedOutputBufferCount();
    VideoDecoderOutputBuffer lastSampleOutput = checkNotNull(decoder.dequeueOutputBuffer());
    long lastSampleTimeUs;
    boolean isLastSample;
    boolean shouldBeSkipped;
    boolean isEndOfStream;
    try {
      lastSampleTimeUs = lastSampleOutput.timeUs;
      isLastSample = lastSampleOutput.isLastSample();
      shouldBeSkipped = lastSampleOutput.shouldBeSkipped;
      VideoDecoderOutputBuffer eosOutput = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        isEndOfStream = eosOutput.isEndOfStream();
      } finally {
        eosOutput.release();
      }
    } finally {
      lastSampleOutput.release();
    }

    assertWithMessage("Timed out waiting for last sample and EOS output buffers")
        .that(outputsAvailable)
        .isTrue();
    assertThat(skippedCount).isEqualTo(1);
    assertThat(lastSampleTimeUs).isEqualTo(20_000);
    assertThat(isLastSample).isTrue();
    assertThat(shouldBeSkipped).isFalse();
    assertThat(isEndOfStream).isTrue();
  }

  @Test(timeout = 10_000)
  public void
      getAndResetSkippedOutputBufferCount_withPendingAndQueuedSkips_harvestsSkipsWithoutDuplication()
          throws Exception {
    checkNotNull(decoder).release();
    decoder = null;
    decoder =
        new Dav1dDecoder(
            NUM_INPUT_BUFFERS,
            NUM_OUTPUT_BUFFERS,
            INITIAL_INPUT_BUFFER_SIZE,
            /* threads= */ 1,
            /* maxFrameDelay= */ 1,
            /* useCustomAllocator= */ false,
            C.THREAD_PRIORITY_NO_OP);
    CountDownLatch inputsProcessedLatch = new CountDownLatch(3);
    CountDownLatch outputAvailableLatch = new CountDownLatch(1);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setOutputStartTimeUs(20_000);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {
            inputsProcessedLatch.countDown();
          }

          @Override
          public void onOutputBufferAvailable() {
            outputAvailableLatch.countDown();
          }
        },
        Runnable::run);
    DecoderInputBuffer firstSkippedInput = checkNotNull(decoder.dequeueInputBuffer());
    firstSkippedInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(firstSkippedInput.data).put(av18bitI420Sample).flip();
    firstSkippedInput.timeUs = 10_000;
    decoder.queueInputBuffer(firstSkippedInput);
    DecoderInputBuffer renderedInput = checkNotNull(decoder.dequeueInputBuffer());
    renderedInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(renderedInput.data).put(av18bitI420Sample).flip();
    renderedInput.timeUs = 20_000;
    decoder.queueInputBuffer(renderedInput);
    DecoderInputBuffer secondSkippedInput = checkNotNull(decoder.dequeueInputBuffer());
    secondSkippedInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(secondSkippedInput.data).put(av18bitI420Sample).flip();
    secondSkippedInput.timeUs = 15_000;
    decoder.queueInputBuffer(secondSkippedInput);
    boolean inputsProcessed = inputsProcessedLatch.await(5, SECONDS);
    boolean outputAvailable = outputAvailableLatch.await(5, SECONDS);

    int harvestedCount = decoder.getAndResetSkippedOutputBufferCount();
    int secondHarvestedCount = decoder.getAndResetSkippedOutputBufferCount();
    VideoDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
    long outputBufferTimeUs = outputBuffer.timeUs;
    int outputBufferSkippedCount = outputBuffer.skippedOutputBufferCount;
    outputBuffer.release();

    assertThat(inputsProcessed).isTrue();
    assertThat(outputAvailable).isTrue();
    assertThat(harvestedCount).isEqualTo(2);
    assertThat(secondHarvestedCount).isEqualTo(0);
    assertThat(outputBufferTimeUs).isEqualTo(20_000);
    assertThat(outputBufferSkippedCount).isEqualTo(0);
  }

  @Test(timeout = 10_000)
  public void flush_withPendingSkippedOutputBuffers_clearsSkippedOutputBufferCountImmediately()
      throws Exception {
    checkNotNull(decoder).release();
    decoder = null;
    decoder =
        new Dav1dDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 1,
            INITIAL_INPUT_BUFFER_SIZE,
            /* threads= */ 1,
            /* maxFrameDelay= */ 1,
            /* useCustomAllocator= */ false,
            C.THREAD_PRIORITY_NO_OP);
    CountDownLatch skippedSampleProcessedLatch = new CountDownLatch(1);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setOutputStartTimeUs(20_000);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {
            skippedSampleProcessedLatch.countDown();
          }

          @Override
          public void onOutputBufferAvailable() {}
        },
        Runnable::run);
    DecoderInputBuffer skippedInput = checkNotNull(decoder.dequeueInputBuffer());
    skippedInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(skippedInput.data).put(av18bitI420Sample).flip();
    skippedInput.timeUs = 10_000;
    decoder.queueInputBuffer(skippedInput);
    boolean skippedSampleProcessed = skippedSampleProcessedLatch.await(5, SECONDS);
    checkNotNull(decoder.dequeueInputBuffer());
    CountDownLatch flushCompletedLatch = new CountDownLatch(1);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {
            flushCompletedLatch.countDown();
          }

          @Override
          public void onOutputBufferAvailable() {}
        },
        Runnable::run);

    decoder.flush();
    int skippedCountImmediatelyAfterFlush = decoder.getAndResetSkippedOutputBufferCount();
    boolean flushCompleted = flushCompletedLatch.await(5, SECONDS);
    int skippedCountAfterFlushCompleted = decoder.getAndResetSkippedOutputBufferCount();

    assertThat(skippedSampleProcessed).isTrue();
    assertThat(skippedCountImmediatelyAfterFlush).isEqualTo(0);
    assertThat(flushCompleted).isTrue();
    assertThat(skippedCountAfterFlushCompleted).isEqualTo(0);
  }

  @Test(timeout = 15_000)
  public void dequeueInputBuffer_whileFlushPending_recoversInputBuffer() throws Exception {
    // Use 1 input buffer so overwriting dequeuedInputBuffer during flush() leaves 0 buffers.
    checkNotNull(this.decoder).release();
    this.decoder = null;
    Dav1dDecoder decoder = createDecoder(/* numInputBuffers= */ 1, NUM_OUTPUT_BUFFERS);
    this.decoder = decoder;
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    ConditionVariable flushed = new ConditionVariable();
    List<@Nullable DecoderInputBuffer> buffersDequeuedWhileFlushPending = new ArrayList<>();
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {}

          @Override
          public void onOutputBufferAvailable() {
            // Run on the decode thread so flushInternal() cannot execute between flush() and
            // dequeueInputBuffer().
            try {
              DecoderInputBuffer unqueued = decoder.dequeueInputBuffer();
              if (unqueued != null) {
                decoder.flush();
                buffersDequeuedWhileFlushPending.add(decoder.dequeueInputBuffer());
              }
              flushed.open();
            } catch (Dav1dDecoderException e) {
              throw new IllegalStateException(e);
            }
          }
        },
        Runnable::run);

    DecoderInputBuffer eos = pollInputBuffer(decoder, DECODE_TIMEOUT_MS);
    assertThat(eos).isNotNull();
    eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    decoder.queueInputBuffer(eos);
    assertThat(flushed.block(DECODE_TIMEOUT_MS)).isTrue();

    assertThat(buffersDequeuedWhileFlushPending).containsExactly((Object) null);
    assertThat(pollInputBuffer(decoder, DECODE_TIMEOUT_MS)).isNotNull();
  }

  @Test(timeout = 15_000)
  public void flush_withFrameDelayedInput_recoversAllInputBuffersAndDecodesStream()
      throws Exception {
    checkNotNull(decoder).release();
    decoder = null;
    decoder = createDecoder(NUM_INPUT_BUFFERS, /* numOutputBuffers= */ 1);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    VideoDecoderOutputBuffer heldOutputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    try {
      decoder.flush();
      dequeueAndQueueBurstAfterFlush(decoder, sdr8BitVideoTrack);
    } finally {
      heldOutputBuffer.release();
    }

    int outputFrameCount =
        feedAndDrainStream(decoder, sdr8BitVideoTrack, NUM_INPUT_BUFFERS, /* surface= */ null);

    assertThat(outputFrameCount).isEqualTo(sdr8BitVideoTrack.getSampleCount());
  }

  private static Dav1dDecoder createDecoder() throws Dav1dDecoderException {
    return createDecoder(NUM_INPUT_BUFFERS, NUM_OUTPUT_BUFFERS);
  }

  private static Dav1dDecoder createDecoder(int numInputBuffers, int numOutputBuffers)
      throws Dav1dDecoderException {
    return new Dav1dDecoder(
        numInputBuffers,
        numOutputBuffers,
        INITIAL_INPUT_BUFFER_SIZE,
        THREADS,
        MAX_FRAME_DELAY,
        /* useCustomAllocator= */ false,
        C.THREAD_PRIORITY_NO_OP);
  }

  @Test(timeout = 10_000)
  public void queueInputBuffer_withEndOfStream_drainsDelayedFramesBeforeEndOfStreamOutputBuffer()
      throws Exception {
    CountDownLatch twoOutputsAvailableLatch = new CountDownLatch(2);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {}

          @Override
          public void onOutputBufferAvailable() {
            twoOutputsAvailableLatch.countDown();
          }
        },
        Runnable::run);
    DecoderInputBuffer sampleBuffer = checkNotNull(decoder.dequeueInputBuffer());
    sampleBuffer.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(sampleBuffer.data).put(av18bitI420Sample).flip();
    sampleBuffer.timeUs = 10_000;
    sampleBuffer.addFlag(C.BUFFER_FLAG_LAST_SAMPLE);
    decoder.queueInputBuffer(sampleBuffer);
    DecoderInputBuffer eosBuffer = checkNotNull(decoder.dequeueInputBuffer());
    eosBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);

    decoder.queueInputBuffer(eosBuffer);
    boolean outputsAvailable = twoOutputsAvailableLatch.await(5, SECONDS);
    @Nullable VideoDecoderOutputBuffer drainedFrame = decoder.dequeueOutputBuffer();
    @Nullable VideoDecoderOutputBuffer eosOutput = decoder.dequeueOutputBuffer();
    boolean drainedFrameIsEos = drainedFrame != null && drainedFrame.isEndOfStream();
    long drainedFrameTimeUs = drainedFrame != null ? drainedFrame.timeUs : C.TIME_UNSET;
    boolean drainedFrameIsLastSample = drainedFrame != null && drainedFrame.isLastSample();
    boolean eosOutputIsEos = eosOutput != null && eosOutput.isEndOfStream();
    if (drainedFrame != null) {
      drainedFrame.release();
    }
    if (eosOutput != null) {
      eosOutput.release();
    }

    assertThat(outputsAvailable).isTrue();
    assertThat(drainedFrame).isNotNull();
    assertThat(drainedFrameIsEos).isFalse();
    assertThat(drainedFrameTimeUs).isEqualTo(10_000);
    assertThat(drainedFrameIsLastSample).isTrue();
    assertThat(eosOutput).isNotNull();
    assertThat(eosOutputIsEos).isTrue();
  }

  @Test(timeout = 10_000)
  public void flush_withDequeuedInputBuffer_reclaimsInputBuffer() throws Exception {
    decoder.release();
    decoder =
        new Dav1dDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 1,
            /* initialInputBufferSize= */ 1024,
            /* threads= */ 1,
            /* maxFrameDelay= */ 1,
            /* useCustomAllocator= */ false,
            C.THREAD_PRIORITY_NO_OP);
    DecoderInputBuffer initialBuffer = checkNotNull(decoder.dequeueInputBuffer());
    CountDownLatch inputBufferAvailableLatch = new CountDownLatch(1);
    @Nullable DecoderInputBuffer[] reclaimedBuffer = new DecoderInputBuffer[1];
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {
            try {
              reclaimedBuffer[0] = decoder.dequeueInputBuffer();
            } catch (Dav1dDecoderException e) {
              throw new RuntimeException(e);
            } finally {
              inputBufferAvailableLatch.countDown();
            }
          }

          @Override
          public void onOutputBufferAvailable() {}
        },
        Runnable::run);

    decoder.flush();
    boolean callbackReceived = inputBufferAvailableLatch.await(5, SECONDS);

    assertWithMessage("Timed out waiting for reclaimed input buffer after flush")
        .that(callbackReceived)
        .isTrue();
    assertThat(reclaimedBuffer[0]).isSameInstanceAs(initialBuffer);
  }

  @Test(timeout = 10_000)
  public void flush_withInputBufferHeldByNativeDecoder_reclaimsInputBuffer() throws Exception {
    decoder.release();
    decoder =
        new Dav1dDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 2,
            /* initialInputBufferSize= */ 1024,
            /* threads= */ 1,
            /* maxFrameDelay= */ 1,
            /* useCustomAllocator= */ false,
            C.THREAD_PRIORITY_NO_OP);
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    CountDownLatch firstOutputAvailableLatch = new CountDownLatch(1);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {}

          @Override
          public void onOutputBufferAvailable() {
            firstOutputAvailableLatch.countDown();
          }
        },
        Runnable::run);
    DecoderInputBuffer initialBuffer = checkNotNull(decoder.dequeueInputBuffer());
    initialBuffer.ensureSpaceForWrite(av18bitI420Sample.length * 2);
    ByteBuffer initialBufferData = checkNotNull(initialBuffer.data);
    initialBufferData.put(av18bitI420Sample);
    initialBufferData.put(av18bitI420Sample);
    initialBufferData.flip();
    initialBuffer.timeUs = 10_000;
    decoder.queueInputBuffer(initialBuffer);
    boolean firstOutputAvailable = firstOutputAvailableLatch.await(5, SECONDS);
    @Nullable DecoderInputBuffer inputBufferBeforeFlush = decoder.dequeueInputBuffer();
    CountDownLatch inputBufferReclaimedLatch = new CountDownLatch(1);
    @Nullable DecoderInputBuffer[] reclaimedBuffer = new DecoderInputBuffer[1];
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {
            try {
              reclaimedBuffer[0] = decoder.dequeueInputBuffer();
            } catch (Dav1dDecoderException e) {
              throw new RuntimeException(e);
            } finally {
              inputBufferReclaimedLatch.countDown();
            }
          }

          @Override
          public void onOutputBufferAvailable() {}
        },
        Runnable::run);

    decoder.flush();
    boolean callbackReceived = inputBufferReclaimedLatch.await(5, SECONDS);

    assertThat(firstOutputAvailable).isTrue();
    assertThat(inputBufferBeforeFlush).isNull();
    assertWithMessage("Timed out waiting for reclaimed input buffer after flush")
        .that(callbackReceived)
        .isTrue();
    assertThat(reclaimedBuffer[0]).isSameInstanceAs(initialBuffer);
  }

  @Test(timeout = 10_000)
  public void decode_onMidStreamResolutionChange_updatesOutputBufferDimensions() throws Exception {
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    CountDownLatch outputsAvailableLatch = new CountDownLatch(2);
    decoder.setCallback(
        new Decoder.Callback() {
          @Override
          public void onInputBufferAvailable() {}

          @Override
          public void onOutputBufferAvailable() {
            outputsAvailableLatch.countDown();
          }
        },
        Runnable::run);
    DecoderInputBuffer firstInput = checkNotNull(decoder.dequeueInputBuffer());
    firstInput.ensureSpaceForWrite(av18bitI420Sample.length);
    checkNotNull(firstInput.data).put(av18bitI420Sample).flip();
    firstInput.timeUs = 10_000;
    decoder.queueInputBuffer(firstInput);
    DecoderInputBuffer secondInput = checkNotNull(decoder.dequeueInputBuffer());
    byte[] highResSample = sdr8BitVideoTrack.getSampleData(0);
    secondInput.ensureSpaceForWrite(highResSample.length);
    checkNotNull(secondInput.data).put(highResSample).flip();
    secondInput.timeUs = 20_000;
    decoder.queueInputBuffer(secondInput);
    DecoderInputBuffer eosInput = checkNotNull(decoder.dequeueInputBuffer());
    eosInput.setFlags(C.BUFFER_FLAG_END_OF_STREAM);

    decoder.queueInputBuffer(eosInput);
    boolean outputsAvailable = outputsAvailableLatch.await(5, SECONDS);

    assertWithMessage("Timed out waiting for resolution-change output buffers")
        .that(outputsAvailable)
        .isTrue();
    @Nullable VideoDecoderOutputBuffer firstFrame = decoder.dequeueOutputBuffer();
    @Nullable VideoDecoderOutputBuffer secondFrame = decoder.dequeueOutputBuffer();
    try {
      assertThat(firstFrame).isNotNull();
      assertThat(firstFrame.width).isEqualTo(32);
      assertThat(firstFrame.height).isEqualTo(16);
      assertThat(firstFrame.timeUs).isEqualTo(10_000);
      assertThat(secondFrame).isNotNull();
      assertThat(secondFrame.width).isEqualTo(1080);
      assertThat(secondFrame.height).isEqualTo(720);
      assertThat(secondFrame.timeUs).isEqualTo(20_000);
    } finally {
      if (firstFrame != null) {
        firstFrame.release();
      }
      if (secondFrame != null) {
        secondFrame.release();
      }
    }
  }

  private static @Nullable DecoderInputBuffer pollInputBuffer(Dav1dDecoder decoder, long timeoutMs)
      throws Dav1dDecoderException, InterruptedException {
    long deadlineMs = SystemClock.elapsedRealtime() + timeoutMs;
    while (true) {
      DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
      if (inputBuffer != null || SystemClock.elapsedRealtime() >= deadlineMs) {
        return inputBuffer;
      }
      Thread.sleep(1);
    }
  }

  /**
   * Waits for a pending {@link Dav1dDecoder#flush()} to settle and immediately dequeues and queues
   * the first {@link #NUM_INPUT_BUFFERS} samples of {@code videoTrack} without waiting between
   * buffers.
   *
   * <p>{@link Dav1dDecoder#dequeueInputBuffer()} enforces at most one unqueued input buffer at a
   * time, so each buffer is queued immediately before dequeueing the next to verify that the full
   * input buffer pool is available as soon as the flush completes.
   */
  private static void dequeueAndQueueBurstAfterFlush(
      Dav1dDecoder decoder, FakeTrackOutput videoTrack)
      throws Dav1dDecoderException, InterruptedException {
    DecoderInputBuffer firstBuffer = pollInputBuffer(decoder, DECODE_TIMEOUT_MS);
    assertWithMessage("Timed out waiting for first input buffer after flush")
        .that(firstBuffer)
        .isNotNull();
    queueSampleOrEndOfStream(decoder, firstBuffer, videoTrack, /* sampleIndex= */ 0);

    for (int i = 1; i < NUM_INPUT_BUFFERS; i++) {
      DecoderInputBuffer nextBuffer = decoder.dequeueInputBuffer();
      assertWithMessage(
              "Expected all %s input buffers immediately after flush (null at index %s)",
              NUM_INPUT_BUFFERS, i)
          .that(nextBuffer)
          .isNotNull();
      queueSampleOrEndOfStream(decoder, nextBuffer, videoTrack, i);
    }
  }

  /**
   * Feeds every sample of {@code videoTrack} followed by an end-of-stream buffer, draining output
   * as it becomes available until the end-of-stream output buffer is dequeued.
   *
   * <p>Each non end-of-stream output buffer is rendered to {@code surface} if non-null (asserting
   * that the native picture handle is set before and cleared after rendering), or validated as a
   * YUV buffer otherwise, and then released.
   *
   * @return The number of non end-of-stream output buffers that were dequeued.
   */
  private static int feedAndDrainEntireStream(
      Dav1dDecoder decoder, FakeTrackOutput videoTrack, @Nullable Surface surface)
      throws Dav1dDecoderException, InterruptedException {
    return feedAndDrainStream(decoder, videoTrack, /* startSampleIndex= */ 0, surface);
  }

  private static int feedAndDrainStream(
      Dav1dDecoder decoder,
      FakeTrackOutput videoTrack,
      int startSampleIndex,
      @Nullable Surface surface)
      throws Dav1dDecoderException, InterruptedException {
    int nextSampleIndex = startSampleIndex;
    int outputFrameCount = 0;
    boolean endOfStreamQueued = false;
    long deadlineMs = SystemClock.elapsedRealtime() + STREAM_DECODE_TIMEOUT_MS;

    while (SystemClock.elapsedRealtime() < deadlineMs) {
      boolean madeProgress = false;
      VideoDecoderOutputBuffer outputBuffer = decoder.dequeueOutputBuffer();
      if (outputBuffer != null) {
        madeProgress = true;
        if (outputBuffer.isEndOfStream()) {
          outputBuffer.release();
          return outputFrameCount;
        }
        outputFrameCount++;
        if (surface != null) {
          assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
          assertThat(outputBuffer.decoderPrivate).isNotEqualTo(0);
          decoder.renderToSurface(outputBuffer, surface);
          assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
        } else {
          assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_YUV);
          assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
          assertThat(outputBuffer.yuvPlanes).isNotNull();
        }
        outputBuffer.release();
      }

      if (!endOfStreamQueued) {
        DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
        if (inputBuffer != null) {
          madeProgress = true;
          queueSampleOrEndOfStream(decoder, inputBuffer, videoTrack, nextSampleIndex);
          if (nextSampleIndex < videoTrack.getSampleCount()) {
            nextSampleIndex++;
          } else {
            endOfStreamQueued = true;
          }
        }
      }

      if (!madeProgress) {
        Thread.sleep(5);
      }
    }
    throw new AssertionError(
        "Timed out waiting for end of stream after " + outputFrameCount + " output frames");
  }

  private static VideoDecoderOutputBuffer feedSamplesUntilOutputBuffer(
      Dav1dDecoder decoder, FakeTrackOutput videoTrack)
      throws Dav1dDecoderException, InterruptedException {
    int nextSampleIndex = 0;
    boolean endOfStreamQueued = false;
    long deadlineMs = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS;

    while (SystemClock.elapsedRealtime() < deadlineMs) {
      VideoDecoderOutputBuffer outputBuffer = decoder.dequeueOutputBuffer();
      if (outputBuffer != null) {
        return outputBuffer;
      }

      if (!endOfStreamQueued) {
        DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
        if (inputBuffer != null) {
          queueSampleOrEndOfStream(decoder, inputBuffer, videoTrack, nextSampleIndex);
          if (nextSampleIndex < videoTrack.getSampleCount()) {
            nextSampleIndex++;
          } else {
            endOfStreamQueued = true;
          }
          continue;
        }
      }

      Thread.sleep(5);
    }
    throw new AssertionError("Timed out waiting for decoded output buffer");
  }

  /**
   * Fills {@code inputBuffer} with sample {@code sampleIndex} of {@code videoTrack}, or flags it as
   * end-of-stream if {@code sampleIndex} is past the last sample, and queues it to the decoder.
   */
  private static void queueSampleOrEndOfStream(
      Dav1dDecoder decoder,
      DecoderInputBuffer inputBuffer,
      FakeTrackOutput videoTrack,
      int sampleIndex)
      throws Dav1dDecoderException {
    inputBuffer.clear();
    if (sampleIndex < videoTrack.getSampleCount()) {
      byte[] sampleData = videoTrack.getSampleData(sampleIndex);
      inputBuffer.ensureSpaceForWrite(sampleData.length);
      inputBuffer.data.put(sampleData);
      inputBuffer.flip();
      inputBuffer.timeUs = videoTrack.getSampleTimeUs(sampleIndex);
      if ((videoTrack.getSampleFlags(sampleIndex) & C.BUFFER_FLAG_KEY_FRAME) != 0) {
        inputBuffer.addFlag(C.BUFFER_FLAG_KEY_FRAME);
      }
      if (sampleIndex == 0) {
        inputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
      }
    } else {
      inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    }
    decoder.queueInputBuffer(inputBuffer);
  }

  private static FakeTrackOutput extractVideoTrack(Context context, String assetPath)
      throws IOException {
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
}
