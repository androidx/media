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

import static androidx.media3.muxer.Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.XMP_SAMPLE_DATA;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
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
import androidx.media3.muxer.Muxer.TrackToken;
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ -100L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
    } finally {
      mp4Muxer.close();
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
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> track1Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 3_000L);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 1_000L);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 2_000L);

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
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_000_000L);
    Pair<ByteBuffer, BufferInfo> track1Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_273_908L);
    Pair<ByteBuffer, BufferInfo> track1Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_132_928L);
    Pair<ByteBuffer, BufferInfo> track1Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_000_033_192L);
    Pair<ByteBuffer, BufferInfo> track2Sample1 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_000_000L);
    Pair<ByteBuffer, BufferInfo> track2Sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_273_908L);
    Pair<ByteBuffer, BufferInfo> track2Sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_132_928L);
    Pair<ByteBuffer, BufferInfo> track2Sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 23_001_033_192L);

    try {
      TrackToken track1 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track1, track1Sample1.first, track1Sample1.second);
      mp4Muxer.writeSampleData(track1, track1Sample2.first, track1Sample2.second);
      mp4Muxer.writeSampleData(track1, track1Sample3.first, track1Sample3.second);
      mp4Muxer.writeSampleData(track1, track1Sample4.first, track1Sample4.second);
      TrackToken track2 = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track2, track2Sample1.first, track2Sample1.second);
      mp4Muxer.writeSampleData(track2, track2Sample2.first, track2Sample2.second);
      mp4Muxer.writeSampleData(track2, track2Sample3.first, track2Sample3.second);
      mp4Muxer.writeSampleData(track2, track2Sample4.first, track2Sample4.second);
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
            .setEditableVideoParameters(
                new Mp4Muxer.EditableVideoParameters(
                    /* shouldInterleaveSamples= */ false, () -> cacheFilePath))
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
    // 1 track is written in the outer moov box and 2 tracks are written in the edvd.moov box.
    DumpFileAsserts.assertOutput(
        context,
        outputFileDumpableBox,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_editable_video_tracks_in_edvd.box"));
  }

  @Test
  public void writeMp4File_withFileFormatDefaultAndEditableVideoTracks_doesNotWriteEdvdBox()
      throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer = new Mp4Muxer.Builder(new FileOutputStream(outputFilePath)).build();

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
            .setEditableVideoParameters(
                new Mp4Muxer.EditableVideoParameters(
                    /* shouldInterleaveSamples= */ false, () -> cacheFilePath))
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
    // The Mp4Extractor extracts primary tracks by default.
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
            .setEditableVideoParameters(
                new Mp4Muxer.EditableVideoParameters(
                    /* shouldInterleaveSamples= */ false, () -> cacheFilePath))
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

    FakeExtractorOutput editableTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(
                new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_EDITABLE_VIDEO_TRACKS),
            outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        editableTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath("mp4_with_editable_video_tracks.mp4"));
  }

  @Test
  public void
      writeMp4File_withFileFormatEditableVideoAndEditableVideoTracksAndShouldInterleaveSamples_primaryVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
            .setEditableVideoParameters(
                new Mp4Muxer.EditableVideoParameters(
                    /* shouldInterleaveSamples= */ true, /* cacheFileProvider= */ null))
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
    // The Mp4Extractor extracts primary tracks by default.
    DumpFileAsserts.assertOutput(
        context,
        primaryTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            "mp4_with_primary_tracks_when_editable_track_samples_interleaved.mp4"));
  }

  @Test
  public void
      writeMp4File_withFileFormatEditableVideoAndEditableVideoTracksAndShouldInterleaveSamples_editableVideoTracksMatchesExpected()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setOutputFileFormat(Mp4Muxer.FILE_FORMAT_EDITABLE_VIDEO)
            .setEditableVideoParameters(
                new Mp4Muxer.EditableVideoParameters(
                    /* shouldInterleaveSamples= */ true, /* cacheFileProvider= */ null))
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

    FakeExtractorOutput editableTracksOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(
                new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_EDITABLE_VIDEO_TRACKS),
            outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        editableTracksOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            "mp4_with_editable_video_tracks_when_editable_track_samples_interleaved.mp4"));
  }

  @Test
  public void
      createMp4File_withLastSampleDurationBehaviorUsingEndOfStreamFlag_writesSamplesWithCorrectDurations()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setLastSampleDurationBehavior(
                LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
            .build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> sample1 = getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L);
    Pair<ByteBuffer, BufferInfo> sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L);

    long expectedDurationUs = 1_000L;
    try {
      TrackToken track = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track, sample1.first, sample1.second);
      mp4Muxer.writeSampleData(track, sample2.first, sample2.second);
      mp4Muxer.writeSampleData(track, sample3.first, sample3.second);
      mp4Muxer.writeSampleData(track, sample4.first, sample4.second);
      // Write end of stream sample.
      BufferInfo endOfStreamBufferInfo = new BufferInfo();
      endOfStreamBufferInfo.set(
          /* newOffset= */ 0,
          /* newSize= */ 0,
          /* newTimeUs= */ expectedDurationUs,
          /* newFlags= */ MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      mp4Muxer.writeSampleData(track, ByteBuffer.allocate(0), endOfStreamBufferInfo);
    } finally {
      mp4Muxer.close();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    fakeExtractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO).assertSampleCount(4);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(expectedDurationUs);
  }

  @Test
  public void
      createMp4File_withLastSampleDurationBehaviorUsingEndOfStreamFlagButNoEndOfStreamSample_writesExpectedDurationForTheLastSample()
          throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(new FileOutputStream(outputFilePath))
            .setLastSampleDurationBehavior(
                LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS)
            .build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    Pair<ByteBuffer, BufferInfo> sample1 = getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L);
    Pair<ByteBuffer, BufferInfo> sample2 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 100L);
    Pair<ByteBuffer, BufferInfo> sample3 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 200L);
    Pair<ByteBuffer, BufferInfo> sample4 =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 300L);

    try {
      TrackToken track = mp4Muxer.addTrack(FAKE_VIDEO_FORMAT);
      mp4Muxer.writeSampleData(track, sample1.first, sample1.second);
      mp4Muxer.writeSampleData(track, sample2.first, sample2.second);
      mp4Muxer.writeSampleData(track, sample3.first, sample3.second);
      mp4Muxer.writeSampleData(track, sample4.first, sample4.second);
    } finally {
      mp4Muxer.close();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    fakeExtractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO).assertSampleCount(4);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(400L);
  }

  private static void writeFakeSamples(Mp4Muxer muxer, TrackToken trackToken, int sampleCount)
      throws Muxer.MuxerException {
    for (int i = 0; i < sampleCount; i++) {
      Pair<ByteBuffer, BufferInfo> sampleAndSampleInfo =
          getFakeSampleAndSampleInfo(/* presentationTimeUs= */ i);
      muxer.writeSampleData(trackToken, sampleAndSampleInfo.first, sampleAndSampleInfo.second);
    }
  }
}
