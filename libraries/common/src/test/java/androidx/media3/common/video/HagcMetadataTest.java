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

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.ParserException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link HagcMetadata}.
 *
 * <p>Payloads are written as hex strings, with one group per syntax element (or per byte of packed
 * flags) of Annex C of SMPTE ST 2094-50.
 */
@RunWith(AndroidJUnit4.class)
public final class HagcMetadataTest {

  @Test
  public void parse_payloadWithReferenceWhiteOnly_returnsReferenceWhiteWithoutToneMap()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "01F4"); // Reference white 500 / 5 = 100 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(100f);
    assertThat(metadata.headroomAdaptiveToneMap).isNull();
  }

  @Test
  public void parse_payloadWithoutCustomReferenceWhite_returnsDefaultReferenceWhite()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "00"); // Default reference white, no adaptive tone map.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits)
        .isEqualTo(HagcMetadata.DEFAULT_HDR_REFERENCE_WHITE_NITS);
  }

  @Test
  public void parse_referenceWhiteBelowItsRange_returnsClampedReferenceWhite() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "0000"); // Reference white 0, clamped to 1 / 5 = 0.2 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(0.2f);
  }

  @Test
  public void parse_referenceWhiteAboveItsRange_returnsClampedReferenceWhite() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "FFFF"); // Reference white 65535, clamped to 50000 / 5 = 10000 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(10_000f);
  }

  @Test
  public void parse_payloadWithTrailingBytes_ignoresTrailingBytes() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithTrailingBytes = getBytesFromHexString("008001F4FFFF");

    HagcMetadata metadata = HagcMetadata.parse(payloadWithTrailingBytes);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_payloadWithNonZeroReservedBits_ignoresReservedBits() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithReservedBitsSet =
        getBytesFromHexString(
            "03" // Version 0, minimum version 0, reserved bits set.
                + "BF" // Custom reference white, no adaptive tone map, reserved bits set.
                + "01F4"); // Reference white 100 nits.

    HagcMetadata metadata = HagcMetadata.parse(payloadWithReservedBitsSet);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_nonZeroApplicationVersion_returnsParsedMetadata() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithApplicationVersion7 =
        getBytesFromHexString(
            "E0" // Version 7, minimum version 0.
                + "8001F4");

    HagcMetadata metadata = HagcMetadata.parse(payloadWithApplicationVersion7);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_unsupportedMinimumApplicationVersion_returnsNull() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "04" // Version 0, minimum version 1.
                + "FF"); // Data that would be truncated if it was parsed.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata).isNull();
  }

  @Test
  public void parse_emptyPayload_throwsParserException() {
    byte[] payload = new byte[0];

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_truncatedPayload_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "01"); // Only the first byte of the reference white.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }
}
