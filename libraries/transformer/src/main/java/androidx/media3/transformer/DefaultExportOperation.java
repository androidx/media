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

import static androidx.media3.transformer.TransformerUtil.releaseMuxerWrapperQuietly;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.media.metrics.LogSessionId;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.muxer.Muxer;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.media3.transformer.Transformer.ProgressState;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** An {@link ExportOperation} implementation for single export operations. */
/* package */ final class DefaultExportOperation implements ExportOperation {

  private final Context context;
  private final Composition composition;
  private final TransformationRequest transformationRequest;
  private final AssetLoader.@Nullable Factory assetLoaderFactory;
  private final AudioMixer.Factory audioMixerFactory;
  private final VideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final int maxFramesInEncoder;
  private final ExportOperation.Listener listener;
  private final FallbackListener fallbackListener;
  private final DebugViewProvider debugViewProvider;
  private final Clock clock;

  private final FrameProcessor.@Nullable Factory frameProcessorFactory;

  private final @Nullable HardwareBufferJniWrapper hardwareBufferJniWrapper;

  private final @Nullable LogSessionId logSessionId;
  private final boolean applyMp4EditListTrim;
  private final Muxer.Factory muxerFactory;
  private final TransformerOutputResolver outputResolver;
  private final boolean fileStartsOnVideoFrameEnabled;
  private final ExportResult.Builder exportResultBuilder;
  private final ComponentListener componentListener;
  private final ExportOperationRunner runner;

  public DefaultExportOperation(
      Context context,
      Composition composition,
      TransformationRequest transformationRequest,
      AssetLoader.@Nullable Factory assetLoaderFactory,
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
      FrameProcessor.@Nullable Factory frameProcessorFactory,
      @Nullable HardwareBufferJniWrapper hardwareBufferJniWrapper,
      @Nullable LogSessionId logSessionId,
      boolean applyMp4EditListTrim,
      Muxer.Factory muxerFactory,
      TransformerOutput transformerOutput,
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
    this.debugViewProvider = debugViewProvider;
    this.clock = clock;
    this.frameProcessorFactory = frameProcessorFactory;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.logSessionId = logSessionId;
    this.muxerFactory = muxerFactory;
    this.outputResolver = new TransformerOutputResolver(context, transformerOutput);
    this.fileStartsOnVideoFrameEnabled = fileStartsOnVideoFrameEnabled;
    this.applyMp4EditListTrim = applyMp4EditListTrim;
    exportResultBuilder = new ExportResult.Builder();
    componentListener = new ComponentListener();
    runner =
        new ExportOperationRunner(
            "Transformer:DefaultExport", clock, applicationHandler, componentListener);
  }

  @Override
  public void start() {
    runner.start(this::startInternal);
  }

  @Override
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    @Nullable TransformerInternal transformerInternal = runner.getTransformerInternal();
    return transformerInternal == null
        ? Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY
        : transformerInternal.getProgress(progressHolder);
  }

  @Override
  public void cancel() {
    // Blocks until the export has been released, closing the file descriptor, so that the
    // MediaStore entry can be safely deleted below. The entry also needs deleting if the export
    // completed but its result was dropped because of the cancellation.
    runner.cancel();
    outputResolver.deleteIfPresent();
  }

  @Override
  public void endWithException(ExportException exportException) {
    runner.endWithException(exportException);
  }

  private void startInternal() throws ExportException {
    MuxerWrapper muxerWrapper;
    try {
      muxerWrapper =
          outputResolver.createMuxerWrapper(
              muxerFactory,
              componentListener,
              MuxerWrapper.MUXER_MODE_DEFAULT,
              /* dropSamplesBeforeFirstVideoSample= */ fileStartsOnVideoFrameEnabled,
              /* appendVideoFormat= */ null);
    } catch (IOException e) {
      throw ExportException.createForMuxer(e, ExportException.ERROR_CODE_MUXING_FAILED);
    }
    TransformerInternal transformerInternal;
    try {
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
              runner.getTransformerInternalListener(),
              fallbackListener,
              runner.getInternalHandlerThread(),
              debugViewProvider,
              clock,
              frameProcessorFactory,
              hardwareBufferJniWrapper,
              /* videoSampleTimestampOffsetUs= */ 0,
              logSessionId,
              applyMp4EditListTrim,
              /* forceRemuxing= */ false);
    } catch (RuntimeException e) {
      // The muxer is only released by the TransformerInternal when it ends, so release it here
      // to close the output file descriptor.
      releaseMuxerWrapperQuietly(muxerWrapper);
      throw e;
    }
    runner.startTransformerInternal(transformerInternal);
  }

  private void reportCompletedInternal() {
    ExportResult exportResult = exportResultBuilder.build();
    // Publish the output on the application thread, so that it isn't published if the export is
    // cancelled before the callback runs.
    runner.reportCompleted(() -> {
      outputResolver.clearPendingFlag();
      listener.onCompleted(exportResult);
    });
  }

  private void reportErrorInternal(ExportException exportException) {
    ExportResult exportResult = exportResultBuilder.setExportException(exportException).build();
    runner.reportError(() -> listener.onError(exportResult, exportException));
  }

  /**
   * Listener for components ({@link ExportOperationRunner} and {@link MuxerWrapper}). All methods
   * are called on the internal thread.
   */
  private final class ComponentListener
      implements ExportOperationRunner.Listener, MuxerWrapper.Listener {

    // ExportOperationRunner.Listener implementation

    @Override
    public void onTransformerInternalCompleted(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName) {
      ExportResultUtil.updateProcessingDetails(
          exportResultBuilder, processedInputs, audioEncoderName, videoEncoderName);
      reportCompletedInternal();
    }

    @Override
    public void onTransformerInternalError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException) {
      ExportResultUtil.updateProcessingDetails(
          exportResultBuilder, processedInputs, audioEncoderName, videoEncoderName);
      reportErrorInternal(exportException);
    }

    @Override
    public void onStageError(ExportException exportException) {
      reportErrorInternal(exportException);
    }

    @Override
    public void onReleased(boolean discardOutput) {
      if (discardOutput) {
        outputResolver.deleteIfPresent();
      }
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
    public void onEnded(long approximateDurationMs) {
      exportResultBuilder.setApproximateDurationMs(approximateDurationMs);
      checkNotNull(runner.getTransformerInternal()).endWithCompletion();
    }

    @Override
    public void onFileSizeBytesAvailable(long fileSizeBytes) {
      exportResultBuilder.setFileSizeBytes(fileSizeBytes);
    }

    @Override
    public void onError(ExportException exportException) {
      runner.endWithException(exportException);
    }
  }
}
