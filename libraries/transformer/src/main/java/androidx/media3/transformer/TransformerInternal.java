/*
 * Copyright 2022 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.transformer.AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED;
import static androidx.media3.transformer.AssetLoader.SUPPORTED_OUTPUT_TYPE_ENCODED;
import static androidx.media3.transformer.ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK;
import static androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_FAILED;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static androidx.media3.transformer.TransformerUtil.areVideoEffectsAllNoOp;
import static androidx.media3.transformer.TransformerUtil.containsSlowMotionData;
import static androidx.media3.transformer.TransformerUtil.getProcessedTrackType;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.effect.Presentation;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/* package */ final class TransformerInternal implements MuxerWrapper.Listener {

  public interface Listener {

    void onCompleted(ExportResult exportResult);

    void onError(ExportResult exportResult, ExportException exportException);
  }

  /**
   * Represents a reason for ending an export. May be one of {@link #END_REASON_COMPLETED}, {@link
   * #END_REASON_CANCELLED} or {@link #END_REASON_ERROR}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({END_REASON_COMPLETED, END_REASON_CANCELLED, END_REASON_ERROR})
  private @interface EndReason {}
  /** The export completed successfully. */
  private static final int END_REASON_COMPLETED = 0;
  /** The export was cancelled. */
  private static final int END_REASON_CANCELLED = 1;
  /** An error occurred during the export. */
  private static final int END_REASON_ERROR = 2;

  // Internal messages.
  private static final int MSG_START = 0;
  private static final int MSG_REGISTER_SAMPLE_PIPELINE = 1;
  private static final int MSG_DRAIN_PIPELINES = 2;
  private static final int MSG_END = 3;
  private static final int MSG_UPDATE_PROGRESS = 4;

  private static final int DRAIN_PIPELINES_DELAY_MS = 10;

  private final Context context;
  private final CapturingEncoderFactory encoderFactory;
  private final Listener listener;
  private final HandlerWrapper applicationHandler;
  private final Clock clock;
  private final HandlerThread internalHandlerThread;
  private final HandlerWrapper internalHandler;
  private final List<SequenceAssetLoader> sequenceAssetLoaders;
  private final AtomicInteger trackCountsToReport;
  private final AtomicInteger tracksToAdd;
  private final AtomicBoolean outputHasAudio;
  private final AtomicBoolean outputHasVideo;
  private final List<SamplePipeline> samplePipelines;
  private final MuxerWrapper muxerWrapper;
  private final ConditionVariable transformerConditionVariable;
  private final ExportResult.Builder exportResultBuilder;

  private boolean isDrainingPipelines;
  private @Transformer.ProgressState int progressState;
  private @MonotonicNonNull RuntimeException cancelException;

  private volatile boolean released;

  // Warning suppression is needed to assign the MuxerWrapper with "this" as listener.
  @SuppressWarnings("assignment.type.incompatible")
  public TransformerInternal(
      Context context,
      Composition composition,
      String outputPath,
      TransformationRequest transformationRequest,
      AssetLoader.Factory assetLoaderFactory,
      Codec.EncoderFactory encoderFactory,
      Muxer.Factory muxerFactory,
      Listener listener,
      FallbackListener fallbackListener,
      HandlerWrapper applicationHandler,
      DebugViewProvider debugViewProvider,
      Clock clock) {
    this.context = context;
    this.encoderFactory = new CapturingEncoderFactory(encoderFactory);
    this.listener = listener;
    this.applicationHandler = applicationHandler;
    this.clock = clock;
    internalHandlerThread = new HandlerThread("Transformer:Internal");
    internalHandlerThread.start();
    sequenceAssetLoaders = new ArrayList<>();
    Looper internalLooper = internalHandlerThread.getLooper();
    for (int i = 0; i < composition.sequences.size(); i++) {
      SequenceAssetLoaderListener sequenceAssetLoaderListener =
          new SequenceAssetLoaderListener(
              /* sequenceIndex= */ i,
              composition,
              transformationRequest,
              fallbackListener,
              debugViewProvider);
      sequenceAssetLoaders.add(
          new SequenceAssetLoader(
              composition.sequences.get(i),
              composition.forceAudioTrack,
              assetLoaderFactory,
              internalLooper,
              sequenceAssetLoaderListener,
              clock));
    }
    trackCountsToReport = new AtomicInteger(composition.sequences.size());
    tracksToAdd = new AtomicInteger();
    outputHasAudio = new AtomicBoolean();
    outputHasVideo = new AtomicBoolean();
    samplePipelines = new ArrayList<>();
    transformerConditionVariable = new ConditionVariable();
    exportResultBuilder = new ExportResult.Builder();
    // It's safe to use "this" because we don't send a message before exiting the constructor.
    @SuppressWarnings("nullness:methodref.receiver.bound")
    HandlerWrapper internalHandler =
        clock.createHandler(internalLooper, /* callback= */ this::handleMessage);
    this.internalHandler = internalHandler;
    // It's safe to use "this" because we don't mux any data before exiting the constructor.
    @SuppressWarnings("nullness:argument.type.incompatible")
    MuxerWrapper muxerWrapper = new MuxerWrapper(outputPath, muxerFactory, /* listener= */ this);
    this.muxerWrapper = muxerWrapper;
  }

  public void start() {
    internalHandler.sendEmptyMessage(MSG_START);
  }

  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (released) {
      return PROGRESS_STATE_NOT_STARTED;
    }
    internalHandler.obtainMessage(MSG_UPDATE_PROGRESS, progressHolder).sendToTarget();
    // TODO: figure out why calling clock.onThreadBlocked() here makes the tests fail.
    transformerConditionVariable.blockUninterruptible();
    transformerConditionVariable.close();
    return progressState;
  }

  public void cancel() {
    if (released) {
      return;
    }
    internalHandler
        .obtainMessage(MSG_END, END_REASON_CANCELLED, /* unused */ 0, /* exportException */ null)
        .sendToTarget();
    clock.onThreadBlocked();
    transformerConditionVariable.blockUninterruptible();
    transformerConditionVariable.close();
    if (cancelException != null) {
      throw cancelException;
    }
  }

  // MuxerWrapper.Listener implementation

  @Override
  public void onTrackEnded(
      @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {
    if (trackType == C.TRACK_TYPE_AUDIO) {
      exportResultBuilder.setAverageAudioBitrate(averageBitrate);
      if (format.channelCount != Format.NO_VALUE) {
        exportResultBuilder.setChannelCount(format.channelCount);
      }
      if (format.sampleRate != Format.NO_VALUE) {
        exportResultBuilder.setSampleRate(format.sampleRate);
      }
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      exportResultBuilder
          .setAverageVideoBitrate(averageBitrate)
          .setColorInfo(format.colorInfo)
          .setVideoFrameCount(sampleCount);
      if (format.height != Format.NO_VALUE) {
        exportResultBuilder.setHeight(format.height);
      }
      if (format.width != Format.NO_VALUE) {
        exportResultBuilder.setWidth(format.width);
      }
    }
  }

  @Override
  public void onEnded(long durationMs, long fileSizeBytes) {
    exportResultBuilder.setDurationMs(durationMs).setFileSizeBytes(fileSizeBytes);

    internalHandler
        .obtainMessage(MSG_END, END_REASON_COMPLETED, /* unused */ 0, /* exportException */ null)
        .sendToTarget();
  }

  @Override
  public void onError(ExportException exportException) {
    internalHandler
        .obtainMessage(MSG_END, END_REASON_ERROR, /* unused */ 0, exportException)
        .sendToTarget();
  }

  // Private methods.

  private boolean handleMessage(Message msg) {
    // Some messages cannot be ignored when resources have been released. End messages must be
    // handled to report release timeouts and to unblock the transformer condition variable in case
    // of cancellation. Progress update messages must be handled to unblock the transformer
    // condition variable.
    if (released && msg.what != MSG_END && msg.what != MSG_UPDATE_PROGRESS) {
      return true;
    }
    try {
      switch (msg.what) {
        case MSG_START:
          startInternal();
          break;
        case MSG_REGISTER_SAMPLE_PIPELINE:
          registerSamplePipelineInternal((SamplePipeline) msg.obj);
          break;
        case MSG_DRAIN_PIPELINES:
          drainPipelinesInternal();
          break;
        case MSG_END:
          endInternal(/* endReason= */ msg.arg1, /* exportException= */ (ExportException) msg.obj);
          break;
        case MSG_UPDATE_PROGRESS:
          updateProgressInternal(/* progressHolder= */ (ProgressHolder) msg.obj);
          break;
        default:
          return false;
      }
    } catch (ExportException e) {
      endInternal(END_REASON_ERROR, e);
    } catch (RuntimeException e) {
      endInternal(END_REASON_ERROR, ExportException.createForUnexpected(e));
    }
    return true;
  }

  private void startInternal() {
    for (int i = 0; i < sequenceAssetLoaders.size(); i++) {
      sequenceAssetLoaders.get(i).start();
    }
  }

  private void registerSamplePipelineInternal(SamplePipeline samplePipeline) {
    samplePipelines.add(samplePipeline);
    if (!isDrainingPipelines) {
      internalHandler.sendEmptyMessage(MSG_DRAIN_PIPELINES);
      isDrainingPipelines = true;
    }
  }

  private void drainPipelinesInternal() throws ExportException {
    for (int i = 0; i < samplePipelines.size(); i++) {
      while (samplePipelines.get(i).processData()) {}
    }

    if (!muxerWrapper.isEnded()) {
      internalHandler.sendEmptyMessageDelayed(MSG_DRAIN_PIPELINES, DRAIN_PIPELINES_DELAY_MS);
    }
  }

  private void endInternal(@EndReason int endReason, @Nullable ExportException exportException) {
    ImmutableList.Builder<ExportResult.ProcessedInput> processedInputsBuilder =
        new ImmutableList.Builder<>();
    for (int i = 0; i < sequenceAssetLoaders.size(); i++) {
      processedInputsBuilder.addAll(sequenceAssetLoaders.get(i).getProcessedInputs());
    }
    exportResultBuilder
        .setProcessedInputs(processedInputsBuilder.build())
        .setAudioEncoderName(encoderFactory.getAudioEncoderName())
        .setVideoEncoderName(encoderFactory.getVideoEncoderName());

    boolean forCancellation = endReason == END_REASON_CANCELLED;
    @Nullable ExportException releaseExportException = null;
    if (!released) {
      released = true;
      for (int i = 0; i < sequenceAssetLoaders.size(); i++) {
        try {
          sequenceAssetLoaders.get(i).release();
        } catch (RuntimeException e) {
          if (releaseExportException == null) {
            releaseExportException = ExportException.createForUnexpected(e);
            // cancelException is not reported through a listener. It is thrown in cancel(), as this
            // method is blocking.
            cancelException = e;
          }
        }
      }
      for (int i = 0; i < samplePipelines.size(); i++) {
        try {
          samplePipelines.get(i).release();
        } catch (RuntimeException e) {
          if (releaseExportException == null) {
            releaseExportException = ExportException.createForUnexpected(e);
            cancelException = e;
          }
        }
      }
      try {
        muxerWrapper.release(forCancellation);
      } catch (Muxer.MuxerException e) {
        if (releaseExportException == null) {
          releaseExportException = ExportException.createForMuxer(e, ERROR_CODE_MUXING_FAILED);
        }
      } catch (RuntimeException e) {
        if (releaseExportException == null) {
          releaseExportException = ExportException.createForUnexpected(e);
          cancelException = e;
        }
      }
      // Quit thread lazily so that all events that got triggered when releasing the AssetLoader are
      // still delivered.
      internalHandler.post(internalHandlerThread::quitSafely);
    }

    if (forCancellation) {
      transformerConditionVariable.open();
      return;
    }

    ExportException exception = exportException;
    if (exception == null) {
      // We only report the exception caused by releasing the resources if there is no other
      // exception. It is more intuitive to call the error callback only once and reporting the
      // exception caused by releasing the resources can be confusing if it is a consequence of the
      // first exception.
      exception = releaseExportException;
    }

    if (exception != null) {
      ExportException finalException = exception;
      applicationHandler.post(
          () ->
              listener.onError(
                  exportResultBuilder.setExportException(finalException).build(), finalException));
    } else {
      applicationHandler.post(() -> listener.onCompleted(exportResultBuilder.build()));
    }
  }

  private void updateProgressInternal(ProgressHolder progressHolder) {
    int progressSum = 0;
    ProgressHolder individualProgressHolder = new ProgressHolder();
    for (int i = 0; i < sequenceAssetLoaders.size(); i++) {
      progressState = sequenceAssetLoaders.get(i).getProgress(individualProgressHolder);
      if (progressState != PROGRESS_STATE_AVAILABLE) {
        transformerConditionVariable.open();
        return;
      }
      progressSum += individualProgressHolder.progress;
    }
    progressHolder.progress = progressSum / sequenceAssetLoaders.size();
    transformerConditionVariable.open();
  }

  private final class SequenceAssetLoaderListener implements AssetLoader.Listener {

    private final int sequenceIndex;
    private final ImmutableList<EditedMediaItem> editedMediaItems;
    private final Composition composition;
    private final TransformationRequest transformationRequest;
    private final FallbackListener fallbackListener;
    private final DebugViewProvider debugViewProvider;
    private final Map<Integer, AddedTrackInfo> addedTrackInfoByTrackType;

    public SequenceAssetLoaderListener(
        int sequenceIndex,
        Composition composition,
        TransformationRequest transformationRequest,
        FallbackListener fallbackListener,
        DebugViewProvider debugViewProvider) {
      this.sequenceIndex = sequenceIndex;
      editedMediaItems = composition.sequences.get(sequenceIndex).editedMediaItems;
      this.composition = composition;
      this.transformationRequest = transformationRequest;
      this.fallbackListener = fallbackListener;
      this.debugViewProvider = debugViewProvider;
      addedTrackInfoByTrackType = new HashMap<>();
    }

    @Override
    public void onDurationUs(long durationUs) {}

    @Override
    public void onTrackCount(int trackCount) {
      if (trackCount <= 0) {
        onError(
            ExportException.createForAssetLoader(
                new IllegalStateException("AssetLoader instances must provide at least 1 track."),
                ERROR_CODE_FAILED_RUNTIME_CHECK));
        return;
      }
      tracksToAdd.addAndGet(trackCount);
      trackCountsToReport.decrementAndGet();
    }

    @Override
    public boolean onTrackAdded(
        Format firstAssetLoaderInputFormat,
        @AssetLoader.SupportedOutputTypes int supportedOutputTypes,
        long streamStartPositionUs,
        long streamOffsetUs) {
      @C.TrackType
      int trackType = getProcessedTrackType(firstAssetLoaderInputFormat.sampleMimeType);
      AddedTrackInfo trackInfo =
          new AddedTrackInfo(
              firstAssetLoaderInputFormat,
              supportedOutputTypes,
              streamStartPositionUs,
              streamOffsetUs);
      addedTrackInfoByTrackType.put(trackType, trackInfo);

      if (trackType == C.TRACK_TYPE_AUDIO) {
        outputHasAudio.set(true);
      } else {
        outputHasVideo.set(true);
      }
      if (tracksToAdd.decrementAndGet() == 0 && trackCountsToReport.get() == 0) {
        int outputTrackCount = (outputHasAudio.get() ? 1 : 0) + (outputHasVideo.get() ? 1 : 0);
        muxerWrapper.setTrackCount(outputTrackCount);
        fallbackListener.setTrackCount(outputTrackCount);
      }

      return trackInfo.shouldTranscode;
    }

    @Nullable
    @Override
    public SampleConsumer onOutputFormat(Format assetLoaderOutputFormat) throws ExportException {
      if (trackCountsToReport.get() > 0 || tracksToAdd.get() > 0) {
        return null;
      }

      @C.TrackType int trackType = getProcessedTrackType(assetLoaderOutputFormat.sampleMimeType);
      AddedTrackInfo trackInfo = checkStateNotNull(addedTrackInfoByTrackType.get(trackType));
      SamplePipeline samplePipeline = getSamplePipeline(assetLoaderOutputFormat, trackInfo);

      sequenceAssetLoaders
          .get(sequenceIndex)
          .addOnMediaItemChangedListener(samplePipeline, trackType);
      internalHandler.obtainMessage(MSG_REGISTER_SAMPLE_PIPELINE, samplePipeline).sendToTarget();
      return samplePipeline;
    }

    @Override
    public void onError(ExportException exportException) {
      TransformerInternal.this.onError(exportException);
    }

    // Private methods.

    private SamplePipeline getSamplePipeline(
        Format firstAssetLoaderOutputFormat, AddedTrackInfo addedTrackInfo) throws ExportException {
      if (addedTrackInfo.shouldTranscode) {
        EditedMediaItem firstEditedMediaItem = editedMediaItems.get(0);
        if (MimeTypes.isAudio(firstAssetLoaderOutputFormat.sampleMimeType)) {
          return new AudioSamplePipeline(
              addedTrackInfo.firstAssetLoaderInputFormat,
              /* firstPipelineInputFormat= */ firstAssetLoaderOutputFormat,
              addedTrackInfo.streamOffsetUs,
              transformationRequest,
              firstEditedMediaItem.flattenForSlowMotion,
              firstEditedMediaItem.effects.audioProcessors,
              encoderFactory,
              muxerWrapper,
              fallbackListener);
        } else { // MIME type is video or image.
          ImmutableList<Effect> compositionVideoEffects = composition.effects.videoEffects;
          @Nullable
          Presentation compositionPresentation =
              compositionVideoEffects.isEmpty()
                  ? null
                  : (Presentation) compositionVideoEffects.get(0);

          // TODO(b/267301878): Pass firstAssetLoaderOutputFormat once surface creation not in VSP.
          return new VideoSamplePipeline(
              context,
              addedTrackInfo.firstAssetLoaderInputFormat,
              addedTrackInfo.streamStartPositionUs,
              addedTrackInfo.streamOffsetUs,
              transformationRequest,
              firstEditedMediaItem.effects.videoEffects,
              compositionPresentation,
              firstEditedMediaItem.effects.videoFrameProcessorFactory,
              firstEditedMediaItem.effects.glObjectsProvider,
              encoderFactory,
              muxerWrapper,
              /* errorConsumer= */ this::onError,
              fallbackListener,
              debugViewProvider);
        }
      }

      return new EncodedSamplePipeline(
          firstAssetLoaderOutputFormat,
          addedTrackInfo.streamStartPositionUs,
          transformationRequest,
          muxerWrapper,
          fallbackListener);
    }

    private final class AddedTrackInfo {
      public final Format firstAssetLoaderInputFormat;
      public final long streamStartPositionUs;
      public final long streamOffsetUs;
      public final boolean shouldTranscode;

      public AddedTrackInfo(
          Format firstAssetLoaderInputFormat,
          @AssetLoader.SupportedOutputTypes int supportedOutputTypes,
          long streamStartPositionUs,
          long streamOffsetUs) {
        this.firstAssetLoaderInputFormat = firstAssetLoaderInputFormat;
        this.streamStartPositionUs = streamStartPositionUs;
        this.streamOffsetUs = streamOffsetUs;
        shouldTranscode =
            shouldTranscode(
                firstAssetLoaderInputFormat,
                supportedOutputTypes,
                streamStartPositionUs,
                streamOffsetUs);
      }

      private boolean shouldTranscode(
          Format inputFormat,
          @AssetLoader.SupportedOutputTypes int supportedOutputTypes,
          long streamStartPositionUs,
          long streamOffsetUs) {
        boolean assetLoaderCanOutputDecoded =
            (supportedOutputTypes & SUPPORTED_OUTPUT_TYPE_DECODED) != 0;
        boolean assetLoaderCanOutputEncoded =
            (supportedOutputTypes & SUPPORTED_OUTPUT_TYPE_ENCODED) != 0;
        checkArgument(assetLoaderCanOutputDecoded || assetLoaderCanOutputEncoded);

        @C.TrackType int trackType = getProcessedTrackType(inputFormat.sampleMimeType);

        boolean shouldTranscode = false;
        if (!assetLoaderCanOutputEncoded) {
          shouldTranscode = true;
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
          shouldTranscode = shouldTranscodeAudio(inputFormat);
        } else if (trackType == C.TRACK_TYPE_VIDEO) {
          shouldTranscode =
              shouldTranscodeVideo(inputFormat, streamStartPositionUs, streamOffsetUs);
        }

        checkState(!shouldTranscode || assetLoaderCanOutputDecoded);

        return shouldTranscode;
      }

      private boolean shouldTranscodeAudio(Format inputFormat) {
        if (composition.sequences.size() > 1 || editedMediaItems.size() > 1) {
          return !composition.transmuxAudio;
        }
        if (encoderFactory.audioNeedsEncoding()) {
          return true;
        }
        if (transformationRequest.audioMimeType != null
            && !transformationRequest.audioMimeType.equals(inputFormat.sampleMimeType)) {
          return true;
        }
        if (transformationRequest.audioMimeType == null
            && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
          return true;
        }
        EditedMediaItem firstEditedMediaItem = editedMediaItems.get(0);
        if (firstEditedMediaItem.flattenForSlowMotion && containsSlowMotionData(inputFormat)) {
          return true;
        }
        if (!firstEditedMediaItem.effects.audioProcessors.isEmpty()) {
          return true;
        }
        return false;
      }

      private boolean shouldTranscodeVideo(
          Format inputFormat, long streamStartPositionUs, long streamOffsetUs) {
        if (composition.sequences.size() > 1 || editedMediaItems.size() > 1) {
          return !composition.transmuxVideo;
        }
        EditedMediaItem firstEditedMediaItem = editedMediaItems.get(0);
        if ((streamStartPositionUs - streamOffsetUs) != 0
            && !firstEditedMediaItem.mediaItem.clippingConfiguration.startsAtKeyFrame) {
          return true;
        }
        if (encoderFactory.videoNeedsEncoding()) {
          return true;
        }
        if (transformationRequest.hdrMode != TransformationRequest.HDR_MODE_KEEP_HDR) {
          return true;
        }
        if (transformationRequest.videoMimeType != null
            && !transformationRequest.videoMimeType.equals(inputFormat.sampleMimeType)) {
          return true;
        }
        if (transformationRequest.videoMimeType == null
            && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
          return true;
        }
        if (inputFormat.pixelWidthHeightRatio != 1f) {
          return true;
        }
        if (!areVideoEffectsAllNoOp(firstEditedMediaItem.effects.videoEffects, inputFormat)) {
          return true;
        }
        return false;
      }
    }
  }
}
