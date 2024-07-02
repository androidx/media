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

import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.XMP_SAMPLE_DATA;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.media.MediaCodec.BufferInfo;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.muxer.Muxer.TrackToken;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link Mp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public class Mp4MuxerEndToEndTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void writeMp4File_withSampleAndMetadata_matchedExpectedBoxStructure() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    byte[] xmpBytes = TestUtil.getByteArray(context, XMP_SAMPLE_DATA);

    try {
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
      TrackToken token = muxer.addTrack(FAKE_VIDEO_FORMAT);
      muxer.writeSampleData(token, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    } finally {
      muxer.close();
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();

    try {
      mp4Muxer.addTrack(/* sortKey= */ 0, FAKE_VIDEO_FORMAT);
      mp4Muxer.addMetadataEntry(new Mp4OrientationData(/* orientation= */ 90));
      mp4Muxer.addMetadataEntry(
          new MdtaMetadataEntry(
              "key",
              /* value= */ Util.getUtf8Bytes("value"),
              MdtaMetadataEntry.TYPE_INDICATOR_STRING));
    } finally {
      mp4Muxer.close();
    }

    byte[] outputFileBytes = TestUtil.getByteArrayFromFilePath(outputFilePath);
    assertThat(outputFileBytes).isEmpty();
  }

  @Test
  public void createMp4File_withSameTracksOffset_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);

      // Add same track again but with different samples.
      TrackToken track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
    } finally {
      mp4Muxer.close();
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);

      // Add same track again but with different samples.
      TrackToken track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
    } finally {
      mp4Muxer.close();
    }

    // The presentation time of second track's first sample is forcefully changed to 0L.
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_different_tracks_offset.mp4"));
  }

  @Test
  public void createMp4File_withOutOfOrderBframes_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 3000L);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 1000L);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 2000L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
      mp4Muxer.writeSampleData(track1, track1Sample4.first, track1Sample4.second);
    } finally {
      mp4Muxer.close();
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23698215060L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23698488968L);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23698347988L);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23698248252L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
      mp4Muxer.writeSampleData(track1, track1Sample4.first, track1Sample4.second);
    } finally {
      mp4Muxer.close();
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      // Add same track again but without any samples.
      mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
    } finally {
      mp4Muxer.close();
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
    Mp4Muxer muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken token = muxer.addTrack(FAKE_VIDEO_FORMAT);
      for (int i = 0; i < 50_000; i++) {
        Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
            getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i);
        muxer.writeSampleData(token, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
      }
    } finally {
      muxer.close();
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
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setAttemptStreamableOutputEnabled(false)
            .build();
    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken token = muxer.addTrack(FAKE_VIDEO_FORMAT);
      for (int i = 0; i < 1_000; i++) {
        Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
            getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i);
        muxer.writeSampleData(token, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
      }
    } finally {
      muxer.close();
    }

    DumpableMp4Box dumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    DumpFileAsserts.assertOutput(
        context,
        dumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_moov_at_the_end_and_no_free_box.mp4"));
  }

  @Test
  public void createMp4Muxer_withFileFormatEditableVideoButWithoutCacheFileProvider_throws()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
                .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
                .build());
  }

  @Test
  public void writeMp4File_withFileFormatEditableVideoAndEditableVideoTracks_writesEdvdBox()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
            .setCacheFileProvider(() -> cacheFilePath)
            .build();

    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken primaryVideoTrackToken = muxer.addTrack(FAKE_VIDEO_FORMAT);
      TrackToken sharpVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      TrackToken depthLinearVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackToken, /* sampleCount= */ 5);
    } finally {
      muxer.close();
    }

    DumpableMp4Box outputFileDumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // 1 track is written in the outer moov box and 2 tracks are writtin in the edvd.moov box.
    DumpFileAsserts.assertOutput(
        context,
        outputFileDumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_editable_video_tracks_in_edvd.box"));
  }

  @Test
  public void writeMp4File_withFileFormatDefaultAndEditableVideoTracks_doesNotWriteEdvdBox()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setCacheFileProvider(() -> cacheFilePath)
            .build();

    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken primaryVideoTrackToken = muxer.addTrack(FAKE_VIDEO_FORMAT);
      TrackToken sharpVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      TrackToken depthLinearVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackToken, /* sampleCount= */ 5);
    } finally {
      muxer.close();
    }

    DumpableMp4Box outputFileDumpableBox =
        new DumpableMp4Box(ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(outputFilePath)));
    // All 3 tracks are written in the outer moov box and no edvd box.
    DumpFileAsserts.assertOutput(
        context,
        outputFileDumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_editable_video_tracks_without_edvd.box"));
  }

  @Test
  public void
      writeMp4File_withFileFormatEditableVideoAndEditableVideoTracks_primaryVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
            .setCacheFileProvider(() -> cacheFilePath)
            .build();

    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken primaryVideoTrackToken = muxer.addTrack(FAKE_VIDEO_FORMAT);
      TrackToken sharpVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      TrackToken depthLinearVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackToken, /* sampleCount= */ 5);
    } finally {
      muxer.close();
    }

    FakeExtractorOutput primaryTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    // The Mp4Extractor can not read edvd box and can only parse primary tracks.
    DumpFileAsserts.assertOutput(
        context,
        primaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_primary_tracks.mp4"));
  }

  @Test
  public void
      writeMp4File_withFileFormatEditableVideoAndEditableVideoTracks_editableVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    String cacheFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
            .setCacheFileProvider(() -> cacheFilePath)
            .build();

    try {
      muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 1_000_000L,
              /* modificationTimestampSeconds= */ 5_000_000L));
      TrackToken primaryVideoTrackToken = muxer.addTrack(FAKE_VIDEO_FORMAT);
      TrackToken sharpVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_ORIGINAL)
                  .build());
      TrackToken depthLinearVideoTrackToken =
          muxer.addTrack(
              FAKE_VIDEO_FORMAT
                  .buildUpon()
                  .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                  .setAuxiliaryTrackType(C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR)
                  .build());
      writeFakeSamples(muxer, primaryVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, sharpVideoTrackToken, /* sampleCount= */ 5);
      writeFakeSamples(muxer, depthLinearVideoTrackToken, /* sampleCount= */ 5);
    } finally {
      muxer.close();
    }

    byte[] edvdBoxPayload = getEdvdBoxPayload(outputFilePath);
    FakeExtractorOutput editableTracksOutput =
        TestUtil.extractAllSamplesFromByteArray(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), edvdBoxPayload);
    // The Mp4Extractor can parse the MP4 embedded in the edvd box.
    DumpFileAsserts.assertOutput(
        context,
        editableTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_editable_video_tracks.mp4"));
  }

  private static void writeFakeSamples(Mp4Muxer muxer, TrackToken trackToken, int sampleCount)
      throws Muxer.MuxerException {
    for (int i = 0; i < sampleCount; i++) {
      Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
          getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i);
      muxer.writeSampleData(trackToken, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }
  }

  private static byte[] getEdvdBoxPayload(String filePath) throws IOException {
    ParsableByteArray data = new ParsableByteArray(TestUtil.getByteArrayFromFilePath(filePath));
    while (data.bytesLeft() > 0) {
      long size = data.readInt();
      String name = data.readString(/* length= */ 4);
      long payloadSize = size - 8;
      if (size == 1) {
        size = data.readUnsignedLongToLong();
        // Parsing is not supported for box having size > Integer.MAX_VALUE.
        Assertions.checkState(size <= Integer.MAX_VALUE);
        // Subtract 4 bytes (32-bit box size) + 4 bytes (box name) + 8 bytes (64-bit box size).
        payloadSize = size - 16;
      }
      if (name.equals("edvd")) {
        byte[] payloadData = new byte[(int) payloadSize];
        data.readBytes(payloadData, /* offset= */ 0, (int) payloadSize);
        return payloadData;
      } else {
        data.skipBytes((int) payloadSize);
      }
    }
    return new byte[0];
  }
}
