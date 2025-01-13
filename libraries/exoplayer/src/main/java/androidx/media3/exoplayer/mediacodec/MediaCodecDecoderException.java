/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.media.MediaCodec;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderException;

/** Thrown when a failure occurs in a {@link MediaCodec} decoder. */
@UnstableApi
public class MediaCodecDecoderException extends DecoderException {

  /** The {@link MediaCodecInfo} of the decoder that failed. Null if unknown. */
  @Nullable public final MediaCodecInfo codecInfo;

  /** An optional developer-readable diagnostic information string. May be null. */
  @Nullable public final String diagnosticInfo;

  /** An optional error code reported by the codec. May be 0 if no error code could be obtained. */
  public final int errorCode;

  public MediaCodecDecoderException(Throwable cause, @Nullable MediaCodecInfo codecInfo) {
    super("Decoder failed: " + (codecInfo == null ? null : codecInfo.name), cause);
    this.codecInfo = codecInfo;
    diagnosticInfo =
        cause instanceof MediaCodec.CodecException
            ? ((MediaCodec.CodecException) cause).getDiagnosticInfo()
            : null;
    errorCode =
        Util.SDK_INT >= 23
            ? getErrorCodeV23(cause)
            : Util.getErrorCodeFromPlatformDiagnosticsInfo(diagnosticInfo);
  }

  @RequiresApi(23)
  private static int getErrorCodeV23(Throwable cause) {
    if (cause instanceof MediaCodec.CodecException) {
      return ((MediaCodec.CodecException) cause).getErrorCode();
    }
    return 0;
  }
}
