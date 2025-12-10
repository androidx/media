/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.muxer.Mp4Muxer.FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION;
import static androidx.media3.muxer.Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_AUDIO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.XMP_SAMPLE_DATA;
import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link Mp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public class Mp4MuxerEndToEndTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  public static final String MP4_FILE_ASSET_DIRECTORY = "asset:///media/mp4/";

  private static final String H265_HDR10_MP4 = "hdr10-720p.mp4";
  private static final String AV1_MP4 = "sample_av1.mp4";
  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void writeMp4File_withSampleAndMetadata_matchedExpectedBoxStructure() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    byte[] xmpBytes = TestUtil.getByteArray(context, XMP_SAMPLE_DATA);

    try (Mp4Muxer muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      muxer.addMetadataEntry(new Mp4OrientationData(/* orientation= */ 90));
      muxer.addMetadataEntry(new Mp4LocationData(/* latitude= */ 33.0f, /* longitude= */ -120f));
      float captureFps = 120.0f;
      muxer.addMetadataEntry(
          new MdtaMetadataEntry(
              MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS,
              /* value= */ Util.toByteArray(captureFps),
              MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32));
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      muxer.addMetadataEntry(
          new MdtaMetadataEntry(
              "StringKey1",
              /* value= */ Util.getUtf8Bytes("StringValue"),
              MdtaMetadataEntry.TYPE_INDICATOR_STRING));
      muxer.addMetadataEntry(new XmpData(xmpBytes));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }

    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    DumpFileAsserts.assertOutput(
        context,
        dumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_samples_and_metadata.mp4"));
  }

  @Test
  public void createMp4File_addTrackAndMetadataButNoSamples_createsEmptyFile() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addTrack(/* sortKey= */ 0, FAKE_VIDEO_FORMAT);
      mp4Muxer.addMetadataEntry(new Mp4OrientationData(/* orientation= */ 90));
      mp4Muxer.addMetadataEntry(
          new MdtaMetadataEntry(
              "key",
              /* value= */ Util.getUtf8Bytes("value"),
              MdtaMetadataEntry.TYPE_INDICATOR_STRING));
    }

    byte[] outputFileBytes = TestUtil.getByteArrayFromFilePath(outputFilePath);
    assertThat(outputFileBytes).isEmpty();
  }

  @Test
  public void createAv1Mp4File_withoutCsd_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context,
          mp4Muxer,
          MP4_FILE_ASSET_DIRECTORY + AV1_MP4,
          /* removeInitializationData= */ true,
          /* removeAudioSampleFlags= */ false);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    DumpFileAsserts.assertOutput(
        context, fakeExtractorOutput, MuxerTestUtil.getExpectedDumpFilePath(AV1_MP4));
  }

  @Test
  public void createMp4File_muxerNotClosed_createsPartiallyWrittenValidFile() throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();
    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputPath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, MP4_FILE_ASSET_DIRECTORY + H265_HDR10_MP4);

      // Muxer not closed.

      // The output depends on Mp4Muxer.MOOV_BOX_UPDATE_INTERVAL_US and whether or not
      // sample batching is enabled.
      // Audio sample written = 187 out of 195.
      // Video sample written = 93 out of 127.
      // Output is still a valid MP4 file.
      FakeExtractorOutput fakeExtractorOutput =
          TestUtil.extractAllSamplesFromFilePath(
              new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
      DumpFileAsserts.assertOutput(
          context,
          fakeExtractorOutput,
          MuxerTestUtil.getExpectedDumpFilePath("partial_" + H265_HDR10_MP4));
    }
  }

  @Test
  public void createMp4File_withSameTracksOffset_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L, /* isVideo= */ true);
    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);

      // Add same track again but with different samples.
      int track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
    }

    // Presentation timestamps in dump file are:
    // Track 1 Sample 1 = 0L
    // Track 1 Sample 2 = 100L
    // Track 2 Sample 1 = 0L
    // Track 2 Sample 2 = 200L
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_same_tracks_offset.mp4"));
  }

  @Test
  public void createMp4File_withDifferentTracksOffset_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);

      // Add same track again but with different samples.
      int track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
    }

    // The presentation time of second track's first sample is retained through edit box.
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_different_tracks_offset.mp4"));
  }

  @Test
  public void createMp4File_withNegativeTracksOffset_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ -100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
    }

    // Presentation timestamps in dump file are:
    // Track 1 Sample 1 = -100L
    // Track 1 Sample 2 = 100L
    // Track 1 Sample 3 = 300L
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_negative_tracks_offset.mp4"));
  }

  @Test
  public void createMp4File_withOutOfOrderBframes_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 3_000L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 1_000L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 2_000L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
      mp4Muxer.writeSampleData(track1, track1Sample4.first, track1Sample4.second);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_b_frame.mp4"));
  }

  @Test
  public void createMp4File_withOutOfOrderBframesLargePresentationTimestamps_matchesExpected()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_000_000L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_273_908L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_132_928L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_033_192L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_000_000L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_273_908L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_132_928L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track2Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_033_192L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
      mp4Muxer.writeSampleData(track1, track1Sample4.first, track1Sample4.second);
      int track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
      mp4Muxer.writeSampleData(track2, track2Sample3.first, track2Sample3.second);
      mp4Muxer.writeSampleData(track2, track2Sample4.first, track2Sample4.second);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_b_frame_large_pts.mp4"));
  }

  @Test
  public void createMp4File_withOneTrackEmpty_doesNotWriteEmptyTrack() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      // Add same track again but without any samples.
      mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
    }

    // The FakeExtractorOutput omits tracks with no samples so the dump file will be the same
    // with/without the empty track. Hence used DumpableMp4Box instead.
    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // Output contains only one trak box.
    DumpFileAsserts.assertOutput(
        context, dumpableBox, MuxerTestUtil.getExpectedDumpFilePath("mp4_without_empty_track.mp4"));
  }

  @Test
  public void writeMp4File_withLargeNumberOfSamples_writesMoovBoxAtTheEndAndFreeBoxAtStart()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      for (int i = 0; i < 50_000; i++) {
        Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
            getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i, /* isVideo= */ true);
        muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
      }
    }

    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    DumpFileAsserts.assertOutput(
        context,
        dumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath(
            "mp4_with_moov_at_the_end_and_free_box_at_start.mp4"));
  }

  @Test
  public void writeMp4File_withAttemptStreamableMp4SetToFalse_writesMoovBoxAtTheEndAndNoFreeBox()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setAttemptStreamableOutputEnabled(false)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      for (int i = 0; i < 1_000; i++) {
        Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
            getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i, /* isVideo= */ true);
        muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
      }
    }

    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    DumpFileAsserts.assertOutput(
        context,
        dumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_moov_at_the_end_and_no_free_box.mp4"));
  }

  @Test
  public void
      createMp4Muxer_withMp4WithAuxiliaryTracksExtensionFileFormatButWithoutCacheFileProvider_throws()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
                .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
                .build());
  }

  @Test
  public void
      writeMp4File_withMp4WithAuxiliaryTracksExtensionFileFormatAndAuxiliaryTracks_writesAxteBox()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setCacheFileSupplier(() -> cacheFilePath)
            .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
            .setMp4AtFileParameters(
                new Mp4Muxer.Mp4AtFileParameters(/* shouldInterleaveSamples= */ false))
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    DumpableMp4Box outputFileDumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // 1 track is written in the outer moov box and 2 tracks are written in the axte.moov box.
    DumpFileAsserts.assertOutput(
        context,
        outputFileDumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_auxiliary_tracks_in_axte.box"));
  }

  @Test
  public void writeMp4File_withDefaultFileFormatAndAuxiliaryTracks_doesNotWriteAxteBox()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    DumpableMp4Box outputFileDumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // All 3 tracks are written in the outer moov box and no axte box.
    DumpFileAsserts.assertOutput(
        context,
        outputFileDumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_auxiliary_tracks_without_axte.box"));
  }

  @Test
  public void
      writeMp4File_withMp4WithAuxiliaryTracksExtensionFileFormatAndAuxiliaryVideoTracks_primaryVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setCacheFileSupplier(() -> cacheFilePath)
            .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
            .setMp4AtFileParameters(
                new Mp4Muxer.Mp4AtFileParameters(/* shouldInterleaveSamples= */ false))
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    FakeExtractorOutput primaryTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    // The Mp4Extractor extracts primary tracks by default.
    DumpFileAsserts.assertOutput(
        context,
        primaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_primary_tracks.mp4"));
  }

  @Test
  public void
      writeMp4File_withMp4WithAuxiliaryTracksExtensionFileFormatAndAuxiliaryTracks_auxiliaryTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setCacheFileSupplier(() -> cacheFilePath)
            .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
            .setMp4AtFileParameters(
                new Mp4Muxer.Mp4AtFileParameters(/* shouldInterleaveSamples= */ false))
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    FakeExtractorOutput auxiliaryTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(
                new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_AUXILIARY_TRACKS),
            outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        auxiliaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_auxiliary_tracks.mp4"));
  }

  @Test
  public void
      writeMp4File_withMp4WithAuxiliaryTracksExtensionFileFormatAndAuxiliaryTracksAndShouldInterleaveSamples_primaryVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
            .setMp4AtFileParameters(
                new Mp4Muxer.Mp4AtFileParameters(/* shouldInterleaveSamples= */ true))
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    FakeExtractorOutput primaryTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    // The Mp4Extractor extracts primary tracks by default.
    DumpFileAsserts.assertOutput(
        context,
        primaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            "mp4_with_primary_tracks_when_auxiliary_track_samples_interleaved.mp4"));
  }

  @Test
  public void
      writeMp4File_withMp4WithAuxiliaryTracksExtensionFileFormatAndAuxiliaryTracksAndShouldInterleaveSamples_auxiliaryTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setOutputFileFormat(FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION)
            .setMp4AtFileParameters(
                new Mp4Muxer.Mp4AtFileParameters(/* shouldInterleaveSamples= */ true))
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int primaryVideoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      int sharpVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      int depthLinearVideoTrackId =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackId, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackId, /* sampleCount= */ 5);
    }

    FakeExtractorOutput auxiliaryTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(
                new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_AUXILIARY_TRACKS),
            outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        auxiliaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            "mp4_with_auxiliary_tracks_when_auxiliary_track_samples_interleaved.mp4"));
  }

  @Test
  public void
      createMp4File_withLastSampleDurationBehaviorUsingEndOfStreamFlag_writesSamplesWithCorrectDurations()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L, /* isVideo= */ true);
    long expectedDurationUs = 1_000L;

    try (Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setLastSampleDurationBehavior(
                LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
            .build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track, sample1.first, sample1.second);
      mp4Muxer.writeSampleData(track, sample2.first, sample2.second);
      mp4Muxer.writeSampleData(track, sample3.first, sample3.second);
      mp4Muxer.writeSampleData(track, sample4.first, sample4.second);
      // Write end of stream sample.
      BufferInfo endOfStreamBufferInfo =
          new BufferInfo(
              /* presentationTimeUs= */ expectedDurationUs,
              /* size= */ 0,
              C.BUFFER_FLAG_END_OF_STREAM);
      mp4Muxer.writeSampleData(track, ByteBuffer.allocate(0), endOfStreamBufferInfo);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .assertSampleCount(4);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(expectedDurationUs);
  }

  @Test
  public void
      createMp4File_withLastSampleDurationBehaviorUsingEndOfStreamFlagButNoEndOfStreamSample_writesExpectedDurationForTheLastSample()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L, /* isVideo= */ true);
    Pair<ByteBuffer, BufferInfo> sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L, /* isVideo= */ true);

    try (Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .setLastSampleDurationBehavior(
                LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
            .build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      int track = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track, sample1.first, sample1.second);
      mp4Muxer.writeSampleData(track, sample2.first, sample2.second);
      mp4Muxer.writeSampleData(track, sample3.first, sample3.second);
      mp4Muxer.writeSampleData(track, sample4.first, sample4.second);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .assertSampleCount(4);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(400L);
  }

  @Test
  public void createMp4File_withSampleBatchingDisabled_matchesExpected() throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputPath))
            .setSampleBatchingEnabled(false)
            .build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context, mp4Muxer, checkNotNull(MP4_FILE_ASSET_DIRECTORY + H265_HDR10_MP4));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("sample_batching_disabled_" + H265_HDR10_MP4));
  }

  @Test
  public void createMp4File_withSampleBatchingAndAttemptStreamableOutputDisabled_matchesExpected()
      throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();

    try (Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputPath))
            .setSampleBatchingEnabled(false)
            .setAttemptStreamableOutputEnabled(false)
            .build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context, mp4Muxer, checkNotNull(MP4_FILE_ASSET_DIRECTORY + H265_HDR10_MP4));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            "sample_batching_and_attempt_streamable_output_disabled_" + H265_HDR10_MP4));
  }

  @Test
  public void
      writeMp4File_withSomeFreeSpaceAfterFileTypeBoxAndAttemptStreamableOutputDisabled_reservesExpectedFreeSpace()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);

    try (Mp4Muxer muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath))
            .experimentalSetFreeSpaceAfterFileTypeBox(500)
            .setAttemptStreamableOutputEnabled(false)
            .build()) {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      int trackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }

    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    DumpFileAsserts.assertOutput(
        context,
        dumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_some_free_space_after_ftyp.mp4"));
  }

  @Test
  public void writeSampleData_updatesSampleByteBufferPosition() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Pair<ByteBuffer, BufferInfo> audioSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);
    Pair<ByteBuffer, BufferInfo> videoSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ true);

    try (Mp4Muxer muxer = new Mp4Muxer.Builder(SeekableMuxerOutput.of(outputFilePath)).build()) {
      int audioTrackId = muxer.addTrack(FAKE_AUDIO_FORMAT);
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_FORMAT);
      muxer.writeSampleData(audioTrackId, audioSampleInfo.first, audioSampleInfo.second);
      muxer.writeSampleData(videoTrackId, videoSampleInfo.first, videoSampleInfo.second);
    }

    assertThat(audioSampleInfo.first.remaining()).isEqualTo(0);
    assertThat(videoSampleInfo.first.remaining()).isEqualTo(0);
  }

  private static void writeFakeSamples(Mp4Muxer muxer, int trackId, int sampleCount)
      throws MuxerException {
    for (int i = 0; i < sampleCount; i++) {
      Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
          getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i, /* isVideo= */ true);
      muxer.writeSampleData(trackId, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }
  }
}
