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

import static androidx.media3.common.util.Util.getPcmFormat;
import static androidx.media3.common.util.Util.getPcmFrameSize;
import static androidx.media3.common.util.Util.isPcmEncodingBigEndian;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static androidx.media3.test.utils.AssetInfo.AUDIO_ONLY_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_720P_4_SECOND_HDR10;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_AV1_VIDEO;
import static androidx.media3.test.utils.TestUtil.buildDoubleTestSamples;
import static androidx.media3.test.utils.TestUtil.buildFloatTestSamples;
import static androidx.media3.test.utils.TestUtil.buildTestData;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

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
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** End to end instrumentation tests for {@link FragmentedMp4Muxer}. */
@RunWith(RobolectricTestParameterInjector.class)
public class FragmentedMp4MuxerEndToEndTest {
  private static final Format FAKE_VIDEO_FORMAT_WITHOUT_REORDERING =
      FAKE_VIDEO_FORMAT.buildUpon().setMaxNumReorderSamples(0).build();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createFragmentedMp4File_fromInputFileSampleData_matchesExpectedBoxStructure()
      throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputPath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MP4_ASSET_720P_4_SECOND_HDR10.uri);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(MP4_ASSET_720P_4_SECOND_HDR10.uri, MuxerTestUtil.MP4)
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
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int trackId = muxer.addTrack(dolbyVisionFormat);
      muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }

    byte[] outputFileBytes = TestUtil.getByteArrayFromFilePath(outputFilePath);
    assertThat(MuxerTestUtil.ftypBoxContainsCompatibleBrand(outputFileBytes, "dby1")).isTrue();
  }

  @Test
  public void createFragmentedMp4File_withPcmCBox_writesAndExtractsCorrectSamples(
      @TestParameter(valuesProvider = PcmCEncodingValuesProvider.class) @C.PcmEncoding
          int pcmEncoding)
      throws Exception {
    File outputFile = temporaryFolder.newFile();
    Format pcmFormat = getPcmFormat(pcmEncoding, /* channels= */ 1, /* sampleRate= */ 48_000);
    ByteBuffer sampleBuffer = getTestSamplesForEncoding(pcmEncoding);
    byte[] sampleData = createByteArray(sampleBuffer.duplicate());
    BufferInfo bufferInfo =
        new BufferInfo(/* presentationTimeUs= */ 0L, sampleData.length, C.BUFFER_FLAG_KEY_FRAME);

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFile).getChannel()).build()) {
      int trackId = muxer.addTrack(pcmFormat);
      muxer.writeSampleData(trackId, sampleBuffer, bufferInfo);
    }

    FakeExtractorOutput extractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(new DefaultSubtitleParserFactory()), outputFile.getPath());
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.valueAt(0);
    Format extractedFormat = checkNotNull(trackOutput.lastFormat);
    assertThat(extractedFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_RAW);
    assertThat(extractedFormat.pcmEncoding).isEqualTo(pcmEncoding);
    assertThat(trackOutput.getSampleData(/* index= */ 0)).isEqualTo(sampleData);
  }

  @Test
  public void createFragmentedMp4File_with8BitPcm_throws() throws Exception {
    File outputFile = temporaryFolder.newFile();
    Format pcmFormat =
        getPcmFormat(C.ENCODING_PCM_8BIT, /* channels= */ 1, /* sampleRate= */ 48_000);
    ByteBuffer sampleBuffer = ByteBuffer.allocate(100);
    BufferInfo bufferInfo =
        new BufferInfo(/* presentationTimeUs= */ 0L, 100, C.BUFFER_FLAG_KEY_FRAME);

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFile).getChannel()).build()) {
      int trackId = muxer.addTrack(pcmFormat);
      // Header boxes and format validation are deferred until the first sample is written.
      assertThrows(
          IllegalArgumentException.class,
          () -> muxer.writeSampleData(trackId, sampleBuffer, bufferInfo));
    }
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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, AUDIO_ONLY_MP4_ASSET.uri);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    // For a 15 sec audio, there should be 8 fragments (2 sec fragment duration).
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(AUDIO_ONLY_MP4_ASSET.uri, MuxerTestUtil.MP4)
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
          MP4_ASSET_AV1_VIDEO.uri,
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
            MuxerTestUtil.getSubstitutedPath(MP4_ASSET_AV1_VIDEO.uri, MuxerTestUtil.MP4)
                + "_fragmented"));
  }

  @Test
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
          H264_MP4_ASSET.uri,
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
            MuxerTestUtil.getSubstitutedPath(H264_MP4_ASSET.uri, MuxerTestUtil.MP4)
                + "_fragmented"));
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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, H264_MP4_ASSET.uri);
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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, H264_MP4_ASSET.uri);
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

  /**
   * A fragmented MP4 file states its timeline twice: as the {@code tfdt} of each fragment, and as
   * the sum of the {@code trun} sample durations. The two must agree.
   *
   * <p>Frames within a group of pictures are 33367us apart, but only 32000us separate the last
   * frame of a group from the next key frame, so the last sample duration of a fragment cannot be
   * copied from the preceding one.
   */
  @Test
  public void writeSampleData_withShorterFrameDurationBeforeKeyFrame_fragmentsAreContiguous()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    long frameDurationUs = 33_367L;
    long frameDurationBeforeKeyFrameUs = 32_000L;
    int framesPerGroupOfPictures = 5;
    int groupOfPicturesCount = 3;

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .build()) {
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      long presentationTimeUs = 0L;
      for (int i = 0; i < groupOfPicturesCount; i++) {
        for (int j = 0; j < framesPerGroupOfPictures; j++) {
          ByteBuffer sampleData =
              getFakeSampleAndSampleInfo(presentationTimeUs, /* isVideo= */ true).first;
          muxer.writeSampleData(
              trackId,
              sampleData,
              new BufferInfo(
                  presentationTimeUs,
                  sampleData.remaining(),
                  /* flags= */ j == 0 ? C.BUFFER_FLAG_KEY_FRAME : 0));
          presentationTimeUs +=
              j == framesPerGroupOfPictures - 1 ? frameDurationBeforeKeyFrameUs : frameDurationUs;
        }
      }
    }

    FakeTrackOutput fromTfdt =
        TestUtil.extractAllSamplesFromFilePath(
                new FragmentedMp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath)
            .trackOutputs
            .get(0);
    FakeTrackOutput fromDurations =
        TestUtil.extractAllSamplesFromFilePath(
                new FragmentedMp4Extractor(
                    new DefaultSubtitleParserFactory(),
                    FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_TFDT_BOX),
                outputFilePath)
            .trackOutputs
            .get(0);
    ImmutableList.Builder<Long> tfdtSampleTimesUs = ImmutableList.builder();
    for (int i = 0; i < fromTfdt.getSampleCount(); i++) {
      tfdtSampleTimesUs.add(fromTfdt.getSampleTimeUs(i));
    }
    ImmutableList.Builder<Long> durationSampleTimesUs = ImmutableList.builder();
    for (int i = 0; i < fromDurations.getSampleCount(); i++) {
      durationSampleTimesUs.add(fromDurations.getSampleTimeUs(i));
    }
    assertThat(durationSampleTimesUs.build()).isEqualTo(tfdtSampleTimesUs.build());
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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, H264_MP4_ASSET.uri);
      feedInputDataToMuxer(context, fragmentedMp4Muxer, AUDIO_ONLY_MP4_ASSET.uri);
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

  @Test
  public void writeFragmentedMp4File_onlyEndOfStreamSample_doesNotCrash() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Format format = new Format.Builder().setSampleMimeType("xyz").build();
    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int trackId = muxer.addTrack(format);
      BufferInfo endOfStreamBufferInfo =
          new BufferInfo(
              /* presentationTimeUs= */ 2_000_000L, /* size= */ 0, C.BUFFER_FLAG_END_OF_STREAM);
      muxer.writeSampleData(trackId, ByteBuffer.allocate(0), endOfStreamBufferInfo);
    }

    byte[] outputFileBytes = TestUtil.getByteArrayFromFilePath(outputFilePath);
    assertThat(outputFileBytes).isEmpty();
  }

  @Test
  public void addTrack_afterClose_throws() throws Exception {
    FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build();
    muxer.close();
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    assertThrows(IllegalStateException.class, () -> muxer.addTrack(format));
  }

  @Test
  public void writeSampleData_afterClose_throws() throws Exception {
    FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build();
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    int trackId = muxer.addTrack(format);
    muxer.close();
    BufferInfo bufferInfo =
        new BufferInfo(/* presentationTimeUs= */ 0, /* size= */ 0, /* flags= */ 0);
    ByteBuffer sampleData = ByteBuffer.allocate(0);

    assertThrows(
        IllegalStateException.class, () -> muxer.writeSampleData(trackId, sampleData, bufferInfo));
  }

  @Test
  public void close_afterClose_throws() throws Exception {
    FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build();
    muxer.close();

    assertThrows(IllegalStateException.class, muxer::close);
  }

  @Test
  public void addTrack_withDolbyVisionProfile20_throws() throws Exception {
    Format dolbyVisionProfile20Format =
        FAKE_VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.20.01")
            .build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build()) {
      assertThrows(MuxerException.class, () -> muxer.addTrack(dolbyVisionProfile20Format));
    }
  }

  @Test
  public void addTrack_withDolbyVisionNullCodecs_throws() throws Exception {
    Format dolbyVisionNullCodecsFormat =
        FAKE_VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs(null)
            .build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build()) {
      assertThrows(MuxerException.class, () -> muxer.addTrack(dolbyVisionNullCodecsFormat));
    }
  }

  @Test
  public void addTrack_withDolbyVisionMalformedCodecs_throws() throws Exception {
    Format dolbyVisionMalformedCodecsFormat =
        FAKE_VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.xx.01")
            .build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel())
            .build()) {
      assertThrows(MuxerException.class, () -> muxer.addTrack(dolbyVisionMalformedCodecsFormat));
    }
  }

  @Test
  public void createFragmentedMp4File_withMultipleTracks_preservesTrackIds() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build()) {
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int audioTrackId = muxer.addTrack(MuxerTestUtil.FAKE_AUDIO_FORMAT);

      assertThat(videoTrackId).isEqualTo(1);
      assertThat(audioTrackId).isEqualTo(2);

      Pair<ByteBuffer, BufferInfo> videoSample =
          getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
      muxer.writeSampleData(videoTrackId, videoSample.first, videoSample.second);

      Pair<ByteBuffer, BufferInfo> audioSample =
          getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);
      muxer.writeSampleData(audioTrackId, audioSample.first, audioSample.second);
    }

    FragmentedMp4Extractor extractor =
        new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(extractor, outputFilePath);

    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(2);
    assertThat(fakeExtractorOutput.trackOutputs.get(0).lastFormat.sampleMimeType)
        .isEqualTo(FAKE_VIDEO_FORMAT.sampleMimeType);
    assertThat(fakeExtractorOutput.trackOutputs.get(1).lastFormat.sampleMimeType)
        .isEqualTo(MuxerTestUtil.FAKE_AUDIO_FORMAT.sampleMimeType);
  }

  @Test
  public void writeSampleData_fragmentsBetweenKeyFramesEnabled_writesFragmentsAtNonKeyFrames()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      for (int i = 0; i < 15; i++) {
        writeFakeVideoSample(muxer, trackId, i * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be 3 fragments, of which the last 2 start with non-key frames.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_fragments_between_key_frames.mp4"));
  }

  @Test
  public void writeSampleData_fragmentsBetweenKeyFramesDisabled_writesFragmentsAtKeyFramesOnly()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      for (int i = 0; i < 15; i++) {
        writeFakeVideoSample(muxer, trackId, i * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be a single fragment as there is a single key frame.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_without_fragments_between_key_frames.mp4"));
  }

  @Test
  public void writeSampleData_unsetMaxNumReorderSamples_writesFragmentsAtKeyFramesOnly()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      // The default maxNumReorderSamples is Format.NO_VALUE.
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      for (int i = 0; i < 15; i++) {
        writeFakeVideoSample(muxer, trackId, i * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // The output should be the same as when the option is disabled.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_without_fragments_between_key_frames.mp4"));
  }

  @Test
  public void
      writeSampleData_fragmentsBetweenKeyFramesEnabledWithBFrames_writesFragmentsAtKeyFramesOnly()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Format videoFormat = FAKE_VIDEO_FORMAT.buildUpon().setMaxNumReorderSamples(2).build();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int trackId = muxer.addTrack(videoFormat);
      // Samples are in decode order I P B B P B B ...
      for (int i = 0; i < 15; i++) {
        int frameIndex = i == 0 ? 0 : i % 3 == 1 ? i + 2 : i - 1;
        writeFakeVideoSample(muxer, trackId, frameIndex * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be a single fragment as there is a single key frame.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_b_frames_and_fragments_between_key_frames.mp4"));
  }

  @Test
  public void
      writeSampleData_fragmentsBetweenKeyFramesEnabledWithUnexpectedBFrames_fallsBackToKeyFramesOnly()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      // The format declares no reordering, but B-frames appear after the first 6 samples.
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      // Samples are in decode order I P P P P P P B B P B B ...
      for (int i = 0; i < 15; i++) {
        int frameIndex = i < 6 ? i : (i - 6) % 3 == 0 ? i + 2 : i - 1;
        writeFakeVideoSample(muxer, trackId, frameIndex * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be 2 fragments: the second starts at a non-key frame before the first
    // out-of-order sample is seen, after which fragments end only at key frames.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_unexpected_b_frames_and_fragments_between_key_frames.mp4"));
  }

  @Test
  public void
      writeSampleData_fragmentsBetweenKeyFramesEnabledWithAudio_writesFragmentsAtNonKeyFrames()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      int audioTrackId = muxer.addTrack(MuxerTestUtil.FAKE_AUDIO_FORMAT);
      // Audio samples lag one frame behind video, so audio timestamps are often earlier than the
      // last video timestamp. This must not be treated as out-of-order video.
      for (int i = 0; i < 15; i++) {
        writeFakeVideoSample(muxer, videoTrackId, i * 33_333L, /* isKeyFrame= */ i == 0);
        if (i > 0) {
          Pair<ByteBuffer, BufferInfo> audioSample =
              getFakeSampleAndSampleInfo((i - 1) * 33_333L, /* isVideo= */ false);
          muxer.writeSampleData(audioTrackId, audioSample.first, audioSample.second);
        }
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be 3 fragments, of which the last 2 start with non-key video frames.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_audio_and_fragments_between_key_frames.mp4"));
  }

  @Test
  public void
      writeSampleData_fragmentsBetweenKeyFramesEnabledWithDuplicateTimestamps_doesNotEndFragmentBetweenThem()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      // The sixth sample has the same timestamp as the fifth, which is where the first fragment
      // would otherwise end.
      for (int i = 0; i < 15; i++) {
        int frameIndex = i <= 4 ? i : i - 1;
        writeFakeVideoSample(muxer, trackId, frameIndex * 33_333L, /* isKeyFrame= */ i == 0);
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // The first fragment should end after both samples with the duplicate timestamp.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_duplicate_timestamps_and_fragments_between_key_frames.mp4"));
  }

  @Test
  public void writeSampleData_fragmentsBetweenKeyFramesEnabledWithEmptySample_skipsEmptySample()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FragmentedMp4Muxer muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputFilePath).getChannel())
            .setFragmentDurationMs(100)
            .setFragmentsBetweenKeyFramesEnabled(true)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT_WITHOUT_REORDERING);
      for (int i = 0; i < 5; i++) {
        writeFakeVideoSample(muxer, trackId, i * 33_333L, /* isKeyFrame= */ i == 0);
      }
      // The pending samples span the fragment duration, but an empty sample must not end the
      // fragment.
      muxer.writeSampleData(
          trackId,
          ByteBuffer.allocate(0),
          new BufferInfo(/* presentationTimeUs= */ 200_000L, /* size= */ 0, /* flags= */ 0));
      writeFakeVideoSample(
          muxer, trackId, /* presentationTimeUs= */ 210_000L, /* isKeyFrame= */ true);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // There should be 2 fragments, each starting with a key frame.
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedMp4DumpFilePath(
            "fragmented_mp4_with_empty_sample_and_fragments_between_key_frames.mp4"));
  }

  private static void writeFakeVideoSample(
      FragmentedMp4Muxer muxer, int trackId, long presentationTimeUs, boolean isKeyFrame)
      throws MuxerException {
    ByteBuffer sampleData =
        getFakeSampleAndSampleInfo(presentationTimeUs, /* isVideo= */ true).first;
    muxer.writeSampleData(
        trackId,
        sampleData,
        new BufferInfo(
            presentationTimeUs,
            sampleData.remaining(),
            /* flags= */ isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0));
  }

  private static ByteBuffer getTestSamplesForEncoding(@C.PcmEncoding int pcmEncoding) {
    Random random = new Random(/* seed= */ 0);
    int sampleCount = 100;
    ByteBuffer sampleBuffer;
    if (pcmEncoding == C.ENCODING_PCM_DOUBLE || pcmEncoding == C.ENCODING_PCM_DOUBLE_BIG_ENDIAN) {
      sampleBuffer = createByteBuffer(buildDoubleTestSamples(sampleCount, random));
    } else if (pcmEncoding == C.ENCODING_PCM_FLOAT
        || pcmEncoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN) {
      sampleBuffer = createByteBuffer(buildFloatTestSamples(sampleCount, random));
    } else {
      int bytesPerSample = getPcmFrameSize(pcmEncoding, /* channelCount= */ 1);
      byte[] rawBytes = buildTestData(sampleCount * bytesPerSample, random);
      sampleBuffer = createByteBuffer(rawBytes);
    }
    return sampleBuffer.order(
        isPcmEncodingBigEndian(pcmEncoding) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
  }

  private static final class PcmCEncodingValuesProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(TestParameterValuesProvider.Context context) {
      return ImmutableList.of(
          value(C.ENCODING_PCM_FLOAT).withName("ENCODING_PCM_FLOAT"),
          value(C.ENCODING_PCM_FLOAT_BIG_ENDIAN).withName("ENCODING_PCM_FLOAT_BIG_ENDIAN"),
          value(C.ENCODING_PCM_DOUBLE).withName("ENCODING_PCM_DOUBLE"),
          value(C.ENCODING_PCM_DOUBLE_BIG_ENDIAN).withName("ENCODING_PCM_DOUBLE_BIG_ENDIAN"),
          value(C.ENCODING_PCM_24BIT).withName("ENCODING_PCM_24BIT"),
          value(C.ENCODING_PCM_24BIT_BIG_ENDIAN).withName("ENCODING_PCM_24BIT_BIG_ENDIAN"),
          value(C.ENCODING_PCM_32BIT).withName("ENCODING_PCM_32BIT"),
          value(C.ENCODING_PCM_32BIT_BIG_ENDIAN).withName("ENCODING_PCM_32BIT_BIG_ENDIAN"));
    }
  }
}
