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

import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

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
}
