/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.extractor.text.teletext;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TeletextParser}. */
@RunWith(AndroidJUnit4.class)
public final class TeletextParserTest {

  private static final int[] HAMMING_84_CODEWORDS = {
    0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F,
    0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA
  };

  @Test
  public void hamming84Decode_validSingleAndDoubleBitErrors() {
    for (int value = 0; value < 16; value++) {
      int codeword = HAMMING_84_CODEWORDS[value];
      assertThat(TeletextParser.hamming84Decode(codeword)).isEqualTo(value);
      // Every single-bit error must be corrected.
      for (int bit = 0; bit < 8; bit++) {
        assertThat(TeletextParser.hamming84Decode(codeword ^ (1 << bit))).isEqualTo(value);
      }
      // Every double-bit error must be rejected.
      for (int b1 = 0; b1 < 8; b1++) {
        for (int b2 = b1 + 1; b2 < 8; b2++) {
          assertThat(TeletextParser.hamming84Decode(codeword ^ (1 << b1) ^ (1 << b2)))
              .isEqualTo(-1);
        }
      }
    }
  }

  @Test
  public void parse_pesWithStuffingAndMultiRowSubtitlePage_emitsCue() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {8, (byte) 0x88}), "eng");
    List<CuesWithTiming> outputs = new ArrayList<>();

    byte[] pes =
        buildPesPayload(
            0x10,
            buildStuffingUnit(),
            buildPageHeaderUnit(
                /* bitReversed= */ true,
                /* mag= */ 8,
                /* page= */ 0x88,
                /* erase= */ true,
                /* subtitle= */ true,
                /* inhibit= */ false,
                /* serial= */ false,
                /* natOptBits= */ 0),
            buildRowUnit(
                /* bitReversed= */ true, /* mag= */ 8, /* row= */ 20, "\u000b\u000bHello\n"),
            buildRowUnit(
                /* bitReversed= */ true, /* mag= */ 8, /* row= */ 22, "\u000b\u000bWorld\n"),
            buildStuffingUnit(),
            buildPageHeaderUnit(
                /* bitReversed= */ true,
                /* mag= */ 8,
                /* page= */ 0xFF,
                /* erase= */ false,
                /* subtitle= */ false,
                /* inhibit= */ false,
                /* serial= */ false,
                /* natOptBits= */ 0));

    parser.parse(pes, 0, pes.length, OutputOptions.allCues(), outputs::add);

    assertThat(outputs).hasSize(1);
    assertThat(outputs.get(0).startTimeUs).isEqualTo(C.TIME_UNSET);
    assertThat(outputs.get(0).durationUs).isEqualTo(C.TIME_UNSET);
    assertThat(outputs.get(0).cues).hasSize(1);
    assertThat(outputs.get(0).cues.get(0).text.toString()).isEqualTo("Hello\nWorld");
  }

  @Test
  public void parse_lsbFirstFramingCode0x27_decodesWithoutBitReversal() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {7, (byte) 0x77}), "ita");
    List<CuesWithTiming> outputs = new ArrayList<>();

    byte[] pes =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ false,
                /* mag= */ 7,
                /* page= */ 0x77,
                /* erase= */ false,
                /* subtitle= */ true,
                /* inhibit= */ false,
                /* serial= */ false,
                /* natOptBits= */ 3),
            buildRowUnit(
                /* bitReversed= */ false, /* mag= */ 7, /* row= */ 21, "\u000bCiao\n"),
            buildPageHeaderUnit(
                /* bitReversed= */ false,
                /* mag= */ 7,
                /* page= */ 0xFF,
                /* erase= */ false,
                /* subtitle= */ false,
                /* inhibit= */ false,
                /* serial= */ false,
                /* natOptBits= */ 0));

    parser.parse(pes, 0, pes.length, OutputOptions.allCues(), outputs::add);

    assertThat(outputs).hasSize(1);
    assertThat(outputs.get(0).cues.get(0).text.toString()).isEqualTo("Ciao");
  }

  @Test
  public void parse_pageNumber0x00_isValidTargetPage() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {8, 0x00}), "eng");
    List<CuesWithTiming> outputs = new ArrayList<>();

    byte[] pes =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x00, false, true, false, false, 0),
            buildRowUnit(/* bitReversed= */ true, 8, 20, "\u000bPage 800\n"),
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0xFF, false, false, false, false, 0));

    parser.parse(pes, 0, pes.length, OutputOptions.allCues(), outputs::add);

    assertThat(outputs).hasSize(1);
    assertThat(outputs.get(0).cues.get(0).text.toString()).isEqualTo("Page 800");
  }

  @Test
  public void parse_boxingConcealMosaicsDoubleHeightAndPacket25() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {8, (byte) 0x88}), "eng");
    List<CuesWithTiming> outputs = new ArrayList<>();

    // Row 20: outside box ignored, inside box visible, concealed hidden until color code,
    // double height skips row 21.
    String row20 = "OUTSIDE\u000b\rHello\u0018SECRET\u0007World\u0010\u0021\nIGNORED";
    byte[] pes =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x88, false, true, false, false, 0),
            buildRowUnit(/* bitReversed= */ true, 8, 20, row20),
            buildRowUnit(/* bitReversed= */ true, 8, 21, "\u000bSKIPPED_DOUBLE_HEIGHT\n"),
            buildRowUnit(/* bitReversed= */ true, 8, 22, "\u000bSecond Line\n"),
            buildRowUnit(/* bitReversed= */ true, 8, 25, "\u000bNON_DISPLAYABLE_25\n"),
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0xFF, false, false, false, false, 0));

    parser.parse(pes, 0, pes.length, OutputOptions.allCues(), outputs::add);

    assertThat(outputs).hasSize(1);
    assertThat(outputs.get(0).cues.get(0).text.toString()).isEqualTo("Hello  World\nSecond Line");
  }

  @Test
  public void parse_multiPesPageEraseAndInhibitDisplay() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {8, (byte) 0x88}), "eng");
    List<CuesWithTiming> outputs = new ArrayList<>();

    // PES 1: header only
    byte[] pes1 =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x88, true, true, false, false, 0));
    // PES 2: row data
    byte[] pes2 =
        buildPesPayload(
            0x10, buildRowUnit(/* bitReversed= */ true, 8, 20, "\u000bAcross PES\n"));
    // PES 3: new empty erase page (terminates PES 1/2 page AND opens an erase page)
    byte[] pes3 =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x88, true, true, false, false, 0));
    // PES 4: terminating header flushes the empty erase page -> clears display
    byte[] pes4 =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0xFF, false, false, false, false, 0));

    parser.parse(pes1, 0, pes1.length, OutputOptions.allCues(), outputs::add);
    parser.parse(pes2, 0, pes2.length, OutputOptions.allCues(), outputs::add);
    assertThat(outputs).isEmpty();

    parser.parse(pes3, 0, pes3.length, OutputOptions.allCues(), outputs::add);
    assertThat(outputs).hasSize(1);
    assertThat(outputs.get(0).cues.get(0).text.toString()).isEqualTo("Across PES");

    parser.parse(pes4, 0, pes4.length, OutputOptions.allCues(), outputs::add);
    assertThat(outputs).hasSize(2);
    assertThat(outputs.get(1).cues).isEmpty();

    // Inhibit display (C10=1) suppresses rows
    byte[] pesInhibit =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x88, false, true, /* inhibit= */ true, false, 0),
            buildRowUnit(/* bitReversed= */ true, 8, 20, "\u000bHidden\n"),
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0xFF, false, false, false, false, 0));
    parser.parse(pesInhibit, 0, pesInhibit.length, OutputOptions.allCues(), outputs::add);
    assertThat(outputs).hasSize(2);
  }

  @Test
  public void reset_discardsInProgressPage() {
    TeletextParser parser =
        new TeletextParser(Collections.singletonList(new byte[] {8, (byte) 0x88}), "eng");
    List<CuesWithTiming> outputs = new ArrayList<>();

    byte[] pes1 =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0x88, false, true, false, false, 0),
            buildRowUnit(/* bitReversed= */ true, 8, 20, "\u000bBefore Seek\n"));
    parser.parse(pes1, 0, pes1.length, OutputOptions.allCues(), outputs::add);

    parser.reset();

    byte[] pes2 =
        buildPesPayload(
            0x10,
            buildPageHeaderUnit(
                /* bitReversed= */ true, 8, 0xFF, false, false, false, false, 0));
    parser.parse(pes2, 0, pes2.length, OutputOptions.allCues(), outputs::add);

    assertThat(outputs).isEmpty();
  }

  private static byte[] buildPesPayload(int dataIdentifier, byte[]... units) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(dataIdentifier);
    for (byte[] unit : units) {
      out.write(unit, 0, unit.length);
    }
    return out.toByteArray();
  }

  private static byte[] buildStuffingUnit() {
    byte[] unit = new byte[46];
    unit[0] = (byte) 0xFF;
    unit[1] = 0x2C;
    for (int i = 2; i < 46; i++) {
      unit[i] = (byte) 0xFF;
    }
    return unit;
  }

  private static byte[] buildPageHeaderUnit(
      boolean bitReversed,
      int mag,
      int page,
      boolean erase,
      boolean subtitle,
      boolean inhibit,
      boolean serial,
      int natOptBits) {
    byte[] unit = new byte[46];
    unit[0] = 0x03; // EBU Teletext subtitle data unit
    unit[1] = 0x2C; // 44 bytes
    unit[2] = (byte) 0xE7; // field_parity + line_offset
    unit[3] = (byte) (bitReversed ? 0xE4 : 0x27); // framing code

    int magBits = (mag == 8) ? 0 : mag;
    unit[4] = encodeHamming(magBits, bitReversed);
    unit[5] = encodeHamming(0, bitReversed); // packet 0

    int pageUnits = page & 0x0F;
    int pageTens = (page >> 4) & 0x0F;
    int s2C4 = erase ? 0x08 : 0x00;
    int s4C5C6 = subtitle ? 0x08 : 0x00;
    int c7C10 = inhibit ? 0x08 : 0x00;
    int c12 = (natOptBits >> 2) & 0x01;
    int c13 = (natOptBits >> 1) & 0x01;
    int c14 = natOptBits & 0x01;
    int c11C14 = (serial ? 0x01 : 0x00) | (c12 << 1) | (c13 << 2) | (c14 << 3);

    unit[6] = encodeHamming(pageUnits, bitReversed);
    unit[7] = encodeHamming(pageTens, bitReversed);
    unit[8] = encodeHamming(0, bitReversed); // S1
    unit[9] = encodeHamming(s2C4, bitReversed); // S2 + C4
    unit[10] = encodeHamming(0, bitReversed); // S3
    unit[11] = encodeHamming(s4C5C6, bitReversed); // S4 + C5 + C6
    unit[12] = encodeHamming(c7C10, bitReversed); // C7..C10
    unit[13] = encodeHamming(c11C14, bitReversed); // C11..C14
    for (int i = 14; i < 46; i++) {
      unit[i] = encodeOddParity(' ', bitReversed);
    }
    return unit;
  }

  private static byte[] buildRowUnit(boolean bitReversed, int mag, int row, String text) {
    byte[] unit = new byte[46];
    unit[0] = 0x03;
    unit[1] = 0x2C;
    unit[2] = (byte) 0xE7;
    unit[3] = (byte) (bitReversed ? 0xE4 : 0x27);

    int magBits = (mag == 8) ? 0 : mag;
    int v1 = magBits | ((row & 0x01) << 3);
    int v2 = (row >> 1) & 0x0F;
    unit[4] = encodeHamming(v1, bitReversed);
    unit[5] = encodeHamming(v2, bitReversed);
    for (int i = 0; i < 40; i++) {
      char c = i < text.length() ? text.charAt(i) : ' ';
      unit[6 + i] = encodeOddParity(c, bitReversed);
    }
    return unit;
  }

  private static byte encodeHamming(int value, boolean bitReversed) {
    int cw = HAMMING_84_CODEWORDS[value & 0x0F];
    return (byte) (bitReversed ? (Integer.reverse(cw) >>> 24) : cw);
  }

  private static byte encodeOddParity(int ch, boolean bitReversed) {
    int b = ch & 0x7F;
    if (Integer.bitCount(b) % 2 == 0) {
      b |= 0x80;
    }
    return (byte) (bitReversed ? (Integer.reverse(b) >>> 24) : b);
  }
}
