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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A {@link SubtitleParser} for DVB Teletext subtitles (ETSI EN 300 472 and ETSI EN 300 706).
 */
@UnstableApi
public final class TeletextParser implements SubtitleParser {

  /**
   * The {@link CueReplacementBehavior} for consecutive {@link CuesWithTiming} emitted by this
   * implementation.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final int NO_TARGET = -1;
  private static final int DATA_IDENTIFIER_MIN = 0x10;
  private static final int DATA_IDENTIFIER_MAX = 0x1F;
  private static final int DATA_UNIT_EBU_TELETEXT_NON_SUBTITLE = 0x02;
  private static final int DATA_UNIT_EBU_TELETEXT_SUBTITLE = 0x03;
  private static final int TELETEXT_DATA_UNIT_LENGTH = 0x2C;
  private static final int FRAMING_CODE_LSB_FIRST = 0x27;
  private static final int FRAMING_CODE_MSB_FIRST = 0xE4;
  private static final int MAX_DISPLAYABLE_ROW = 24;
  private static final int ROW_BYTE_COUNT = 40;

  // ETSI EN 300 706 §8.2 Hamming (8,4) lookup table (LSB-first bit order).
  private static final int[] HAMMING_84_LOOKUP = new int[256];
  private static final int[] REVERSE_LOOKUP = new int[256];

  static {
    Arrays.fill(HAMMING_84_LOOKUP, -1);
    int[] codewords = {
      0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F,
      0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA
    };
    for (int b = 0; b < 256; b++) {
      int minDistance = 9;
      int closestIndex = -1;
      for (int i = 0; i < 16; i++) {
        int dist = Integer.bitCount(b ^ codewords[i]);
        if (dist < minDistance) {
          minDistance = dist;
          closestIndex = i;
        }
      }
      // Hamming (8,4) corrects single-bit errors and rejects double-bit errors.
      if (minDistance <= 1) {
        HAMMING_84_LOOKUP[b] = closestIndex;
      }
    }
    for (int i = 0; i < 256; i++) {
      REVERSE_LOOKUP[i] = Integer.reverse(i) >>> 24;
    }
  }

  private final int targetMagazine;
  private final int targetPage;
  private final @Nullable String normalizedLanguage;
  private final ParsableByteArray scratch;
  private final StringBuilder rowBuilder;
  private final StringBuilder pageBuilder;
  private final @Nullable String[] rows;
  private final boolean[] doubleHeightRows;

  private boolean hasActivePage;
  private int activeMagazine;
  private boolean erasePage;
  private boolean inhibitDisplay;
  private boolean isSubtitlePage;
  private int nationalOptionBits;

  /** Creates an instance that decodes any page flagged as a Teletext subtitle (C6 bit set). */
  public TeletextParser() {
    this(/* initializationData= */ null, /* language= */ null);
  }

  /**
   * Creates an instance configured from the track's initialization data and language.
   *
   * @param initializationData Optional initialization data. When non-empty, the first entry must
   *     contain at least 2 bytes: {@code magazineNumber} (1–8) and BCD-encoded {@code pageNumber}
   *     ({@code 0x00..0xFF}).
   * @param language The ISO 639 language code of the track, or {@code null}.
   */
  public TeletextParser(@Nullable List<byte[]> initializationData, @Nullable String language) {
    if (initializationData != null
        && !initializationData.isEmpty()
        && initializationData.get(0).length >= 2) {
      byte[] init = initializationData.get(0);
      this.targetMagazine = init[0] & 0xFF;
      this.targetPage = init[1] & 0xFF;
    } else {
      this.targetMagazine = NO_TARGET;
      this.targetPage = NO_TARGET;
    }
    this.normalizedLanguage = Util.normalizeLanguageCode(language);
    this.scratch = new ParsableByteArray();
    this.rowBuilder = new StringBuilder(ROW_BYTE_COUNT);
    this.pageBuilder = new StringBuilder();
    this.rows = new @Nullable String[MAX_DISPLAYABLE_ROW + 1];
    this.doubleHeightRows = new boolean[MAX_DISPLAYABLE_ROW + 1];
    this.activeMagazine = -1;
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior() {
    return CUE_REPLACEMENT_BEHAVIOR;
  }

  @Override
  public void reset() {
    hasActivePage = false;
    activeMagazine = -1;
    erasePage = false;
    inhibitDisplay = false;
    isSubtitlePage = false;
    nationalOptionBits = 0;
    Arrays.fill(rows, null);
    Arrays.fill(doubleHeightRows, false);
  }

  @Override
  public void parse(
      byte[] data,
      int offset,
      int length,
      OutputOptions outputOptions,
      Consumer<CuesWithTiming> output) {
    scratch.reset(data, /* limit= */ offset + length);
    scratch.setPosition(offset);

    if (scratch.bytesLeft() < 1) {
      return;
    }
    // ETSI EN 300 472 §4.3: data_identifier appears once at the start of the PES data field.
    int dataIdentifier = scratch.readUnsignedByte();
    if (dataIdentifier < DATA_IDENTIFIER_MIN || dataIdentifier > DATA_IDENTIFIER_MAX) {
      return;
    }

    // ETSI EN 300 472 §4.4: sequence of (data_unit_id, data_unit_length, data_field) units.
    while (scratch.bytesLeft() >= 2) {
      int dataUnitId = scratch.readUnsignedByte();
      int dataUnitLength = scratch.readUnsignedByte();
      if (dataUnitLength > scratch.bytesLeft()) {
        break;
      }
      int nextUnitPosition = scratch.getPosition() + dataUnitLength;
      if ((dataUnitId == DATA_UNIT_EBU_TELETEXT_NON_SUBTITLE
              || dataUnitId == DATA_UNIT_EBU_TELETEXT_SUBTITLE)
          && dataUnitLength == TELETEXT_DATA_UNIT_LENGTH) {
        parseTeletextUnit(scratch, output);
      }
      scratch.setPosition(nextUnitPosition);
    }
  }

  private void parseTeletextUnit(ParsableByteArray data, Consumer<CuesWithTiming> output) {
    // Byte 0: reserved_future_use (2), field_parity (1), line_offset (5).
    data.skipBytes(1);

    // Byte 1: framing_code. In DVB (MSB-first), 0x27 appears bit-reversed as 0xE4.
    int framingCode = data.readUnsignedByte();
    boolean bitReversed;
    if (framingCode == FRAMING_CODE_MSB_FIRST) {
      bitReversed = true;
    } else if (framingCode == FRAMING_CODE_LSB_FIRST) {
      bitReversed = false;
    } else {
      return;
    }

    int v1 = hamming84Decode(readTeletextByte(data, bitReversed));
    int v2 = hamming84Decode(readTeletextByte(data, bitReversed));
    if (v1 < 0 || v2 < 0) {
      return;
    }

    int magazine = v1 & 0x07;
    if (magazine == 0) {
      magazine = 8;
    }
    int packetNumber = ((v1 & 0x08) >> 3) | (v2 << 1);

    if (packetNumber == 0) {
      parsePageHeader(data, bitReversed, magazine, output);
    } else if (packetNumber >= 1 && packetNumber <= MAX_DISPLAYABLE_ROW) {
      if (hasActivePage && magazine == activeMagazine) {
        parseRow(data, bitReversed, packetNumber);
      }
    }
  }

  private void parsePageHeader(
      ParsableByteArray data,
      boolean bitReversed,
      int magazine,
      Consumer<CuesWithTiming> output) {
    // ETSI EN 300 706 §9.3.1: Packet 0 contains 8 Hamming (8,4) bytes (Bytes 6..13)
    // followed by 32 header display bytes.
    int pageUnits = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 6
    int pageTens = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 7
    readTeletextByte(data, bitReversed); // Byte 8: Sub-code S1
    int s2AndC4 = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 9: S2 + C4
    readTeletextByte(data, bitReversed); // Byte 10: Sub-code S3
    int s4AndC5C6 = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 11: S4 + C5 + C6
    int c7ToC10 = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 12: C7..C10
    int c11ToC14 = hamming84Decode(readTeletextByte(data, bitReversed)); // Byte 13: C11..C14

    if (pageUnits < 0 || pageTens < 0) {
      return;
    }

    int page = (pageTens << 4) | pageUnits;
    boolean erase = s2AndC4 >= 0 && (s2AndC4 & 0x08) != 0; // C4
    boolean subtitle = s4AndC5C6 >= 0 && (s4AndC5C6 & 0x08) != 0; // C6
    boolean inhibit = c7ToC10 >= 0 && (c7ToC10 & 0x08) != 0; // C10
    boolean magazineSerial = c11ToC14 >= 0 && (c11ToC14 & 0x01) != 0; // C11
    int natOptBits = 0;
    if (c11ToC14 >= 0) {
      int c12 = (c11ToC14 >> 1) & 0x01;
      int c13 = (c11ToC14 >> 2) & 0x01;
      int c14 = (c11ToC14 >> 3) & 0x01;
      natOptBits = (c12 << 2) | (c13 << 1) | c14;
    }

    // In serial mode (C11=1), any page header terminates the active page.
    // In parallel mode (C11=0), a page header terminates the active page on the same magazine.
    if (hasActivePage && (magazineSerial || magazine == activeMagazine)) {
      flushActivePage(output);
    }

    // Page 0xFF is a time-filling / terminating header and is never a displayable subtitle page.
    boolean isTarget =
        page != 0xFF
            && (targetMagazine != NO_TARGET
                ? (magazine == targetMagazine && page == targetPage)
                : subtitle);
    if (isTarget) {
      hasActivePage = true;
      activeMagazine = magazine;
      erasePage = erase;
      inhibitDisplay = inhibit;
      isSubtitlePage = subtitle;
      nationalOptionBits = natOptBits;
      Arrays.fill(rows, null);
      Arrays.fill(doubleHeightRows, false);
    }
  }

  private void parseRow(ParsableByteArray data, boolean bitReversed, int packetNumber) {
    rowBuilder.setLength(0);
    boolean inBox = !isSubtitlePage;
    boolean conceal = false;
    boolean mosaicMode = false;
    boolean doubleHeight = false;

    for (int i = 0; i < ROW_BYTE_COUNT; i++) {
      int rawByte = readTeletextByte(data, bitReversed);
      // ETSI EN 300 706 §8.2: row characters use odd parity.
      if (Integer.bitCount(rawByte) % 2 == 0) {
        if (inBox) {
          rowBuilder.append(' ');
        }
        continue;
      }

      int code = rawByte & 0x7F;
      if (code < 0x20) {
        // Spacing attribute control codes (ETSI EN 300 706 §12.2).
        if (code <= 0x07) {
          mosaicMode = false;
          conceal = false;
        } else if (code == 0x0A) {
          // End Box.
          inBox = false;
        } else if (code == 0x0B) {
          // Start Box.
          inBox = true;
        } else if (code == 0x0C) {
          // Normal Size.
          doubleHeight = false;
        } else if (code == 0x0D) {
          // Double Height.
          doubleHeight = true;
        } else if (code >= 0x10 && code <= 0x17) {
          // Mosaic color codes.
          mosaicMode = true;
          conceal = false;
        } else if (code == 0x18) {
          // Conceal.
          conceal = true;
        }
        if (inBox) {
          rowBuilder.append(' ');
        }
      } else if (inBox && !conceal) {
        if (mosaicMode && (code < 0x40 || code >= 0x60)) {
          // Block mosaic graphics character; not displayable text.
          rowBuilder.append(' ');
        } else {
          rowBuilder.append(
              TeletextCharacterSets.mapChar(code, normalizedLanguage, nationalOptionBits));
        }
      }
    }

    String trimmed = rowBuilder.toString().trim();
    if (!trimmed.isEmpty()) {
      rows[packetNumber] = trimmed;
      doubleHeightRows[packetNumber] = doubleHeight;
    } else {
      rows[packetNumber] = null;
      doubleHeightRows[packetNumber] = false;
    }
  }

  private void flushActivePage(Consumer<CuesWithTiming> output) {
    pageBuilder.setLength(0);
    if (!inhibitDisplay) {
      for (int row = 1; row <= MAX_DISPLAYABLE_ROW; row++) {
        @Nullable String rowText = rows[row];
        if (rowText != null && !rowText.isEmpty()) {
          if (pageBuilder.length() > 0) {
            pageBuilder.append('\n');
          }
          pageBuilder.append(rowText);
        }
        // ETSI EN 300 706 §12.2: Double Height occupies the current row and the following row.
        if (doubleHeightRows[row]) {
          row++;
        }
      }
    }

    if (pageBuilder.length() > 0) {
      Cue cue =
          new Cue.Builder()
              .setText(pageBuilder.toString())
              .setLine(0.92f, Cue.LINE_TYPE_FRACTION)
              .setLineAnchor(Cue.ANCHOR_TYPE_END)
              .build();
      output.accept(
          new CuesWithTiming(
              ImmutableList.of(cue),
              /* startTimeUs= */ C.TIME_UNSET,
              /* durationUs= */ C.TIME_UNSET));
    } else if (erasePage) {
      output.accept(
          new CuesWithTiming(
              ImmutableList.of(),
              /* startTimeUs= */ C.TIME_UNSET,
              /* durationUs= */ C.TIME_UNSET));
    }

    hasActivePage = false;
    erasePage = false;
    inhibitDisplay = false;
    Arrays.fill(rows, null);
    Arrays.fill(doubleHeightRows, false);
  }

  private static int readTeletextByte(ParsableByteArray data, boolean bitReversed) {
    int b = data.readUnsignedByte();
    return bitReversed ? REVERSE_LOOKUP[b] : b;
  }

  /* package */ static int hamming84Decode(int b) {
    return HAMMING_84_LOOKUP[b & 0xFF];
  }
}
