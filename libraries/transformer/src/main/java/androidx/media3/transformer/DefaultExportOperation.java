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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.media.metrics.LogSessionId;
import androidx.annotation.Nullable;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.PacketProcessor;
import androidx.media3.muxer.Muxer;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.media3.transformer.Transformer.ProgressState;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** An {@link ExportOperation} implementation for single export operations. */
/* package */ final class DefaultExportOperation implements ExportOperation {

  private final Context context;
  private final Composition composition;
  private final TransformationRequest transformationRequest;
  @Nullable private final AssetLoader.Factory assetLoaderFactory;
  private final AudioMixer.Factory audioMixerFactory;
  private final VideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final int maxFramesInEncoder;
  private final ExportOperation.Listener listener;
  private final FallbackListener fallbackListener;
  private final HandlerWrapper applicationHandler;
  private final DebugViewProvider debugViewProvider;
  private final Clock clock;

  @Nullable
  private final PacketProcessor<List<? extends HardwareBufferFrame>, HardwareBufferFrame>
      packetProcessor;

  @Nullable private final ExecutorService glExecutorService;
  @Nullable private final GlObjectsProvider glObjectsProvider;
  @Nullable private final LogSessionId logSessionId;
  private final boolean applyMp4EditListTrim;
  private final Muxer.Factory muxerFactory;
  private final String outputFilePath;
  private final boolean fileStartsOnVideoFrameEnabled;
  private final ExportResult.Builder exportResultBuilder;
  private final ComponentListener componentListener;

  @Nullable private TransformerInternal transformerInternal;

  public DefaultExportOperation(
      Context context,
      Composition composition,
      TransformationRequest transformationRequest,
      @Nullable AssetLoader.Factory assetLoaderFactory,
      AudioMixer.Factory audioMixerFactory,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      Codec.EncoderFactory encoderFactory,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      int maxFramesInEncoder,
      ExportOperation.Listener listener,
      FallbackListener fallbackListener,
      HandlerWrapper applicationHandler,
      DebugViewProvider debugViewProvider,
      Clock clock,
      @Nullable
          PacketProcessor<List<? extends HardwareBufferFrame>, HardwareBufferFrame> packetProcessor,
      @Nullable ExecutorService glExecutorService,
      @Nullable GlObjectsProvider glObjectsProvider,
      @Nullable LogSessionId logSessionId,
      boolean applyMp4EditListTrim,
      Muxer.Factory muxerFactory,
      String outputFilePath,
      boolean fileStartsOnVideoFrameEnabled) {
    this.context = context;
    this.composition = composition;
    this.transformationRequest = transformationRequest;
    this.assetLoaderFactory = assetLoaderFactory;
    this.audioMixerFactory = audioMixerFactory;
    this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    this.encoderFactory = encoderFactory;
    this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
    this.maxFramesInEncoder = maxFramesInEncoder;
    this.listener = listener;
    this.fallbackListener = fallbackListener;
    this.applicationHandler = applicationHandler;
    this.debugViewProvider = debugViewProvider;
    this.clock = clock;
    this.packetProcessor = packetProcessor;
    this.glExecutorService = glExecutorService;
    this.glObjectsProvider = glObjectsProvider;
    this.logSessionId = logSessionId;
    this.muxerFactory = muxerFactory;
    this.outputFilePath = outputFilePath;
    this.fileStartsOnVideoFrameEnabled = fileStartsOnVideoFrameEnabled;
    this.applyMp4EditListTrim = applyMp4EditListTrim;
    exportResultBuilder = new ExportResult.Builder();
    componentListener = new ComponentListener();
  }

  @Override
  public void start() {
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            outputFilePath,
            muxerFactory,
            componentListener,
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ fileStartsOnVideoFrameEnabled,
            /* appendVideoFormat= */ null);
    transformerInternal =
        new TransformerInternal(
            context,
            composition,
            transformationRequest,
            assetLoaderFactory,
            audioMixerFactory,
            videoFrameProcessorFactory,
            encoderFactory,
            allowedEncodingRotationDegrees,
            maxFramesInEncoder,
            muxerWrapper,
            componentListener,
            fallbackListener,
            applicationHandler,
            debugViewProvider,
            clock,
            packetProcessor,
            glExecutorService,
            glObjectsProvider,
            /* videoSampleTimestampOffsetUs= */ 0,
            logSessionId,
            applyMp4EditListTrim,
            /* forceRemuxing= */ false);
    transformerInternal.start();
  }

  @Override
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    return transformerInternal == null
        ? Transformer.PROGRESS_STATE_NOT_STARTED
        : transformerInternal.getProgress(progressHolder);
  }

  @Override
  public void cancel() {
    if (transformerInternal != null) {
      transformerInternal.cancel();
    }
  }

  @Override
  public void endWithException(ExportException exportException) {
    checkNotNull(transformerInternal).endWithException(exportException);
  }

  private final class ComponentListener
      implements TransformerInternal.Listener, MuxerWrapper.Listener {

    // TransformerInternal.Listener implementation

    @Override
    public void onCompleted(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName) {
      ExportResultUtil.updateProcessingDetails(
          exportResultBuilder, processedInputs, audioEncoderName, videoEncoderName);
      listener.onCompleted(exportResultBuilder.build());
    }

    @Override
    @SuppressWarnings("UngroupedOverloads") // Grouped by interface.
    public void onError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException) {
      ExportResultUtil.updateProcessingDetails(
          exportResultBuilder, processedInputs, audioEncoderName, videoEncoderName);
      listener.onError(
          exportResultBuilder.setExportException(exportException).build(), exportException);
    }

    // MuxerWrapper.Listener implementation

    @Override
    public void onTrackEnded(
        @TrackType int trackType, Format format, int averageBitrate, int sampleCount) {
      ExportResultUtil.updateTrackDetails(
          exportResultBuilder, trackType, format, averageBitrate, sampleCount);
    }

    @Override
    public void onSampleWrittenOrDropped() {
      listener.onSampleWrittenOrDropped();
    }

    @Override
    public void onEnded(long approximateDurationMs, long fileSizeBytes) {
      exportResultBuilder
          .setApproximateDurationMs(approximateDurationMs)
          .setFileSizeBytes(fileSizeBytes);
      checkNotNull(transformerInternal).endWithCompletion();
    }

    @Override
    @SuppressWarnings("UngroupedOverloads") // Grouped by interface.
    public void onError(ExportException exportException) {
      endWithException(exportException);
    }
  }
}
