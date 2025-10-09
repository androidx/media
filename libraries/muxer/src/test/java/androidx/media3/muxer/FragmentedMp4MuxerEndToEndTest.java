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
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** End to end instrumentation tests for {@link FragmentedMp4Muxer}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class FragmentedMp4MuxerEndToEndTest {
  // Video Codecs
  private static final String H263_3GP = "bbb_176x144_128kbps_15fps_h263.3gp";
  private static final String H264_MP4 = "sample_no_bframes.mp4";
  private static final String H264_WITH_NON_REFERENCE_B_FRAMES_MP4 =
      "bbb_800x640_768kbps_30fps_avc_non_reference_3b.mp4";
  private static final String H264_WITH_PYRAMID_B_FRAMES_MP4 =
      "bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4";
  private static final String H264_WITH_FIRST_PTS_10_SEC =
      "bbb_800x640_768kbps_30fps_avc_2b_firstpts_10_sec.mp4";
  private static final String H264_DOLBY_VISION = "video_dovi_1920x1080_60fps_dvav_09.mp4";
  private static final String H265_DOLBY_VISION = "sample_edit_list.mp4";
  private static final String H265_HDR10_MP4 = "hdr10-720p.mp4";
  private static final String H265_WITH_METADATA_TRACK_MP4 = "h265_with_metadata_track.mp4";
  private static final String APV_MP4 = "sample_with_apvc.mp4";
  private static final String AV1_MP4 = "sample_av1.mp4";
  private static final String MPEG4_MP4 = "bbb_176x144_192kbps_15fps_mpeg4.mp4";

  // Contains CSD in CodecPrivate format.
  private static final String VP9_MP4 = "bbb_800x640_768kbps_30fps_vp9.mp4";
  private static final String VP9_WEB = "bbb_642x642_768kbps_30fps_vp9.webm";
  // Audio Codecs
  private static final String AUDIO_ONLY_MP4 = "sample_audio_only_15s.mp4";
  private static final String AMR_NB_3GP = "bbb_mono_8kHz_12.2kbps_amrnb.3gp";
  private static final String AMR_WB_3GP = "bbb_mono_16kHz_23.05kbps_amrwb.3gp";
  private static final String OPUS_OGG = "bbb_6ch_8kHz_opus.ogg";
  private static final String VORBIS_OGG = "bbb_1ch_16kHz_q10_vorbis.ogg";
  private static final String RAW_WAV = "bbb_2ch_44kHz.wav";

  public static final String MP4_FILE_ASSET_DIRECTORY = "asset:///media/mp4/";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        H263_3GP,
        H264_MP4,
        H264_WITH_NON_REFERENCE_B_FRAMES_MP4,
        H264_WITH_PYRAMID_B_FRAMES_MP4,
        H264_WITH_FIRST_PTS_10_SEC,
        H264_DOLBY_VISION,
        H265_DOLBY_VISION,
        H265_HDR10_MP4,
        H265_WITH_METADATA_TRACK_MP4,
        APV_MP4,
        AV1_MP4,
        MPEG4_MP4,
        VP9_MP4,
        VP9_WEB,
        AMR_NB_3GP,
        AMR_WB_3GP,
        OPUS_OGG,
        VORBIS_OGG,
        RAW_WAV);
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Parameter public @MonotonicNonNull String inputFile;

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createFragmentedMp4File_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();

    try (FragmentedMp4Muxer fragmentedMp4Muxer =
        new FragmentedMp4Muxer.Builder(new FileOutputStream(outputPath).getChannel()).build()) {
      fragmentedMp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context, fragmentedMp4Muxer, checkNotNull(MP4_FILE_ASSET_DIRECTORY + inputFile));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath(inputFile + "_fragmented"));
  }

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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MP4_FILE_ASSET_DIRECTORY + H265_HDR10_MP4);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(H265_HDR10_MP4 + "_fragmented_box_structure"));
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
      feedInputDataToMuxer(context, fragmentedMp4Muxer, MP4_FILE_ASSET_DIRECTORY + AUDIO_ONLY_MP4);
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    // For a 15 sec audio, there should be 8 fragments (2 sec fragment duration).
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        MuxerTestUtil.getExpectedDumpFilePath(AUDIO_ONLY_MP4 + "_fragmented_box_structure"));
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
          MP4_FILE_ASSET_DIRECTORY + AV1_MP4,
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
        MuxerTestUtil.getExpectedDumpFilePath(AV1_MP4 + "_fragmented"));
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
          MP4_FILE_ASSET_DIRECTORY + H264_MP4,
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
        MuxerTestUtil.getExpectedDumpFilePath(H264_MP4 + "_fragmented"));
  }
}
