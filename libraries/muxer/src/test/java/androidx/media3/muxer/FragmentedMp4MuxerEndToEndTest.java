/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.muxer;

import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** End to end instrumentation tests for {@link FragmentedMp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public class FragmentedMp4MuxerEndToEndTest {
  private static final String H264_MP4 = "mp4/sample_no_bframes.mp4";
  private static final String H265_HDR10_MP4 = "mp4/hdr10-720p.mp4";
  private static final String AV1_MP4 = "mp4/sample_av1.mp4";
  private static final String AUDIO_ONLY_MP4 = "mp4/sample_audio_only_15s.mp4";

  public static final String MEDIA_ASSET_DIRECTORY = "asset:///media/";

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  // TODO: b/507292304 - Suppressed due to failure on SDK 23.
  @Config(minSdk = 24)
  public void createFragmentedMp4File_fromInputFileSampleData_matchesExpectedBoxStructure()
      throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputPath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + H265_HDR10_MP4);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(H265_HDR10_MP4, MuxerTestUtil.MP4)
                + "_fragmented_box_structure"));
  }

  @Test
  public void createFragmentedMp4File_withDolbyVisionTrack_ftypContainsDby1CompatibleBrand()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Format dolbyVisionFormat =
        FAKE_VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvav.09.02")
            .build();
    Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .build()) {
      int trackId = muxer.addTrack(dolbyVisionFormat);
      muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }

    byte[] outputFileBytes = TestUtil.getByteArrayFromFilePath(outputFilePath);
    assertThat(MuxerTestUtil.ftypBoxContainsCompatibleBrand(outputFileBytes, "dby1")).isTrue();
  }

  @Test
  public void createFragmentedMp4File_fromAudioOnlyInputFile_writesExpectedFragments()
      throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputPath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + AUDIO_ONLY_MP4);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    // For a 15 sec audio, there should be 8 fragments (2 sec fragment duration).
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(AUDIO_ONLY_MP4, MuxerTestUtil.MP4)
                + "_fragmented_box_structure"));
  }

  @Test
  public void createAv1FragmentedMp4File_withoutCsd_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context,
          fragmentedMp4Muxer,
          MEDIA_ASSET_DIRECTORY + AV1_MP4,
          /* removeInitializationData= */ true,
          /* removeAudioSampleFlags= */ false);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(new DefaultSubtitleParserFactory()),
            checkNotNull(outputFilePath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(AV1_MP4, MuxerTestUtil.MP4) + "_fragmented"));
  }

  @Test
  // TODO: b/507292304 - Suppressed due to failure on SDK 23.
  @Config(minSdk = 24)
  public void createFragmentedMp4File_withoutAudioSampleFlags_writesAudioSamplesAsSyncSamples()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context,
          fragmentedMp4Muxer,
          MEDIA_ASSET_DIRECTORY + H264_MP4,
          /* removeInitializationData= */ false,
          /* removeAudioSampleFlags= */ true);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(new DefaultSubtitleParserFactory()),
            checkNotNull(outputFilePath));
    // The dump file should be same as before when audio sample flags were set.
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(H264_MP4, MuxerTestUtil.MP4) + "_fragmented"));
  }

  @Test
  public void createFragmentedMp4File_withSomeMetadataTrack_writesAsTextMetadataTrack()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    // Fake metadata payload
    byte[] sampleData = new byte[] {0x05, 0x06, 0x07, 0x08};
    Format metadataTrackFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_META).build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      // Add the metadata track.
      int trackId = muxer.addTrack(metadataTrackFormat);
      // Write fake metadata track samples.
      for (int i = 0; i < 5; i++) {
        muxer.writeSampleData(
            trackId,
            ByteBuffer.wrap(sampleData),
            new BufferInfo(
                /* presentationTimeUs= */ i * 100_000L,
                /* size= */ sampleData.length,
                /* flags= */ 0));
      }
    }

    // TODO: b/496518585 - FakeExtractorOutput is not dumping this metadata track.
    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputFilePath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath("fragmented_mp4_with_metadata_track.mp4"));
  }

  @Test
  public void createFragmentedMp4File_withSomeUnknownTrack_writesAsTextMetadataTrack()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    // Fake metadata payload
    byte[] sampleData = new byte[] {0x05, 0x06, 0x07, 0x08};
    Format metadataTrackFormat = new Format.Builder().setSampleMimeType("xyz").build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      // Add the metadata track.
      int trackId = muxer.addTrack(metadataTrackFormat);
      // Write fake metadata samples.
      for (int i = 0; i < 5; i++) {
        muxer.writeSampleData(
            trackId,
            ByteBuffer.wrap(sampleData),
            new BufferInfo(
                /* presentationTimeUs= */ i * 100_000L,
                /* size= */ sampleData.length,
                /* flags= */ 0));
      }
    }

    // TODO: b/496518585 - FakeExtractorOutput is not dumping this metadata track.
    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputFilePath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath("fragmented_mp4_with_unknown_track.mp4"));
  }

  @Test
  public void write_singleTrack_extractorSeekMapIsSeekable() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + H264_MP4);
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(
            new DefaultSubtitleParserFactory(), FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    assertThat(fakeExtractorOutput.seekMap.isSeekable()).isTrue();
    assertThat(fakeExtractorOutput.seekMap.getSeekPoints(/* timeUs= */ 0).first.position)
        .isGreaterThan(0);
    assertThat(fakeExtractorOutput.seekMap.getSeekPoints(/* timeUs= */ 500_000L).first.position)
        .isGreaterThan(0);
  }

  @Test
  public void write_fragmentedMp4_extractorParsesSampleTimestampsCorrectly() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + H264_MP4);
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    // 43990L is the initial sample presentation timestamp (in microseconds) extracted from
    // sample_no_bframes.mp4 after FragmentedMp4Extractor parses the written tfdt box.
    FakeTrackOutput trackOutput = fakeExtractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(43990L);
  }

  @Test
  public void write_negativeInitialSampleTimestamp_clampsBaseMediaDecodeTimeToZero()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int trackId = fragmentedMp4Muxer.addTrack(MuxerTestUtil.FAKE_AUDIO_FORMAT);
      ByteBuffer sampleData = ByteBuffer.allocate(10);
      fragmentedMp4Muxer.writeSampleData(
          trackId,
          sampleData,
          new BufferInfo(
              /* presentationTimeUs= */ -1000L,
              /* size= */ sampleData.remaining(),
              /* flags= */ C.BUFFER_FLAG_KEY_FRAME));
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    FakeTrackOutput trackOutput = fakeExtractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0L);
  }

  @Test
  public void write_multiTrack_extractorSeekMapIsSeekable() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + H264_MP4);
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MEDIA_ASSET_DIRECTORY + AUDIO_ONLY_MP4);
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(
            new DefaultSubtitleParserFactory(), FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(2);
    assertThat(fakeExtractorOutput.seekMap.isSeekable()).isTrue();
    assertThat(fakeExtractorOutput.seekMap.getSeekPoints(/* timeUs= */ 0).first.position)
        .isGreaterThan(0);
    assertThat(fakeExtractorOutput.seekMap.getSeekPoints(/* timeUs= */ 500_000L).first.position)
        .isGreaterThan(0);
  }

  // Non-video/metadata tracks treat all samples as random access points (!MimeTypes.isVideo(...) ==
  // true),
  // which records tfra entries into the mfra box and allows the extractor to build a seekable
  // SeekMap.
  @Test
  public void write_withEmptyTrackInFragment_correctlySetsActiveTrafIndex() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    byte[] sampleData = new byte[] {0x00, 0x00, 0x00, 0x01, 0x65, 0x01};
    Format track1Format = new Format.Builder().setSampleMimeType("meta1").build();
    Format track2Format = new Format.Builder().setSampleMimeType("meta2").build();
    Format track3Format = new Format.Builder().setSampleMimeType("meta3").build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int track1Id = muxer.addTrack(track1Format);
      int unusedTrack2Id = muxer.addTrack(track2Format);
      int track3Id = muxer.addTrack(track3Format);

      // Track 1 gets a sample.
      muxer.writeSampleData(
          track1Id,
          ByteBuffer.wrap(sampleData),
          new BufferInfo(
              /* presentationTimeUs= */ 0L, /* size= */ sampleData.length, /* flags= */ 0));

      // Track 2 receives NO samples in this fragment.

      // Track 3 gets a sample.
      muxer.writeSampleData(
          track3Id,
          ByteBuffer.wrap(sampleData),
          new BufferInfo(
              /* presentationTimeUs= */ 0L, /* size= */ sampleData.length, /* flags= */ 0));
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(
            new DefaultSubtitleParserFactory(), FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    assertThat(fakeExtractorOutput.seekMap.isSeekable()).isTrue();
    assertThat(fakeExtractorOutput.seekMap.getSeekPoints(/* timeUs= */ 0).first.position)
        .isGreaterThan(0);
  }

  @Test
  public void createMp4File_withAudioEAc3Joc_createsDec3Box() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    byte[] expectedDec3Payload = new byte[] {0x00, 0x00, 0x00, 0x03, 0x00};
    Format eac3JocFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setInitializationData(ImmutableList.of(expectedDec3Payload))
            .build();
    byte[] sampleData = new byte[] {0x00, 0x01, 0x02, 0x03};

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int audioTrack = fragmentedMp4Muxer.addTrack(eac3JocFormat);
      fragmentedMp4Muxer.writeSampleData(
          audioTrack,
          ByteBuffer.wrap(sampleData),
          new BufferInfo(
              /* presentationTimeUs= */ 0L,
              /* size= */ sampleData.length,
              /* flags= */ C.BUFFER_FLAG_KEY_FRAME));
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput extractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);
    Format extractedFormat = checkNotNull(extractorOutput.trackOutputs.valueAt(0).lastFormat);
    assertThat(extractedFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_E_AC3);
    assertThat(extractedFormat.initializationData).hasSize(1);
    assertThat(extractedFormat.initializationData.get(0)).isEqualTo(expectedDec3Payload);
  }
}
