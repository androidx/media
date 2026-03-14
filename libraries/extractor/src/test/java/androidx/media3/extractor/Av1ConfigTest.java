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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Av1Config}. */
@RunWith(AndroidJUnit4.class)
public final class Av1ConfigTest {

  @Test
  public void parse_8bit_noOptionalObus() throws ParserException {
    // 0x81: marker = 1, version = 1
    // 0x01: seq_profile = 0, seq_level_idx_0 = 1
    // 0x00: seq_tier_0 = 0 (Main), high_bitdepth = 0, twelve_bit = 0, monochrome = 0
    //       chroma_subsampling_x = 0, chroma_subsampling_y = 0, chroma_sample_position = 0
    // 0x00: reserved | initial_presentation_delay_present
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_10bit_noOptionalObus() throws ParserException {
    // 0x81: marker = 1, version = 1
    // 0x04: seq_profile = 0, seq_level_idx_0 = 4
    // 0xC0: seq_tier_0 = 1 (High), high_bitdepth = 1, twelve_bit = 0, monochrome = 0
    //       chroma_subsampling_x = 0, chroma_subsampling_y = 0, chroma_sample_position = 0
    // 0x00: reserved | initial_presentation_delay_present
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x04, 0xC0, 0x00);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(10);
    assertThat(config.codecs).isEqualTo("av01.0.04H.10");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_12bit_noOptionalObus() throws ParserException {
    // 0x81: marker = 1, version = 1
    // 0x48: seq_profile = 2, seq_level_idx_0 = 8
    // 0x60: seq_tier_0 = 0 (Main), high_bitdepth = 1, twelve_bit = 1, monochrome = 0
    //       chroma_subsampling_x = 0, chroma_subsampling_y = 0, chroma_sample_position = 0
    // 0x00: reserved | initial_presentation_delay_present
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x48, 0x60, 0x00);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(12);
    assertThat(config.codecs).isEqualTo("av01.2.08M.12");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_doesntReadUnsupportedObu() throws ParserException {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x20: obu_forbidden_bit = 0, obu_type = 2, obu_extension_flag = 0,
    //       obu_has_size_field = 0, obu_reserved_1bit = 0
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x20);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withUnsupportedObuExtensionFlag() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x0c: obu_forbidden_bit = 0, obu_type = 1, obu_extension_flag = 1,
    //       obu_has_size_field = 0, obu_reserved_1bit = 0
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x0c);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withExcessiveObuSize_doesntReadIt() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x0a: obu_forbidden_bit = 0, obu_type = 1, obu_extension_flag = 0,
    //       obu_has_size_field = 1, obu_reserved_1bit = 0
    // 0x80: obu_size = 128
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x0a, 0x80);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withReducedStillPictureHeader_returnsConfigWithDefaults() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x08: obu_forbidden_bit = 0, obu_type = 1, obu_extension_flag = 0,
    //       obu_has_size_field = 0, obu_reserved_1bit = 0
    // 0x08: seq_profile = 0, still_picture = 0, reduced_still_picture_header = 1
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x08, 0x08);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withTimingInfoPresent_returnsConfigWithDefaults() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x04: seq_profile = 0, still_picture = 0, reduced_still_picture_header = 0,
    //       timing_info_present_flag = 1
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x08, 0x04);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withInitialDisplayDelayPresent_returnsConfigWithDefaults() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x02: seq_profile = 0, still_picture = 0, reduced_still_picture_header = 0,
    //       timing_info_present_flag = 0, initial_display_delay_present_flag = 1
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x08, 0x02);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(config.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parse_withValidSequenceHeader_returnsConfigWithColorInfo() throws Exception {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x01: color_description_present_flag = 1
    // 0x09: color_primaries = 9 (BT2020)
    // 0x10: transfer_characteristics = 16 (ST2084)
    // 0x09: matrix_coefficients = 9
    // 0x00: color_range = 0 (LIMITED)
    byte[] initializationData =
        TestUtil.createByteArray(
            0x81, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x09, 0x10,
            0x09, 0x00);
    Av1Config config = Av1Config.parse(initializationData);

    assertThat(config.initializationData).containsExactly(initializationData);
    assertThat(config.bitdepth).isEqualTo(8);
    assertThat(config.codecs).isEqualTo("av01.0.00M.08");
    assertThat(config.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(config.colorTransfer).isEqualTo(C.COLOR_TRANSFER_ST2084);
    assertThat(config.colorRange).isEqualTo(C.COLOR_RANGE_LIMITED);
  }

  @Test
  public void parse_withTruncatedInitializationData_throwsParserException() {
    // 0x81: marker = 1, version = 1
    // 0x00: seq_profile = 0, seq_level_idx_0 = 0
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00);

    assertThrows(ParserException.class, () -> Av1Config.parse(initializationData));
  }

  @Test
  public void parse_withTruncatedSequenceHeader_throwsParserException() {
    // 0x81: marker = 1, version = 1
    // ...
    // 0x08: obu_forbidden_bit = 0, obu_type = 1, obu_extension_flag = 0,
    //       obu_has_size_field = 0, obu_reserved_1bit = 0
    // 0x00: seq_profile = 0, still_picture = 0, reduced_still_picture_header = 0,
    //       timing_info_present_flag = 0, initial_display_delay_present_flag = 0
    byte[] initializationData = TestUtil.createByteArray(0x81, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00);

    assertThrows(ParserException.class, () -> Av1Config.parse(initializationData));
  }
}
