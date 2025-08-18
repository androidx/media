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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
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
import android.os.Build;
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
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import androidx.media3.effect.ByteBufferGlEffect;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.media3.test.utils.VideoDecodingWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;
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

  /**
   * Log in logcat and in an analysis file that this test was skipped.
   *
   * <p>Analysis file is a JSON summarising the test, saved to the application cache.
   *
   * <p>The analysis json will contain a {@code skipReason} key, with the reason for skipping the
   * test case.
   */
  public static void recordTestSkipped(Context context, String testId, String reason)
      throws JSONException, IOException {
    Log.i(TAG, testId + ": " + reason);
    JSONObject testJson = new JSONObject();
    testJson.put("skipReason", reason);

    writeTestSummaryToFile(context, testId, testJson);
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
   * Writes the summary of a test run to the application cache file.
   *
   * <p>The cache filename follows the pattern {@code <testId>-result.txt}.
   *
   * @param context The {@link Context}.
   * @param testId A unique identifier for the transformer test run.
   * @param testJson A {@link JSONObject} containing a summary of the test run.
   */
  public static void writeTestSummaryToFile(Context context, String testId, JSONObject testJson)
      throws IOException, JSONException {
    testJson.put("testId", testId).put("device", JsonUtil.getDeviceDetailsAsJsonObject());

    String analysisContents = testJson.toString(/* indentSpaces= */ 2);

    // Log contents as well as writing to file, for easier visibility on individual device testing.
    for (String line : Util.split(analysisContents, "\n")) {
      Log.i(TAG, testId + ": " + line);
    }

    File analysisFile =
        createExternalCacheFile(
            context, /* directoryName= */ "analysis", /* fileName= */ testId + "-result.txt");
    try (FileWriter fileWriter = new FileWriter(analysisFile)) {
      fileWriter.write(analysisContents);
    }
  }

  /**
   * Assumes that the device supports decoding the input format, and encoding/muxing the output
   * format if needed.
   *
   * <p>This is equivalent to calling {@link #assumeFormatsSupported(Context, String, Format,
   * Format, boolean)} with {@code isPortraitEncodingEnabled} set to {@code false}.
   */
  public static void assumeFormatsSupported(
      Context context, String testId, @Nullable Format inputFormat, @Nullable Format outputFormat)
      throws IOException, JSONException, MediaCodecUtil.DecoderQueryException {
    assumeFormatsSupported(
        context, testId, inputFormat, outputFormat, /* isPortraitEncodingEnabled= */ false);
  }

  /**
   * Assumes that the device supports decoding the input format, and encoding/muxing the output
   * format if needed.
   *
   * @param context The {@link Context context}.
   * @param testId The test ID.
   * @param inputFormat The {@link Format format} to decode, or the input is not produced by
   *     MediaCodec, like an image.
   * @param outputFormat The {@link Format format} to encode/mux or {@code null} if the output won't
   *     be encoded or muxed.
   * @param isPortraitEncodingEnabled Whether portrait encoding is enabled.
   * @throws AssumptionViolatedException If the device does not support the formats. In this case,
   *     the reason for skipping the test is logged.
   */
  public static void assumeFormatsSupported(
      Context context,
      String testId,
      @Nullable Format inputFormat,
      @Nullable Format outputFormat,
      boolean isPortraitEncodingEnabled)
      throws IOException, JSONException, MediaCodecUtil.DecoderQueryException {
    boolean canDecode = inputFormat == null || canDecode(inputFormat);

    boolean canEncode = outputFormat == null || canEncode(outputFormat, isPortraitEncodingEnabled);
    boolean canMux = outputFormat == null || canMux(outputFormat);
    if (canDecode && canEncode && canMux) {
      return;
    }

    StringBuilder skipReasonBuilder = new StringBuilder();
    if (!canDecode) {
      skipReasonBuilder.append("Cannot decode ").append(inputFormat).append('\n');
    }
    if (!canEncode) {
      skipReasonBuilder.append("Cannot encode ").append(outputFormat).append('\n');
    }
    if (!canMux) {
      skipReasonBuilder.append("Cannot mux ").append(outputFormat);
    }
    String skipReason = skipReasonBuilder.toString();
    recordTestSkipped(context, testId, skipReason);
    throw new AssumptionViolatedException(skipReason);
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

  /** Returns a {@link Muxer.Factory} depending upon the API level. */
  public static Muxer.Factory getMuxerFactoryBasedOnApi() {
    // MediaMuxer supports B-frame from API > 24.
    return SDK_INT > 24 ? new DefaultMuxer.Factory() : new InAppMp4Muxer.Factory();
  }

  private static boolean canDecode(Format format) throws MediaCodecUtil.DecoderQueryException {
    if (MimeTypes.isImage(format.sampleMimeType)) {
      return Util.isBitmapFactorySupportedMimeType(format.sampleMimeType);
    }

    // Check decoding capability in the same way as the default decoder factory.
    return findDecoderForFormat(format) != null && !deviceNeedsDisable8kWorkaround(format);
  }

  @Nullable
  private static String findDecoderForFormat(Format format)
      throws MediaCodecUtil.DecoderQueryException {
    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> decoderInfoList =
        MediaCodecUtil.getDecoderInfosSortedByFullFormatSupport(
            MediaCodecUtil.getDecoderInfosSoftMatch(
                MediaCodecSelector.DEFAULT,
                format,
                /* requiresSecureDecoder= */ false,
                /* requiresTunnelingDecoder= */ false),
            format);

    for (int i = 0; i < decoderInfoList.size(); i++) {
      androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoderInfo = decoderInfoList.get(i);
      // On some devices this method can return false even when the format can be decoded. For
      // example, Pixel 6a can decode an 8K video but this method returns false. The
      // DefaultDecoderFactory does not rely on this method rather it directly initialize the
      // decoder. See b/222095724#comment9.
      if (decoderInfo.isFormatSupported(format)) {
        return decoderInfo.name;
      }
    }

    return null;
  }

  private static boolean deviceNeedsDisable8kWorkaround(Format format) {
    // Fixed on API 31+. See http://b/278234847#comment40 for more information.
    // Duplicate of DefaultDecoderFactory#deviceNeedsDisable8kWorkaround.
    return SDK_INT < 31
        && format.width >= 7680
        && format.height >= 4320
        && format.sampleMimeType != null
        && format.sampleMimeType.equals(MimeTypes.VIDEO_H265)
        && (Ascii.equalsIgnoreCase(Build.MODEL, "SM-F711U1")
            || Ascii.equalsIgnoreCase(Build.MODEL, "SM-F926U1"));
  }

  private static boolean canEncode(Format format, boolean isPortraitEncodingEnabled) {
    String mimeType = checkNotNull(format.sampleMimeType);
    ImmutableList<android.media.MediaCodecInfo> supportedEncoders =
        EncoderUtil.getSupportedEncoders(mimeType);
    if (supportedEncoders.isEmpty()) {
      return false;
    }

    android.media.MediaCodecInfo encoder = supportedEncoders.get(0);
    // VideoSampleExporter rotates videos into landscape before encoding if portrait encoding is not
    // enabled.
    int width = format.width;
    int height = format.height;
    if (!isPortraitEncodingEnabled && width < height) {
      width = format.height;
      height = format.width;
    }
    boolean sizeSupported = EncoderUtil.isSizeSupported(encoder, mimeType, width, height);
    boolean bitrateSupported =
        format.averageBitrate == Format.NO_VALUE
            || EncoderUtil.getSupportedBitrateRange(encoder, mimeType)
                .contains(format.averageBitrate);
    return sizeSupported && bitrateSupported;
  }

  private static boolean canMux(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    return new DefaultMuxer.Factory()
        .getSupportedSampleMimeTypes(MimeTypes.getTrackType(mimeType))
        .contains(mimeType);
  }

  /**
   * Creates a {@link File} of the {@code fileName} in the application cache directory.
   *
   * <p>If a file of that name already exists, it is overwritten.
   *
   * @param context The {@link Context}.
   * @param fileName The filename to save to the cache.
   */
  /* package */ static File createExternalCacheFile(Context context, String fileName)
      throws IOException {
    return createExternalCacheFile(context, /* directoryName= */ "", fileName);
  }

  /**
   * Creates a {@link File} of the {@code fileName} in a directory {@code directoryName} within the
   * application cache directory.
   *
   * <p>If a file of that name already exists, it is overwritten.
   *
   * @param context The {@link Context}.
   * @param directoryName The directory name within the external cache to save the file in.
   * @param fileName The filename to save to the cache.
   */
  /* package */ static File createExternalCacheFile(
      Context context, String directoryName, String fileName) throws IOException {
    File fileDirectory = new File(context.getExternalCacheDir(), directoryName);
    fileDirectory.mkdirs();
    File file = new File(fileDirectory, fileName);
    checkState(
        !file.exists() || file.delete(), "Could not delete file: %s", file.getAbsolutePath());
    checkState(file.createNewFile(), "Could not create file: %s", file.getAbsolutePath());
    return file;
  }

  private AndroidTestUtil() {}
}
