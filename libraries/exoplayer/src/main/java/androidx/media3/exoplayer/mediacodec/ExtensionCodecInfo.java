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
package androidx.media3.exoplayer.mediacodec;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;

import android.content.Context;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons;
import java.util.Objects;

/** Information about an extension decoder that can be used via {@link ExtensionCodecAdapter}. */
@RestrictTo(LIBRARY_GROUP)
public class ExtensionCodecInfo extends MediaCodecInfo {

  /**
   * Creates a new instance.
   *
   * @param name The name of the decoder.
   * @param mimeType The MIME type of media supported by the decoder.
   */
  protected ExtensionCodecInfo(String name, String mimeType) {
    super(
        /* name= */ name,
        /* mimeType= */ mimeType,
        /* codecMimeType= */ mimeType,
        /* capabilities= */ null,
        /* hardwareAccelerated= */ false,
        /* softwareOnly= */ true,
        /* vendor= */ false,
        /* adaptive= */ MimeTypes.isVideo(mimeType),
        /* tunneling= */ false,
        /* secure= */ false,
        /* detachedSurfaceSupported= */ MimeTypes.isVideo(mimeType));
  }

  /**
   * Creates a new instance.
   *
   * @param name The name of the decoder.
   * @param mimeType The MIME type of media supported by the decoder.
   * @return The created instance.
   */
  @VisibleForTesting
  public static ExtensionCodecInfo newInstance(String name, String mimeType) {
    return new ExtensionCodecInfo(name, mimeType);
  }

  @Override
  public boolean isSeamlessAdaptationSupported(Format format) {
    return adaptive;
  }

  @Override
  public final boolean isFormatFunctionallySupported(Context context, Format format) {
    return isFormatDrmSupported(format) && isFormatSupported(format);
  }

  @Override
  public final boolean isFormatSupported(Context context, Format format) {
    return isFormatDrmSupported(format) && isFormatSupported(format);
  }

  /**
   * Returns whether the decoder supports the given {@code format}.
   *
   * <p>The default implementation checks that {@link Format#sampleMimeType} (or its {@linkplain
   * MediaCodecUtil#getAlternativeCodecMimeType alternative codec MIME type}) matches {@link
   * #mimeType}.
   */
  protected boolean isFormatSupported(Format format) {
    return Objects.equals(mimeType, format.sampleMimeType)
        || Objects.equals(mimeType, MediaCodecUtil.getAlternativeCodecMimeType(format));
  }

  /**
   * Evaluates whether the extension decoder can be reused for a new {@link Format}.
   *
   * <p>For audio formats, reuse is not supported and {@link DecoderReuseEvaluation#REUSE_RESULT_NO}
   * is returned. For video formats, {@link
   * DecoderReuseEvaluation#REUSE_RESULT_YES_WITHOUT_RECONFIGURATION} is returned if the sample MIME
   * type matches, {@link Format#initializationData} matches (when {@link
   * #requiresInitializationDataMatch()} is {@code true}), {@link Format#cryptoType} and {@link
   * Format#drmInitData} match, {@link #isFormatDrmSupported(Format)} returns {@code true}, and
   * {@link #isFormatSupported(Format)} returns {@code true} for {@code newFormat}.
   */
  @Override
  public DecoderReuseEvaluation canReuseCodec(Format oldFormat, Format newFormat) {
    if (MimeTypes.isAudio(mimeType)) {
      return new DecoderReuseEvaluation(
          name,
          oldFormat,
          newFormat,
          /* result= */ REUSE_RESULT_NO,
          /* discardReasons= */ DISCARD_REASON_REUSE_NOT_IMPLEMENTED);
    }
    @DecoderDiscardReasons int discardReasons = 0;
    if (!Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)) {
      discardReasons |= DISCARD_REASON_MIME_TYPE_CHANGED;
    }
    if (requiresInitializationDataMatch() && !oldFormat.initializationDataEquals(newFormat)) {
      discardReasons |= DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
    }
    if (oldFormat.cryptoType != newFormat.cryptoType
        || !Objects.equals(oldFormat.drmInitData, newFormat.drmInitData)
        || !isFormatDrmSupported(newFormat)) {
      discardReasons |= DISCARD_REASON_DRM_SESSION_CHANGED;
    }
    if (discardReasons == 0 && !isFormatSupported(newFormat)) {
      return new DecoderReuseEvaluation(
          name, oldFormat, newFormat, /* result= */ REUSE_RESULT_NO, /* discardReasons= */ 0);
    }
    return new DecoderReuseEvaluation(
        name,
        oldFormat,
        newFormat,
        /* result= */ discardReasons == 0
            ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION
            : REUSE_RESULT_NO,
        discardReasons);
  }

  /**
   * Returns whether the decoder supports the DRM configuration of the given {@code format}.
   *
   * <p>The default implementation returns {@code true} only for unencrypted formats ({@code
   * format.cryptoType == C.CRYPTO_TYPE_NONE && format.drmInitData == null}).
   */
  protected boolean isFormatDrmSupported(Format format) {
    return format.cryptoType == C.CRYPTO_TYPE_NONE && format.drmInitData == null;
  }

  /**
   * Returns whether the decoder requires {@link Format#initializationData} to match when evaluating
   * whether the codec can be reused across format changes.
   *
   * <p>Returns {@code true} by default. Subclasses for codecs that parse configuration headers
   * in-band from the bitstream and ignore {@link Format#initializationData} can override this
   * method to return {@code false}.
   */
  protected boolean requiresInitializationDataMatch() {
    return true;
  }
}
