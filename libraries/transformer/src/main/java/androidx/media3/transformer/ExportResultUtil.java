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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import com.google.common.collect.ImmutableList;

/** Utility methods for updating {@link ExportResult.Builder} instances. */
/* package */ final class ExportResultUtil {

  private ExportResultUtil() {}

  /**
   * Updates the {@link ExportResult.Builder} with processing details, such as processed inputs and
   * encoder names.
   */
  public static void updateProcessingDetails(
      ExportResult.Builder builder,
      ImmutableList<ProcessedInput> processedInputs,
      @Nullable String audioEncoderName,
      @Nullable String videoEncoderName) {
    builder.addProcessedInputs(processedInputs);

    // Set encoder names only when available to avoid overwriting values from
    // previous intermittent export steps.
    if (audioEncoderName != null) {
      builder.setAudioEncoderName(audioEncoderName);
    }
    if (videoEncoderName != null) {
      builder.setVideoEncoderName(videoEncoderName);
    }
  }

  /** Updates the {@link ExportResult.Builder} with details of an individual track. */
  public static void updateTrackDetails(
      ExportResult.Builder builder,
      @C.TrackType int trackType,
      Format format,
      int averageBitrate,
      int sampleCount) {
    if (trackType == C.TRACK_TYPE_AUDIO) {
      builder
          .setAudioMimeType(format.sampleMimeType)
          .setChannelCount(format.channelCount)
          .setSampleRate(format.sampleRate)
          .setAverageAudioBitrate(averageBitrate);
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      builder
          .setVideoMimeType(format.sampleMimeType)
          .setWidth(format.width)
          .setHeight(format.height)
          .setColorInfo(format.colorInfo)
          .setAverageVideoBitrate(averageBitrate)
          .setVideoFrameCount(sampleCount);
    }
  }
}
