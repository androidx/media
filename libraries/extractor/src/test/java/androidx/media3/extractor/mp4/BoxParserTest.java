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
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.BoxUtils;
import androidx.media3.container.Mp4Box;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4TrackReferenceData;
import androidx.media3.container.OpusUtil;
import androidx.media3.extractor.GaplessInfoHolder;
import androidx.media3.extractor.metadata.Chapter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.ImmutableLongArray;
import com.google.common.primitives.Ints;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link BoxParser}. */
@RunWith(AndroidJUnit4.class)
public final class BoxParserTest {

  private static final String ATOM_HEADER = "000000000000000000000000";
  private static final String SAMPLE_COUNT = "00000004";
  private static final byte[] FOUR_BIT_STZ2 =
      Util.getBytesFromHexString(ATOM_HEADER + "00000004" + SAMPLE_COUNT + "1234");
  private static final byte[] EIGHT_BIT_STZ2 =
      Util.getBytesFromHexString(ATOM_HEADER + "00000008" + SAMPLE_COUNT + "01020304");
  private static final byte[] SIXTEEN_BIT_STZ2 =
      Util.getBytesFromHexString(ATOM_HEADER + "00000010" + SAMPLE_COUNT + "0001000200030004");

  // Sample 'vexu' with 'eyes' containing 'stri' along with other optional boxes.
  private static final byte[] VEXU_DATA0 =
      new byte[] {
        // size (101), 'vexu'
        0,
        0,
        0,
        101,
        118,
        101,
        120,
        117,
        // size (69), 'eyes'
        0,
        0,
        0,
        69,
        101,
        121,
        101,
        115,
        // size (13), 'stri'
        0,
        0,
        0,
        13,
        115,
        116,
        114,
        105,
        0,
        0,
        0,
        0,
        3,
        // size (24), 'cams'
        0,
        0,
        0,
        24,
        99,
        97,
        109,
        115,
        0,
        0,
        0,
        16,
        98,
        108,
        105,
        110,
        0,
        0,
        0,
        0,
        0,
        0,
        75,
        40,
        // size (24), 'cmfy'
        0,
        0,
        0,
        24,
        99,
        109,
        102,
        121,
        0,
        0,
        0,
        16,
        100,
        97,
        100,
        106,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        -56,
        // size (24), 'proj'
        0,
        0,
        0,
        24,
        112,
        114,
        111,
        106,
        0,
        0,
        0,
        16,
        112,
        114,
        106,
        105,
        0,
        0,
        0,
        0,
        114,
        101,
        99,
        116,
      };
  // Sample 'vexu' with the use of 'must' to list required boxes.
  private static final byte[] VEXU_DATA1 =
      new byte[] {
        // size (78), 'vexu'
        0,
        0,
        0,
        78,
        118,
        101,
        120,
        117,
        // size (16), 'must' --> requires 'eyes'
        0,
        0,
        0,
        16,
        109,
        117,
        115,
        116,
        0,
        0,
        0,
        0,
        101,
        121,
        101,
        115,
        // size (54), 'eyes'
        0,
        0,
        0,
        54,
        101,
        121,
        101,
        115,
        // size (20), 'must' --> requires 'stri' and 'hero'
        0,
        0,
        0,
        20,
        109,
        117,
        115,
        116,
        0,
        0,
        0,
        0,
        115,
        116,
        114,
        105,
        104,
        101,
        114,
        111,
        // size (13), 'stri'
        0,
        0,
        0,
        13,
        115,
        116,
        114,
        105,
        0,
        0,
        0,
        0,
        3,
        // size (13), 'hero'
        0,
        0,
        0,
        13,
        104,
        101,
        114,
        111,
        0,
        0,
        0,
        0,
        1,
      };

  @Test
  public void parseCommonEncryptionSinfFromParentIgnoresUnknownSchemeType() throws ParserException {
    byte[] cencSinf =
        new byte[] {
          0, 0, 0, 24, 115, 105, 110, 102, // size (4), 'sinf' (4)
          0, 0, 0, 16, 115, 99, 104, 109, // size (4), 'schm' (4)
          0, 0, 0, 0, 88, 88, 88, 88
        }; // version (1), flags (3), 'xxxx' (4)
    assertThat(
            BoxParser.parseCommonEncryptionSinfFromParent(
                new ParsableByteArray(cencSinf), 0, cencSinf.length))
        .isNull();
  }

  @Test
  public void stz2Parsing4BitFieldSize() {
    verifyStz2Parsing(new Mp4Box.LeafBox(Mp4Box.TYPE_stsz, new ParsableByteArray(FOUR_BIT_STZ2)));
  }

  @Test
  public void stz2Parsing8BitFieldSize() {
    verifyStz2Parsing(new Mp4Box.LeafBox(Mp4Box.TYPE_stsz, new ParsableByteArray(EIGHT_BIT_STZ2)));
  }

  @Test
  public void stz2Parsing16BitFieldSize() {
    verifyStz2Parsing(
        new Mp4Box.LeafBox(Mp4Box.TYPE_stsz, new ParsableByteArray(SIXTEEN_BIT_STZ2)));
  }

  @Test
  public void vexuParsings() throws ParserException {
    BoxParser.VexuData vexuData = null;
    assertThat(
            vexuData =
                BoxParser.parseVideoExtendedUsageBox(
                    new ParsableByteArray(VEXU_DATA0), 0, VEXU_DATA0.length))
        .isNotNull();
    assertThat(vexuData).isNotNull();
    assertThat(vexuData.hasBothEyeViews()).isTrue();
    assertThat(
            vexuData =
                BoxParser.parseVideoExtendedUsageBox(
                    new ParsableByteArray(VEXU_DATA1), 0, VEXU_DATA1.length))
        .isNotNull();
    assertThat(vexuData).isNotNull();
    assertThat(vexuData.hasBothEyeViews()).isTrue();
  }

  @Test
  public void chplParsing() {
    byte[] data =
        ByteBuffer.allocate(100)
            .put(new byte[5]) // version (1), flags (3), reserved byte (1)
            .putInt(3) // chapter count
            // first chapter
            .putLong(5_000_000) // start time in 100-nanoseconds resolution
            .put((byte) 9) // Title length
            .put("Chapter 1".getBytes(UTF_8))
            // second chapter
            .putLong(10_000_000) // start time in 100-nanoseconds resolution
            .put((byte) 9) // Title length
            .put("Chapter 2".getBytes(UTF_8))
            // third chapter
            .putLong(30_000_000_000L) // start time in 100-nanoseconds resolution
            .put((byte) 9) // Title length
            .put("Chapter 3".getBytes(UTF_8))
            .array();

    Metadata metadata = BoxParser.parseChpl(new ParsableByteArray(data));

    assertThat(metadata.length()).isEqualTo(3);
    Chapter chapter1 = (Chapter) metadata.get(0);
    assertThat(chapter1.getStartTimeMs()).isEqualTo(500);
    assertThat(chapter1.getEndTimeMs()).isEqualTo(C.TIME_UNSET);
    assertThat(chapter1.getTitle().value).isEqualTo("Chapter 1");
    Chapter chapter2 = (Chapter) metadata.get(1);
    assertThat(chapter2.getStartTimeMs()).isEqualTo(1000);
    assertThat(chapter2.getEndTimeMs()).isEqualTo(C.TIME_UNSET);
    assertThat(chapter2.getTitle().value).isEqualTo("Chapter 2");
    Chapter chapter3 = (Chapter) metadata.get(2);
    assertThat(chapter3.getStartTimeMs()).isEqualTo(3_000_000);
    assertThat(chapter3.getEndTimeMs()).isEqualTo(C.TIME_UNSET);
    assertThat(chapter3.getTitle().value).isEqualTo("Chapter 3");
  }

  @Test
  public void parseChpl_negativeStartTime_returnsTimeUnset() {
    byte[] data =
        ByteBuffer.allocate(50)
            .put(new byte[5]) // version (1), flags (3), reserved byte (1)
            .putInt(1) // chapter count
            // first chapter
            .putLong(-10_000L) // start time in 100-nanoseconds resolution
            .put((byte) 9) // Title length
            .put("Chapter 1".getBytes(UTF_8))
            .array();

    Metadata metadata = BoxParser.parseChpl(new ParsableByteArray(data));

    assertThat(metadata.length()).isEqualTo(1);
    Chapter chapter = (Chapter) metadata.get(0);
    assertThat(chapter.getStartTimeMs()).isEqualTo(C.TIME_UNSET);
    assertThat(chapter.getEndTimeMs()).isEqualTo(C.TIME_UNSET);
    assertThat(chapter.getTitle().value).isEqualTo("Chapter 1");
  }

  @Test
  public void parseChpl_truncatedData_returnsNull() {
    byte[] data =
        ByteBuffer.allocate(23)
            .put(new byte[5]) // version (1), flags (3), reserved byte (1)
            .putInt(2) // Claims 2 chapters, but only has data for 1
            .putLong(1000)
            .put((byte) 5)
            .put("Title".getBytes(UTF_8))
            .array();

    Metadata metadata = BoxParser.parseChpl(new ParsableByteArray(data));
    assertThat(metadata).isNull();
  }

  @Test
  public void parseXyz_withTwoCoordinates_returnsLocationData() {
    ParsableByteArray xyzBox = createXyzBox("+35.1345-15.1020/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata)
        .isEqualTo(
            new Metadata(
                new Mp4LocationData(/* latitude= */ 35.1345f, /* longitude= */ -15.1020f)));
  }

  @Test
  public void parseXyz_withThreeCoordinates_returnsLocationDataWithAltitude() {
    ParsableByteArray xyzBox = createXyzBox("+37.7749-122.4194+15.0000/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata)
        .isEqualTo(
            new Metadata(
                new Mp4LocationData(
                    /* latitude= */ 37.7749f, /* longitude= */ -122.4194f, /* altitude= */ 15.0f)));
  }

  @Test
  public void parseXyz_withNegativeAltitudeAndCrs_returnsLocationData() {
    ParsableByteArray xyzBox = createXyzBox("-12.3456+78.9012-100.5CRSWGS_84/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata)
        .isEqualTo(
            new Metadata(
                new Mp4LocationData(
                    /* latitude= */ -12.3456f,
                    /* longitude= */ 78.9012f,
                    /* altitude= */ -100.5f)));
  }

  @Test
  public void parseXyz_withTwoCoordinatesAndCrs_returnsLocationData() {
    ParsableByteArray xyzBox = createXyzBox("+37.7749-122.4194CRSWGS_84/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata)
        .isEqualTo(
            new Metadata(
                new Mp4LocationData(/* latitude= */ 37.7749f, /* longitude= */ -122.4194f)));
  }

  @Test
  public void parseXyz_withInvalidFormat_returnsNull() {
    ParsableByteArray xyzBox = createXyzBox("invalid-location");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata).isNull();
  }

  @Test
  public void parseXyz_withCoordinatesOutOfRange_returnsNull() {
    ParsableByteArray xyzBox = createXyzBox("+95.0000-122.4194/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata).isNull();
  }

  @Test
  public void parseXyz_withoutTerminatingSlash_returnsNull() {
    ParsableByteArray xyzBox = createXyzBox("+37.7749-122.4194+15.0000");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata).isNull();
  }

  @Test
  public void parseXyz_withExtraCoordinates_returnsNull() {
    ParsableByteArray xyzBox = createXyzBox("+10-20+30-40/");

    Metadata metadata = BoxParser.parseXyz(xyzBox);

    assertThat(metadata).isNull();
  }

  @Test
  public void parseStbl_withEmptyEditAndOmitTrackSampleTable_includesEmptyEditInDuration()
      throws Exception {
    Track track =
        new Track.Builder()
            .setTimescale(1_000)
            .setMovieTimescale(1_000)
            .setFormat(new Format.Builder().build())
            .setEditListDurations(ImmutableLongArray.of(200, 800))
            .setEditListMediaTimes(ImmutableLongArray.of(-1, 0))
            .build();
    Mp4Box.ContainerBox stblBox = new Mp4Box.ContainerBox(Mp4Box.TYPE_stbl, /* endPosition= */ 0);
    stblBox.add(createFullBox(Mp4Box.TYPE_stsz, 100, 1)); // sample_size, sample_count
    stblBox.add(createFullBox(Mp4Box.TYPE_stco, 1, 0)); // entry_count, chunk_offset
    stblBox.add(createFullBox(Mp4Box.TYPE_stsc, 1, 1, 1, 1)); // count, chunk, samples, desc_index
    stblBox.add(createFullBox(Mp4Box.TYPE_stts, 1, 1, 800)); // entry_count, sample_count, delta

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(
            track, stblBox, new GaplessInfoHolder(), /* omitTrackSampleTable= */ true);

    assertThat(sampleTable.durationUs).isEqualTo(1_000_000);
  }

  @Test
  public void parseStbl_withAacEditListAndShortenedLastSample_populatesEncoderPadding()
      throws Exception {
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 132_357);
    // (sample_count, delta) x2: 130 full frames and a last frame shortened to 261.
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 131, 130, 1_024, 1, 261);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(131);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(763);
  }

  @Test
  public void parseStbl_withAacShortenedLastSampleAndCoarseMovieTimescale_populatesExactPadding()
      throws Exception {
    // FFmpeg writes a 1000 Hz movie timescale by default, so the edit list duration of 3001 ms only
    // approximates the 132357 samples of the track.
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 1_000,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 3_001);
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 131, 130, 1_024, 1, 261);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(131);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(763);
  }

  @Test
  public void parseStbl_withAacShortenedLastSampleAndShorterEditList_includesEditTrimInPadding()
      throws Exception {
    // The edit list ends one full frame before the shortened last sample.
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 131_333);
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 131, 130, 1_024, 1, 261);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(131);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(763 + 1_024);
  }

  @Test
  public void parseStbl_withAacEditListAndTimestampGapBeforeLastSample_doesNotAddGapToPadding()
      throws Exception {
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 3_072);
    // (sample_count, delta) x3: 1024, 2048 (dropped frame gap), 1024.
    Mp4Box.ContainerBox stblBox =
        createAudioStblBox(/* sampleCount= */ 3, 1, 1_024, 1, 2_048, 1, 1_024);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(3);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(0);
  }

  @Test
  public void parseStbl_withAacEditListAndOnlyTwoSamples_doesNotAdjustEncoderPadding()
      throws Exception {
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 2_048);
    // (sample_count, delta) x2: 2048 (dropped frame gap), 1024. With only two samples there is no
    // way to tell a shortened last sample from a gap before it.
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 2, 1, 2_048, 1, 1_024);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(2);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(0);
  }

  @Test
  public void parseStbl_withAacEditListAndLongerLastSample_doesNotAdjustEncoderPadding()
      throws Exception {
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 3_524);
    // (sample_count, delta) x2: two full frames and a last sample longer than a frame.
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 3, 2, 1_024, 1, 2_500);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(3);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(0);
  }

  @Test
  public void parseStbl_withAacEditListEndingSlightlyAfterLastSample_clampsEncoderPaddingToZero()
      throws Exception {
    // The edit list ends 1 timescale unit after the track, within the rounding tolerance.
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_AAC,
            /* timescale= */ 44_100,
            /* movieTimescale= */ 44_100,
            /* editListMediaTime= */ 1_024,
            /* editListDuration= */ 2_049);
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 3, 3, 1_024);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(3);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(1_024);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(0);
  }

  @Test
  public void parseStbl_withNonAacEditListAndShortenedLastSample_doesNotAdjustEncoderPadding()
      throws Exception {
    Track track =
        createAudioTrack(
            MimeTypes.AUDIO_OPUS,
            /* timescale= */ 48_000,
            /* movieTimescale= */ 48_000,
            /* editListMediaTime= */ 312,
            /* editListDuration= */ 2_088);
    // (sample_count, delta) x2: two full 20 ms frames and a last frame shortened to 480.
    Mp4Box.ContainerBox stblBox = createAudioStblBox(/* sampleCount= */ 3, 2, 960, 1, 480);
    GaplessInfoHolder gaplessInfoHolder = new GaplessInfoHolder();

    TrackSampleTable sampleTable =
        BoxParser.parseStbl(track, stblBox, gaplessInfoHolder, /* omitTrackSampleTable= */ false);

    assertThat(sampleTable.sampleCount).isEqualTo(3);
    assertThat(gaplessInfoHolder.encoderDelay).isEqualTo(312);
    assertThat(gaplessInfoHolder.encoderPadding).isEqualTo(0);
  }

  private static Track createAudioTrack(
      String sampleMimeType,
      int timescale,
      int movieTimescale,
      long editListMediaTime,
      long editListDuration) {
    return new Track.Builder()
        .setType(C.TRACK_TYPE_AUDIO)
        .setTimescale(timescale)
        .setMovieTimescale(movieTimescale)
        .setFormat(
            new Format.Builder().setSampleMimeType(sampleMimeType).setSampleRate(timescale).build())
        .setEditListDurations(ImmutableLongArray.of(editListDuration))
        .setEditListMediaTimes(ImmutableLongArray.of(editListMediaTime))
        .build();
  }

  /**
   * Creates an stbl box with {@code sampleCount} samples in a single chunk, where {@code
   * sttsEntries} are (sample_count, sample_delta) pairs.
   */
  private static Mp4Box.ContainerBox createAudioStblBox(int sampleCount, int... sttsEntries) {
    Mp4Box.ContainerBox stblBox = new Mp4Box.ContainerBox(Mp4Box.TYPE_stbl, /* endPosition= */ 0);
    stblBox.add(createFullBox(Mp4Box.TYPE_stsz, 100, sampleCount)); // sample_size, sample_count
    stblBox.add(createFullBox(Mp4Box.TYPE_stco, 1, 0)); // entry_count, chunk_offset
    // entry_count, first_chunk, samples_per_chunk, sample_description_index
    stblBox.add(createFullBox(Mp4Box.TYPE_stsc, 1, 1, sampleCount, 1));
    stblBox.add(
        createFullBox(
            Mp4Box.TYPE_stts, Ints.concat(new int[] {sttsEntries.length / 2}, sttsEntries)));
    return stblBox;
  }

  private static Mp4Box.LeafBox createFullBox(int type, int... fields) {
    ByteBuffer buffer =
        ByteBuffer.allocate(Mp4Box.FULL_HEADER_SIZE + fields.length * Integer.BYTES)
            .put(new byte[Mp4Box.FULL_HEADER_SIZE]);
    for (int field : fields) {
      buffer.putInt(field);
    }
    return new Mp4Box.LeafBox(type, new ParsableByteArray(buffer.array()));
  }

  @Test
  public void parseTrak_withOpusDopsBox_convertsDopsToLittleEndianOpusHead()
      throws ParserException {
    byte[] dOpsBody =
        ByteBuffer.allocate(11)
            .order(ByteOrder.BIG_ENDIAN)
            .put((byte) 0) // Version
            .put((byte) 2) // OutputChannelCount
            .putShort((short) 312) // PreSkip
            .putInt(48_000) // InputSampleRate
            .putShort((short) 258) // OutputGain
            .put((byte) 0) // ChannelMappingFamily
            .array();
    byte[] expectedOpusHead =
        ByteBuffer.allocate(19)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".getBytes(UTF_8))
            .put((byte) 1) // Version
            .put((byte) 2) // OutputChannelCount
            .putShort((short) 312) // PreSkip
            .putInt(48_000) // InputSampleRate
            .putShort((short) 258) // OutputGain
            .put((byte) 0) // ChannelMappingFamily
            .array();
    Mp4Box.ContainerBox trak = createOpusTrakWithDopsBox(dOpsBody);
    Mp4Box.LeafBox mvhd =
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_mvhd,
            new ParsableByteArray(ByteBuffer.allocate(24).putInt(/* index= */ 20, 1000).array()));

    Track track =
        BoxParser.parseTrak(
            trak,
            mvhd,
            /* duration= */ C.TIME_UNSET,
            /* drmInitData= */ null,
            /* ignoreEditLists= */ false,
            /* isQuickTime= */ false);

    assertThat(track).isNotNull();
    assertThat(track.format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_OPUS);
    assertThat(track.format.initializationData).hasSize(3);
    byte[] opusHead = track.format.initializationData.get(0);
    assertThat(opusHead).isEqualTo(expectedOpusHead);
    assertThat(OpusUtil.getPreSkipSamples(opusHead)).isEqualTo(312);
    long preSkipNanos =
        ByteBuffer.wrap(track.format.initializationData.get(1))
            .order(ByteOrder.nativeOrder())
            .getLong();
    assertThat(preSkipNanos).isEqualTo(6_500_000L);
  }

  @Test
  public void parseTrak_withTruncatedOpusDopsBox_throwsParserException() {
    byte[] truncatedDopsBody = new byte[10]; // Minimum valid dOps payload size is 11 bytes.
    Mp4Box.ContainerBox trak = createOpusTrakWithDopsBox(truncatedDopsBody);
    Mp4Box.LeafBox mvhd =
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_mvhd,
            new ParsableByteArray(ByteBuffer.allocate(24).putInt(/* index= */ 20, 1000).array()));

    assertThrows(
        ParserException.class,
        () ->
            BoxParser.parseTrak(
                trak,
                mvhd,
                /* duration= */ C.TIME_UNSET,
                /* drmInitData= */ null,
                /* ignoreEditLists= */ false,
                /* isQuickTime= */ false));
  }

  private static Mp4Box.ContainerBox createOpusTrakWithDopsBox(byte[] dOpsBody) {
    int dOpsBoxSize = Mp4Box.HEADER_SIZE + dOpsBody.length;
    int opusEntrySize = Mp4Box.HEADER_SIZE + 8 + 20 + dOpsBoxSize;
    int stsdBoxSize = Mp4Box.FULL_HEADER_SIZE + 4 + opusEntrySize;
    byte[] stsdBytes =
        ByteBuffer.allocate(stsdBoxSize)
            .putInt(stsdBoxSize)
            .putInt(Mp4Box.TYPE_stsd)
            .putInt(0) // version and flags
            .putInt(1) // numberOfEntries
            .putInt(opusEntrySize)
            .putInt(Mp4Box.TYPE_Opus)
            .put(new byte[6]) // reserved
            .putShort((short) 1) // data_reference_index
            .put(new byte[8]) // reserved
            .putShort((short) 2) // channelCount
            .putShort((short) 16) // sampleSize
            .putShort((short) 0) // compressionId
            .putShort((short) 0) // packetSize
            .putInt(48_000 << 16) // sampleRate (16.16 fixed-point)
            .putInt(dOpsBoxSize)
            .putInt(Mp4Box.TYPE_dOps)
            .put(dOpsBody)
            .array();
    Mp4Box.ContainerBox stbl = new Mp4Box.ContainerBox(Mp4Box.TYPE_stbl, /* endPosition= */ 0);
    stbl.add(new Mp4Box.LeafBox(Mp4Box.TYPE_stsd, new ParsableByteArray(stsdBytes)));
    Mp4Box.ContainerBox minf = new Mp4Box.ContainerBox(Mp4Box.TYPE_minf, /* endPosition= */ 0);
    minf.add(stbl);
    Mp4Box.ContainerBox mdia = new Mp4Box.ContainerBox(Mp4Box.TYPE_mdia, /* endPosition= */ 0);
    mdia.add(
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_hdlr,
            new ParsableByteArray(
                ByteBuffer.allocate(20).putInt(/* index= */ 16, 0x736f756e).array())));
    mdia.add(
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_mdhd,
            new ParsableByteArray(
                ByteBuffer.allocate(32).putInt(/* index= */ 20, 48_000).array())));
    mdia.add(minf);
    Mp4Box.ContainerBox trak = new Mp4Box.ContainerBox(Mp4Box.TYPE_trak, /* endPosition= */ 0);
    trak.add(
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_tkhd,
            new ParsableByteArray(ByteBuffer.allocate(92).putInt(/* index= */ 20, 1).array())));
    trak.add(mdia);
    return trak;
  }

  private static ParsableByteArray createXyzBox(String locationString) {
    byte[] stringBytes = locationString.getBytes(UTF_8);
    byte[] data =
        ByteBuffer.allocate(2 + 2 + stringBytes.length)
            .putShort((short) stringBytes.length)
            .putShort((short) 0x15C7) // language code
            .put(stringBytes)
            .array();
    return new ParsableByteArray(data);
  }

  private static void verifyStz2Parsing(Mp4Box.LeafBox stz2Atom) {
    BoxParser.Stz2SampleSizeBox box = new BoxParser.Stz2SampleSizeBox(stz2Atom);
    assertThat(box.getSampleCount()).isEqualTo(4);
    assertThat(box.getFixedSampleSize()).isEqualTo(C.LENGTH_UNSET);
    for (int i = 0; i < box.getSampleCount(); i++) {
      assertThat(box.readNextSampleSize()).isEqualTo(i + 1);
    }
  }

  @Test
  public void maybeConvertMebxTrackToIt35_withMultipleKeys_selectsHagcIt35Key() {
    byte[] nonIt35Key =
        createMebxKeyEntry(
            /* localKeyId= */ "met1",
            /* keyNamespace= */ 0x6d647461, // 'mdta'
            /* keyValue= */ 0x74657374,
            /* setuPayload= */ null);
    byte[] shortIt35Prefix = new byte[] {(byte) 0xB5};
    byte[] shortIt35Key =
        createMebxKeyEntry(
            /* localKeyId= */ "shrt",
            /* keyNamespace= */ 0x6d653463, // 'me4c'
            /* keyValue= */ Mp4Box.TYPE_it35,
            createSetuPayload(shortIt35Prefix));
    byte[] nonHagcIt35Prefix = new byte[] {(byte) 0xB5, 0x00, 0x31, 0x00, 0x01};
    byte[] nonHagcIt35Key =
        createMebxKeyEntry(
            /* localKeyId= */ "h10p",
            /* keyNamespace= */ 0x6d653463, // 'me4c'
            /* keyValue= */ Mp4Box.TYPE_it35,
            createSetuPayload(nonHagcIt35Prefix));
    byte[] hagcPrefix = new byte[] {(byte) 0xB5, 0x00, (byte) 0x90, 0x00, 0x01};
    byte[] hrsdPayload = "SMPTE-ST2094-50\0".getBytes(UTF_8);
    byte[] hagcSetuPayloadWithHrsd =
        Bytes.concat(createSetuPayload(hagcPrefix), BoxUtils.wrapIntoBox("hrsd", hrsdPayload));
    byte[] hagcIt35Key =
        createMebxKeyEntry(
            /* localKeyId= */ "hagc",
            /* keyNamespace= */ 0x6d653463, // 'me4c'
            /* keyValue= */ Mp4Box.TYPE_it35,
            hagcSetuPayloadWithHrsd);
    byte[] keysPayload =
        ByteBuffer.allocate(
                nonIt35Key.length
                    + shortIt35Key.length
                    + nonHagcIt35Key.length
                    + hagcIt35Key.length)
            .put(nonIt35Key)
            .put(shortIt35Key)
            .put(nonHagcIt35Key)
            .put(hagcIt35Key)
            .array();
    Track mebxTrack =
        new Track.Builder()
            .setId(2)
            .setType(C.TRACK_TYPE_METADATA)
            .setFormat(
                new Format.Builder()
                    .setId(2)
                    .setSampleMimeType(MimeTypes.APPLICATION_MEBX)
                    .setInitializationData(ImmutableList.of(keysPayload))
                    .build())
            .build();

    Track convertedTrack = BoxParser.maybeConvertMebxTrackToIt35(mebxTrack);

    assertThat(convertedTrack).isNotNull();
    assertThat(convertedTrack.format.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_ITUT_T35);
    assertThat(convertedTrack.format.initializationData.get(0)).isEqualTo(hagcPrefix);
    assertThat(convertedTrack.sampleTransformation).isEqualTo(Track.TRANSFORMATION_MEBX_UNBOX);
    assertThat(convertedTrack.mebxKeyId).isEqualTo(0x68616763); // 'hagc'
  }

  @Test
  public void positionAtMebxBoxPayload_withMultiplexedSample_positionsAtMatchingPayload() {
    byte[] otherPayload = new byte[] {0x01, 0x02, 0x03, 0x04};
    byte[] targetPayload = new byte[] {0x10, 0x20, 0x30};
    byte[] trailingPayload = new byte[] {0x7F};
    byte[] sampleBytes =
        Bytes.concat(
            BoxUtils.wrapIntoBox("met1", otherPayload),
            BoxUtils.wrapIntoBox("hagc", targetPayload),
            BoxUtils.wrapIntoBox("met2", trailingPayload));
    ParsableByteArray sampleData = new ParsableByteArray(sampleBytes);

    boolean found = BoxParser.positionAtMebxBoxPayload(sampleData, /* targetKeyId= */ 0x68616763);

    assertThat(found).isTrue();
    byte[] extracted = new byte[sampleData.bytesLeft()];
    sampleData.readBytes(extracted, /* offset= */ 0, extracted.length);
    assertThat(extracted).isEqualTo(targetPayload);
  }

  @Test
  public void parseTrak_withCdscAndRndrTrackReferences_populatesFormatMetadata()
      throws ParserException {
    byte[] dOpsBody =
        ByteBuffer.allocate(11)
            .order(ByteOrder.BIG_ENDIAN)
            .put((byte) 0) // Version
            .put((byte) 2) // OutputChannelCount
            .putShort((short) 312) // PreSkip
            .putInt(48_000) // InputSampleRate
            .putShort((short) 258) // OutputGain
            .put((byte) 0) // ChannelMappingFamily
            .array();
    Mp4Box.ContainerBox trak = createOpusTrakWithDopsBox(dOpsBody);
    Mp4Box.ContainerBox tref = new Mp4Box.ContainerBox(Mp4Box.TYPE_tref, /* endPosition= */ 0);
    byte[] cdscBytes = BoxUtils.wrapIntoBox("cdsc", Ints.toByteArray(2));
    byte[] rndrBytes = BoxUtils.wrapIntoBox("rndr", Ints.toByteArray(1), Ints.toByteArray(3));
    tref.add(new Mp4Box.LeafBox(Mp4Box.TYPE_cdsc, new ParsableByteArray(cdscBytes)));
    tref.add(new Mp4Box.LeafBox(Mp4Box.TYPE_rndr, new ParsableByteArray(rndrBytes)));
    trak.add(tref);
    Mp4Box.LeafBox mvhd =
        new Mp4Box.LeafBox(
            Mp4Box.TYPE_mvhd,
            new ParsableByteArray(ByteBuffer.allocate(24).putInt(/* index= */ 20, 1000).array()));

    Track track =
        BoxParser.parseTrak(
            trak,
            mvhd,
            /* duration= */ C.TIME_UNSET,
            /* drmInitData= */ null,
            /* ignoreEditLists= */ false,
            /* isQuickTime= */ false);

    assertThat(track).isNotNull();
    assertThat(track.format.metadata).isNotNull();
    assertThat(track.format.metadata.getEntriesOfType(Mp4TrackReferenceData.class))
        .containsExactly(
            new Mp4TrackReferenceData(Mp4Box.TYPE_cdsc, ImmutableList.of(2)),
            new Mp4TrackReferenceData(Mp4Box.TYPE_rndr, ImmutableList.of(1, 3)))
        .inOrder();
  }

  @Test
  public void parseTrackReference_withEmptyPayload_returnsNull() {
    byte[] emptyRndrBytes = BoxUtils.wrapIntoBox("rndr");
    Mp4Box.LeafBox rndrBox =
        new Mp4Box.LeafBox(Mp4Box.TYPE_rndr, new ParsableByteArray(emptyRndrBytes));

    Mp4TrackReferenceData trackReference = BoxParser.parseTrackReference(rndrBox);

    assertThat(trackReference).isNull();
  }

  private static byte[] createSetuPayload(byte[] identifier) {
    return ByteBuffer.allocate(1 + identifier.length)
        .put((byte) identifier.length)
        .put(identifier)
        .array();
  }

  private static byte[] createMebxKeyEntry(
      String localKeyId, int keyNamespace, int keyValue, byte @Nullable [] setuPayload) {
    byte[] keydBox =
        BoxUtils.wrapIntoBox("keyd", Ints.toByteArray(keyNamespace), Ints.toByteArray(keyValue));
    byte[] setuBox = setuPayload != null ? BoxUtils.wrapIntoBox("setu", setuPayload) : new byte[0];
    return BoxUtils.wrapIntoBox(localKeyId, keydBox, setuBox);
  }
}
