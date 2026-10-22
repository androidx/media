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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.C;
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
   * {@link Dav1dDecoder} emits the end-of-stream output buffer without draining libdav1d, so up to
   * {@code threads + maxFrameDelay} trailing frames may legitimately never be output.
   */
  private static final int EOS_UNDRAINED_FRAME_SLACK = THREADS + MAX_FRAME_DELAY;

  /**
   * Minimum API level for tests that render to a {@link Surface}.
   *
   * <p>On the x86 emulator images below API 29, software-rendering YV12 frames through {@code
   * ANativeWindow_lock}/{@code unlockAndPost} corrupts the native heap and the process later
   * crashes inside libdav1d's picture pool allocator ({@code dav1d_mem_pool_pop}). This reproduces
   * with the unmodified JNI code and does not occur on API 29+ emulators or on real devices.
   */
  private static final int MIN_API_FOR_SURFACE_RENDERING = 29;

  private static FakeTrackOutput sdr8BitVideoTrack;
  private static FakeTrackOutput hdr10BitVideoTrack;

  @Nullable private Dav1dDecoder decoder;
  @Nullable private Surface surface;
  @Nullable private SurfaceTexture surfaceTexture;

  @BeforeClass
  public static void extractTestTracks() throws IOException {
    Context context = ApplicationProvider.getApplicationContext();
    sdr8BitVideoTrack = extractVideoTrack(context, SDR_8BIT_AV1_ASSET);
    hdr10BitVideoTrack = extractVideoTrack(context, HDR_10BIT_AV1_ASSET);
  }

  @Before
  public void setUp() {
    assertWithMessage("Dav1d library is not available").that(Dav1dLibrary.isAvailable()).isTrue();
  }

  @After
  public void tearDown() {
    if (decoder != null) {
      decoder.release();
      decoder = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (surfaceTexture != null) {
      surfaceTexture.release();
      surfaceTexture = null;
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
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);

    assertThat(outputBuffer.mode).isEqualTo(C.VIDEO_OUTPUT_MODE_YUV);
    assertThat(outputBuffer.width).isEqualTo(1080);
    assertThat(outputBuffer.height).isEqualTo(720);
    assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
    assertThat(outputBuffer.data).isNotNull();
    assertThat(outputBuffer.data.remaining()).isGreaterThan(0);
    assertThat(outputBuffer.yuvPlanes).isNotNull();
    assertThat(outputBuffer.yuvPlanes).hasLength(3);
    outputBuffer.release();
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
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    long initialDecoderPrivate = outputBuffer.decoderPrivate;

    // When rendering the output buffer to the surface and then releasing the buffer.
    decoder.renderToSurface(outputBuffer, surface);
    long decoderPrivateAfterRender = outputBuffer.decoderPrivate;
    outputBuffer.release();

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
    decoder = createDecoder();
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
   * Tests decode-only preroll handling when {@code outputStartTimeUs} is set.
   *
   * <p>Ensures that frames earlier than the target start time are marked as decode-only,
   * unreferenced immediately, and skipped without emitting output buffers.
   */
  @Test(timeout = 10_000)
  public void decode_framesBeforeOutputStartTimeUs_skipsDecodeOnlyFrames() throws Exception {
    long targetStartTimeUs = sdr8BitVideoTrack.getSampleTimeUs(3);
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
    decoder.setOutputStartTimeUs(targetStartTimeUs);

    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);

    assertThat(outputBuffer.timeUs).isAtLeast(targetStartTimeUs);
    assertThat(outputBuffer.skippedOutputBufferCount).isEqualTo(3);
    outputBuffer.release();
  }

  /**
   * Tests error handling when attempting 10-bit HDR decoding in software YUV output mode.
   *
   * <p>Ensures that bit-depth validation rejects 10-bit YUV output and unreferences the stack
   * picture before throwing {@link Dav1dDecoderException}.
   */
  @Test(timeout = 10_000)
  public void decode_10BitInYuvOutputMode_throwsDav1dDecoderException() throws Exception {
    decoder = createDecoder();
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
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
    VideoDecoderOutputBuffer outputBuffer =
        feedSamplesUntilOutputBuffer(decoder, sdr8BitVideoTrack);
    decoder.renderToSurface(outputBuffer, surface);

    // When rendering the same buffer a second time.
    Dav1dDecoderException thrown =
        assertThrows(
            Dav1dDecoderException.class, () -> decoder.renderToSurface(outputBuffer, surface));

    // Then the native layer reports the missing picture rather than reading freed memory.
    assertThat(thrown).hasMessageThat().contains("Failed to render output buffer to surface");
    assertThat(outputBuffer.decoderPrivate).isEqualTo(0);
    outputBuffer.release();
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
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);

    // When decoding the whole stream, rendering and releasing every output buffer so that each
    // of the NUM_OUTPUT_BUFFERS buffers is recycled multiple times.
    int renderedFrameCount = feedAndDrainEntireStream(decoder, sdr8BitVideoTrack, surface);

    // Then every emitted frame was rendered without native errors or double frees.
    int sampleCount = sdr8BitVideoTrack.getSampleCount();
    assertThat(renderedFrameCount).isAtLeast(sampleCount - EOS_UNDRAINED_FRAME_SLACK);
    assertThat(renderedFrameCount).isAtMost(sampleCount);
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
    decoder = createDecoder();
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);

    // When decoding the whole stream and releasing every output buffer.
    int outputFrameCount =
        feedAndDrainEntireStream(decoder, sdr8BitVideoTrack, /* surface= */ null);

    // Then every emitted frame carried YUV planes and no native picture handle.
    int sampleCount = sdr8BitVideoTrack.getSampleCount();
    assertThat(outputFrameCount).isAtLeast(sampleCount - EOS_UNDRAINED_FRAME_SLACK);
    assertThat(outputFrameCount).isAtMost(sampleCount);
  }

  private static Dav1dDecoder createDecoder() throws Dav1dDecoderException {
    return new Dav1dDecoder(
        NUM_INPUT_BUFFERS,
        NUM_OUTPUT_BUFFERS,
        INITIAL_INPUT_BUFFER_SIZE,
        THREADS,
        MAX_FRAME_DELAY,
        /* useCustomAllocator= */ false,
        C.THREAD_PRIORITY_NO_OP);
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
    int nextSampleIndex = 0;
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
