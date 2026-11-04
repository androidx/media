/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common.video;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.Util;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Represents SMPTE ST 2094-50 (HAGC) dynamic HDR metadata of a frame.
 *
 * <p>Holds {@link Frame#KEY_HDR_ST2094_50_INFO} metadata. The bitstream format is defined in Annex
 * C of <a href="https://pub.smpte.org/pub/st2094-50/">SMPTE ST 2094-50:2026</a>.
 */
/* package */ final class HagcMetadata {

  /** The {@link #hdrReferenceWhiteNits} of a payload that doesn't specify one. */
  public static final float DEFAULT_HDR_REFERENCE_WHITE_NITS = 203f;

  // The highest minimum_application_version that this class can parse. Payloads that require a
  // higher version are ignored (Clause C.2.1).
  private static final int IMPLEMENTED_APPLICATION_VERSION = 0;

  // The maximum value of hdr_reference_white, and the divisor that maps it to nits, when
  // has_custom_hdr_reference_white_flag is 1 (Clause C.3.3).
  private static final int MAX_HDR_REFERENCE_WHITE_CODE = 50_000;
  private static final float HDR_REFERENCE_WHITE_DIVISOR = 5f;

  /**
   * The luminance of HDR reference white, in nits. Headrooms and gain curve inputs are relative to
   * this luminance.
   */
  public final float hdrReferenceWhiteNits;

  /**
   * The headroom-adaptive tone map, or {@code null} if the payload doesn't specify one, in which
   * case the output system decides how to tone map (Clause A.3).
   */
  public final @Nullable HeadroomAdaptiveToneMap headroomAdaptiveToneMap;

  private HagcMetadata(
      float hdrReferenceWhiteNits, @Nullable HeadroomAdaptiveToneMap headroomAdaptiveToneMap) {
    this.hdrReferenceWhiteNits = hdrReferenceWhiteNits;
    this.headroomAdaptiveToneMap = headroomAdaptiveToneMap;
  }

  /**
   * Parses an SMPTE ST 2094-50 payload.
   *
   * <p>Data after the last syntax element is ignored.
   *
   * @param payload The {@code smpte_st_2094_50_application_info()} bitstream specified in Annex C
   *     of SMPTE ST 2094-50, without the ITU-T T.35 header.
   * @return The parsed metadata, or {@code null} if the payload's minimum application version is
   *     higher than the version this class implements, in which case the specification requires the
   *     payload to be ignored.
   * @throws ParserException If the payload is truncated or violates a constraint of the
   *     specification.
   */
  public static @Nullable HagcMetadata parse(byte[] payload) throws ParserException {
    ParsableBitArray data = new ParsableBitArray(payload);
    try {
      return parseApplicationInfo(data);
    } catch (RuntimeException e) {
      throw ParserException.createForMalformedDataOfUnknownType("Error parsing HAGC metadata", e);
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HagcMetadata)) {
      return false;
    }
    HagcMetadata other = (HagcMetadata) o;
    return Float.compare(hdrReferenceWhiteNits, other.hdrReferenceWhiteNits) == 0
        && Objects.equals(headroomAdaptiveToneMap, other.headroomAdaptiveToneMap);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hdrReferenceWhiteNits, headroomAdaptiveToneMap);
  }

  private static @Nullable HagcMetadata parseApplicationInfo(ParsableBitArray data)
      throws ParserException {
    data.skipBits(3); // application_version
    int minimumApplicationVersion = data.readBits(3);
    data.skipBits(2); // reserved_zero
    if (minimumApplicationVersion > IMPLEMENTED_APPLICATION_VERSION) {
      return null;
    }

    boolean hasCustomHdrReferenceWhite = data.readBit();
    boolean hasAdaptiveToneMap = data.readBit();
    data.skipBits(6); // reserved_zero
    float hdrReferenceWhiteNits =
        hasCustomHdrReferenceWhite
            ? Util.constrainValue(
                    data.readBits(16), /* min= */ 1, /* max= */ MAX_HDR_REFERENCE_WHITE_CODE)
                / HDR_REFERENCE_WHITE_DIVISOR
            : DEFAULT_HDR_REFERENCE_WHITE_NITS;
    if (hasAdaptiveToneMap) {
      // TODO: b/575451438 - Parse the headroom-adaptive tone map as specified in Clause C.2.3.
      throw ParserException.createForUnsupportedContainerFeature(
          "HAGC headroom-adaptive tone maps aren't supported yet");
    }
    return new HagcMetadata(hdrReferenceWhiteNits, /* headroomAdaptiveToneMap= */ null);
  }

  /** The headroom-adaptive tone map of a frame. */
  /* package */ static final class HeadroomAdaptiveToneMap {}
}
