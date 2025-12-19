/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.test.utils.TestUtil.extractAllSamplesFromFilePath;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.media.Image;
import android.media.MediaCodecInfo;
import android.media.metrics.LogSessionId;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoGraph.Listener;
import androidx.media3.common.util.GlRect;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Size;
import androidx.media3.effect.ByteBufferGlEffect;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.VideoDecodingWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AssumptionViolatedException;

/** Utilities for instrumentation tests. */
public final class AndroidTestUtil {

  /** A {@link MediaCodecVideoRenderer} subclass that supports replaying a frame. */
  public static class ReplayVideoRenderer extends MediaCodecVideoRenderer {

    public ReplayVideoRenderer(Context context) {
      super(new Builder(context).setMediaCodecSelector(MediaCodecSelector.DEFAULT));
    }

    @Override
    protected PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
        Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      return new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
          .setEnablePlaylistMode(true)
          .setClock(getClock())
          .setEnableReplayableCache(true)
          .build();
    }
  }

  private static final String TAG = "AndroidTestUtil";

  /** An {@link Effects} instance that forces video transcoding. */
  public static final Effects FORCE_TRANSCODE_VIDEO_EFFECTS =
      new Effects(
          /* audioProcessors= */ ImmutableList.of(),
          ImmutableList.of(
              new ScaleAndRotateTransformation.Builder().setRotationDegrees(45).build()));

  /** A {@link GlEffect} that adds delay in the video pipeline by putting the thread to sleep. */
  public static final class DelayEffect implements GlEffect {
    private final long delayMs;

    public DelayEffect(long delayMs) {
      this.delayMs = delayMs;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
      return new PassthroughShaderProgram() {
        @Override
        public void queueInputFrame(
            GlObjectsProvider glObjectsProvider,
            GlTextureInfo inputTexture,
            long presentationTimeUs) {
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onError(e);
            return;
          }
          super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
        }
      };
    }
  }

  /** A {@link VideoGraph.Factory} that records test interactions. */
  public static class TestVideoGraphFactory implements VideoGraph.Factory {

    private final VideoGraph.Factory singleInputVideoGraphFactory;

    @Nullable private ColorInfo outputColorInfo;

    public TestVideoGraphFactory() {
      singleInputVideoGraphFactory = new SingleInputVideoGraph.Factory();
    }

    @Override
    public VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      this.outputColorInfo = outputColorInfo;
      return singleInputVideoGraphFactory.create(
          context,
          outputColorInfo,
          debugViewProvider,
          listener,
          listenerExecutor,
          initialTimestampOffsetUs,
          renderFramesAutomatically);
    }

    /** Runs the given task and blocks until it completes, or timeoutSeconds has elapsed. */
    public static void runAsyncTaskAndWait(ThrowingRunnable task, int timeoutSeconds)
        throws TimeoutException, InterruptedException {
      CountDownLatch countDownLatch = new CountDownLatch(1);
      AtomicReference<@NullableType Exception> unexpectedExceptionReference =
          new AtomicReference<>();
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                try {
                  task.run();
                  // Catch all exceptions to report. Exceptions thrown here and not caught will NOT
                  // propagate.
                } catch (Exception e) {
                  unexpectedExceptionReference.set(e);
                } finally {
                  countDownLatch.countDown();
                }
              });

      // Block here until timeout reached or latch is counted down.
      if (!countDownLatch.await(timeoutSeconds, SECONDS)) {
        throw new TimeoutException("Timed out after " + timeoutSeconds + " seconds.");
      }
      @Nullable Exception unexpectedException = unexpectedExceptionReference.get();
      if (unexpectedException != null) {
        throw new IllegalStateException(unexpectedException);
      }
    }

    @Override
    public boolean supportsMultipleInputs() {
      return singleInputVideoGraphFactory.supportsMultipleInputs();
    }

    @Nullable
    public ColorInfo getOutputColorInfo() {
      return outputColorInfo;
    }
  }

  /** A type that can be used to succinctly wrap throwing {@link Runnable} objects. */
  public interface ThrowingRunnable {
    void run() throws Exception;
  }

  /**
   * Creates the GL objects needed to set up a GL environment including an {@link EGLDisplay} and an
   * {@link EGLContext}.
   */
  public static EGLContext createOpenGlObjects() throws GlUtil.GlException {
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    GlObjectsProvider glObjectsProvider =
        new DefaultGlObjectsProvider(/* sharedEglContext= */ null);
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    return eglContext;
  }

  /**
   * Generates a {@linkplain android.opengl.GLES10#GL_TEXTURE_2D traditional GLES texture} from the
   * given bitmap.
   *
   * <p>Must have a GL context set up.
   */
  public static int generateTextureFromBitmap(Bitmap bitmap) throws GlUtil.GlException {
    return GlUtil.createTexture(bitmap);
  }

  public static void assertSdrColors(Context context, String filePath)
      throws ExecutionException, InterruptedException {
    ColorInfo colorInfo = retrieveTrackFormat(context, filePath, C.TRACK_TYPE_VIDEO).colorInfo;
    // Allow unset color values as some encoders don't encode color information for the standard SDR
    // dataspace.
    assertThat(colorInfo.colorTransfer).isAnyOf(C.COLOR_TRANSFER_SDR, Format.NO_VALUE);
    // Before API 34 some encoders output a BT.601 bitstream even though we request BT.709 for SDR
    // output, so allow both color spaces in output files when checking for SDR.
    assertThat(colorInfo.colorSpace)
        .isAnyOf(C.COLOR_SPACE_BT709, C.COLOR_SPACE_BT601, Format.NO_VALUE);
  }

  public static ImmutableList<Bitmap> extractBitmapsFromVideo(Context context, String filePath)
      throws IOException, InterruptedException {
    return extractBitmapsFromVideo(context, filePath, Config.ARGB_8888);
  }

  public static ImmutableList<Bitmap> extractBitmapsFromVideo(
      Context context, String filePath, Bitmap.Config config)
      throws IOException, InterruptedException {
    ImmutableList.Builder<Bitmap> bitmaps = new ImmutableList.Builder<>();
    try (VideoDecodingWrapper decodingWrapper =
        new VideoDecodingWrapper(
            context, filePath, /* comparisonInterval= */ 1, /* maxImagesAllowed= */ 1)) {
      while (true) {
        @Nullable Image image = decodingWrapper.runUntilComparisonFrameOrEnded();
        if (image == null) {
          break;
        }
        bitmaps.add(BitmapPixelTestUtil.createGrayscaleBitmapFromYuv420888Image(image, config));
        image.close();
      }
    }
    return bitmaps.build();
  }

  /**
   * Creates a {@link GlEffect} that counts the number of frames processed in {@code frameCount}.
   */
  public static GlEffect createFrameCountingEffect(AtomicInteger frameCount) {
    return new GlEffect() {
      @Override
      public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
        return new PassthroughShaderProgram() {
          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
            frameCount.incrementAndGet();
          }
        };
      }
    };
  }

  /** A customizable forwarding {@link Codec.EncoderFactory} that forces encoding. */
  public static final class ForceEncodeEncoderFactory implements Codec.EncoderFactory {

    private final Codec.EncoderFactory encoderFactory;

    /** Creates an instance that wraps {@link DefaultEncoderFactory}. */
    public ForceEncodeEncoderFactory(Context context) {
      encoderFactory = new DefaultEncoderFactory.Builder(context).build();
    }

    /**
     * Creates an instance that wraps {@link DefaultEncoderFactory} that wraps another {@link
     * Codec.EncoderFactory}.
     */
    public ForceEncodeEncoderFactory(Codec.EncoderFactory wrappedEncoderFactory) {
      this.encoderFactory = wrappedEncoderFactory;
    }

    @Override
    public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      return encoderFactory.createForAudioEncoding(format, logSessionId);
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      return encoderFactory.createForVideoEncoding(format, logSessionId);
    }

    @Override
    public boolean audioNeedsEncoding() {
      return true;
    }

    @Override
    public boolean videoNeedsEncoding() {
      return true;
    }
  }

  /** A {@link Muxer.Factory} that creates {@link FrameBlockingMuxer} instances. */
  public static final class FrameBlockingMuxerFactory implements Muxer.Factory {
    private final Muxer.Factory wrappedMuxerFactory;
    private final FrameBlockingMuxer.Listener listener;
    private final long presentationTimeUsToBlockFrame;

    FrameBlockingMuxerFactory(
        long presentationTimeUsToBlockFrame, FrameBlockingMuxer.Listener listener) {
      this.wrappedMuxerFactory = new DefaultMuxer.Factory();
      this.listener = listener;
      this.presentationTimeUsToBlockFrame = presentationTimeUsToBlockFrame;
    }

    @Override
    public Muxer create(String path) throws MuxerException {
      return new FrameBlockingMuxer(
          wrappedMuxerFactory.create(path), presentationTimeUsToBlockFrame, listener);
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
      return wrappedMuxerFactory.getSupportedSampleMimeTypes(trackType);
    }
  }

  /** A {@link Muxer} that blocks writing video frames after a specific presentation timestamp. */
  public static final class FrameBlockingMuxer implements Muxer {
    interface Listener {
      void onFrameBlocked();
    }

    private final Muxer wrappedMuxer;
    private final FrameBlockingMuxer.Listener listener;
    private final long presentationTimeUsToBlockFrame;

    private boolean notifiedListener;
    private int videoTrackId;

    private FrameBlockingMuxer(
        Muxer wrappedMuxer,
        long presentationTimeUsToBlockFrame,
        FrameBlockingMuxer.Listener listener) {
      this.wrappedMuxer = wrappedMuxer;
      this.listener = listener;
      this.presentationTimeUsToBlockFrame = presentationTimeUsToBlockFrame;
    }

    @Override
    public int addTrack(Format format) throws MuxerException {
      int trackId = wrappedMuxer.addTrack(format);
      if (MimeTypes.isVideo(format.sampleMimeType)) {
        videoTrackId = trackId;
      }
      return trackId;
    }

    @Override
    public void writeSampleData(int trackId, ByteBuffer data, BufferInfo bufferInfo)
        throws MuxerException {
      if (trackId == videoTrackId
          && bufferInfo.presentationTimeUs >= presentationTimeUsToBlockFrame) {
        if (!notifiedListener) {
          listener.onFrameBlocked();
          notifiedListener = true;
        }
        return;
      }
      wrappedMuxer.writeSampleData(trackId, data, bufferInfo);
    }

    @Override
    public void addMetadataEntry(Metadata.Entry metadataEntry) {
      wrappedMuxer.addMetadataEntry(metadataEntry);
    }

    @Override
    public void close() throws MuxerException {
      wrappedMuxer.close();
    }
  }

  /** A {@link Muxer} that notifies after a specified sample batch size is written. */
  public static final class BatchProgressReportingMuxer implements Muxer {
    interface Listener {
      void onNextBatchWritten();
    }

    /** A {@link Muxer.Factory} that creates {@link BatchProgressReportingMuxer} instances. */
    public static final class Factory implements Muxer.Factory {
      private final Muxer.Factory wrappedMuxerFactory;
      private final BatchProgressReportingMuxer.Listener listener;
      private final int sampleBatchSize;

      public Factory(int sampleBatchSize, BatchProgressReportingMuxer.Listener listener) {
        this.wrappedMuxerFactory = new DefaultMuxer.Factory();
        this.listener = listener;
        this.sampleBatchSize = sampleBatchSize;
      }

      @Override
      public Muxer create(String path) throws MuxerException {
        return new BatchProgressReportingMuxer(
            wrappedMuxerFactory.create(path), sampleBatchSize, listener);
      }

      @Override
      public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
        return wrappedMuxerFactory.getSupportedSampleMimeTypes(trackType);
      }
    }

    private final Muxer wrappedMuxer;
    private final BatchProgressReportingMuxer.Listener listener;
    private final int sampleBatchSize;

    private int samplesWritten;

    private BatchProgressReportingMuxer(
        Muxer wrappedMuxer, int sampleBatchSize, BatchProgressReportingMuxer.Listener listener) {
      this.wrappedMuxer = wrappedMuxer;
      this.listener = listener;
      this.sampleBatchSize = sampleBatchSize;
    }

    @Override
    public int addTrack(Format format) throws MuxerException {
      return wrappedMuxer.addTrack(format);
    }

    @Override
    public void writeSampleData(int trackId, ByteBuffer data, BufferInfo bufferInfo)
        throws MuxerException {
      wrappedMuxer.writeSampleData(trackId, data, bufferInfo);
      samplesWritten++;
      if (samplesWritten % sampleBatchSize == 0) {
        listener.onNextBatchWritten();
      }
    }

    @Override
    public void addMetadataEntry(Metadata.Entry metadataEntry) {
      wrappedMuxer.addMetadataEntry(metadataEntry);
    }

    @Override
    public void close() throws MuxerException {
      wrappedMuxer.close();
    }
  }

  /**
   * Implementation of {@link ByteBufferGlEffect.Processor} that counts how many frames are copied
   * to CPU memory.
   */
  public static final class FrameCountingByteBufferProcessor
      implements ByteBufferGlEffect.Processor<Integer> {
    public final AtomicInteger frameCount;

    private int width;
    private int height;

    public FrameCountingByteBufferProcessor() {
      frameCount = new AtomicInteger();
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
      width = inputWidth;
      height = inputHeight;
      return new Size(width, height);
    }

    @Override
    public GlRect getScaledRegion(long presentationTimeUs) {
      return new GlRect(width, height);
    }

    @Override
    public ListenableFuture<Integer> processImage(
        ByteBufferGlEffect.Image image, long presentationTimeUs) {
      return immediateFuture(frameCount.incrementAndGet());
    }

    @Override
    public void finishProcessingAndBlend(
        GlTextureInfo outputFrame, long presentationTimeUs, Integer result) {}

    @Override
    public void release() {}
  }

  /**
   * Assumes that the device supports encoding with the given MIME type and profile.
   *
   * @param mimeType The {@linkplain MimeTypes MIME type}.
   * @param profile The {@linkplain MediaCodecInfo.CodecProfileLevel codec profile}.
   * @throws AssumptionViolatedException If the device does have required encoder or profile.
   */
  public static void assumeCanEncodeWithProfile(String mimeType, int profile) {
    ImmutableList<MediaCodecInfo> supportedEncoders = EncoderUtil.getSupportedEncoders(mimeType);
    if (supportedEncoders.isEmpty()) {
      throw new AssumptionViolatedException("No supported encoders");
    }

    for (int i = 0; i < supportedEncoders.size(); i++) {
      if (EncoderUtil.findSupportedEncodingProfiles(supportedEncoders.get(i), mimeType)
          .contains(profile)) {
        return;
      }
    }
    throw new AssumptionViolatedException("Profile not supported");
  }

  /**
   * Returns the video timestamps of the given file from the {@link FakeTrackOutput}.
   *
   * @param filePath The {@link String filepath} to get video timestamps for.
   * @return The {@link List} of video timestamps.
   */
  public static ImmutableList<Long> getVideoSampleTimesUs(String filePath) throws IOException {
    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        extractAllSamplesFromFilePath(mp4Extractor, checkNotNull(filePath));
    return Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .getSampleTimesUs();
  }

  private AndroidTestUtil() {}
}
