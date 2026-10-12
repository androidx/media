/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.ogg;

import static androidx.media3.test.utils.TestUtil.getByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.container.OpusUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link OggExtractor} that test specific behaviours and don't need to be parameterized.
 *
 * <p>For parameterized tests using {@link ExtractorAsserts} see {@link
 * OggExtractorParameterizedTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class OggExtractorNonParameterizedTest {

  private static final byte[] opusCommentHeader =
      new byte[] {'O', 'p', 'u', 's', 'T', 'a', 'g', 's', 0, 0, 0, 0, 0, 0, 0, 0};

  @Test
  public void read_afterEndOfInput_doesNotThrowIllegalState() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/bear_flac.ogg");
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    OggExtractor oggExtractor = new OggExtractor();
    oggExtractor.init(new FakeExtractorOutput());
    // We feed data to the extractor until the end of input is reached.
    while (oggExtractor.read(input, new PositionHolder()) != Extractor.RESULT_END_OF_INPUT) {}
    // We call read again to check that it does not throw an IllegalStateException.
    assertThat(oggExtractor.read(input, new PositionHolder())).isEqualTo(C.RESULT_END_OF_INPUT);
  }

  @Test
  public void read_opusWithNonMultipleOfThreeEndPadding_preservesExactSampleCountOnRoundTrip()
      throws Exception {
    // 2 packets of 960 granules = 1920 total granules. Final granule position 1798 means 122
    // granules of end padding (122 % 3 != 0).
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ 1798);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(1);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(122);
  }

  @Test
  public void read_opusWithFirstAudioPageGranuleOffset_computesEndPaddingFromPageGranuleDelta()
      throws Exception {
    // First audio page has 1 packet of 960 granules, but its granulePosition is 1008 (+48 offset
    // allowed by RFC 7845 Section 4.5). The EOS page has 1 packet of 960 granules and
    // granulePosition 1800, meaning 1800 - 1008 = 792 samples to keep and 960 - 792 = 168 samples
    // of end padding.
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 1008, /* finalPageGranulePosition= */ 1800);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(1);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(168);
  }

  @Test
  public void read_opusWithUnsetFinalGranulePosition_doesNotAttachSupplementalData()
      throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960,
            /* finalPageGranulePosition= */ C.INDEX_UNSET);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
  }

  @Test
  public void read_opusWithMalformedNegativeFinalGranulePosition_doesNotAttachSupplementalData()
      throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ -500);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
  }

  @Test
  public void read_opusWithZeroFinalGranulePosition_doesNotAttachSupplementalData()
      throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ 0);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
  }

  @Test
  public void read_opusWithFinalGranulePositionLessThanPreviousPage_doesNotAttachSupplementalData()
      throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ 500);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
  }

  @Test
  public void read_opusWithFinalGranulePositionLessThanLastPacketStart_attachesTotalDiscardPadding()
      throws Exception {
    // Page 1 has 1 packet (0..960). Page 2 (EOS) has 2 packets (960..1920 and 1920..2880).
    // Final granule position 1500 is greater than Page 1's granule position (960), but less than
    // the start of the last packet (1920), meaning 2880 - 1500 = 1380 granules of end padding.
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960,
            /* finalPageGranulePosition= */ 1500,
            /* finalPagePacketCount= */ 2);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(3);
    assertThat(trackOutput.getSampleFlags(0) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
    assertThat(trackOutput.getSampleFlags(2) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(2);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(1380);
  }

  @Test
  public void
      read_opusWithFinalGranulePositionGreaterThanTotalGranules_doesNotAttachSupplementalData()
          throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ 2500);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
  }

  @Test
  public void read_opusWithMultiPacketFinalPage_attachesSupplementalDataOnlyToLastPacket()
      throws Exception {
    // Page 1 has 1 packet (0..960). Page 2 (EOS) has 2 packets (960..1920 and 1920..2880).
    // Final granule position 2700 means 180 granules of end padding on the last packet only.
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960,
            /* finalPageGranulePosition= */ 2700,
            /* finalPagePacketCount= */ 2);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(3);
    assertThat(trackOutput.getSampleFlags(0) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA).isEqualTo(0);
    assertThat(trackOutput.getSampleFlags(2) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(2);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(180);
  }

  @Test
  public void read_opusWithFinalGranulePositionEqualToLastPacketStart_trimsEntireLastPacket()
      throws Exception {
    byte[] data =
        createOggOpusStream(
            /* firstAudioPageGranulePosition= */ 960, /* finalPageGranulePosition= */ 960);

    FakeTrackOutput trackOutput = extractOpusTrack(data);

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(1);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(960);
  }

  @Test
  public void read_opusSeekLandingOnContinuedPage_syncsGranuleAndExtractsPadding()
      throws Exception {
    // TOC byte 0xF8 encodes 1 frame of 20ms (960 samples at 48kHz).
    byte[] opusAudioPacket = new byte[] {(byte) 0xF8};
    // A 255-byte segment has lace value 255, so Ogg treats it as an incomplete packet that
    // continues onto the next page.
    byte[] incompletePacketStart = new byte[255];
    incompletePacketStart[0] = (byte) 0xF8;
    byte[] incompletePacketContinuation = new byte[] {0x00};
    byte[] data =
        Bytes.concat(
            createOggPage(
                /* type= */ 0x02,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 0,
                createOpusIdHeader()),
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 1,
                opusCommentHeader),
            // Page 2 finishes packet 1 (at granule 960) and starts packet 2 (spanning onto page 3).
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 960,
                /* pageSequenceNumber= */ 2,
                opusAudioPacket,
                incompletePacketStart),
            // Page 3 (type=0x01 continued packet) finishes packet 2 (at 1920) and packet 3 (2880).
            createOggPage(
                /* type= */ 0x01,
                /* granulePosition= */ 2880,
                /* pageSequenceNumber= */ 3,
                incompletePacketContinuation,
                opusAudioPacket),
            // Page 4 (type=0x04 EOS) has packet 4 (960 granules -> 3840 total, 3660 -> 180 trim).
            createOggPage(
                /* type= */ 0x04,
                /* granulePosition= */ 3660,
                /* pageSequenceNumber= */ 4,
                opusAudioPacket));

    // Seek to 25ms (1200 granules), which skips page 2 (granule 960) and lands on continued page 3
    // (granule 2880) with startGranule = 960.
    FakeTrackOutput trackOutput = seekAndExtractOpusTrack(data, /* seekTimeUs= */ 25_000);

    // Packet 2 (continued on page 3) is discarded after seek; packets 3 and 4 are emitted.
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(40_000);
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(60_000);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(1);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(180);
  }

  @Test
  public void
      read_opusSeekIntoLastPacketOnContinuedPage_syncsGranuleBeforeTargetCheckAndEmitsPacket()
          throws Exception {
    // TOC byte 0xF8 encodes 1 frame of 20ms (960 samples at 48kHz).
    byte[] opusAudioPacket = new byte[] {(byte) 0xF8};
    byte[] incompletePacketStart = new byte[255];
    incompletePacketStart[0] = (byte) 0xF8;
    byte[] incompletePacketContinuation = new byte[] {0x00};
    byte[] data =
        Bytes.concat(
            createOggPage(
                /* type= */ 0x02,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 0,
                createOpusIdHeader()),
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 1,
                opusCommentHeader),
            // Page 2 finishes packet 1 (at granule 960) and starts packet 2 (spanning onto page 3).
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 960,
                /* pageSequenceNumber= */ 2,
                opusAudioPacket,
                incompletePacketStart),
            // Page 3 (type=0x01 continued packet) finishes packet 2 (at 1920) and packet 3 (2880).
            createOggPage(
                /* type= */ 0x01,
                /* granulePosition= */ 2880,
                /* pageSequenceNumber= */ 3,
                incompletePacketContinuation,
                opusAudioPacket),
            // Page 4 (type=0x04 EOS) has packet 4 (960 granules -> 3840 total, 3660 -> 180 trim).
            createOggPage(
                /* type= */ 0x04,
                /* granulePosition= */ 3660,
                /* pageSequenceNumber= */ 4,
                opusAudioPacket));

    // Seek to 45ms (2160 granules), which falls inside packet 3 (granules 1920..2880) on continued
    // page 3 (granule 2880) with startGranule = 960.
    FakeTrackOutput trackOutput = seekAndExtractOpusTrack(data, /* seekTimeUs= */ 45_000);

    // Packet 3 (1920..2880 >= 2160) must not be skipped because currentGranule is resynced to 1920
    // before checking targetGranule.
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(40_000);
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(60_000);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
  }

  @Test
  public void
      read_opusSeekLandingOnContinuedPageThatAlsoStartsNextPacket_syncsGranuleAndExtractsPadding()
          throws Exception {
    // TOC byte 0xF8 encodes 1 frame of 20ms (960 samples at 48kHz).
    byte[] opusAudioPacket = new byte[] {(byte) 0xF8};
    // A 255-byte segment has lace value 255, so Ogg treats it as an incomplete packet that
    // continues onto the next page.
    byte[] incompletePacketStart = new byte[255];
    incompletePacketStart[0] = (byte) 0xF8;
    byte[] incompletePacketContinuation = new byte[] {0x00};
    byte[] data =
        Bytes.concat(
            createOggPage(
                /* type= */ 0x02,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 0,
                createOpusIdHeader()),
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 1,
                opusCommentHeader),
            // Page 2 finishes packet 1 (at granule 960) and starts packet 2 (spanning onto page 3).
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 960,
                /* pageSequenceNumber= */ 2,
                opusAudioPacket,
                incompletePacketStart),
            // Page 3 (type=0x01 continued packet) finishes packet 2 (at 1920), finishes packet 3
            // (at 2880), and starts packet 4 (255-byte segment spanning onto page 4).
            createOggPage(
                /* type= */ 0x01,
                /* granulePosition= */ 2880,
                /* pageSequenceNumber= */ 3,
                incompletePacketContinuation,
                opusAudioPacket,
                incompletePacketStart),
            // Page 4 (type=0x05 continued + EOS) finishes packet 4 (960 granules -> 3840 total,
            // granulePosition 3660 -> 180 trim).
            createOggPage(
                /* type= */ 0x05,
                /* granulePosition= */ 3660,
                /* pageSequenceNumber= */ 4,
                incompletePacketContinuation));

    // Seek to 25ms (1200 granules), which skips page 2 (granule 960) and lands on continued page 3
    // (granule 2880).
    FakeTrackOutput trackOutput = seekAndExtractOpusTrack(data, /* seekTimeUs= */ 25_000);

    // Packet 2 (continued on page 3) is discarded after seek; packets 3 and 4 are emitted.
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(40_000);
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(60_000);
    assertThat(trackOutput.getSampleFlags(1) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(1);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(180);
  }

  @Test
  public void
      read_opusSeekLandingOnContinuedPageWithOnlyDiscardedPacketCompleted_syncsGranuleAndExtractsPadding()
          throws Exception {
    // TOC byte 0xF8 encodes 1 frame of 20ms (960 samples at 48kHz).
    byte[] opusAudioPacket = new byte[] {(byte) 0xF8};
    byte[] incompletePacketStart = new byte[255];
    incompletePacketStart[0] = (byte) 0xF8;
    byte[] incompletePacketContinuation = new byte[] {0x00};
    byte[] data =
        Bytes.concat(
            createOggPage(
                /* type= */ 0x02,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 0,
                createOpusIdHeader()),
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 0,
                /* pageSequenceNumber= */ 1,
                opusCommentHeader),
            // Page 2 finishes packet 1 (at granule 960) and starts packet 2 (spanning onto page 3).
            createOggPage(
                /* type= */ 0x00,
                /* granulePosition= */ 960,
                /* pageSequenceNumber= */ 2,
                opusAudioPacket,
                incompletePacketStart),
            // Page 3 (type=0x01 continued packet) ONLY finishes packet 2 (at 1920) and starts
            // packet 3 (255-byte segment spanning onto page 4).
            createOggPage(
                /* type= */ 0x01,
                /* granulePosition= */ 1920,
                /* pageSequenceNumber= */ 3,
                incompletePacketContinuation,
                incompletePacketStart),
            // Page 4 (type=0x05 continued + EOS) finishes packet 3 (960 granules -> 2880 total,
            // granulePosition 2700 -> 180 trim).
            createOggPage(
                /* type= */ 0x05,
                /* granulePosition= */ 2700,
                /* pageSequenceNumber= */ 4,
                incompletePacketContinuation));

    // Seek to 25ms (1200 granules), which skips page 2 (granule 960) and lands on continued page 3
    // (granule 1920). Packet 2 is discarded on page 3 and packet 3 finishes on page 4.
    FakeTrackOutput trackOutput = seekAndExtractOpusTrack(data, /* seekTimeUs= */ 25_000);

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(40_000);
    assertThat(trackOutput.getSampleFlags(0) & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        .isEqualTo(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    byte[] sampleData = trackOutput.getSampleData(0);
    long discardPaddingNs =
        ByteBuffer.wrap(sampleData, sampleData.length - 8, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
    int roundTripDiscardSamples =
        (int) ((discardPaddingNs * OpusUtil.SAMPLE_RATE) / C.NANOS_PER_SECOND);
    assertThat(roundTripDiscardSamples).isEqualTo(180);
  }

  @Test
  public void sniffVorbis() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/vorbis_header");
    assertSniff(data, /* expectedResult= */ true);
  }

  @Test
  public void sniffFlac() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/flac_header");
    assertSniff(data, /* expectedResult= */ true);
  }

  @Test
  public void sniffFailsOpusFile() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/opus_header");
    assertSniff(data, /* expectedResult= */ false);
  }

  @Test
  public void sniffFailsInvalidOggHeader() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/invalid_ogg_header");
    assertSniff(data, /* expectedResult= */ false);
  }

  @Test
  public void sniffInvalidHeader() throws Exception {
    byte[] data =
        getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/invalid_header");
    assertSniff(data, /* expectedResult= */ false);
  }

  @Test
  public void sniffFailsEOF() throws Exception {
    byte[] data = getByteArray(ApplicationProvider.getApplicationContext(), "media/ogg/eof_header");
    assertSniff(data, /* expectedResult= */ false);
  }

  private void assertSniff(byte[] data, boolean expectedResult) throws IOException {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(data)
            .setSimulateIOErrors(true)
            .setSimulateUnknownLength(true)
            .setSimulatePartialReads(true)
            .build();
    ExtractorAsserts.assertSniff(new OggExtractor(), input, expectedResult);
  }

  private static FakeTrackOutput extractOpusTrack(byte[] data) throws IOException {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(data).setSimulateUnknownLength(true).build();
    FakeExtractorOutput output = new FakeExtractorOutput();
    OggExtractor extractor = new OggExtractor();
    extractor.init(output);
    PositionHolder positionHolder = new PositionHolder();
    while (true) {
      int result = extractor.read(input, positionHolder);
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      } else if (result == Extractor.RESULT_END_OF_INPUT) {
        break;
      }
    }
    return output.trackOutputs.get(0);
  }

  private static FakeTrackOutput seekAndExtractOpusTrack(byte[] data, long seekTimeUs)
      throws IOException {
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    FakeExtractorOutput output = new FakeExtractorOutput();
    OggExtractor extractor = new OggExtractor();
    extractor.init(output);
    PositionHolder positionHolder = new PositionHolder();
    while (output.seekMap == null) {
      int result = extractor.read(input, positionHolder);
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
    extractor.seek(/* position= */ 1, seekTimeUs);
    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    trackOutput.clear();
    while (true) {
      int result = extractor.read(input, positionHolder);
      if (result == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      } else if (result == Extractor.RESULT_END_OF_INPUT) {
        break;
      }
    }
    return trackOutput;
  }

  private static byte[] createOpusIdHeader() {
    return ByteBuffer.allocate(19)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(new byte[] {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'})
        .put((byte) 1) // version
        .put((byte) 2) // channelCount
        .putShort((short) 0) // preSkip
        .putInt(OpusUtil.SAMPLE_RATE)
        .putShort((short) 0) // outputGain
        .put((byte) 0) // channelMappingFamily
        .array();
  }

  private static byte[] createOggOpusStream(
      long firstAudioPageGranulePosition, long finalPageGranulePosition) {
    return createOggOpusStream(
        firstAudioPageGranulePosition, finalPageGranulePosition, /* finalPagePacketCount= */ 1);
  }

  private static byte[] createOggOpusStream(
      long firstAudioPageGranulePosition, long finalPageGranulePosition, int finalPagePacketCount) {
    // TOC byte 0xF8 encodes 1 frame of 20ms (960 samples at 48kHz).
    byte[] opusAudioPacket = new byte[] {(byte) 0xF8};
    byte[][] finalPagePackets = new byte[finalPagePacketCount][];
    Arrays.fill(finalPagePackets, opusAudioPacket);
    return Bytes.concat(
        createOggPage(
            /* type= */ 0x02,
            /* granulePosition= */ 0,
            /* pageSequenceNumber= */ 0,
            createOpusIdHeader()),
        createOggPage(
            /* type= */ 0x00,
            /* granulePosition= */ 0,
            /* pageSequenceNumber= */ 1,
            opusCommentHeader),
        createOggPage(
            /* type= */ 0x00,
            /* granulePosition= */ firstAudioPageGranulePosition,
            /* pageSequenceNumber= */ 2,
            opusAudioPacket),
        createOggPage(
            /* type= */ 0x04,
            /* granulePosition= */ finalPageGranulePosition,
            /* pageSequenceNumber= */ 3,
            finalPagePackets));
  }

  private static byte[] createOggPage(
      int type, long granulePosition, int pageSequenceNumber, byte[]... packets) {
    int totalPayloadLength = 0;
    for (byte[] packet : packets) {
      totalPayloadLength += packet.length;
    }
    ByteBuffer buffer =
        ByteBuffer.allocate(27 + packets.length + totalPayloadLength)
            .order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(new byte[] {'O', 'g', 'g', 'S'});
    buffer.put((byte) 0); // revision
    buffer.put((byte) type);
    buffer.putLong(granulePosition);
    buffer.putInt(1); // streamSerialNumber
    buffer.putInt(pageSequenceNumber);
    buffer.putInt(0); // pageChecksum
    buffer.put((byte) packets.length); // pageSegmentCount
    for (byte[] packet : packets) {
      buffer.put((byte) packet.length);
    }
    for (byte[] packet : packets) {
      buffer.put(packet);
    }
    return buffer.array();
  }
}
