/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.test.utils;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.test.utils.TestSummaryLogger.recordTestSkipped;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.opengl.EGLDisplay;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.transformer.EncoderUtil;
import java.io.IOException;
import org.json.JSONException;
import org.junit.AssumptionViolatedException;

/** Utility class for checking HDR capabilities. */
@UnstableApi
public final class HdrCapabilitiesUtil {
  private static final String SKIP_REASON_NO_OPENGL_UNDER_API_29 =
      "OpenGL-based HDR to SDR tone mapping is unsupported below API 29.";
  private static final String SKIP_REASON_NO_YUV = "Device lacks YUV extension support.";

  /**
   * Assumes that the device supports OpenGL tone-mapping for the {@code inputFormat}.
   *
   * @throws AssumptionViolatedException if the device does not support OpenGL tone-mapping.
   */
  public static void assumeDeviceSupportsOpenGlToneMapping(String testId, Format inputFormat)
      throws JSONException, IOException, MediaCodecUtil.DecoderQueryException {
    Context context = getApplicationContext();
    if (SDK_INT < 29) {
      recordTestSkipped(context, testId, SKIP_REASON_NO_OPENGL_UNDER_API_29);
      throw new AssumptionViolatedException(SKIP_REASON_NO_OPENGL_UNDER_API_29);
    }
    if (!GlUtil.isYuvTargetExtensionSupported()) {
      recordTestSkipped(context, testId, SKIP_REASON_NO_YUV);
      throw new AssumptionViolatedException(SKIP_REASON_NO_YUV);
    }
    assumeFormatsSupported(context, testId, inputFormat, /* outputFormat= */ null);
  }

  /**
   * Assumes that the device supports HDR editing for the given {@code colorInfo}.
   *
   * @throws AssumptionViolatedException if the device does not support HDR editing.
   */
  public static void assumeDeviceSupportsHdrEditing(String testId, Format format)
      throws JSONException, IOException {
    checkState(ColorInfo.isTransferHdr(format.colorInfo));
    if (EncoderUtil.getSupportedEncodersForHdrEditing(
            checkNotNull(format.sampleMimeType), format.colorInfo)
        .isEmpty()) {
      String skipReason =
          "No HDR editing supported for sample mime type "
              + format.sampleMimeType
              + " and color info "
              + format.colorInfo;
      recordTestSkipped(getApplicationContext(), testId, skipReason);
      throw new AssumptionViolatedException(skipReason);
    }
  }

  /**
   * Assumes that the device does not support HDR editing for the given {@code colorInfo}.
   *
   * @throws AssumptionViolatedException if the device does support HDR editing.
   */
  public static void assumeDeviceDoesNotSupportHdrEditing(String testId, Format format)
      throws JSONException, IOException {
    checkState(ColorInfo.isTransferHdr(format.colorInfo));
    if (!EncoderUtil.getSupportedEncodersForHdrEditing(
            checkNotNull(format.sampleMimeType), format.colorInfo)
        .isEmpty()) {
      String skipReason =
          "HDR editing supported for sample mime type "
              + format.sampleMimeType
              + " and color info "
              + format.colorInfo;
      recordTestSkipped(getApplicationContext(), testId, skipReason);
      throw new AssumptionViolatedException(skipReason);
    }
  }

  /**
   * Assumes that the device supports HDR editing for the given {@code colorInfo}.
   *
   * @throws AssumptionViolatedException if the device does not support HDR editing.
   */
  public static void assumeDeviceSupportsHdrColorTransfer(String testId, Format format)
      throws JSONException, IOException, GlException {
    checkNotNull(format.colorInfo);
    if (!GlUtil.isColorTransferSupported(format.colorInfo.colorTransfer)) {
      String skipReason =
          "HDR display not supported for sampleMimeType "
              + format.sampleMimeType
              + " and colorInfo "
              + format.colorInfo;
      recordTestSkipped(getApplicationContext(), testId, skipReason);
      throw new AssumptionViolatedException(skipReason);
    }
  }

  /**
   * Assumes that the device does not support HDR editing for the given {@code colorInfo}.
   *
   * @throws AssumptionViolatedException if the device does support HDR editing.
   */
  public static void assumeDeviceDoesNotSupportHdrColorTransfer(String testId, Format format)
      throws JSONException, IOException, GlException {
    checkNotNull(format.colorInfo);
    // Required to ensure EGL extensions are initialised.
    @SuppressWarnings("unused")
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    if (GlUtil.isColorTransferSupported(format.colorInfo.colorTransfer)) {
      String skipReason =
          "HDR display is supported for sampleMimeType "
              + format.sampleMimeType
              + " and colorInfo "
              + format.colorInfo;
      recordTestSkipped(getApplicationContext(), testId, skipReason);
      throw new AssumptionViolatedException(skipReason);
    }
  }

  private HdrCapabilitiesUtil() {}
}
