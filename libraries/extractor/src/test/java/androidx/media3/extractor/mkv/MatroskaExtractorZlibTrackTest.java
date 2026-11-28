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
package androidx.media3.extractor.mkv;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests Matroska track selection for zlib subtitle compression. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaExtractorZlibTrackTest {
  private static final int ID_INFO = 0x1549A966;
  private static final int ID_TRACKS = 0x1654AE6B;
  private static final int ID_TRACK_ENTRY = 0xAE;
  private static final int ID_TRACK_NUMBER = 0xD7;
  private static final int ID_TRACK_TYPE = 0x83;
  private static final int ID_CODEC_ID = 0x86;
  private static final int ID_AUDIO = 0xE1;
  private static final int ID_AUDIO_BIT_DEPTH = 0x6264;
  private static final int ID_CONTENT_ENCODINGS = 0x6D80;
  private static final int ID_CONTENT_ENCODING = 0x6240;
  private static final int ID_CONTENT_COMPRESSION = 0x5034;
  private static final int ID_CONTENT_COMPRESSION_ALGORITHM = 0x4254;
  private static final int ID_CLUSTER = 0x1F43B675;
  private static final int ID_TIME_CODE = 0xE7;
  private static final int ID_SIMPLE_BLOCK = 0xA3;

  @Test
  public void read_zlibSubtitleWithEmptyCompressionSettings_outputsCue() throws Exception {
    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            new MatroskaExtractor(new DefaultSubtitleParserFactory()),
            ApplicationProvider.getApplicationContext(),
            "media/mkv/sample_with_empty_zlib_subtitle_settings.mkv");

    FakeTrackOutput text =
        Iterables.getOnlyElement(output.getTrackOutputsForType(C.TRACK_TYPE_TEXT));
    assertThat(text.getSampleCount()).isEqualTo(1);
    byte[] sample = text.getSampleData(0);
    CuesWithTiming cues =
        new CueDecoder().decode(text.getSampleTimeUs(0), sample, 0, sample.length);
    assertThat(Iterables.getOnlyElement(cues.cues).text.toString())
        .isEqualTo("One compressed subtitle cue.");
  }

  @Test
  public void read_unsupportedZlibSubtitle_outputsAudioSamples() throws Exception {
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    extractor.startMasterElement(ID_INFO, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.endMasterElement(ID_INFO);
    extractor.startMasterElement(ID_TRACKS, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.startMasterElement(ID_TRACK_ENTRY, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.integerElement(ID_TRACK_NUMBER, 2);
    extractor.integerElement(ID_TRACK_TYPE, 17);
    extractor.stringElement(ID_CODEC_ID, "S_DVBSUB");
    extractor.startMasterElement(
        ID_CONTENT_ENCODINGS, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.startMasterElement(
        ID_CONTENT_ENCODING, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.startMasterElement(
        ID_CONTENT_COMPRESSION, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.integerElement(ID_CONTENT_COMPRESSION_ALGORITHM, 0);
    extractor.endMasterElement(ID_CONTENT_COMPRESSION);
    extractor.endMasterElement(ID_CONTENT_ENCODING);
    extractor.endMasterElement(ID_CONTENT_ENCODINGS);
    extractor.endMasterElement(ID_TRACK_ENTRY);

    extractor.startMasterElement(ID_TRACK_ENTRY, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.integerElement(ID_TRACK_NUMBER, 1);
    extractor.integerElement(ID_TRACK_TYPE, 2);
    extractor.stringElement(ID_CODEC_ID, "A_PCM/INT/LIT");
    extractor.startMasterElement(ID_AUDIO, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.integerElement(ID_AUDIO_BIT_DEPTH, 16);
    extractor.endMasterElement(ID_AUDIO);
    extractor.endMasterElement(ID_TRACK_ENTRY);
    extractor.endMasterElement(ID_TRACKS);

    extractor.startMasterElement(ID_CLUSTER, /* contentPosition= */ 0, /* contentSize= */ 0);
    extractor.integerElement(ID_TIME_CODE, 0);
    byte[] block = {(byte) 0x81, 0, 0, (byte) 0x80, 0x34, 0x12};
    extractor.binaryElement(
        ID_SIMPLE_BLOCK, block.length, new FakeExtractorInput.Builder().setData(block).build());

    assertThat(output.getTrackOutputsForType(C.TRACK_TYPE_TEXT)).isEmpty();
    FakeTrackOutput audio =
        Iterables.getOnlyElement(output.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    assertThat(audio.lastFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_RAW);
    assertThat(audio.getSampleCount()).isEqualTo(1);
    assertThat(audio.getSampleData(0)).isEqualTo(new byte[] {0x34, 0x12});
  }
}
