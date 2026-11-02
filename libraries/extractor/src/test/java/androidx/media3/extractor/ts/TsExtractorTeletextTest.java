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
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests for Teletext PMT descriptor parsing and extraction in {@link TsExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class TsExtractorTeletextTest {

  private static final int PMT_PID = 0x0100;
  private static final int TELETEXT_PID = 0x0101;
  private static final int[] HAMMING_84_CODEWORDS = {
    0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F,
    0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA
  };

  @Test
  public void extract_filtersNonSubtitleTypesAndTranscodesCues() throws Exception {
    byte[] tsStream = buildTeletextTsStream();
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);

    readAll(extractor, tsStream);

    // PMT has 3 entries: 0x01 (initial page, filtered out), 0x02 (eng 888), 0x05 (deu 777).
    assertThat(output.trackOutputs.size()).isEqualTo(2);

    FakeTrackOutput engTrack = output.trackOutputs.valueAt(0);
    FakeTrackOutput deuTrack = output.trackOutputs.valueAt(1);
    assertThat(engTrack.lastFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_MEDIA3_CUES);
    assertThat(engTrack.lastFormat.codecs).isEqualTo(MimeTypes.APPLICATION_TELETEXT);
    assertThat(engTrack.lastFormat.language).isEqualTo("en");
    assertThat(engTrack.lastFormat.roleFlags).isEqualTo(C.ROLE_FLAG_SUBTITLE);

    assertThat(deuTrack.lastFormat.language).isEqualTo("de");
    assertThat(deuTrack.lastFormat.roleFlags)
        .isEqualTo(C.ROLE_FLAG_SUBTITLE | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND);

    assertThat(engTrack.getSampleCount()).isEqualTo(1);
    CueDecoder cueDecoder = new CueDecoder();
    byte[] sampleData = engTrack.getSampleData(0);
    CuesWithTiming decoded =
        cueDecoder.decode(
            engTrack.getSampleTimeUs(0), sampleData, /* offset= */ 0, sampleData.length);
    assertThat(decoded.cues).hasSize(1);
    assertThat(decoded.cues.get(0).text.toString()).isEqualTo("Teletext Live");
    // German page 777 received no rows.
    assertThat(deuTrack.getSampleCount()).isEqualTo(0);
  }

  @Test
  public void extract_withFlagEmitRawSubtitleData_emitsRawTeletextSamples() throws Exception {
    byte[] tsStream = buildTeletextTsStream();
    TsExtractor extractor =
        new TsExtractor(FLAG_EMIT_RAW_SUBTITLE_DATA, new DefaultSubtitleParserFactory());
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);

    readAll(extractor, tsStream);

    assertThat(output.trackOutputs.size()).isEqualTo(2);
    Format engFormat = output.trackOutputs.valueAt(0).lastFormat;
    assertThat(engFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_TELETEXT);
    assertThat(engFormat.containerMimeType).isEqualTo(MimeTypes.VIDEO_MP2T);
    assertThat(output.trackOutputs.valueAt(0).getSampleCount()).isEqualTo(1);
  }

  private static void readAll(TsExtractor extractor, byte[] data) throws Exception {
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    PositionHolder positionHolder = new PositionHolder();
    int readResult;
    while ((readResult = extractor.read(input, positionHolder)) != Extractor.RESULT_END_OF_INPUT) {
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
  }

  private static byte[] buildTeletextTsStream() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // 1. PAT (PID 0x0000) mapping program 1 -> PMT_PID
    writeTsPacket(out, /* pid= */ 0x0000, /* pusi= */ true, /* cc= */ 0, buildPatSection());
    // 2. PMT (PID 0x0100) with teletext_descriptor (0x56) + 2 trailing garbage bytes (< 5 bytes)
    writeTsPacket(out, PMT_PID, /* pusi= */ true, /* cc= */ 0, buildPmtSection());
    // 3. PES packet on TELETEXT_PID carrying page 888 subtitle + terminating 8FF header
    byte[] pes = buildTeletextPesPacket(/* pts90kHz= */ 90_000L);
    int offset = 0;
    int cc = 0;
    while (offset < pes.length) {
      int chunkLen = Math.min(184, pes.length - offset);
      byte[] chunk = new byte[chunkLen];
      System.arraycopy(pes, offset, chunk, 0, chunkLen);
      writeTsPacket(out, TELETEXT_PID, /* pusi= */ offset == 0, cc++ & 0x0F, chunk);
      offset += chunkLen;
    }
    return out.toByteArray();
  }

  private static byte[] buildPatSection() {
    // pointer_field (0) + table_id (0x00) + section_syntax + section_length (13)
    // + ts_id (1) + flags + sec/last_sec + program 1 -> PMT_PID + CRC32
    byte[] section =
        new byte[] {
          0x00,
          0x00,
          (byte) 0xB0,
          0x0D,
          0x00,
          0x01,
          (byte) 0xC1,
          0x00,
          0x00,
          0x00,
          0x01,
          (byte) (0xE0 | ((PMT_PID >> 8) & 0x1F)),
          (byte) (PMT_PID & 0xFF),
          0x00,
          0x00,
          0x00,
          0x00
        };
    writeSectionCrc32(section);
    return section;
  }

  private static byte[] buildPmtSection() {
    // teletext_descriptor (tag 0x56, length 17 = 3 * 5 + 2 truncated trailing bytes):
    // Entry 1: "eng", type=0x01 (initial page -> filtered out), mag=1, page=0x00
    // Entry 2: "eng", type=0x02 (subtitle), mag=0 (->8), page=0x88
    // Entry 3: "deu", type=0x05 (hearing impaired), mag=7, page=0x77
    byte[] desc =
        new byte[] {
          0x56,
          17,
          'e',
          'n',
          'g',
          (byte) ((0x01 << 3) | 1),
          0x00,
          'e',
          'n',
          'g',
          (byte) ((0x02 << 3) | 0),
          (byte) 0x88,
          'd',
          'e',
          'u',
          (byte) ((0x05 << 3) | 7),
          (byte) 0x77,
          0x00,
          0x00 // Truncated trailing bytes (< 5)
        };
    int esInfoLen = desc.length;
    int sectionLength = 9 + 5 + esInfoLen + 4;
    ByteArrayOutputStream s = new ByteArrayOutputStream();
    s.write(0x00); // pointer_field
    s.write(0x02); // table_id = PMT
    s.write(0xB0 | ((sectionLength >> 8) & 0x0F));
    s.write(sectionLength & 0xFF);
    s.write(0x00);
    s.write(0x01); // program_number = 1
    s.write(0xC1); // version 0, current_next = 1
    s.write(0x00); // section_number
    s.write(0x00); // last_section_number
    s.write(0xE0 | ((TELETEXT_PID >> 8) & 0x1F));
    s.write(TELETEXT_PID & 0xFF); // PCR_PID
    s.write(0xF0);
    s.write(0x00); // program_info_length = 0
    // Elementary stream: stream_type=0x06 (private PES)
    s.write(0x06);
    s.write(0xE0 | ((TELETEXT_PID >> 8) & 0x1F));
    s.write(TELETEXT_PID & 0xFF);
    s.write(0xF0 | ((esInfoLen >> 8) & 0x0F));
    s.write(esInfoLen & 0xFF);
    s.write(desc, 0, desc.length);
    // 4-byte MPEG-2 CRC32
    s.write(0x00);
    s.write(0x00);
    s.write(0x00);
    s.write(0x00);
    byte[] section = s.toByteArray();
    writeSectionCrc32(section);
    return section;
  }

  private static void writeSectionCrc32(byte[] sectionWithPointerField) {
    int crcOffset = sectionWithPointerField.length - 4;
    int crc = Util.crc32(sectionWithPointerField, 1, crcOffset, 0xFFFFFFFF);
    sectionWithPointerField[crcOffset] = (byte) ((crc >>> 24) & 0xFF);
    sectionWithPointerField[crcOffset + 1] = (byte) ((crc >>> 16) & 0xFF);
    sectionWithPointerField[crcOffset + 2] = (byte) ((crc >>> 8) & 0xFF);
    sectionWithPointerField[crcOffset + 3] = (byte) (crc & 0xFF);
  }

  private static byte[] buildTeletextPesPacket(long pts90kHz) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.write(0x10); // EBU data_identifier
    byte[] hdr = buildHeaderUnit(8, 0x88, /* subtitle= */ true);
    byte[] row = buildRowUnit(8, 20, "\u000bTeletext Live\n");
    byte[] endHdr = buildHeaderUnit(8, 0xFF, /* subtitle= */ false);
    payload.write(hdr, 0, hdr.length);
    payload.write(row, 0, row.length);
    payload.write(endHdr, 0, endHdr.length);
    byte[] payloadBytes = payload.toByteArray();

    int pesPacketLength = 3 + 5 + payloadBytes.length;
    ByteArrayOutputStream pes = new ByteArrayOutputStream();
    pes.write(0x00);
    pes.write(0x00);
    pes.write(0x01); // start code
    pes.write(0xBD); // private_stream_1
    pes.write((pesPacketLength >> 8) & 0xFF);
    pes.write(pesPacketLength & 0xFF);
    pes.write(0x84); // marker + data_alignment_indicator
    pes.write(0x80); // PTS flag
    pes.write(0x05); // PES header data length = 5
    // Encode 33-bit PTS
    pes.write((int) (0x21 | (((pts90kHz >> 30) & 0x07) << 1)));
    pes.write((int) ((pts90kHz >> 22) & 0xFF));
    pes.write((int) (0x01 | (((pts90kHz >> 15) & 0x7F) << 1)));
    pes.write((int) ((pts90kHz >> 7) & 0xFF));
    pes.write((int) (0x01 | ((pts90kHz & 0x7F) << 1)));
    pes.write(payloadBytes, 0, payloadBytes.length);
    return pes.toByteArray();
  }

  private static byte[] buildHeaderUnit(int mag, int page, boolean subtitle) {
    byte[] unit = new byte[46];
    unit[0] = 0x03;
    unit[1] = 0x2C;
    unit[2] = (byte) 0xE7;
    unit[3] = (byte) 0xE4;
    unit[4] = encodeHamming(mag == 8 ? 0 : mag);
    unit[5] = encodeHamming(0);
    unit[6] = encodeHamming(page & 0x0F);
    unit[7] = encodeHamming((page >> 4) & 0x0F);
    unit[8] = encodeHamming(0);
    unit[9] = encodeHamming(0);
    unit[10] = encodeHamming(0);
    unit[11] = encodeHamming(subtitle ? 0x08 : 0x00);
    unit[12] = encodeHamming(0);
    unit[13] = encodeHamming(0);
    for (int i = 14; i < 46; i++) {
      unit[i] = encodeOddParity(' ');
    }
    return unit;
  }

  private static byte[] buildRowUnit(int mag, int row, String text) {
    byte[] unit = new byte[46];
    unit[0] = 0x03;
    unit[1] = 0x2C;
    unit[2] = (byte) 0xE7;
    unit[3] = (byte) 0xE4;
    unit[4] = encodeHamming((mag == 8 ? 0 : mag) | ((row & 0x01) << 3));
    unit[5] = encodeHamming((row >> 1) & 0x0F);
    for (int i = 0; i < 40; i++) {
      unit[6 + i] = encodeOddParity(i < text.length() ? text.charAt(i) : ' ');
    }
    return unit;
  }

  private static byte encodeHamming(int value) {
    int cw = HAMMING_84_CODEWORDS[value & 0x0F];
    return (byte) (Integer.reverse(cw) >>> 24);
  }

  private static byte encodeOddParity(int ch) {
    int b = ch & 0x7F;
    if (Integer.bitCount(b) % 2 == 0) {
      b |= 0x80;
    }
    return (byte) (Integer.reverse(b) >>> 24);
  }

  private static void writeTsPacket(
      ByteArrayOutputStream out, int pid, boolean pusi, int cc, byte[] payload) {
    byte[] packet = new byte[188];
    packet[0] = 0x47;
    packet[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
    packet[2] = (byte) (pid & 0xFF);
    if (payload.length < 184) {
      // Use adaptation field for stuffing so payload length is exact.
      int stuffLen = 184 - payload.length;
      packet[3] = (byte) (0x30 | (cc & 0x0F)); // adaptation + payload
      packet[4] = (byte) (stuffLen - 1);
      if (stuffLen > 1) {
        packet[5] = 0x00; // adaptation flags
        for (int i = 6; i < 4 + stuffLen; i++) {
          packet[i] = (byte) 0xFF;
        }
      }
      System.arraycopy(payload, 0, packet, 4 + stuffLen, payload.length);
    } else {
      packet[3] = (byte) (0x10 | (cc & 0x0F)); // payload only
      System.arraycopy(payload, 0, packet, 4, 184);
    }
    out.write(packet, 0, 188);
  }
}
