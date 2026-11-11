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

import static androidx.media3.extractor.AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT;
import static androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_APPEND;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_OTHER;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_EXTRACTION_FAILED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_FORMAT_MISMATCH;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static androidx.media3.transformer.TransformerUtil.maybeSetMuxerWrapperAdditionalRotationDegrees;
import static androidx.media3.transformer.TransformerUtil.releaseMuxerWrapperQuietly;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeAudio;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeVideo;
import static androidx.media3.transformer.TransmuxTranscodeHelper.buildUponCompositionForTrimOptimization;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.round;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.metrics.LogSessionId;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.muxer.Muxer;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.media3.transformer.Transformer.ProgressState;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/** An {@link ExportOperation} implementation for trim optimized exports. */
/* package */ final class TrimOptimizedExportOperation implements ExportOperation {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_PROCESS_FULL_INPUT, STATE_PROCESS_MEDIA_START, STATE_REMUX_REMAINING_MEDIA})
  private @interface ExportState {}

  private static final int STATE_PROCESS_FULL_INPUT = 0;
  private static final int STATE_PROCESS_MEDIA_START = 1;
  private static final int STATE_REMUX_REMAINING_MEDIA = 2;

  private final Context context;
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
  private final Muxer.Factory muxerFactory;
  private final TransformerOutputResolver outputResolver;
  private final boolean applyMp4EditListTrim;
  private final ExportResult.Builder exportResultBuilder;
  private final ComponentListener componentListener;
  private final ExportOperationRunner runner;

  // Written on the internal thread, read on multiple threads.
  private volatile CurrentStage currentStage;

  // Must only be accessed from the internal thread.
  private Composition composition;
  private @Nullable Mp4Info mediaItemInfo;
  private @Nullable MuxerWrapper remuxingMuxerWrapper;

  public TrimOptimizedExportOperation(
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
      TransformerOutput transformerOutput) {
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
    this.applyMp4EditListTrim = applyMp4EditListTrim;
    exportResultBuilder = new ExportResult.Builder();
    componentListener = new ComponentListener();
    runner =
        new ExportOperationRunner(
            "Transformer:TrimOptimizedExport", clock, applicationHandler, componentListener);
    currentStage =
        new CurrentStage(
            STATE_PROCESS_FULL_INPUT, /* transformerInternal= */ null, /* transcodeWeighting= */ 1);
  }

  @Override
  public void start() {
    runner.start(this::processMediaBeforeFirstSyncSampleAfterTrimStartTimeInternal);
  }

  @Override
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    // Read once, as the current stage is replaced on the internal thread.
    CurrentStage currentStage = this.currentStage;
    @ExportState int state = currentStage.state;
    @Nullable TransformerInternal transformerInternal = currentStage.transformerInternal;
    if (state == STATE_PROCESS_FULL_INPUT) {
      return getNextAccumulatedProgress(
          transformerInternal, /* progressSoFar= */ 0, /* nextProgressWeight= */ 1, progressHolder);
    }
    float transcodeWeighting = currentStage.transcodeWeighting;
    if (state == STATE_PROCESS_MEDIA_START) {
      return getNextAccumulatedProgress(
          transformerInternal, /* progressSoFar= */ 0, transcodeWeighting, progressHolder);
    }
    return getNextAccumulatedProgress(
        transformerInternal,
        /* progressSoFar= */ 100 * transcodeWeighting,
        /* nextProgressWeight= */ 1 - transcodeWeighting,
        progressHolder);
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

  private void processMediaBeforeFirstSyncSampleAfterTrimStartTimeInternal()
      throws ExportException {
    EditedMediaItem firstEditedMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0);
    long trimStartTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.startPositionUs;
    long trimEndTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.endPositionUs;
    Mp4Info mp4Info;
    try {
      mp4Info =
          Mp4Info.create(
              context,
              checkNotNull(firstEditedMediaItem.mediaItem.localConfiguration).uri.toString(),
              trimStartTimeUs);
    } catch (IOException | RuntimeException e) {
      // In case of error fallback to normal Export.
      exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_EXTRACTION_FAILED);
      processFullInputInternal();
      return;
    }
    if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_UNSET) {
      exportResultBuilder.setOptimizationResult(OPTIMIZATION_ABANDONED_OTHER);
      processFullInputInternal();
      return;
    }
    if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_END_OF_SOURCE
        || (trimEndTimeUs != C.TIME_END_OF_SOURCE
            && trimEndTimeUs < mp4Info.firstSyncSampleTimestampUsAfterTimeUs)) {
      exportResultBuilder.setOptimizationResult(
          OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
      processFullInputInternal();
      return;
    }
    long maxEncodedAudioBufferDurationUs = 0;
    if (mp4Info.audioFormat != null && mp4Info.audioFormat.sampleRate != Format.NO_VALUE) {
      maxEncodedAudioBufferDurationUs =
          Util.sampleCountToDurationUs(AAC_LC_AUDIO_SAMPLE_COUNT, mp4Info.audioFormat.sampleRate);
    }
    if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == mp4Info.firstVideoSampleTimestampUs) {
      // The video likely includes an edit list. For example, an edit list adds 1_000ms to
      // each video sample and the trim position is from 100ms, the first sample would be
      // at 1_000ms, the first sync sample after 100ms would also be at 1_000ms; but in this
      // case processing should start from 100ms rather than 1_000ms. The resulting video
      // should be 100ms shorter than the original video, and the first video timestamp
      // should have timestamp at 900ms.
      composition =
          buildUponCompositionForTrimOptimization(
              composition,
              trimStartTimeUs,
              trimEndTimeUs,
              mp4Info.durationUs,
              /* startsAtKeyFrame= */ true,
              /* clearVideoEffects= */ false);
      exportResultBuilder.setOptimizationResult(
          OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
      processFullInputInternal();
      return;
    }
    // Ensure there is an audio sample to mux between the two clip times to prevent
    // Transformer from hanging because it received an audio track but no audio samples.
    if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs
            <= maxEncodedAudioBufferDurationUs
        || mp4Info.isFirstVideoSampleAfterTimeUsSyncSample) {
      composition =
          buildUponCompositionForTrimOptimization(
              composition,
              /* startTimeUs= */ mp4Info.firstSyncSampleTimestampUsAfterTimeUs,
              trimEndTimeUs,
              mp4Info.durationUs,
              /* startsAtKeyFrame= */ true,
              /* clearVideoEffects= */ false);
      exportResultBuilder.setOptimizationResult(
          OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
      processFullInputInternal();
      return;
    }
    if (shouldTranscodeVideo(
            checkNotNull(mp4Info.videoFormat),
            composition,
            /* sequenceIndex= */ 0,
            transformationRequest,
            encoderFactory,
            muxerFactory,
            /* hasFrameProcessorFactory= */ frameProcessorFactory != null)
        || (mp4Info.audioFormat != null
            && shouldTranscodeAudio(
                mp4Info.audioFormat,
                composition,
                /* sequenceIndex= */ 0,
                transformationRequest,
                encoderFactory,
                muxerFactory))) {
      exportResultBuilder.setOptimizationResult(
          OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED);
      processFullInputInternal();
      return;
    }
    remuxingMuxerWrapper =
        createMuxerWrapper(MuxerWrapper.MUXER_MODE_MUX_PARTIAL, mp4Info.videoFormat);

    mediaItemInfo = mp4Info;
    maybeSetMuxerWrapperAdditionalRotationDegrees(
        remuxingMuxerWrapper,
        firstEditedMediaItem.effects.videoEffects,
        checkNotNull(mp4Info.videoFormat));
    Composition trancodeComposition =
        buildUponCompositionForTrimOptimization(
            composition,
            trimStartTimeUs,
            /* endTimeUs= */ mp4Info.firstSyncSampleTimestampUsAfterTimeUs,
            /* mediaDurationUs= */ mp4Info.durationUs,
            /* startsAtKeyFrame= */ false,
            /* clearVideoEffects= */ true);
    float transcodeWeighting =
        (float) (mp4Info.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs)
            / mp4Info.durationUs;
    startInternal(
        STATE_PROCESS_MEDIA_START,
        trancodeComposition,
        checkNotNull(remuxingMuxerWrapper),
        /* initialTimestampOffsetUs= */ 0,
        transcodeWeighting);
  }

  private void processFullInputInternal() throws ExportException {
    startInternal(
        STATE_PROCESS_FULL_INPUT,
        checkNotNull(composition),
        createMuxerWrapper(MuxerWrapper.MUXER_MODE_DEFAULT, /* appendVideoFormat= */ null),
        /* initialTimestampOffsetUs= */ 0,
        /* transcodeWeighting= */ 1);
  }

  private MuxerWrapper createMuxerWrapper(
      @MuxerWrapper.MuxerMode int muxerMode, @Nullable Format appendVideoFormat)
      throws ExportException {
    try {
      return outputResolver.createMuxerWrapper(
          muxerFactory,
          componentListener,
          muxerMode,
          /* dropSamplesBeforeFirstVideoSample= */ false,
          appendVideoFormat);
    } catch (IOException e) {
      throw ExportException.createForMuxer(e, ExportException.ERROR_CODE_MUXING_FAILED);
    }
  }

  private void remuxRemainingMediaInternal() {
    EditedMediaItem firstEditedMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0);
    Mp4Info mediaItemInfo = checkNotNull(this.mediaItemInfo);
    long trimStartTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.startPositionUs;
    long trimEndTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.endPositionUs;
    Composition transmuxComposition =
        buildUponCompositionForTrimOptimization(
            composition,
            mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs,
            trimEndTimeUs,
            mediaItemInfo.durationUs,
            /* startsAtKeyFrame= */ true,
            /* clearVideoEffects= */ true);
    checkNotNull(remuxingMuxerWrapper);
    remuxingMuxerWrapper.changeToAppendMode();
    long transcodeDurationUs =
        mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs;
    float transcodeWeighting = (float) transcodeDurationUs / mediaItemInfo.durationUs;
    startInternal(
        STATE_REMUX_REMAINING_MEDIA,
        transmuxComposition,
        remuxingMuxerWrapper,
        /* initialTimestampOffsetUs= */ transcodeDurationUs,
        transcodeWeighting);
  }

  private void startInternal(
      @ExportState int state,
      Composition composition,
      MuxerWrapper muxerWrapper,
      long initialTimestampOffsetUs,
      float transcodeWeighting) {
    DebugTraceUtil.reset();
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
              initialTimestampOffsetUs,
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
    // Published together with the state, so that getProgress() doesn't combine the state of one
    // stage with the TransformerInternal of another.
    currentStage = new CurrentStage(state, transformerInternal, transcodeWeighting);
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

  private static @ProgressState int getNextAccumulatedProgress(
      @Nullable TransformerInternal transformerInternal,
      float progressSoFar,
      float nextProgressWeight,
      ProgressHolder progressHolder) {
    if (transformerInternal == null) {
      progressHolder.progress = round(progressSoFar);
      return progressSoFar == 0
          ? PROGRESS_STATE_WAITING_FOR_AVAILABILITY
          : PROGRESS_STATE_AVAILABLE;
    }
    @ProgressState int ongoingProgressState = transformerInternal.getProgress(progressHolder);
    switch (ongoingProgressState) {
      case PROGRESS_STATE_NOT_STARTED:
      case PROGRESS_STATE_WAITING_FOR_AVAILABILITY:
        progressHolder.progress = round(progressSoFar);
        return progressSoFar == 0
            ? PROGRESS_STATE_WAITING_FOR_AVAILABILITY
            : PROGRESS_STATE_AVAILABLE;
      case PROGRESS_STATE_AVAILABLE:
        progressHolder.progress =
            round(progressSoFar + (progressHolder.progress * nextProgressWeight));
        return PROGRESS_STATE_AVAILABLE;
      case PROGRESS_STATE_UNAVAILABLE:
        return PROGRESS_STATE_UNAVAILABLE;
      default:
        throw new IllegalStateException();
    }
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
      @ExportState int state = currentStage.state;
      if (state == STATE_PROCESS_MEDIA_START) {
        remuxRemainingMediaInternal();
        return;
      }
      if (state == STATE_REMUX_REMAINING_MEDIA) {
        // The TransformerInternal released the muxer when it completed.
        remuxingMuxerWrapper = null;
        exportResultBuilder.setOptimizationResult(ExportResult.OPTIMIZATION_SUCCEEDED);
      }
      reportCompletedInternal();
    }

    @Override
    public void onTransformerInternalError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException)
        throws ExportException {
      ExportResultUtil.updateProcessingDetails(
          exportResultBuilder, processedInputs, audioEncoderName, videoEncoderName);
      if (exportException.errorCode == ERROR_CODE_MUXING_APPEND) {
        remuxingMuxerWrapper = null;
        exportResultBuilder.reset();
        exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_FORMAT_MISMATCH);
        processFullInputInternal();
        return;
      }
      reportErrorInternal(exportException);
    }

    @Override
    public void onStageError(ExportException exportException) {
      reportErrorInternal(exportException);
    }

    @Override
    public void onReleased(boolean discardOutput) {
      if (remuxingMuxerWrapper != null) {
        // The export may have ended while the partially muxed output was kept open for the next
        // stage.
        releaseMuxerWrapperQuietly(remuxingMuxerWrapper);
        remuxingMuxerWrapper = null;
      }
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

  /** The stage whose progress is reported by {@link #getProgress}. */
  private static final class CurrentStage {

    private final @ExportState int state;
    private final @Nullable TransformerInternal transformerInternal;
    private final float transcodeWeighting;

    private CurrentStage(
        @ExportState int state,
        @Nullable TransformerInternal transformerInternal,
        float transcodeWeighting) {
      this.state = state;
      this.transformerInternal = transformerInternal;
      this.transcodeWeighting = transcodeWeighting;
    }
  }
}
