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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.muxer.AndroidMuxerTestUtil.feedInputDataToMuxer;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** End to end parameterized instrumentation tests for {@link Mp4Muxer}. */
@RunWith(Parameterized.class)
public class Mp4MuxerEndToEndParameterizedAndroidTest {
  // Video Codecs
  private static final String H263_3GP = "bbb_176x144_128kbps_15fps_h263.3gp";
  private static final String H264_MP4 = "sample_no_bframes.mp4";
  private static final String H264_WITH_NON_REFERENCE_B_FRAMES_MP4 =
      "bbb_800x640_768kbps_30fps_avc_non_reference_3b.mp4";
  private static final String H264_WITH_PYRAMID_B_FRAMES_MP4 =
      "bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4";
  private static final String H264_WITH_FIRST_PTS_10_SEC =
      "bbb_800x640_768kbps_30fps_avc_2b_firstpts_10_sec.mp4";
  private static final String H265_HDR10_MP4 = "hdr10-720p.mp4";
  private static final String H265_WITH_METADATA_TRACK_MP4 = "h265_with_metadata_track.mp4";
  private static final String AV1_MP4 = "sample_av1.mp4";
  private static final String MPEG4_MP4 = "bbb_176x144_192kbps_15fps_mpeg4.mp4";

  // Contains CSD in CodecPrivate format.
  private static final String VP9_WEB = "bbb_642x642_768kbps_30fps_vp9.webm";
  // Audio Codecs
  private static final String AMR_NB_3GP = "bbb_mono_8kHz_12.2kbps_amrnb.3gp";
  private static final String AMR_WB_3GP = "bbb_mono_16kHz_23.05kbps_amrwb.3gp";
  private static final String OPUS_OGG = "bbb_6ch_8kHz_opus.ogg";
  private static final String VORBIS_OGG = "bbb_1ch_16kHz_q10_vorbis.ogg";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        H263_3GP,
        H264_MP4,
        H264_WITH_NON_REFERENCE_B_FRAMES_MP4,
        H264_WITH_PYRAMID_B_FRAMES_MP4,
        H264_WITH_FIRST_PTS_10_SEC,
        H265_HDR10_MP4,
        H265_WITH_METADATA_TRACK_MP4,
        AV1_MP4,
        MPEG4_MP4,
        VP9_WEB,
        AMR_NB_3GP,
        AMR_WB_3GP,
        OPUS_OGG,
        VORBIS_OGG);
  }

  @Parameter public @MonotonicNonNull String inputFile;
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();
  private @MonotonicNonNull String outputPath;
  private @MonotonicNonNull FileOutputStream outputStream;

  @Before
  public void setUp() throws Exception {
    outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();
    outputStream = new FileOutputStream(outputPath);
  }

  @After
  public void tearDown() throws IOException {
    checkNotNull(outputStream).close();
  }

  @Test
  public void createMp4File_fromInputFileSampleData_matchesExpected() throws Exception {
    @Nullable Mp4Muxer mp4Muxer = null;

    try {
      mp4Muxer = new Mp4Muxer.Builder(checkNotNull(outputStream)).build();
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, checkNotNull(inputFile));
    } finally {
      if (mp4Muxer != null) {
        mp4Muxer.close();
      }
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new Mp4Extractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context, fakeExtractorOutput, AndroidMuxerTestUtil.getExpectedDumpFilePath(inputFile));
  }
}
