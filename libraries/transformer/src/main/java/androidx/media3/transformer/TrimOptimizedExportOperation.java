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
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeAudio;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeVideo;
import static androidx.media3.transformer.TransmuxTranscodeHelper.buildUponCompositionForTrimOptimization;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.round;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.metrics.LogSessionId;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.PacketProcessor;
import androidx.media3.muxer.Muxer;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.media3.transformer.Transformer.ProgressState;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

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
  private final PacketProcessor<List<? extends GlTextureFrame>, GlTextureFrame> packetProcessor;

  @Nullable private final ExecutorService glExecutorService;
  @Nullable private final GlObjectsProvider glObjectsProvider;
  @Nullable private final LogSessionId logSessionId;
  private final Muxer.Factory muxerFactory;
  private final String outputFilePath;
  private final boolean applyMp4EditListTrim;
  private final ExportResult.Builder exportResultBuilder;
  private final ComponentListener componentListener;

  private Composition composition;
  private @ExportState int state;
  private @MonotonicNonNull ListenableFuture<Mp4Info> getMp4InfoFuture;
  @Nullable private TransformerInternal transformerInternal;
  @Nullable private MuxerWrapper remuxingMuxerWrapper;
  @Nullable private Mp4Info mediaItemInfo;

  public TrimOptimizedExportOperation(
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
      @Nullable PacketProcessor<List<? extends GlTextureFrame>, GlTextureFrame> packetProcessor,
      @Nullable ExecutorService glExecutorService,
      @Nullable GlObjectsProvider glObjectsProvider,
      @Nullable LogSessionId logSessionId,
      boolean applyMp4EditListTrim,
      Muxer.Factory muxerFactory,
      String outputFilePath) {
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
    this.applyMp4EditListTrim = applyMp4EditListTrim;
    exportResultBuilder = new ExportResult.Builder();
    componentListener = new ComponentListener();
    state = STATE_PROCESS_FULL_INPUT;
  }

  @Override
  public void start() {
    processMediaBeforeFirstSyncSampleAfterTrimStartTime();
  }

  @Override
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    if (mediaItemInfo == null) {
      return PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
    }
    MediaItem firstMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0).mediaItem;
    long trimStartTimeUs = firstMediaItem.clippingConfiguration.startPositionUs;
    long transcodeDuration = mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs;
    float transcodeWeighting = (float) transcodeDuration / mediaItemInfo.durationUs;

    float progressSoFar = 0f;
    if (state == STATE_PROCESS_MEDIA_START) {
      return getNextAccumulatedProgress(progressSoFar, transcodeWeighting, progressHolder);
    }
    progressSoFar = 100 * transcodeWeighting;
    return getNextAccumulatedProgress(progressSoFar, (1 - transcodeWeighting), progressHolder);
  }

  @Override
  public void cancel() {
    if (transformerInternal != null) {
      transformerInternal.cancel();
    }
    if (getMp4InfoFuture != null) {
      getMp4InfoFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  @Override
  public void endWithException(ExportException exportException) {
    checkNotNull(transformerInternal).endWithException(exportException);
  }

  private void processMediaBeforeFirstSyncSampleAfterTrimStartTime() {
    state = STATE_PROCESS_MEDIA_START;
    EditedMediaItem firstEditedMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0);
    long trimStartTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.startPositionUs;
    long trimEndTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.endPositionUs;
    getMp4InfoFuture =
        TransmuxTranscodeHelper.getMp4Info(
            context,
            checkNotNull(firstEditedMediaItem.mediaItem.localConfiguration).uri.toString(),
            trimStartTimeUs);
    Futures.addCallback(
        getMp4InfoFuture,
        new FutureCallback<Mp4Info>() {
          @Override
          public void onSuccess(Mp4Info mp4Info) {
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_UNSET) {
              exportResultBuilder.setOptimizationResult(OPTIMIZATION_ABANDONED_OTHER);
              processFullInput();
              return;
            }
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_END_OF_SOURCE
                || (trimEndTimeUs != C.TIME_END_OF_SOURCE
                    && trimEndTimeUs < mp4Info.firstSyncSampleTimestampUsAfterTimeUs)) {
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            long maxEncodedAudioBufferDurationUs = 0;
            if (mp4Info.audioFormat != null && mp4Info.audioFormat.sampleRate != Format.NO_VALUE) {
              maxEncodedAudioBufferDurationUs =
                  Util.sampleCountToDurationUs(
                      AAC_LC_AUDIO_SAMPLE_COUNT, mp4Info.audioFormat.sampleRate);
            }
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs
                == mp4Info.firstVideoSampleTimestampUs) {
              // The video likely includes an edit list. For example, an edit list adds 1_000ms to
              // each video sample and the trim position is from 100ms, the first sample would be
              // at 1_000ms, the first sync sample after 100ms would also be at 1_000ms; but in this
              // case processing should start from 100ms rather than 1_000ms. The resulting video
              // should be 100ms shorter than the original video, and the first video timestamp
              // should have timestamp at 900ms.
              TrimOptimizedExportOperation.this.composition =
                  buildUponCompositionForTrimOptimization(
                      composition,
                      trimStartTimeUs,
                      trimEndTimeUs,
                      mp4Info.durationUs,
                      /* startsAtKeyFrame= */ true,
                      /* clearVideoEffects= */ false);
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            // Ensure there is an audio sample to mux between the two clip times to prevent
            // Transformer from hanging because it received an audio track but no audio samples.
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs
                    <= maxEncodedAudioBufferDurationUs
                || mp4Info.isFirstVideoSampleAfterTimeUsSyncSample) {
              TrimOptimizedExportOperation.this.composition =
                  buildUponCompositionForTrimOptimization(
                      composition,
                      /* startTimeUs= */ mp4Info.firstSyncSampleTimestampUsAfterTimeUs,
                      trimEndTimeUs,
                      mp4Info.durationUs,
                      /* startsAtKeyFrame= */ true,
                      /* clearVideoEffects= */ false);
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            remuxingMuxerWrapper =
                new MuxerWrapper(
                    checkNotNull(outputFilePath),
                    muxerFactory,
                    componentListener,
                    MuxerWrapper.MUXER_MODE_MUX_PARTIAL,
                    /* dropSamplesBeforeFirstVideoSample= */ false,
                    mp4Info.videoFormat);
            if (shouldTranscodeVideo(
                    checkNotNull(mp4Info.videoFormat),
                    composition,
                    /* sequenceIndex= */ 0,
                    transformationRequest,
                    encoderFactory,
                    remuxingMuxerWrapper)
                || (mp4Info.audioFormat != null
                    && shouldTranscodeAudio(
                        mp4Info.audioFormat,
                        composition,
                        /* sequenceIndex= */ 0,
                        transformationRequest,
                        encoderFactory,
                        remuxingMuxerWrapper))) {
              remuxingMuxerWrapper = null;
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED);
              processFullInput();
              return;
            }

            TrimOptimizedExportOperation.this.mediaItemInfo = mp4Info;
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
            startInternal(
                trancodeComposition,
                checkNotNull(remuxingMuxerWrapper),
                /* initialTimestampOffsetUs= */ 0);
          }

          @Override
          public void onFailure(Throwable t) {
            exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_EXTRACTION_FAILED);
            processFullInput();
          }
        },
        applicationHandler::post);
  }

  private void processFullInput() {
    state = STATE_PROCESS_FULL_INPUT;
    startInternal(
        checkNotNull(composition),
        new MuxerWrapper(
            checkNotNull(outputFilePath),
            muxerFactory,
            componentListener,
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null),
        /* initialTimestampOffsetUs= */ 0);
  }

  private void remuxRemainingMedia() {
    state = STATE_REMUX_REMAINING_MEDIA;
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
    startInternal(
        transmuxComposition,
        remuxingMuxerWrapper,
        /* initialTimestampOffsetUs= */ mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs
            - trimStartTimeUs);
  }

  private void startInternal(
      Composition composition, MuxerWrapper muxerWrapper, long initialTimestampOffsetUs) {
    DebugTraceUtil.reset();
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
            initialTimestampOffsetUs,
            logSessionId,
            applyMp4EditListTrim,
            /* forceRemuxing= */ false);
    transformerInternal.start();
  }

  private @ProgressState int getNextAccumulatedProgress(
      float progressSoFar, float nextProgressWeight, ProgressHolder progressHolder) {
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
      transformerInternal = null;
      if (state == STATE_PROCESS_MEDIA_START) {
        remuxRemainingMedia();
      } else if (state == STATE_REMUX_REMAINING_MEDIA) {
        mediaItemInfo = null;
        exportResultBuilder.setOptimizationResult(ExportResult.OPTIMIZATION_SUCCEEDED);
        listener.onCompleted(exportResultBuilder.build());
      } else {
        listener.onCompleted(exportResultBuilder.build());
      }
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

      if (exportException.errorCode == ERROR_CODE_MUXING_APPEND) {
        remuxingMuxerWrapper = null;
        transformerInternal = null;
        exportResultBuilder.reset();
        exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_FORMAT_MISMATCH);
        processFullInput();
        return;
      }
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
