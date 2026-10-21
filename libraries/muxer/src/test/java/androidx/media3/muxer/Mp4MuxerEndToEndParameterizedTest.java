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

import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static androidx.media3.test.utils.AssetInfo.AMR_NB_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.AMR_WB_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.APV_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.E_AC3_JOC_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.E_AC3_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H263_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_DOLBY_VISION_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_WITH_FIRST_PTS_10_SEC_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_WITH_NON_REFERENCE_B_FRAMES_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H264_WITH_PYRAMID_B_FRAMES_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H265_DOLBY_VISION_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.H265_WITH_METADATA_TRACK_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.IAMF_OPUS_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_720P_4_SECOND_HDR10;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_AV1_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MPEG4_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.OPUS_OGG_ASSET;
import static androidx.media3.test.utils.AssetInfo.RAW_WAV_ASSET;
import static androidx.media3.test.utils.AssetInfo.VORBIS_OGG_ASSET;
import static androidx.media3.test.utils.AssetInfo.VP9_MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.VP9_WEBM_ASSET;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** End to end parameterized tests for {@link Mp4Muxer}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class Mp4MuxerEndToEndParameterizedTest {
  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        H263_3GP_ASSET.uri,
        H264_MP4_ASSET.uri,
        H264_WITH_NON_REFERENCE_B_FRAMES_MP4_ASSET.uri,
        H264_WITH_PYRAMID_B_FRAMES_MP4_ASSET.uri,
        H264_WITH_FIRST_PTS_10_SEC_MP4_ASSET.uri,
        H264_DOLBY_VISION_MP4_ASSET.uri,
        H265_DOLBY_VISION_MP4_ASSET.uri,
        MP4_ASSET_720P_4_SECOND_HDR10.uri,
        H265_WITH_METADATA_TRACK_MP4_ASSET.uri,
        APV_MP4_ASSET.uri,
        MP4_ASSET_AV1_VIDEO.uri,
        MPEG4_MP4_ASSET.uri,
        VP9_MP4_ASSET.uri,
        VP9_WEBM_ASSET.uri,
        AMR_NB_3GP_ASSET.uri,
        AMR_WB_3GP_ASSET.uri,
        E_AC3_MP4_ASSET.uri,
        E_AC3_JOC_MP4_ASSET.uri,
        IAMF_OPUS_MP4_ASSET.uri,
        OPUS_OGG_ASSET.uri,
        VORBIS_OGG_ASSET.uri,
        RAW_WAV_ASSET.uri);
  }

  @Parameter public @MonotonicNonNull String inputFile;
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createMp4File_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.mp4").getPath();

    try (Mp4Muxer mp4Muxer =
        new Mp4Muxer.Builder(SeekableMuxerOutput.of(new FileOutputStream(outputPath))).build()) {
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, checkNotNull(inputFile));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new Mp4Extractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            MuxerTestUtil.getSubstitutedPath(inputFile, MuxerTestUtil.MP4)));
  }
}
