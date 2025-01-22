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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.usToMs;

import android.content.Context;
import android.hardware.DataSpace;
import android.media.metrics.EditingEndedEvent;
import android.media.metrics.EditingSession;
import android.media.metrics.MediaItemInfo;
import android.media.metrics.MediaMetricsManager;
import android.util.Size;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.SystemClock;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A metrics collector that collects editing events and forwards them to an {@link EditingSession}
 * created by {@link MediaMetricsManager}.
 */
@RequiresApi(35)
/* package */ final class EditingMetricsCollector {

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
  private @MonotonicNonNull EditingSession editingSession;
  private long startTimeMs;

  /**
   * Creates an instance.
   *
   * <p>A new instance must be created before starting a new export.
   *
   * @param context The {@link Context}.
   */
  public EditingMetricsCollector(Context context) {
    @Nullable
    MediaMetricsManager mediaMetricsManager =
        (MediaMetricsManager) context.getSystemService(Context.MEDIA_METRICS_SERVICE);
    if (mediaMetricsManager != null) {
      editingSession = checkNotNull(mediaMetricsManager.createEditingSession());
      startTimeMs = SystemClock.DEFAULT.elapsedRealtime();
    }
  }

  /**
   * Called when export completes with success.
   *
   * @param processedInputs The list of {@link ExportResult.ProcessedInput} instances.
   */
  public void onExportSuccess(ImmutableList<ExportResult.ProcessedInput> processedInputs) {
    if (editingSession == null) {
      return;
    }
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_SUCCEEDED)
            .setFinalProgressPercent(SUCCESS_PROGRESS_PERCENTAGE);

    List<MediaItemInfo> inputMediaItemInfoList = getMediaItemInfos(processedInputs);
    for (int i = 0; i < inputMediaItemInfoList.size(); i++) {
      MediaItemInfo inputMediaItemInfo = inputMediaItemInfoList.get(i);
      editingEndedEventBuilder.addInputMediaItemInfo(inputMediaItemInfo);
    }

    editingSession.reportEditingEndedEvent(editingEndedEventBuilder.build());
    editingSession.close();
  }

  /**
   * Called when export completes with an error.
   *
   * @param progressPercentage The progress of the export operation in percent. Value is {@link
   *     C#PERCENTAGE_UNSET} if unknown or between 0 and 100 inclusive.
   * @param exportException The {@link ExportException} describing the exception.
   * @param processedInputs The list of {@link ExportResult.ProcessedInput} instances.
   */
  public void onExportError(
      int progressPercentage,
      ExportException exportException,
      ImmutableList<ExportResult.ProcessedInput> processedInputs) {
    if (editingSession == null) {
      return;
    }
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_ERROR)
            .setErrorCode(getEditingEndedEventErrorCode(exportException.errorCode));
    if (progressPercentage != C.PERCENTAGE_UNSET) {
      editingEndedEventBuilder.setFinalProgressPercent(progressPercentage);
    }

    List<MediaItemInfo> inputMediaItemInfoList = getMediaItemInfos(processedInputs);
    for (int i = 0; i < inputMediaItemInfoList.size(); i++) {
      MediaItemInfo inputMediaItemInfo = inputMediaItemInfoList.get(i);
      editingEndedEventBuilder.addInputMediaItemInfo(inputMediaItemInfo);
    }

    editingSession.reportEditingEndedEvent(editingEndedEventBuilder.build());
    editingSession.close();
  }

  /**
   * Called when export is cancelled.
   *
   * @param progressPercentage The progress of the export operation in percent. Value is {@link
   *     C#PERCENTAGE_UNSET} if unknown or between 0 and 100 inclusive.
   */
  public void onExportCancelled(int progressPercentage) {
    if (editingSession == null) {
      return;
    }
    EditingEndedEvent.Builder editingEndedEventBuilder =
        createEditingEndedEventBuilder(EditingEndedEvent.FINAL_STATE_CANCELED);
    if (progressPercentage != C.PERCENTAGE_UNSET) {
      editingEndedEventBuilder.setFinalProgressPercent(progressPercentage);
    }
    editingSession.reportEditingEndedEvent(editingEndedEventBuilder.build());
    editingSession.close();
  }

  private EditingEndedEvent.Builder createEditingEndedEventBuilder(int finalState) {
    long endTimeMs = SystemClock.DEFAULT.elapsedRealtime();
    return new EditingEndedEvent.Builder(finalState)
        .setTimeSinceCreatedMillis(endTimeMs - startTimeMs);
  }

  private static List<MediaItemInfo> getMediaItemInfos(
      ImmutableList<ExportResult.ProcessedInput> processedInputs) {
    List<MediaItemInfo> mediaItemInfoList = new ArrayList<>();
    for (int i = 0; i < processedInputs.size(); i++) {
      ExportResult.ProcessedInput processedInput = processedInputs.get(i);
      MediaItemInfo.Builder mediaItemInfoBuilder = new MediaItemInfo.Builder();
      long durationMs = usToMs(processedInput.durationUs);
      mediaItemInfoBuilder.setDurationMillis(durationMs);
      Format format = processedInput.format;
      if (format != null) {
        if (format.containerMimeType != null) {
          mediaItemInfoBuilder.setContainerMimeType(format.containerMimeType);
        }
        if (format.sampleMimeType != null) {
          mediaItemInfoBuilder.addSampleMimeType(format.sampleMimeType);
        }
        if (format.frameRate != Format.NO_VALUE) {
          mediaItemInfoBuilder.setVideoFrameRate(format.frameRate);
        }
        Size videoSize =
            new Size(
                format.width != Format.NO_VALUE ? format.width : MediaItemInfo.VALUE_UNSPECIFIED,
                format.height != Format.NO_VALUE ? format.height : MediaItemInfo.VALUE_UNSPECIFIED);
        mediaItemInfoBuilder.setVideoSize(videoSize);
        if (format.colorInfo != null) {
          ColorInfo colorInfo = format.colorInfo;
          int colorStandard =
              DATA_SPACE_STANDARD_CONVERSION_MAP.get(
                  colorInfo.colorSpace, DataSpace.STANDARD_UNSPECIFIED);
          int colorTransfer =
              DATA_SPACE_TRANSFER_CONVERSION_MAP.get(
                  colorInfo.colorTransfer, DataSpace.TRANSFER_UNSPECIFIED);
          int colorRange =
              DATA_SPACE_RANGE_CONVERSION_MAP.get(
                  colorInfo.colorRange, DataSpace.RANGE_UNSPECIFIED);
          mediaItemInfoBuilder.setVideoDataSpace(
              DataSpace.pack(colorStandard, colorTransfer, colorRange));
        }
      }
      mediaItemInfoList.add(mediaItemInfoBuilder.build());
    }
    return mediaItemInfoList;
  }

  private static int getEditingEndedEventErrorCode(@ExportException.ErrorCode int errorCode) {
    return ERROR_CODE_CONVERSION_MAP.get(errorCode, EditingEndedEvent.ERROR_CODE_NONE);
  }
}
