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

import static androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_APPEND;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_FORMAT_MISMATCH;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
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
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.PacketProcessor;
import androidx.media3.effect.RenderingPacketConsumer;
import androidx.media3.muxer.Muxer;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.media3.transformer.Transformer.ProgressState;
import androidx.media3.transformer.TransmuxTranscodeHelper.ResumeMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** An {@link ExportOperation} implementation for resumed exports. */
/* package */ final class ResumedExportOperation implements ExportOperation {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_PROCESS_FULL_INPUT,
    STATE_REMUX_PROCESSED_VIDEO,
    STATE_PROCESS_REMAINING_VIDEO,
    STATE_PROCESS_AUDIO,
    STATE_COPY_OUTPUT
  })
  private @interface ExportState {}

  /** The default Transformer state. */
  private static final int STATE_PROCESS_FULL_INPUT = 0;

  /**
   * The first state of a resumed export.
   *
   * <p>In this state, the paused export file's encoded video track is muxed into a video-only file,
   * stored at {@code oldFilePath}.
   *
   * <p>The video-only file is kept open to allow the {@link #STATE_PROCESS_REMAINING_VIDEO} to
   * continue writing to the same file & video track.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link
   * #STATE_PROCESS_REMAINING_VIDEO} state.
   */
  private static final int STATE_REMUX_PROCESSED_VIDEO = 1;

  /**
   * The second state of a resumed export.
   *
   * <p>In this state, the remaining {@link Composition} video data is processed and muxed into the
   * same video-only file, stored at {@code oldFilePath}.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link
   * #STATE_PROCESS_AUDIO} state.
   */
  private static final int STATE_PROCESS_REMAINING_VIDEO = 2;

  /**
   * The third state of a resumed export.
   *
   * <p>In this state, the entire {@link Composition} audio is processed and muxed. This same
   * operation also transmuxes the video-only file produced by {@link
   * #STATE_PROCESS_REMAINING_VIDEO}, interleaving of the audio and video tracks. The output is
   * stored at {@code oldFilePath}.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link #STATE_COPY_OUTPUT}
   * state.
   */
  private static final int STATE_PROCESS_AUDIO = 3;

  /**
   * The final state of a resumed export.
   *
   * <p>In this state, the successful exported file (stored at {@code oldFilePath}) is copied to the
   * {@code outputFilePath}.
   */
  private static final int STATE_COPY_OUTPUT = 4;

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

  @Nullable RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer;

  @Nullable private final LogSessionId logSessionId;
  private final boolean applyMp4EditListTrim;
  private final Muxer.Factory muxerFactory;
  private final String outputFilePath;
  private final String oldFilePath;
  private final ExportResult.Builder exportResultBuilder;
  private final ComponentListener componentListener;
  private @ExportState int state;
  private @MonotonicNonNull ListenableFuture<ResumeMetadata> getResumeMetadataFuture;
  private @MonotonicNonNull ResumeMetadata resumeMetadata;
  private @MonotonicNonNull ListenableFuture<Void> copyOutputFuture;
  @Nullable private TransformerInternal transformerInternal;
  @Nullable private MuxerWrapper remuxingMuxerWrapper;

  public ResumedExportOperation(
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
      @Nullable RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer,
      @Nullable LogSessionId logSessionId,
      boolean applyMp4EditListTrim,
      Muxer.Factory muxerFactory,
      String outputFilePath,
      String oldFilePath) {
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
    this.packetRenderer = packetRenderer;
    this.logSessionId = logSessionId;
    this.applyMp4EditListTrim = applyMp4EditListTrim;
    this.muxerFactory = muxerFactory;
    this.outputFilePath = outputFilePath;
    this.oldFilePath = oldFilePath;
    exportResultBuilder = new ExportResult.Builder();
    componentListener = new ComponentListener();
    state = STATE_PROCESS_FULL_INPUT;
  }

  @Override
  public void start() {
    remuxProcessedVideo();
  }

  @Override
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    float remuxProcessedVideoProgressWeight = 0.15f;
    float processRemainingVideoProgressWeight = 0.40f;
    float processAudioProgressWeight = 0.30f;
    // Remaining 15% progress is for copying the output to the final location.

    float progressSoFar = 0f;
    if (state == STATE_REMUX_PROCESSED_VIDEO) {
      return getNextAccumulatedProgress(
          progressSoFar, remuxProcessedVideoProgressWeight, progressHolder);
    }
    progressSoFar = remuxProcessedVideoProgressWeight * 100;
    if (state == STATE_PROCESS_REMAINING_VIDEO) {
      return getNextAccumulatedProgress(
          progressSoFar, processRemainingVideoProgressWeight, progressHolder);
    }
    progressSoFar += processRemainingVideoProgressWeight * 100;
    if (state == STATE_PROCESS_AUDIO) {
      return getNextAccumulatedProgress(progressSoFar, processAudioProgressWeight, progressHolder);
    }
    progressSoFar += processAudioProgressWeight * 100;

    // Progress for copying the output can not be determined. After this, the export should complete
    // soon indicating 100% progress.
    progressHolder.progress = round(progressSoFar);
    return PROGRESS_STATE_AVAILABLE;
  }

  @Override
  public void cancel() {
    if (transformerInternal != null) {
      transformerInternal.cancel();
    }
    if (getResumeMetadataFuture != null && !getResumeMetadataFuture.isDone()) {
      getResumeMetadataFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
    if (copyOutputFuture != null && !copyOutputFuture.isDone()) {
      copyOutputFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
  }

  @Override
  public void endWithException(ExportException exportException) {
    checkNotNull(transformerInternal).endWithException(exportException);
  }

  private void remuxProcessedVideo() {
    state = STATE_REMUX_PROCESSED_VIDEO;
    getResumeMetadataFuture =
        TransmuxTranscodeHelper.getResumeMetadataAsync(
            context, checkNotNull(oldFilePath), checkNotNull(composition));
    Futures.addCallback(
        getResumeMetadataFuture,
        new FutureCallback<ResumeMetadata>() {
          @Override
          public void onSuccess(TransmuxTranscodeHelper.ResumeMetadata resumeMetadata) {
            // If there is no video track to remux or the last sync sample is actually the first
            // sample, then start the normal Export.
            if (resumeMetadata.lastSyncSampleTimestampUs == C.TIME_UNSET
                || resumeMetadata.lastSyncSampleTimestampUs == 0) {
              processFullInput();
              return;
            }

            ResumedExportOperation.this.resumeMetadata = resumeMetadata;

            remuxingMuxerWrapper =
                new MuxerWrapper(
                    checkNotNull(outputFilePath),
                    muxerFactory,
                    componentListener,
                    MuxerWrapper.MUXER_MODE_MUX_PARTIAL,
                    /* dropSamplesBeforeFirstVideoSample= */ false,
                    /* appendVideoFormat= */ resumeMetadata.videoFormat);

            startInternal(
                TransmuxTranscodeHelper.createVideoOnlyComposition(
                    oldFilePath,
                    /* clippingEndPositionUs= */ resumeMetadata.lastSyncSampleTimestampUs),
                checkNotNull(remuxingMuxerWrapper),
                /* initialTimestampOffsetUs= */ 0,
                /* forceRemuxing= */ true);
          }

          @Override
          public void onFailure(Throwable t) {
            // In case of error fallback to normal Export.
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
        /* initialTimestampOffsetUs= */ 0,
        /* forceRemuxing= */ false);
  }

  private void processRemainingVideo() {
    state = STATE_PROCESS_REMAINING_VIDEO;
    Composition videoOnlyComposition =
        TransmuxTranscodeHelper.buildUponComposition(
            checkNotNull(composition),
            /* sequenceTrackTypes= */ ImmutableSet.of(C.TRACK_TYPE_VIDEO),
            resumeMetadata);

    checkNotNull(remuxingMuxerWrapper);
    remuxingMuxerWrapper.changeToAppendMode();

    startInternal(
        videoOnlyComposition,
        remuxingMuxerWrapper,
        /* initialTimestampOffsetUs= */ checkNotNull(resumeMetadata).lastSyncSampleTimestampUs,
        /* forceRemuxing= */ false);
  }

  private void processAudio() {
    state = STATE_PROCESS_AUDIO;

    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            checkNotNull(oldFilePath),
            muxerFactory,
            componentListener,
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    startInternal(
        TransmuxTranscodeHelper.createAudioTranscodeAndVideoTransmuxComposition(
            checkNotNull(composition), checkNotNull(outputFilePath)),
        muxerWrapper,
        /* initialTimestampOffsetUs= */ 0,
        /* forceRemuxing= */ false);
  }

  // TODO: b/308253384 - Move copy output logic into MuxerWrapper.
  private void copyOutput() {
    state = STATE_COPY_OUTPUT;
    copyOutputFuture =
        TransmuxTranscodeHelper.copyFileAsync(
            new File(checkNotNull(oldFilePath)), new File(checkNotNull(outputFilePath)));

    Futures.addCallback(
        copyOutputFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            listener.onCompleted(exportResultBuilder.build());
          }

          @Override
          public void onFailure(Throwable t) {
            ExportException exception =
                ExportException.createForUnexpected(
                    new IOException("Copy output task failed for the resumed export", t));
            exportResultBuilder.setExportException(exception);
            listener.onError(exportResultBuilder.build(), exception);
          }
        },
        applicationHandler::post);
  }

  private void startInternal(
      Composition composition,
      MuxerWrapper muxerWrapper,
      long initialTimestampOffsetUs,
      boolean forceRemuxing) {
    DebugTraceUtil.reset();
    transformerInternal =
        new TransformerInternal(
            context,
            composition,
            transformationRequest,
            assetLoaderFactory,
            audioMixerFactory,
            videoFrameProcessorFactory,
            forceRemuxing
                ? new DefaultEncoderFactory.Builder(this.context).build()
                : encoderFactory,
            allowedEncodingRotationDegrees,
            maxFramesInEncoder,
            muxerWrapper,
            componentListener,
            fallbackListener,
            applicationHandler,
            debugViewProvider,
            clock,
            packetProcessor,
            packetRenderer,
            initialTimestampOffsetUs,
            logSessionId,
            applyMp4EditListTrim,
            forceRemuxing);
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
      // TODO: b/213341814 - Add event flags for Transformer events.
      transformerInternal = null;
      if (state == STATE_REMUX_PROCESSED_VIDEO) {
        processRemainingVideo();
      } else if (state == STATE_PROCESS_REMAINING_VIDEO) {
        remuxingMuxerWrapper = null;
        processAudio();
      } else if (state == STATE_PROCESS_AUDIO) {
        copyOutput();
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
