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
import androidx.media3.extractor.ts.TsPayloadReader.TeletextSubtitleInfo;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TeletextSubtitleReader}. */
@RunWith(AndroidJUnit4.class)
public final class TeletextSubtitleReaderTest {

  @Test
  public void createTracks_singleSubtitleTrack_createsMedia3CuesTrack() {
    TeletextSubtitleInfo info =
        new TeletextSubtitleInfo(
            /* language= */ "eng",
            /* teletextType= */ 0x02,
            /* magazineNumber= */ 8,
            /* pageNumber= */ 0x88);
    TeletextSubtitleReader reader = new TeletextSubtitleReader(Collections.singletonList(info));
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    TrackIdGenerator idGenerator = new TrackIdGenerator(1, 1);

    reader.createTracks(extractorOutput, idGenerator);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(1);
    assertThat(trackOutput).isNotNull();
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_MEDIA3_CUES);
    assertThat(format.language).isEqualTo("en");
    assertThat(format.roleFlags).isEqualTo(C.ROLE_FLAG_SUBTITLE);
  }

  @Test
  public void createTracks_hearingImpairedSubtitle_setsHearingImpairedRoleFlag() {
    TeletextSubtitleInfo info =
        new TeletextSubtitleInfo(
            /* language= */ "deu",
            /* teletextType= */ 0x05,
            /* magazineNumber= */ 7,
            /* pageNumber= */ 0x77);
    TeletextSubtitleReader reader = new TeletextSubtitleReader(Collections.singletonList(info));
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
  public void createTracks_emptySubtitleInfos_createsFallbackTrack() {
    TeletextSubtitleReader reader = new TeletextSubtitleReader(Collections.emptyList());
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    TrackIdGenerator idGenerator = new TrackIdGenerator(1, 1);

    reader.createTracks(extractorOutput, idGenerator);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(1);
    assertThat(trackOutput).isNotNull();
    Format format = trackOutput.lastFormat;
    assertThat(format).isNotNull();
    assertThat(format.language).isEqualTo(C.LANGUAGE_UNDETERMINED);
    assertThat(format.roleFlags).isEqualTo(C.ROLE_FLAG_SUBTITLE);
  }
}
