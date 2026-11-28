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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ts.TsPayloadReader.TeletextSubtitleInfo;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TeletextSubtitleReader}. */
@RunWith(AndroidJUnit4.class)
public final class TeletextSubtitleReaderTest {

  @Test
  public void createTracks_singleSubtitleTrack_setsTeletextFormatAndInitData() {
    TeletextSubtitleInfo info =
        new TeletextSubtitleInfo(
            /* language= */ "eng",
            /* teletextType= */ 0x02,
            /* magazineNumber= */ 8,
            /* pageNumber= */ 0x88);
    TeletextSubtitleReader reader =
        new TeletextSubtitleReader(Collections.singletonList(info), MimeTypes.VIDEO_MP2T);
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    TrackIdGenerator idGenerator = new TrackIdGenerator(1, 1);

    reader.createTracks(extractorOutput, idGenerator);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(1);
    assertThat(trackOutput).isNotNull();
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.containerMimeType).isEqualTo(MimeTypes.VIDEO_MP2T);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_TELETEXT);
    assertThat(format.language).isEqualTo("en");
    assertThat(format.roleFlags).isEqualTo(C.ROLE_FLAG_SUBTITLE);
    assertThat(format.initializationData).hasSize(1);
    assertThat(format.initializationData.get(0)).isEqualTo(new byte[] {8, (byte) 0x88});
  }

  @Test
  public void createTracks_hearingImpairedSubtitle_setsHearingImpairedRoleFlag() {
    TeletextSubtitleInfo info =
        new TeletextSubtitleInfo(
            /* language= */ "deu",
            /* teletextType= */ 0x05,
            /* magazineNumber= */ 7,
            /* pageNumber= */ 0x77);
    TeletextSubtitleReader reader =
        new TeletextSubtitleReader(Collections.singletonList(info), MimeTypes.VIDEO_MP2T);
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    TrackIdGenerator idGenerator = new TrackIdGenerator(1, 1);

    reader.createTracks(extractorOutput, idGenerator);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(1);
    assertThat(trackOutput).isNotNull();
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.roleFlags)
        .isEqualTo(C.ROLE_FLAG_SUBTITLE | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND);
  }

  @Test
  public void consumeAndSeek_emitsRawPesPayloadsToAllTracks() {
    TeletextSubtitleReader reader =
        new TeletextSubtitleReader(
            Arrays.asList(
                new TeletextSubtitleInfo("eng", 0x02, 8, 0x88),
                new TeletextSubtitleInfo("deu", 0x05, 7, 0x77)),
            MimeTypes.VIDEO_MP2T);
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    reader.createTracks(extractorOutput, new TrackIdGenerator(1, 1));

    byte[] payload1 = new byte[] {0x10, 0x03, 0x2C, 0x01};
    byte[] payload2 = new byte[] {0x02, 0x03};
    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, /* flags= */ 0);
    reader.consume(new ParsableByteArray(payload1));
    reader.consume(new ParsableByteArray(payload2));
    reader.packetFinished();

    // Packet with unset timestamp should be ignored.
    reader.packetStarted(/* pesTimeUs= */ C.TIME_UNSET, /* flags= */ 0);
    reader.consume(new ParsableByteArray(payload1));
    reader.packetFinished();

    // Interrupted packet cleared by seek().
    reader.packetStarted(/* pesTimeUs= */ 2_000_000L, /* flags= */ 0);
    reader.consume(new ParsableByteArray(payload1));
    reader.seek();
    reader.packetFinished();

    byte[] expected = new byte[] {0x10, 0x03, 0x2C, 0x01, 0x02, 0x03};
    for (int trackId : new int[] {1, 2}) {
      FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(trackId);
      assertThat(trackOutput.getSampleCount()).isEqualTo(1);
      assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(1_000_000L);
      assertThat(trackOutput.getSampleData(0)).isEqualTo(expected);
    }
  }
}
