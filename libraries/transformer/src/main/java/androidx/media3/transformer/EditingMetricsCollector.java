/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.common.util.Util.usToMs;

import android.content.Context;
import android.hardware.DataSpace;
import android.media.metrics.BundleSession;
import android.media.metrics.EditingEndedEvent;
import android.media.metrics.EditingSession;
import android.media.metrics.LogSessionId;
import android.media.metrics.MediaItemInfo;
import android.media.metrics.MediaMetricsManager;
import android.os.PersistableBundle;
import android.util.Size;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.ByteBufferGlEffect;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.Crop;
import androidx.media3.effect.FrameCache;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.LanczosResample;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SeparableConvolution;
import androidx.media3.effect.TimestampWrapper;
import androidx.media3.exoplayer.audio.ChannelMappingAudioProcessor;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A metrics collector that collects editing events and forwards them to {@link MetricsReporter}.
 */
@RequiresApi(35)
/* package */ final class EditingMetricsCollector {

  /** Reports the collected metrics. */
  public interface MetricsReporter extends AutoCloseable {
    /** Factory for metrics reporters */
    interface Factory {
      /** Returns a new {@link MetricsReporter}. */
      MetricsReporter create();
    }

    /**
     * Reports the given {@link EditingEndedEvent}.
     *
     * <p>The method should be called at most once.
     */
    void reportMetrics(EditingEndedEvent editingEndedEvent);

    /** Reports effect metrics using a {@link PersistableBundle}. */
    default void reportEffectMetrics(PersistableBundle bundle) {}
  }

  /**
   * A default implementation of {@link MetricsReporter} that reports metrics to an {@link
   * EditingSession}.
   */
  static final class DefaultMetricsReporter implements MetricsReporter {
    /** A {@link MetricsReporter.Factory} for {@link DefaultMetricsReporter}. */
    public static final class Factory implements MetricsReporter.Factory {
      private final Context context;

      /**
       * Creates an instance.
       *
       * @param context The {@link Context}.
       */
      public Factory(Context context) {
        this.context = context;
      }

      @Override
      public MetricsReporter create() {
        return new DefaultMetricsReporter(context);
      }
    }

    /** The {@link EditingSession} to report collected metrics to. */
    @Nullable private EditingSession editingSession;

    @Nullable private final MediaMetricsManager mediaMetricsManager;

    private boolean metricsReported;
    private boolean effectMetricsReported;

    private DefaultMetricsReporter(Context context) {
      mediaMetricsManager =
          (MediaMetricsManager) context.getSystemService(Context.MEDIA_METRICS_SERVICE);
      if (mediaMetricsManager != null) {
        editingSession = mediaMetricsManager.createEditingSession();
      }
    }

    @Override
    public void reportMetrics(EditingEndedEvent editingEndedEvent) {
      if (!metricsReported && editingSession != null) {
        editingSession.reportEditingEndedEvent(editingEndedEvent);
        metricsReported = true;
      }
    }

    @Override
    public void reportEffectMetrics(PersistableBundle bundle) {
      if (!effectMetricsReported && mediaMetricsManager != null) {
        if (editingSession != null) {
          LogSessionId logSessionId = editingSession.getSessionId();
          if (logSessionId != null) {
            bundle.putString(KEY_PRIMARY_LOG_SESSION_ID, logSessionId.getStringId());
          }
        }
        bundle.putInt(BundleSession.KEY_STATSD_ATOM, STATSD_ATOM_MEDIA_PROCESSING_EVENT_REPORTED);
        try (BundleSession bundleSession = mediaMetricsManager.createBundleSession()) {
          bundleSession.reportBundleMetrics(bundle);
          effectMetricsReported = true;
        }
      }
    }

    @Override
    public void close() {
      if (editingSession != null) {
        editingSession.close();
        editingSession = null;
      }
    }

    /**
     * Returns the {@link LogSessionId} associated with the current editing session, or {@code null}
     * if no {@link EditingSession} is active.
     */
    @Nullable
    public LogSessionId getLogSessionId() {
      if (editingSession != null) {
        return editingSession.getSessionId();
      }
      return null;
    }
  }

  private static final String TAG = "EditingMetricsCollector";
  // TODO: b/386328723 - Add missing error codes to EditingEndedEvent.ErrorCode.
  private static final SparseIntArray ERROR_CODE_CONVERSION_MAP = new SparseIntArray();
  private static final SparseIntArray DATA_SPACE_STANDARD_CONVERSION_MAP = new SparseIntArray();
  private static final SparseIntArray DATA_SPACE_RANGE_CONVERSION_MAP = new SparseIntArray();
  private static final SparseIntArray DATA_SPACE_TRANSFER_CONVERSION_MAP = new SparseIntArray();

  static {
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_UNSPECIFIED, EditingEndedEvent.ERROR_CODE_NONE);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        EditingEndedEvent.ERROR_CODE_FAILED_RUNTIME_CHECK);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_UNSPECIFIED, EditingEndedEvent.ERROR_CODE_IO_UNSPECIFIED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        EditingEndedEvent.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        EditingEndedEvent.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        EditingEndedEvent.ERROR_CODE_IO_UNSPECIFIED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        EditingEndedEvent.ERROR_CODE_IO_BAD_HTTP_STATUS);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        EditingEndedEvent.ERROR_CODE_IO_FILE_NOT_FOUND);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_NO_PERMISSION, EditingEndedEvent.ERROR_CODE_IO_NO_PERMISSION);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        EditingEndedEvent.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        EditingEndedEvent.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        EditingEndedEvent.ERROR_CODE_DECODER_INIT_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_DECODING_FAILED, EditingEndedEvent.ERROR_CODE_DECODING_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        EditingEndedEvent.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        EditingEndedEvent.ERROR_CODE_ENCODER_INIT_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_ENCODING_FAILED, EditingEndedEvent.ERROR_CODE_ENCODING_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        EditingEndedEvent.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        EditingEndedEvent.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED,
        EditingEndedEvent.ERROR_CODE_AUDIO_PROCESSING_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_MUXING_FAILED, EditingEndedEvent.ERROR_CODE_MUXING_FAILED);
    ERROR_CODE_CONVERSION_MAP.put(
        ExportException.ERROR_CODE_MUXING_TIMEOUT,
        EditingEndedEvent.ERROR_CODE_FAILED_RUNTIME_CHECK);
    DATA_SPACE_STANDARD_CONVERSION_MAP.put(Format.NO_VALUE, DataSpace.STANDARD_UNSPECIFIED);
    DATA_SPACE_STANDARD_CONVERSION_MAP.put(C.COLOR_SPACE_BT601, DataSpace.STANDARD_BT601_625);
    DATA_SPACE_STANDARD_CONVERSION_MAP.put(C.COLOR_SPACE_BT709, DataSpace.STANDARD_BT709);
    DATA_SPACE_STANDARD_CONVERSION_MAP.put(C.COLOR_SPACE_BT2020, DataSpace.STANDARD_BT2020);
    DATA_SPACE_RANGE_CONVERSION_MAP.put(Format.NO_VALUE, DataSpace.RANGE_UNSPECIFIED);
    DATA_SPACE_RANGE_CONVERSION_MAP.put(C.COLOR_RANGE_LIMITED, DataSpace.RANGE_LIMITED);
    DATA_SPACE_RANGE_CONVERSION_MAP.put(C.COLOR_RANGE_FULL, DataSpace.RANGE_FULL);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(Format.NO_VALUE, DataSpace.TRANSFER_UNSPECIFIED);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_LINEAR, DataSpace.TRANSFER_LINEAR);
    // MediaCodec defines SDR to be SMPTE_170M, but many OEMs use Gamma 2.2.
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_SDR, DataSpace.TRANSFER_SMPTE_170M);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_SRGB, DataSpace.TRANSFER_SRGB);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_GAMMA_2_2, DataSpace.TRANSFER_GAMMA2_2);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_ST2084, DataSpace.TRANSFER_ST2084);
    DATA_SPACE_TRANSFER_CONVERSION_MAP.put(C.COLOR_TRANSFER_HLG, DataSpace.TRANSFER_HLG);
  }

  private static final int SUCCESS_PROGRESS_PERCENTAGE = 100;

  // Constants for Atom 1279 (MediaProcessingEventReported) and BundleSession keys.
  @VisibleForTesting static final String KEY_PRIMARY_LOG_SESSION_ID = "primary_log_session_id";
  @VisibleForTesting static final String KEY_PROCESSOR_NAME = "processor_name";
  @VisibleForTesting static final String KEY_COMPONENTS = "components";
  @VisibleForTesting static final String KEY_COMPONENT_SCOPES = "component_scopes";
  @VisibleForTesting static final String KEY_METRICS = "metrics";
  @VisibleForTesting static final String KEY_METRIC_VALUES = "metric_values";
  private static final int STATSD_ATOM_MEDIA_PROCESSING_EVENT_REPORTED = 1279;

  // Media3 Component category enums for Atom 1279.
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_COLOR = 100;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_SPATIAL = 101;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_CONVOLUTION = 102;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_OVERLAY = 103;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_TEMPORAL = 104;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_BUFFER = 105;
  @VisibleForTesting static final int COMPONENT_MEDIA3_EFFECT_CUSTOM = 199;

  @VisibleForTesting static final int COMPONENT_MEDIA3_AUDIO_SPEED_AND_PITCH = 200;
  @VisibleForTesting static final int COMPONENT_MEDIA3_AUDIO_CHANNEL_MANIPULATION = 201;
  @VisibleForTesting static final int COMPONENT_MEDIA3_AUDIO_TEMPORAL_TRIM = 202;
  @VisibleForTesting static final int COMPONENT_MEDIA3_AUDIO_PROCESSOR_CUSTOM = 299;

  // Quantitative Metric keys for Atom 1279.
  @VisibleForTesting static final int METRIC_ROTATION_DEGREES = 301;

  private final long startTimeMs;
  private final boolean compositionHasAudioProcessors;
  private final boolean compositionHasVideoEffects;
  private final String exporterName;
  @Nullable private final String muxerName;
  private final MetricsReporter metricsReporter;

  /**
   * Creates an instance.
   *
   * <p>A new instance must be created before starting a new export.
   *
   * <p>Both {@code exporterName} and {@code muxerName} should follow the format
   * "<packageName>:<version>".
   *
   * @param metricsReporter The {@link MetricsReporter} to report metrics.
   * @param exporterName Java package name and version of the library or application implementing
   *     the editing operation.
   * @param muxerName Java package name and version of the library or application that writes to the
   *     output file.
   */
  public EditingMetricsCollector(
      MetricsReporter metricsReporter,
      String exporterName,
      @Nullable String muxerName,
      boolean compositionHasAudioProcessors,
      boolean compositionHasVideoEffects) {
    this.metricsReporter = metricsReporter;
    this.exporterName = exporterName;
    this.muxerName = muxerName;
    this.compositionHasAudioProcessors = compositionHasAudioProcessors;
    this.compositionHasVideoEffects = compositionHasVideoEffects;
    startTimeMs = SystemClock.DEFAULT.elapsedRealtime();
  }

  /**
   * Called when export completes with success.
   *
   * @param composition The exported {@link Composition}.
   * @param exportResult The {@link ExportResult} of the export.
   * @param isExportResumed Whether the export was resumed.
   */
  public void onExportSuccess(
      Composition composition, ExportResult exportResult, boolean isExportResumed) {
    reportEffectMetrics(composition);
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_SUCCEEDED)
            .setFinalProgressPercent(SUCCESS_PROGRESS_PERCENTAGE);
    populateOperationTypes(editingEndedEventBuilder, exportResult, isExportResumed);

    List<MediaItemInfo> inputMediaItemInfoList =
        getInputMediaItemInfos(exportResult.processedInputs);
    for (int i = 0; i < inputMediaItemInfoList.size(); i++) {
      MediaItemInfo inputMediaItemInfo = inputMediaItemInfoList.get(i);
      editingEndedEventBuilder.addInputMediaItemInfo(inputMediaItemInfo);
    }
    editingEndedEventBuilder.setOutputMediaItemInfo(getOutputMediaItemInfo(exportResult));

    metricsReporter.reportMetrics(editingEndedEventBuilder.build());
    try {
      metricsReporter.close();
    } catch (Exception e) {
      Log.e(TAG, "error while closing the metrics reporter", e);
    }
  }

  /**
   * Called when export completes with an error.
   *
   * @param composition The exported {@link Composition}.
   * @param progressPercentage The progress of the export operation in percent. Value is {@link
   *     C#PERCENTAGE_UNSET} if unknown or between 0 and 100 inclusive.
   * @param exportException The {@link ExportException} describing the exception.
   * @param exportResult The {@link ExportResult} of the export.
   * @param isExportResumed Whether the export was resumed.
   */
  public void onExportError(
      Composition composition,
      int progressPercentage,
      ExportException exportException,
      ExportResult exportResult,
      boolean isExportResumed) {
    reportEffectMetrics(composition);
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_ERROR)
            .setErrorCode(getEditingEndedEventErrorCode(exportException.errorCode));
    if (progressPercentage != C.PERCENTAGE_UNSET) {
      editingEndedEventBuilder.setFinalProgressPercent(progressPercentage);
    }
    populateOperationTypes(editingEndedEventBuilder, exportResult, isExportResumed);

    List<MediaItemInfo> inputMediaItemInfoList =
        getInputMediaItemInfos(exportResult.processedInputs);
    for (int i = 0; i < inputMediaItemInfoList.size(); i++) {
      MediaItemInfo inputMediaItemInfo = inputMediaItemInfoList.get(i);
      editingEndedEventBuilder.addInputMediaItemInfo(inputMediaItemInfo);
    }
    editingEndedEventBuilder.setOutputMediaItemInfo(getOutputMediaItemInfo(exportResult));

    metricsReporter.reportMetrics(editingEndedEventBuilder.build());
    try {
      metricsReporter.close();
    } catch (Exception e) {
      Log.e(TAG, "error while closing the metrics reporter", e);
    }
  }

  /**
   * Called when export is cancelled.
   *
   * @param composition The exported {@link Composition}.
   * @param progressPercentage The progress of the export operation in percent. Value is {@link
   *     C#PERCENTAGE_UNSET} if unknown or between 0 and 100 inclusive.
   */
  public void onExportCancelled(Composition composition, int progressPercentage) {
    reportEffectMetrics(composition);
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_CANCELED);
    if (progressPercentage != C.PERCENTAGE_UNSET) {
      editingEndedEventBuilder.setFinalProgressPercent(progressPercentage);
    }
    if (compositionHasAudioProcessors) {
      editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_AUDIO_EDIT);
    }
    if (compositionHasVideoEffects) {
      editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_VIDEO_EDIT);
    }

    metricsReporter.reportMetrics(editingEndedEventBuilder.build());
    try {
      metricsReporter.close();
    } catch (Exception e) {
      Log.e(TAG, "error while closing the metrics reporter", e);
    }
  }

  private EditingEndedEvent.Builder createEditingEndedEventBuilder(int finalState) {
    long endTimeMs = SystemClock.DEFAULT.elapsedRealtime();
    EditingEndedEvent.Builder editingEndedEventBuilder =
        new EditingEndedEvent.Builder(finalState)
            .setTimeSinceCreatedMillis(endTimeMs - startTimeMs)
            .setExporterName(exporterName);
    if (muxerName != null) {
      // TODO: b/391888233 - Update `PATTERN_KNOWN_EDITING_LIBRARY_NAMES` regex pattern to accept
      //  Framework Muxer's library name.
      editingEndedEventBuilder.setMuxerName(muxerName);
    }
    return editingEndedEventBuilder;
  }

  private static List<MediaItemInfo> getInputMediaItemInfos(
      ImmutableList<ExportResult.ProcessedInput> processedInputs) {
    List<MediaItemInfo> mediaItemInfoList = new ArrayList<>();
    for (int i = 0; i < processedInputs.size(); i++) {
      ExportResult.ProcessedInput processedInput = processedInputs.get(i);
      MediaItemInfo.Builder mediaItemInfoBuilder = new MediaItemInfo.Builder();
      long durationMs = usToMs(processedInput.durationUs);
      mediaItemInfoBuilder.setClipDurationMillis(durationMs);
      if (processedInput.videoDecoderName != null) {
        mediaItemInfoBuilder.addCodecName(processedInput.videoDecoderName);
      }
      if (processedInput.audioDecoderName != null) {
        mediaItemInfoBuilder.addCodecName(processedInput.audioDecoderName);
      }
      @Nullable Format videoFormat = processedInput.videoFormat;
      if (videoFormat != null) {
        if (videoFormat.containerMimeType != null) {
          mediaItemInfoBuilder.setContainerMimeType(videoFormat.containerMimeType);
        }
        if (videoFormat.sampleMimeType != null) {
          mediaItemInfoBuilder.addSampleMimeType(videoFormat.sampleMimeType);
          mediaItemInfoBuilder.addDataType(getDataTypes(videoFormat.sampleMimeType));
        }
        if (videoFormat.frameRate != Format.NO_VALUE) {
          mediaItemInfoBuilder.setVideoFrameRate(videoFormat.frameRate);
        }
        Size videoSize =
            new Size(
                videoFormat.width != Format.NO_VALUE
                    ? videoFormat.width
                    : MediaItemInfo.VALUE_UNSPECIFIED,
                videoFormat.height != Format.NO_VALUE
                    ? videoFormat.height
                    : MediaItemInfo.VALUE_UNSPECIFIED);
        mediaItemInfoBuilder.setVideoSize(videoSize);
        if (videoFormat.colorInfo != null) {
          mediaItemInfoBuilder.setVideoDataSpace(getVideoDataSpace(videoFormat.colorInfo));
        }
      }
      Format audioFormat = processedInput.audioFormat;
      if (audioFormat != null) {
        if (audioFormat.sampleMimeType != null) {
          mediaItemInfoBuilder.addSampleMimeType(audioFormat.sampleMimeType);
          mediaItemInfoBuilder.addDataType(getDataTypes(audioFormat.sampleMimeType));
        }
        if (audioFormat.channelCount != Format.NO_VALUE) {
          mediaItemInfoBuilder.setAudioChannelCount(audioFormat.channelCount);
        }
        if (audioFormat.sampleRate != Format.NO_VALUE) {
          mediaItemInfoBuilder.setAudioSampleRateHz(audioFormat.sampleRate);
        }
      }
      mediaItemInfoList.add(mediaItemInfoBuilder.build());
    }
    return mediaItemInfoList;
  }

  private static MediaItemInfo getOutputMediaItemInfo(ExportResult exportResult) {
    MediaItemInfo.Builder mediaItemInfoBuilder = new MediaItemInfo.Builder();
    if (exportResult.approximateDurationMs != C.TIME_UNSET) {
      mediaItemInfoBuilder.setDurationMillis(exportResult.approximateDurationMs);
    }
    if (exportResult.audioMimeType != null) {
      mediaItemInfoBuilder.addSampleMimeType(exportResult.audioMimeType);
      mediaItemInfoBuilder.addDataType(getDataTypes(exportResult.audioMimeType));
    }
    if (exportResult.videoMimeType != null) {
      mediaItemInfoBuilder.addSampleMimeType(exportResult.videoMimeType);
      mediaItemInfoBuilder.addDataType(getDataTypes(exportResult.videoMimeType));
    }
    if (exportResult.channelCount != C.LENGTH_UNSET) {
      mediaItemInfoBuilder.setAudioChannelCount(exportResult.channelCount);
    }
    if (exportResult.sampleRate != C.RATE_UNSET_INT) {
      mediaItemInfoBuilder.setAudioSampleRateHz(exportResult.sampleRate);
    }
    if (exportResult.audioEncoderName != null) {
      mediaItemInfoBuilder.addCodecName(exportResult.audioEncoderName);
    }
    if (exportResult.videoEncoderName != null) {
      mediaItemInfoBuilder.addCodecName(exportResult.videoEncoderName);
    }
    mediaItemInfoBuilder.setVideoSampleCount(exportResult.videoFrameCount);
    Size videoSize =
        new Size(
            exportResult.width != C.LENGTH_UNSET
                ? exportResult.width
                : MediaItemInfo.VALUE_UNSPECIFIED,
            exportResult.height != C.LENGTH_UNSET
                ? exportResult.height
                : MediaItemInfo.VALUE_UNSPECIFIED);
    mediaItemInfoBuilder.setVideoSize(videoSize);
    if (exportResult.colorInfo != null) {
      mediaItemInfoBuilder.setVideoDataSpace(getVideoDataSpace(exportResult.colorInfo));
    }
    return mediaItemInfoBuilder.build();
  }

  private static long getDataTypes(@Nullable String sampleMimeType) {
    long dataTypes = 0L;
    if (MimeTypes.isAudio(sampleMimeType)) {
      dataTypes |= MediaItemInfo.DATA_TYPE_AUDIO;
    }
    if (MimeTypes.isVideo(sampleMimeType)) {
      dataTypes |= MediaItemInfo.DATA_TYPE_VIDEO;
    }
    if (MimeTypes.isImage(sampleMimeType)) {
      dataTypes |= MediaItemInfo.DATA_TYPE_IMAGE;
    }
    return dataTypes;
  }

  private static int getVideoDataSpace(ColorInfo colorInfo) {
    int colorStandard =
        DATA_SPACE_STANDARD_CONVERSION_MAP.get(
            colorInfo.colorSpace, DataSpace.STANDARD_UNSPECIFIED);
    int colorTransfer =
        DATA_SPACE_TRANSFER_CONVERSION_MAP.get(
            colorInfo.colorTransfer, DataSpace.TRANSFER_UNSPECIFIED);
    int colorRange =
        DATA_SPACE_RANGE_CONVERSION_MAP.get(colorInfo.colorRange, DataSpace.RANGE_UNSPECIFIED);
    return DataSpace.pack(colorStandard, colorTransfer, colorRange);
  }

  private static int getEditingEndedEventErrorCode(@ExportException.ErrorCode int errorCode) {
    return ERROR_CODE_CONVERSION_MAP.get(errorCode, EditingEndedEvent.ERROR_CODE_NONE);
  }

  private void populateOperationTypes(
      EditingEndedEvent.Builder editingEndedEventBuilder,
      ExportResult exportResult,
      boolean isExportResumed) {
    if (isExportResumed) {
      editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_RESUMED);
    }

    boolean hasAudio =
        Iterables.any(
            exportResult.processedInputs, processedInput -> processedInput.audioFormat != null);
    boolean hasVideo =
        Iterables.any(
            exportResult.processedInputs, processedInput -> processedInput.videoFormat != null);

    if (hasAudio) {
      if (exportResult.audioEncoderName != null) {
        editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_AUDIO_TRANSCODE);
      } else {
        editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_AUDIO_TRANSMUX);
      }
    }
    if (hasVideo) {
      if (exportResult.videoEncoderName != null) {
        editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_VIDEO_TRANSCODE);
      } else {
        editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_VIDEO_TRANSMUX);
      }
    }
    if (compositionHasAudioProcessors) {
      editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_AUDIO_EDIT);
    }
    if (compositionHasVideoEffects) {
      editingEndedEventBuilder.addOperationType(EditingEndedEvent.OPERATION_TYPE_VIDEO_EDIT);
    }
  }

  private static final class ExtractedEffectMetrics {
    final ImmutableList<Integer> components;
    final ImmutableList<Integer> scopes;
    final ImmutableList<Integer> metricKeys;
    final ImmutableList<Long> metricValues;

    ExtractedEffectMetrics(
        ImmutableList<Integer> components,
        ImmutableList<Integer> scopes,
        ImmutableList<Integer> metricKeys,
        ImmutableList<Long> metricValues) {
      this.components = components;
      this.scopes = scopes;
      this.metricKeys = metricKeys;
      this.metricValues = metricValues;
    }
  }

  private static final class MetricEntry {
    final int key;
    final long value;

    MetricEntry(int key, long value) {
      this.key = key;
      this.value = value;
    }
  }

  /** Reports video effects and audio processors telemetry for the given {@link Composition}. */
  private void reportEffectMetrics(Composition composition) {
    ExtractedEffectMetrics metrics = extractEffectMetrics(composition);
    if (metrics.components.isEmpty()) {
      return;
    }

    PersistableBundle bundle = new PersistableBundle();
    bundle.putString(KEY_PROCESSOR_NAME, exporterName);

    int[] componentsArray = new int[metrics.components.size()];
    int[] scopesArray = new int[metrics.scopes.size()];
    for (int i = 0; i < metrics.components.size(); i++) {
      componentsArray[i] = metrics.components.get(i);
      scopesArray[i] = metrics.scopes.get(i);
    }
    bundle.putIntArray(KEY_COMPONENTS, componentsArray);
    bundle.putIntArray(KEY_COMPONENT_SCOPES, scopesArray);

    if (!metrics.metricKeys.isEmpty()) {
      int[] metricsArray = new int[metrics.metricKeys.size()];
      long[] metricValuesArray = new long[metrics.metricValues.size()];
      for (int i = 0; i < metrics.metricKeys.size(); i++) {
        metricsArray[i] = metrics.metricKeys.get(i);
        metricValuesArray[i] = metrics.metricValues.get(i);
      }
      bundle.putIntArray(KEY_METRICS, metricsArray);
      bundle.putLongArray(KEY_METRIC_VALUES, metricValuesArray);
    }

    metricsReporter.reportEffectMetrics(bundle);
  }

  private static ExtractedEffectMetrics extractEffectMetrics(Composition composition) {
    ImmutableList.Builder<Integer> componentListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Integer> scopeListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Integer> metricKeyListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Long> metricValueListBuilder = ImmutableList.builder();

    Set<String> processedTuples = new HashSet<>();

    // Scope 0: Composition level
    extractFromEffects(
        composition.effects.videoEffects,
        composition.effects.audioProcessors,
        /* scope= */ 0,
        componentListBuilder,
        scopeListBuilder,
        metricKeyListBuilder,
        metricValueListBuilder,
        processedTuples);

    // Scope 1..N: MediaItem level
    int itemScope = 1;
    for (EditedMediaItemSequence sequence : composition.sequences) {
      for (EditedMediaItem item : sequence.editedMediaItems) {
        extractFromEffects(
            item.effects.videoEffects,
            item.effects.audioProcessors,
            itemScope++,
            componentListBuilder,
            scopeListBuilder,
            metricKeyListBuilder,
            metricValueListBuilder,
            processedTuples);
      }
    }

    return new ExtractedEffectMetrics(
        componentListBuilder.build(),
        scopeListBuilder.build(),
        metricKeyListBuilder.build(),
        metricValueListBuilder.build());
  }

  private static void extractFromEffects(
      List<Effect> videoEffects,
      List<AudioProcessor> audioProcessors,
      int scope,
      ImmutableList.Builder<Integer> componentListBuilder,
      ImmutableList.Builder<Integer> scopeListBuilder,
      ImmutableList.Builder<Integer> metricKeyListBuilder,
      ImmutableList.Builder<Long> metricValueListBuilder,
      Set<String> processedTuples) {
    for (Effect effect : videoEffects) {
      if (effect instanceof TimestampWrapper) {
        effect = ((TimestampWrapper) effect).glEffect;
      }
      int componentEnum = getComponentEnumForVideoEffect(effect);
      String tupleKey = componentEnum + ":" + scope;
      if (processedTuples.add(tupleKey)) {
        componentListBuilder.add(componentEnum);
        scopeListBuilder.add(scope);
      }
      for (MetricEntry metricEntry : extractMetricsForVideoEffect(effect)) {
        metricKeyListBuilder.add(metricEntry.key);
        metricValueListBuilder.add(metricEntry.value);
      }
    }
    for (AudioProcessor audioProcessor : audioProcessors) {
      int componentEnum = getComponentEnumForAudioProcessor(audioProcessor);
      String tupleKey = componentEnum + ":" + scope;
      if (processedTuples.add(tupleKey)) {
        componentListBuilder.add(componentEnum);
        scopeListBuilder.add(scope);
      }
    }
  }

  private static int getComponentEnumForVideoEffect(Effect effect) {
    if (effect instanceof RgbMatrix
        || effect instanceof ColorLut
        || effect instanceof HslAdjustment
        || effect instanceof AlphaScale) {
      return COMPONENT_MEDIA3_EFFECT_COLOR;
    } else if (effect instanceof Crop
        || effect instanceof Presentation
        || effect instanceof ScaleAndRotateTransformation
        || effect instanceof LanczosResample) {
      return COMPONENT_MEDIA3_EFFECT_SPATIAL;
    } else if (effect instanceof SeparableConvolution || effect instanceof GaussianBlur) {
      return COMPONENT_MEDIA3_EFFECT_CONVOLUTION;
    } else if (effect instanceof OverlayEffect) {
      return COMPONENT_MEDIA3_EFFECT_OVERLAY;
    } else if (effect instanceof FrameDropEffect) {
      return COMPONENT_MEDIA3_EFFECT_TEMPORAL;
    } else if (effect instanceof ByteBufferGlEffect || effect instanceof FrameCache) {
      return COMPONENT_MEDIA3_EFFECT_BUFFER;
    } else if (effect instanceof GlEffect) {
      return COMPONENT_MEDIA3_EFFECT_CUSTOM;
    }
    return COMPONENT_MEDIA3_EFFECT_CUSTOM;
  }

  private static int getComponentEnumForAudioProcessor(AudioProcessor audioProcessor) {
    if (audioProcessor instanceof SonicAudioProcessor
        || audioProcessor instanceof SpeedChangingAudioProcessor) {
      return COMPONENT_MEDIA3_AUDIO_SPEED_AND_PITCH;
    } else if (audioProcessor instanceof GainProcessor
        || audioProcessor instanceof ChannelMixingAudioProcessor
        || audioProcessor instanceof ChannelMappingAudioProcessor) {
      return COMPONENT_MEDIA3_AUDIO_CHANNEL_MANIPULATION;
    } else if (audioProcessor instanceof SilenceSkippingAudioProcessor
        || audioProcessor instanceof TrimmingAudioProcessor) {
      return COMPONENT_MEDIA3_AUDIO_TEMPORAL_TRIM;
    }
    return COMPONENT_MEDIA3_AUDIO_PROCESSOR_CUSTOM;
  }

  private static ImmutableList<MetricEntry> extractMetricsForVideoEffect(Effect effect) {
    if (effect instanceof ScaleAndRotateTransformation) {
      ScaleAndRotateTransformation transformation = (ScaleAndRotateTransformation) effect;
      return ImmutableList.of(
          new MetricEntry(
              METRIC_ROTATION_DEGREES, (long) Math.round(transformation.rotationDegrees)));
    }
    return ImmutableList.of();
  }
}
