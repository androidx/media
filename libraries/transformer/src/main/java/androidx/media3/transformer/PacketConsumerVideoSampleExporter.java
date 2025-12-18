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

import static androidx.media3.common.C.COLOR_RANGE_FULL;
import static androidx.media3.common.C.COLOR_SPACE_BT2020;
import static androidx.media3.common.C.COLOR_TRANSFER_HLG;
import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.isTransferHdr;
import static androidx.media3.effect.HardwareBufferFrame.END_OF_STREAM_FRAME;
import static androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.media.MediaCodec.BufferInfo;
import android.media.metrics.LogSessionId;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.GlTextureFrameRenderer;
import androidx.media3.effect.GlTextureFrameRenderer.Listener;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.PacketConsumer.Packet;
import androidx.media3.effect.PacketConsumer.Packet.EndOfStream;
import androidx.media3.effect.PacketConsumerCaller;
import androidx.media3.effect.PacketConsumerUtil;
import androidx.media3.effect.PacketProcessor;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.transformer.Codec.EncoderFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.initialization.qual.Initialized;

/** Processes, encodes and muxes raw video frames. */
/* package */ final class PacketConsumerVideoSampleExporter extends SampleExporter {

  private static final long RELEASE_TIMEOUT_MS = 100;

  private final VideoGraphWrapper videoGraphWrapper;
  private final VideoEncoderWrapper encoderWrapper;
  private final DecoderInputBuffer encoderOutputBuffer;
  private final GlObjectsProvider glObjectsProvider;
  private final ExecutorService glExecutorService;
  private final Consumer<ExportException> errorConsumer;
  private final GlTextureFrameRenderer glTextureFrameRenderer;
  private final PacketProcessor<List<? extends GlTextureFrame>, GlTextureFrame> packetProcessor;
  private final PacketConsumerCaller<List<? extends GlTextureFrame>> packetConsumerCaller;
  private final Composition composition;
  private final FrameAggregator frameAggregator;

  /**
   * The timestamp of the last buffer processed before {@linkplain
   * VideoFrameProcessor.Listener#onEnded() frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;
  private boolean hasProducedFrameWithTimestampZero;

  public PacketConsumerVideoSampleExporter(
      Context context,
      Composition composition,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      PacketProcessor<List<? extends GlTextureFrame>, GlTextureFrame> packetProcessor,
      GlObjectsProvider glObjectsProvider,
      ExecutorService glExecutorService,
      EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      Consumer<ExportException> errorConsumer,
      FallbackListener fallbackListener,
      DebugViewProvider debugViewProvider,
      long initialTimestampOffsetUs,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      @Nullable LogSessionId logSessionId)
      throws ExportException {
    // TODO: b/278259383 - Consider delaying configuration of VideoSampleExporter to use the decoder
    //  output format instead of the extractor output format, to match AudioSampleExporter behavior.
    super(firstInputFormat, muxerWrapper);
    checkArgument(
        composition.sequences.size() == 1,
        "Transformer with PacketProcessor supports only single-sequence");
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.errorConsumer = errorConsumer;
    this.composition = composition;

    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;

    ColorInfo videoGraphInputColor = checkNotNull(firstInputFormat.colorInfo);
    ColorInfo videoGraphOutputColor;
    if (Objects.equals(firstInputFormat.sampleMimeType, MimeTypes.IMAGE_JPEG_R)
        && videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_SRGB) {
      // We only support the sRGB color transfer for Ultra HDR images.
      // When an Ultra HDR image transcoded into a video, we use BT2020 HLG full range colors in the
      // resulting HDR video.
      videoGraphOutputColor =
          new ColorInfo.Builder()
              .setColorSpace(COLOR_SPACE_BT2020)
              .setColorTransfer(COLOR_TRANSFER_HLG)
              .setColorRange(COLOR_RANGE_FULL)
              .build();
    } else if (videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_SRGB
        || videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2) {
      // Convert to BT.709 which is a more commonly used color space.
      // COLOR_TRANSFER_SDR (BT.709), COLOR_TRANSFER_SRGB and COLOR_TRANSFER_GAMMA_2_2 are similar,
      // so the conversion should not bring a large quality degradation.
      videoGraphOutputColor = SDR_BT709_LIMITED;
    } else {
      videoGraphOutputColor = videoGraphInputColor;
    }

    encoderWrapper =
        new VideoEncoderWrapper(
            encoderFactory,
            firstInputFormat.buildUpon().setColorInfo(videoGraphOutputColor).build(),
            allowedEncodingRotationDegrees,
            muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO),
            transformationRequest,
            fallbackListener,
            logSessionId);
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    boolean isGlToneMapping =
        encoderWrapper.getHdrModeAfterFallback() == HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            && isTransferHdr(videoGraphInputColor);
    if (isGlToneMapping) {
      videoGraphOutputColor = SDR_BT709_LIMITED;
    }

    glTextureFrameRenderer =
        GlTextureFrameRenderer.create(
            context,
            listeningDecorator(glExecutorService),
            glObjectsProvider,
            (e) -> errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e)),
            new FrameRendererListener(errorConsumer));
    this.packetProcessor = packetProcessor;
    this.packetProcessor.setOutput(glTextureFrameRenderer);
    packetConsumerCaller =
        PacketConsumerCaller.create(
            packetProcessor,
            glExecutorService,
            (e) ->
                errorConsumer.accept(
                    ExportException.createForVideoFrameProcessingException(
                        VideoFrameProcessingException.from(e))));
    packetConsumerCaller.run();

    @SuppressWarnings("nullness:assignment")
    @Initialized
    PacketConsumerVideoSampleExporter thisRef = this;

    frameAggregator =
        new FrameAggregator(composition.sequences.size(), thisRef::queueAggregatedFrames);
    try {
      videoGraphWrapper =
          new VideoGraphWrapper(
              context,
              videoFrameProcessorFactory,
              videoGraphOutputColor,
              glObjectsProvider,
              glExecutorService,
              debugViewProvider,
              errorConsumer,
              initialTimestampOffsetUs);
      videoGraphWrapper.initialize();
    } catch (VideoFrameProcessingException e) {
      throw ExportException.createForVideoFrameProcessingException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private void queueAggregatedFrames(List<HardwareBufferFrame> frames) {
    // We don't need to apply backpressure here - it's applied implicitly via the texture listener
    // capacity.
    ListenableFuture<Void> queuePacketFuture;

    if (frames.contains(END_OF_STREAM_FRAME)) {
      queuePacketFuture = packetConsumerCaller.queuePacket(EndOfStream.INSTANCE);
    } else {
      ImmutableList.Builder<GlTextureFrame> framesWithReleaseTime = new ImmutableList.Builder<>();
      for (int i = 0; i < frames.size(); i++) {
        // TODO: b/449956936 - Use HardwareBufferFrame instead of this non-functional wrapping
        // of GlTextureFrame.
        GlTextureFrame glTextureFrame = (GlTextureFrame) frames.get(i).internalFrame;
        // The encoder will use the releaseTimeNs as the frame's presentation time.
        framesWithReleaseTime.add(
            checkNotNull(glTextureFrame)
                .buildUpon()
                .setReleaseTimeNs(glTextureFrame.presentationTimeUs * 1000)
                .build());
      }

      queuePacketFuture =
          packetConsumerCaller.queuePacket(Packet.of(framesWithReleaseTime.build()));
    }
    Futures.addCallback(
        queuePacketFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {}

          @Override
          public void onFailure(Throwable t) {
            errorConsumer.accept(ExportException.createForUnexpected(t));
          }
        },
        directExecutor());
  }

  @Override
  public GraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex) {
    return videoGraphWrapper.videoEncoderGraphInput;
  }

  @Override
  public void release() {
    videoGraphWrapper.release();
    encoderWrapper.release();
    // Wait until the frame renderer and packet processors are released.
    ListenableFuture<Void> releaseFrameRendererFuture =
        PacketConsumerUtil.release(glTextureFrameRenderer, glExecutorService);
    ListenableFuture<Void> releasePacketProcessorFuture =
        PacketConsumerUtil.release(packetProcessor, glExecutorService);

    ListenableFuture<List<Void>> releaseFutures =
        Futures.allAsList(
            ImmutableList.of(releasePacketProcessorFuture, releaseFrameRendererFuture));

    @Nullable Exception exception = null;
    try {
      releaseFutures.get(RELEASE_TIMEOUT_MS, MILLISECONDS);
      glObjectsProvider.release(GlUtil.getDefaultEglDisplay());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exception = e;
    } catch (Exception e) {
      exception = e;
    } finally {
      glExecutorService.shutdown();
      if (exception != null) {
        errorConsumer.accept(ExportException.createForUnexpected(exception));
      }
    }
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    return encoderWrapper.getOutputFormat();
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    encoderOutputBuffer.data = encoderWrapper.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    BufferInfo bufferInfo = checkNotNull(encoderWrapper.getOutputBufferInfo());
    if (bufferInfo.presentationTimeUs == 0) {
      // Internal ref b/235045165: Some encoder incorrectly set a zero presentation time on the
      // penultimate buffer (before EOS), and sets the actual timestamp on the EOS buffer. Use the
      // last processed frame presentation time instead.
      if (hasProducedFrameWithTimestampZero == hasMuxedTimestampZero
          && finalFramePresentationTimeUs != C.TIME_UNSET
          && bufferInfo.size > 0) {
        bufferInfo.presentationTimeUs = finalFramePresentationTimeUs;
      }
    }
    encoderOutputBuffer.timeUs = bufferInfo.presentationTimeUs;
    encoderOutputBuffer.setFlags(bufferInfo.flags);
    lastMuxerInputBufferTimestampUs = bufferInfo.presentationTimeUs;
    return encoderOutputBuffer;
  }

  @Override
  protected void releaseMuxerInputBuffer() throws ExportException {
    if (lastMuxerInputBufferTimestampUs == 0) {
      hasMuxedTimestampZero = true;
    }
    encoderWrapper.releaseOutputBuffer(/* render= */ false);
  }

  @Override
  protected boolean isMuxerInputEnded() {
    return encoderWrapper.isEnded();
  }

  public final class VideoGraphWrapper implements VideoGraph.Listener {

    private final Consumer<ExportException> errorConsumer;
    private final VideoGraph videoGraph;
    private final VideoEncoderGraphInput videoEncoderGraphInput;

    public VideoGraphWrapper(
        Context context,
        VideoFrameProcessor.Factory videoFrameProcessorFactory,
        ColorInfo videoFrameProcessorOutputColor,
        GlObjectsProvider glObjectsProvider,
        ExecutorService glExecutorService,
        DebugViewProvider debugViewProvider,
        Consumer<ExportException> errorConsumer,
        long initialTimestampOffsetUs)
        throws VideoFrameProcessingException {
      if (!(videoFrameProcessorFactory instanceof DefaultVideoFrameProcessor.Factory)) {
        videoFrameProcessorFactory = new DefaultVideoFrameProcessor.Factory.Builder().build();
      }
      DefaultVideoFrameProcessor.Factory defaultVideoFrameProcessorFactory =
          ((DefaultVideoFrameProcessor.Factory) videoFrameProcessorFactory)
              .buildUpon()
              .setGlObjectsProvider(glObjectsProvider)
              .setExecutorService(glExecutorService)
              .build();
      this.errorConsumer = errorConsumer;

      // TODO: b/449956776 - Add multiple sequence support
      int sequenceIndex = 0;
      CompositionTextureListener textureListener =
          new CompositionTextureListener(
              checkNotNull(composition),
              sequenceIndex,
              checkNotNull(frameAggregator),
              glExecutorService);
      InternalListener videoGraphListener = new InternalListener(textureListener, sequenceIndex);
      videoGraph =
          new SingleInputVideoGraph.Factory(
                  defaultVideoFrameProcessorFactory
                      .buildUpon()
                      .setTextureOutput(textureListener, /* textureOutputCapacity= */ 2)
                      .build())
              .create(
                  context,
                  videoFrameProcessorOutputColor,
                  debugViewProvider,
                  videoGraphListener,
                  /* listenerExecutor= */ directExecutor(),
                  initialTimestampOffsetUs,
                  /* renderFramesAutomatically= */ true);
      videoGraph.registerInput(sequenceIndex);
      videoEncoderGraphInput =
          new VideoEncoderGraphInput(videoGraph, sequenceIndex, initialTimestampOffsetUs);
    }

    public void initialize() throws VideoFrameProcessingException {
      videoGraph.initialize();
    }

    public void release() {
      videoGraph.release();
    }

    private class InternalListener implements VideoGraph.Listener {

      private final CompositionTextureListener textureListener;
      private final int inputIndex;

      private InternalListener(CompositionTextureListener textureListener, int inputIndex) {
        this.textureListener = textureListener;
        this.inputIndex = inputIndex;
      }

      @Override
      public void onOutputFrameAvailableForRendering(
          long framePresentationTimeUs, boolean isRedrawnFrame) {
        textureListener.willOutputFrame(framePresentationTimeUs, inputIndex);
      }

      @Override
      public void onEnded(long finalFramePresentationTimeUs) {
        checkNotNull(frameAggregator).queueEndOfStream(inputIndex);
      }

      @Override
      public void onError(VideoFrameProcessingException e) {
        errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
      }
    }
  }

  private class FrameRendererListener implements Listener {

    private final Consumer<ExportException> errorConsumer;

    private FrameRendererListener(Consumer<ExportException> errorConsumer) {
      this.errorConsumer = errorConsumer;
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
      @Nullable SurfaceInfo surfaceInfo = null;
      try {
        surfaceInfo = encoderWrapper.getSurfaceInfo(width, height);
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
      glTextureFrameRenderer.setOutputSurfaceInfo(surfaceInfo);
    }

    @Override
    public void onOutputFrameAvailableForRendering(long presentationTimeUs) {
      if (presentationTimeUs == 0) {
        hasProducedFrameWithTimestampZero = true;
      }
    }

    @Override
    public void onEnded() {
      try {
        encoderWrapper.signalEndOfInputStream();
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
      finalFramePresentationTimeUs = C.TIME_UNSET;
    }
  }
}
