/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.test.utils;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.TestSummaryLogger.recordTestSkipped;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.transformer.DefaultMuxer;
import androidx.media3.transformer.EncoderUtil;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.junit.AssumptionViolatedException;

/**
 * Utility methods for checking and assuming format support in instrumentation tests.
 *
 * <p>These methods are used to skip tests based on device capabilities for decoding and encoding
 * specific media formats.
 */
@UnstableApi
public class FormatSupportAssumptions {

  /**
   * {@linkplain AssumptionViolatedException Assumes} that the device supports decoding the input
   * format, and encoding/muxing the output format if needed.
   *
   * <p>This is equivalent to calling {@link #assumeFormatsSupported(Context, String, Format,
   * Format, boolean)} with {@code isPortraitEncodingEnabled} set to {@code false}.
   */
  public static void assumeFormatsSupported(
      Context context, String testId, @Nullable Format inputFormat, @Nullable Format outputFormat)
      throws IOException, JSONException, MediaCodecUtil.DecoderQueryException {
    assumeFormatsSupported(
        context, testId, inputFormat, outputFormat, /* isPortraitEncodingEnabled= */ false);
  }

  /**
   * {@linkplain AssumptionViolatedException Assumes} that the device supports decoding the input
   * format, and encoding/muxing the output format if needed.
   *
   * @param context The {@link Context context}.
   * @param testId The test ID.
   * @param inputFormat The {@link Format format} to decode, or the input is not produced by
   *     MediaCodec, like an image.
   * @param outputFormat The {@link Format format} to encode/mux or {@code null} if the output won't
   *     be encoded or muxed.
   * @param isPortraitEncodingEnabled Whether portrait encoding is enabled.
   * @throws AssumptionViolatedException If the device does not support the formats. In this case,
   *     the reason for skipping the test is logged.
   */
  public static void assumeFormatsSupported(
      Context context,
      String testId,
      @Nullable Format inputFormat,
      @Nullable Format outputFormat,
      boolean isPortraitEncodingEnabled)
      throws IOException, JSONException, MediaCodecUtil.DecoderQueryException {
    boolean canDecode = inputFormat == null || canDecode(inputFormat);

    boolean canEncode = outputFormat == null || canEncode(outputFormat, isPortraitEncodingEnabled);
    boolean canMux = outputFormat == null || canMux(outputFormat);
    if (canDecode && canEncode && canMux) {
      return;
    }

    StringBuilder skipReasonBuilder = new StringBuilder();
    if (!canDecode) {
      skipReasonBuilder.append("Cannot decode ").append(inputFormat).append('\n');
    }
    if (!canEncode) {
      skipReasonBuilder.append("Cannot encode ").append(outputFormat).append('\n');
    }
    if (!canMux) {
      skipReasonBuilder.append("Cannot mux ").append(outputFormat);
    }
    String skipReason = skipReasonBuilder.toString();
    recordTestSkipped(context, testId, skipReason);
    throw new AssumptionViolatedException(skipReason);
  }

  private static boolean canDecode(Format format) throws MediaCodecUtil.DecoderQueryException {
    if (MimeTypes.isImage(format.sampleMimeType)) {
      return Util.isBitmapFactorySupportedMimeType(checkNotNull(format.sampleMimeType));
    }

    // Check decoding capability in the same way as the default decoder factory.
    return findDecoderForFormat(format) != null && !deviceNeedsDisable8kWorkaround(format);
  }

  @Nullable
  private static String findDecoderForFormat(Format format)
      throws MediaCodecUtil.DecoderQueryException {
    checkNotNull(format.sampleMimeType);
    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> decoderInfoList =
        MediaCodecUtil.getDecoderInfosSortedByFullFormatSupport(
            MediaCodecUtil.getDecoderInfosSoftMatch(
                MediaCodecSelector.DEFAULT,
                format,
                /* requiresSecureDecoder= */ false,
                /* requiresTunnelingDecoder= */ false),
            format);

    for (int i = 0; i < decoderInfoList.size(); i++) {
      androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoderInfo = decoderInfoList.get(i);
      // On some devices this method can return false even when the format can be decoded. For
      // example, Pixel 6a can decode an 8K video but this method returns false. The
      // DefaultDecoderFactory does not rely on this method rather it directly initialize the
      // decoder. See b/222095724#comment9.
      if (decoderInfo.isFormatSupported(format)) {
        return decoderInfo.name;
      }
    }

    return null;
  }

  private static boolean deviceNeedsDisable8kWorkaround(Format format) {
    // Fixed on API 31+. See http://b/278234847#comment40 for more information.
    // Duplicate of DefaultDecoderFactory#deviceNeedsDisable8kWorkaround.
    return SDK_INT < 31
        && format.width >= 7680
        && format.height >= 4320
        && format.sampleMimeType != null
        && format.sampleMimeType.equals(MimeTypes.VIDEO_H265)
        && (Ascii.equalsIgnoreCase(Build.MODEL, "SM-F711U1")
            || Ascii.equalsIgnoreCase(Build.MODEL, "SM-F926U1"));
  }

  private static boolean canEncode(Format format, boolean isPortraitEncodingEnabled) {
    String mimeType = checkNotNull(format.sampleMimeType);
    ImmutableList<MediaCodecInfo> supportedEncoders = EncoderUtil.getSupportedEncoders(mimeType);
    if (supportedEncoders.isEmpty()) {
      return false;
    }

    android.media.MediaCodecInfo encoder = supportedEncoders.get(0);
    // VideoSampleExporter rotates videos into landscape before encoding if portrait encoding is not
    // enabled.
    int width = format.width;
    int height = format.height;
    if (!isPortraitEncodingEnabled && width < height) {
      width = format.height;
      height = format.width;
    }
    boolean sizeSupported = EncoderUtil.isSizeSupported(encoder, mimeType, width, height);
    boolean bitrateSupported =
        format.averageBitrate == Format.NO_VALUE
            || EncoderUtil.getSupportedBitrateRange(encoder, mimeType)
                .contains(format.averageBitrate);
    return sizeSupported && bitrateSupported;
  }

  private static boolean canMux(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    return new DefaultMuxer.Factory()
        .getSupportedSampleMimeTypes(MimeTypes.getTrackType(mimeType))
        .contains(mimeType);
  }

  private FormatSupportAssumptions() {}
}
