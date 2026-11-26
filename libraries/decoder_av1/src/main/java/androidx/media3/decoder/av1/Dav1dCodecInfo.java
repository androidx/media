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
package androidx.media3.decoder.av1;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.mediacodec.ExtensionCodecInfo;
import org.jspecify.annotations.Nullable;

/** Information about the capabilities of {@link Dav1dDecoder}. */
@UnstableApi
public final class Dav1dCodecInfo extends ExtensionCodecInfo {

  // LINT.IfChange(codec_name)
  /** The codec name for the Dav1d decoder. */
  public static final String NAME = "c2.media3.av1.dav1d.decoder";

  // LINT.ThenChange(../../../../../../../../exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/DefaultMediaCodecAdapterFactory.java:dav1d_adapter_factory)

  // LINT.IfChange(constructor)
  /** Creates an instance. */
  @RestrictTo(LIBRARY_GROUP)
  public Dav1dCodecInfo() {
    super(NAME, MimeTypes.VIDEO_AV1);
  }

  // LINT.ThenChange(
  //     ../../../../../../../../exoplayer/proguard-rules.txt,
  //
  // ../../../../../../../../exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecUtil.java:dav1d_extension_discovery)

  @Override
  protected boolean isFormatSupported(Format format) {
    if (!MimeTypes.VIDEO_AV1.equals(format.sampleMimeType) || !super.isFormatSupported(format)) {
      return false;
    }
    @Nullable String codecs = Util.getCodecsOfType(format.codecs, C.TRACK_TYPE_VIDEO);
    return codecs == null || isSupportedAv1Codec(codecs);
  }

  @Override
  protected boolean requiresInitializationDataMatch() {
    // Dav1dDecoder parses sequence headers in-band and ignores Format.initializationData.
    return false;
  }

  // Keep in sync with the bit-depth (kJniStatusInvalidBitDepthError) and pixel-layout
  // (kJniStatusUnsupportedPixelLayoutError, YV12/P010 rendering) checks in dav1d_jni.cc.
  private static boolean isSupportedAv1Codec(String codecs) {
    String[] parts = Util.split(codecs.trim(), "\\.");
    if (!parts[0].equals("av01")) {
      return false;
    }
    if (parts.length < 4 || parts[2].length() < 2) {
      // Ignore incomplete/malformed av01 codec strings, matching CodecSpecificDataUtil.
      return true;
    }
    int profile;
    int level;
    int bitDepth;
    try {
      profile = Integer.parseInt(parts[1]);
      level = Integer.parseInt(parts[2].substring(0, 2));
      bitDepth = Integer.parseInt(parts[3]);
    } catch (NumberFormatException e) {
      // Ignore malformed av01 codec strings, matching CodecSpecificDataUtil.
      return true;
    }
    if ((bitDepth != 8 && bitDepth != 10) || ((level < 0 || level > 23) && level != 31)) {
      return false;
    }
    if (profile == 0) {
      return true;
    }
    if (profile == 2) {
      // Per AV1 Bitstream Specification section 5.5.2, 8-bit and 10-bit monochrome (I400) streams
      // in Professional Profile (profile 2) have monochrome=1 and subsampling_x=1, subsampling_y=1
      // ("11x" in ISO-BMFF av01 codec strings; also accept "000" or omitted chroma subsampling).
      return parts.length >= 5
          && parts[4].equals("1")
          && (parts.length < 6
              || (parts[5].length() == 3 && parts[5].startsWith("11"))
              || parts[5].equals("000"));
    }
    return false;
  }
}
