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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.CueEncoder;
import androidx.media3.extractor.ts.TsPayloadReader.TeletextSubtitleInfo;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses DVB Teletext subtitle data as defined in ETSI EN 300 472 and ETSI EN 300 706.
 */
@UnstableApi
public final class TeletextSubtitleReader implements ElementaryStreamReader {

  private static final int DEFAULT_DURATION_US = 4_000_000;

  private static final class PageBuffer {
    final Map<Integer, String> rows = new HashMap<>();
    long startTimeUs = C.TIME_UNSET;
    long firstContentTimeUs = C.TIME_UNSET;
    long lastFlushTimeUs = C.TIME_UNSET;
    boolean hasContent;
    int currentPage = -1;
    boolean isSubtitlePage;
    boolean isTargetPage;
    int charsetHint = 0;
    String lastEmittedText = "";
    boolean shouldClearOnNextHeader;
    boolean pendingErase;

    void clear() {
      rows.clear();
      startTimeUs = C.TIME_UNSET;
      firstContentTimeUs = C.TIME_UNSET;
      hasContent = false;
      currentPage = -1;
      isSubtitlePage = false;
      isTargetPage = false;
      charsetHint = 0;
      shouldClearOnNextHeader = false;
      pendingErase = false;
    }
  }

  private final List<TeletextSubtitleInfo> subtitleInfos;
  private final TrackOutput[] outputs;
  private final PageBuffer[] pageBuffers;
  private final CueEncoder cueEncoder;
  private final int[] magazineCurrentPages = new int[9];
  private long sampleTimeUs = C.TIME_UNSET;
  private long lastEmittedUs = -1;

  // Unit reassembly state
  private final ParsableByteArray unitBuffer = new ParsableByteArray(46);
  private int state;
  private int bytesRead;
  private int currentUnitLen;

  private static final int STATE_FINDING_IDENTIFIER = 0;
  private static final int STATE_READING_UNIT_HEADER = 1;
  private static final int STATE_READING_UNIT_BODY = 2;
  private static final int STATE_SKIPPING_UNIT = 3;

  /**
   * @param subtitleInfos Information about the Teletext subtitle tracks defined in the PMT.
   */
  public TeletextSubtitleReader(List<TeletextSubtitleInfo> subtitleInfos) {
    if (subtitleInfos.isEmpty()) {
      this.subtitleInfos = new ArrayList<>();
      this.subtitleInfos.add(
          new TeletextSubtitleInfo(
              /* language= */ C.LANGUAGE_UNDETERMINED,
              /* teletextType= */ 0x02,
              /* magazineNumber= */ 0,
              /* pageNumber= */ 0));
    } else {
      this.subtitleInfos = subtitleInfos;
    }

    outputs = new TrackOutput[this.subtitleInfos.size()];
    pageBuffers = new PageBuffer[this.subtitleInfos.size()];
    for (int i = 0; i < pageBuffers.length; i++) {
      pageBuffers[i] = new PageBuffer();
    }
    cueEncoder = new CueEncoder();
    java.util.Arrays.fill(this.magazineCurrentPages, -1);
  }

  @Override
  public void seek() {
    sampleTimeUs = C.TIME_UNSET;
    lastEmittedUs = -1;
    for (PageBuffer buffer : pageBuffers) {
      buffer.clear();
    }
    java.util.Arrays.fill(magazineCurrentPages, -1);
    state = STATE_FINDING_IDENTIFIER;
    bytesRead = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    if (outputs.length > 0 && outputs[0] != null) {
      return;
    }
    for (int i = 0; i < outputs.length; i++) {
      TeletextSubtitleInfo info = subtitleInfos.get(i);
      idGenerator.generateNewId();
      TrackOutput output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
      int roleFlags = C.ROLE_FLAG_SUBTITLE;
      if (info.teletextType == 0x05) {
        roleFlags |= C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND;
      }
      output.format(
          new Format.Builder()
              .setId(idGenerator.getFormatId())
              .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
              .setLanguage(info.language)
              .setRoleFlags(roleFlags)
              .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
              .build());
      outputs[i] = output;
    }
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      sampleTimeUs = pesTimeUs;
    }
    state = STATE_FINDING_IDENTIFIER;
    bytesRead = 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      int toRead;
      int actualRead;
      switch (state) {
        case STATE_FINDING_IDENTIFIER:
          int b = data.readUnsignedByte();
          if ((b >= 0x10 && b <= 0x1F) || b == 0x99 || b == 0x2C) {
            state = STATE_READING_UNIT_HEADER;
            bytesRead = 0;
          } else if (b == 0x02 || b == 0x03) {
            unitBuffer.getData()[0] = (byte) b;
            bytesRead = 1;
            state = STATE_READING_UNIT_HEADER;
          }
          break;
        case STATE_READING_UNIT_HEADER:
          toRead = 2 - bytesRead;
          actualRead = Math.min(data.bytesLeft(), toRead);
          data.readBytes(unitBuffer.getData(), bytesRead, actualRead);
          bytesRead += actualRead;
          if (bytesRead == 2) {
            int unitId = unitBuffer.getData()[0] & 0xFF;
            int unitLen = unitBuffer.getData()[1] & 0xFF;
            // Standard EBU Unit IDs (0x02 non-subtitle, 0x03 subtitle) and 44-byte length
            if ((unitId == 0x02 || unitId == 0x03 || unitId == 0x99 || unitId == 0x2C)
                && unitLen == 0x2C) {
              currentUnitLen = unitLen;
              state = STATE_READING_UNIT_BODY;
            } else {
              currentUnitLen = unitLen;
              state = STATE_SKIPPING_UNIT;
            }
            bytesRead = 0;
          }
          break;
        case STATE_READING_UNIT_BODY:
          toRead = currentUnitLen - bytesRead;
          actualRead = Math.min(data.bytesLeft(), toRead);
          if (bytesRead + actualRead <= 44) {
            data.readBytes(unitBuffer.getData(), 2 + bytesRead, actualRead);
          } else if (bytesRead < 44) {
            int fill = 44 - bytesRead;
            data.readBytes(unitBuffer.getData(), 2 + bytesRead, fill);
            data.skipBytes(actualRead - fill);
          } else {
            data.skipBytes(actualRead);
          }
          bytesRead += actualRead;
          if (bytesRead >= currentUnitLen) {
            if (currentUnitLen == 44) {
              unitBuffer.setPosition(2);
              parseTeletextUnit(unitBuffer);
            }
            state = STATE_FINDING_IDENTIFIER;
            bytesRead = 0;
          }
          break;
        case STATE_SKIPPING_UNIT:
          toRead = currentUnitLen - bytesRead;
          actualRead = Math.min(data.bytesLeft(), toRead);
          data.skipBytes(actualRead);
          bytesRead += actualRead;
          if (bytesRead >= currentUnitLen) {
            state = STATE_FINDING_IDENTIFIER;
            bytesRead = 0;
          }
          break;
      }
    }
  }

  private void parseTeletextUnit(ParsableByteArray data) {
    if (data.bytesLeft() < 43) {
      return;
    }

    data.skipBytes(1); // Skip field parity and line offset

    int bFramingRaw = data.readUnsignedByte();
    // 0x27 is standard framing code. 0xE4 is bit-reversed 0x27.
    if (bFramingRaw != 0x27 && bFramingRaw != 0xE4) {
      return;
    }

    if (data.bytesLeft() < 2) {
      return;
    }

    int b1 = data.readUnsignedByte();
    int b2 = data.readUnsignedByte();
    int v1 = hamming84Decode(REVERSE_LOOKUP[b1 & 0xFF]);
    int v2 = hamming84Decode(REVERSE_LOOKUP[b2 & 0xFF]);

    if (v1 < 0 || v2 < 0) {
      return;
    }

    int mag = v1 & 0x07;
    if (mag == 0) {
      mag = 8;
    }
    int pkt = ((v1 & 0x08) >> 3) | (v2 << 1);

    if (pkt == 0) {
      // Packet 0: Page Header
      if (data.bytesLeft() < 6) {
        return;
      }
      int b3 = data.readUnsignedByte(); // Page Units
      int b4 = data.readUnsignedByte(); // Page Tens
      int b5 = data.readUnsignedByte(); // Control bits C4-C7
      data.readUnsignedByte(); // Control bits C8-C11
      int b7 = data.readUnsignedByte(); // Control bits C12-C14 (charset hint)
      data.readUnsignedByte(); // Magazine Serial

      int v3 = hamming84Decode(REVERSE_LOOKUP[b3 & 0xFF]);
      int v4 = hamming84Decode(REVERSE_LOOKUP[b4 & 0xFF]);
      int v5 = hamming84Decode(REVERSE_LOOKUP[b5 & 0xFF]);
      int v7 = hamming84Decode(REVERSE_LOOKUP[b7 & 0xFF]);

      if (v3 >= 0 && v4 >= 0) {
        int page = (v4 << 4) | v3;
        // C4: Erase Page, C6: Subtitle flag
        boolean erasePage = v5 >= 0 && (v5 & 0x01) != 0;
        boolean isSubtitlePage = v5 >= 0 && (v5 & 0x04) != 0;
        int charsetHint = v7 >= 0 ? (v7 & 0x07) : 0;

        magazineCurrentPages[mag] = page;

        for (int i = 0; i < subtitleInfos.size(); i++) {
          TeletextSubtitleInfo info = subtitleInfos.get(i);
          if (mag == info.magazineNumber || info.magazineNumber == 0) {
            PageBuffer buffer = pageBuffers[i];
            boolean isTargetPage =
                (info.pageNumber != 0) ? (page == info.pageNumber) : isSubtitlePage;

            boolean currentIsTarget =
                (buffer.currentPage != -1)
                    && (buffer.currentPage == info.pageNumber
                        || (info.pageNumber == 0 && buffer.isSubtitlePage));

            if (currentIsTarget && !isTargetPage) {
              continue;
            }

            boolean shouldClear =
                page != buffer.currentPage || erasePage || buffer.shouldClearOnNextHeader;

            if (buffer.currentPage != -1 && buffer.hasContent && shouldClear) {
              flushPage(i);
            }

            if (shouldClear) {
              buffer.clear();
              buffer.currentPage = page;
              buffer.isSubtitlePage = isSubtitlePage;
              buffer.isTargetPage = isTargetPage;
              buffer.charsetHint = charsetHint;
              buffer.startTimeUs = sampleTimeUs;
              buffer.shouldClearOnNextHeader = false;

              if (erasePage && buffer.isTargetPage) {
                buffer.pendingErase = true;
              }
            } else {
              buffer.isTargetPage = isTargetPage;
            }
          }
        }
      }
      data.skipBytes(Math.min(data.bytesLeft(), 34));
    } else if (pkt >= 1 && pkt <= 25) {
      // Packets 1-25: Row data
      byte[] rowData = new byte[40];
      data.readBytes(rowData, 0, 40);

      // Verify character parity (odd parity per ETSI EN 300 706 Section 8.2)
      int parityErrors = 0;
      for (int i = 0; i < 40; i++) {
        if (Integer.bitCount(rowData[i] & 0xFF) % 2 == 0) {
          parityErrors++;
        }
      }
      if (parityErrors > 1) {
        return;
      }

      for (int i = 0; i < subtitleInfos.size(); i++) {
        TeletextSubtitleInfo info = subtitleInfos.get(i);
        if (mag == info.magazineNumber || info.magazineNumber == 0) {
          PageBuffer buffer = pageBuffers[i];
          int activePage = magazineCurrentPages[mag];
          if (activePage != -1 && activePage == buffer.currentPage && buffer.currentPage != -1) {
            if (buffer.isTargetPage) {
              String text = decode7BitRow(rowData, info.language, buffer.charsetHint);
              String trimmedText = text.trim();
              if (trimmedText.isEmpty()) {
                buffer.rows.remove(pkt);
                continue;
              }
              buffer.rows.put(pkt, trimmedText);
              buffer.hasContent = true;
              buffer.pendingErase = false;
              buffer.firstContentTimeUs =
                  (buffer.firstContentTimeUs == C.TIME_UNSET)
                      ? sampleTimeUs
                      : buffer.firstContentTimeUs;

              if (buffer.isSubtitlePage) {
                flushPage(i);
              }
              break;
            }
          }
        }
      }
    } else {
      data.skipBytes(Math.min(data.bytesLeft(), 40));
    }
  }

  private void flushPage(int index) {
    PageBuffer buffer = pageBuffers[index];
    if (!buffer.hasContent) {
      return;
    }

    long startUs =
        (sampleTimeUs != C.TIME_UNSET)
            ? sampleTimeUs
            : (lastEmittedUs == -1 ? 0 : lastEmittedUs + 1);
    if (startUs <= lastEmittedUs) {
      startUs = lastEmittedUs + 1;
    }
    lastEmittedUs = startUs;

    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 25; i++) {
      String row = buffer.rows.get(i);
      if (row != null && !row.isEmpty()) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append(row);
      }
    }

    String text = sb.toString().trim();
    if (text.equals(buffer.lastEmittedText)
        && (buffer.lastFlushTimeUs != C.TIME_UNSET
            && startUs - buffer.lastFlushTimeUs < 3_000_000L)) {
      buffer.hasContent = false;
      return;
    }

    emitSample(index, startUs, DEFAULT_DURATION_US, text);
    buffer.lastEmittedText = text;
    buffer.lastFlushTimeUs = startUs;
    buffer.hasContent = false;
    buffer.pendingErase = false;
  }

  private void emitSample(int index, long startUs, long durationUs, String text) {
    List<Cue> cues =
        text.isEmpty()
            ? Collections.emptyList()
            : Collections.singletonList(
                new Cue.Builder()
                    .setText(text)
                    .setLine(0.92f, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
                    .build());

    byte[] sample = cueEncoder.encode(cues, durationUs);
    outputs[index].sampleData(new ParsableByteArray(sample), sample.length);
    outputs[index].sampleMetadata(startUs, C.BUFFER_FLAG_KEY_FRAME, sample.length, 0, null);
  }

  private String decode7BitRow(byte[] rowData, String language, int charsetHint) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      int b = REVERSE_LOOKUP[rowData[i] & 0xFF] & 0x7F;
      if (b < 0x20 || b == 0x7F) {
        sb.append(' ');
      } else {
        sb.append(mapChar(b, language, charsetHint));
      }
    }
    return sb.toString();
  }

  private char mapChar(int b, String language, int charsetHint) {
    if ("rus".equals(language) || "ukr".equals(language) || "bel".equals(language)) {
      return TeletextCharacterSets.getCyrillic().charAt(b - 0x20);
    }

    Map<Integer, Character> options = null;
    if (language != null) {
      options = TeletextCharacterSets.getNationalOptions().get(language);
    }
    if ((options == null || options.isEmpty()) && charsetHint != 0) {
      String hintLang = TeletextCharacterSets.getLanguageFromHint(charsetHint);
      options = TeletextCharacterSets.getNationalOptions().get(hintLang);
    }
    if (options != null) {
      Character c = options.get(b);
      if (c != null) {
        return c;
      }
    }
    if (b == 0x23) {
      return '£';
    }
    return (char) b;
  }

  @Override
  public void packetFinished() {
    packetFinished(/* isEndOfInput= */ false);
  }

  @Override
  public void endOfInputReached() {
    packetFinished(/* isEndOfInput= */ true);
  }

  private void packetFinished(boolean isEndOfInput) {
    for (int i = 0; i < pageBuffers.length; i++) {
      PageBuffer buffer = pageBuffers[i];
      buffer.shouldClearOnNextHeader = true;
      if (buffer.pendingErase && !buffer.hasContent) {
        long clearTimeUs =
            (sampleTimeUs != C.TIME_UNSET)
                ? sampleTimeUs
                : (lastEmittedUs == -1 ? 0 : lastEmittedUs + 1);
        if (clearTimeUs <= lastEmittedUs) {
          clearTimeUs = lastEmittedUs + 1;
        }
        lastEmittedUs = clearTimeUs;
        emitSample(i, clearTimeUs, C.TIME_UNSET, "");
        buffer.lastEmittedText = "";
        buffer.pendingErase = false;
      }
      if (buffer.hasContent) {
        if (isEndOfInput
            || (sampleTimeUs != C.TIME_UNSET
                && buffer.firstContentTimeUs != C.TIME_UNSET
                && sampleTimeUs - buffer.firstContentTimeUs > 1_000_000L)) {
          flushPage(i);
        }
      }
    }
  }

  private static int hamming84Decode(int b) {
    return HAMMING_84_LOOKUP[b & 0xFF];
  }

  private static final int[] HAMMING_84_LOOKUP = new int[256];
  private static final int[] REVERSE_LOOKUP = new int[256];

  static {
    java.util.Arrays.fill(HAMMING_84_LOOKUP, -1);
    int[] codewords = {
      0x15, 0x02, 0x49, 0x5E, 0x64, 0x71, 0x38, 0x2D,
      0x8A, 0x9F, 0xD2, 0xC7, 0xBF, 0xAA, 0xF3, 0xE6
    };
    for (int b = 0; b < 256; b++) {
      int minDistance = 9;
      int closestIndex = -1;
      int closestCount = 0;
      for (int i = 0; i < 16; i++) {
        int dist = Integer.bitCount(b ^ codewords[i]);
        if (dist < minDistance) {
          minDistance = dist;
          closestIndex = i;
          closestCount = 1;
        } else if (dist == minDistance) {
          closestCount++;
        }
      }
      if (minDistance <= 2 && closestCount == 1) {
        HAMMING_84_LOOKUP[b] = closestIndex;
      }
    }
    for (int i = 0; i < 256; i++) {
      REVERSE_LOOKUP[i] = Integer.reverse(i) >>> 24;
    }
  }
}
