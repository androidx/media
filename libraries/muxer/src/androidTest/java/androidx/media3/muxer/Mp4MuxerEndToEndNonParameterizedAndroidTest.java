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
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end instrumentation tests for {@link Mp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public class Mp4MuxerEndToEndNonParameterizedAndroidTest {
  private static final String H265_HDR10_MP4 = "hdr10-720p.mp4";

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
  public void createMp4File_muxerNotClosed_createsPartiallyWrittenValidFile() throws Exception {
    Mp4Muxer mp4Muxer = new Mp4Muxer.Builder(checkNotNull(outputStream)).build();
    mp4Muxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    feedInputDataToMuxer(context, mp4Muxer, H265_HDR10_MP4);

    // Muxer not closed.

    // Audio sample written = 192 out of 195.
    // Video sample written = 125 out of 127.
    // Output is still a valid MP4 file.
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new Mp4Extractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        AndroidMuxerTestUtil.getExpectedDumpFilePath("partial_" + H265_HDR10_MP4));
  }

  @Test
  public void createMp4File_fromVp9Mp4InputFileSampleData_matchesExpected() throws Exception {
    // Contains CSD in vpcC format.
    String vp9Mp4 = "bbb_800x640_768kbps_30fps_vp9.mp4";
    @Nullable Mp4Muxer mp4Muxer = null;

    try {
      mp4Muxer = new Mp4Muxer.Builder(checkNotNull(outputStream)).build();
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, checkNotNull(vp9Mp4));
    } finally {
      if (mp4Muxer != null) {
        mp4Muxer.close();
      }
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
    // Only one VP9 video track should be present.
    assertThat(fakeExtractorOutput.trackOutputs.get(0).lastFormat.sampleMimeType)
        .isEqualTo(MimeTypes.VIDEO_VP9);
    // TODO: b/373822496 - The produced dump file is different on different SDK versions.
    /*DumpFileAsserts.assertOutput(
    context, fakeExtractorOutput, AndroidMuxerTestUtil.getExpectedDumpFilePath(vp9Mp4));*/
  }

  @Test
  public void createMp4File_withSampleBatchingDisabled_matchesExpected() throws Exception {
    @Nullable Mp4Muxer mp4Muxer = null;

    try {
      mp4Muxer =
          new Mp4Muxer.Builder(checkNotNull(outputStream)).setSampleBatchingEnabled(false).build();
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, checkNotNull(H265_HDR10_MP4));
    } finally {
      if (mp4Muxer != null) {
        mp4Muxer.close();
      }
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        AndroidMuxerTestUtil.getExpectedDumpFilePath("sample_batching_disabled_" + H265_HDR10_MP4));
  }

  @Test
  public void createMp4File_withSampleBatchingAndAttemptStreamableOutputDisabled_matchesExpected()
      throws Exception {
    @Nullable Mp4Muxer mp4Muxer = null;

    try {
      mp4Muxer =
          new Mp4Muxer.Builder(checkNotNull(outputStream))
              .setSampleBatchingEnabled(false)
              .setAttemptStreamableOutputEnabled(false)
              .build();
      mp4Muxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, mp4Muxer, checkNotNull(H265_HDR10_MP4));
    } finally {
      if (mp4Muxer != null) {
        mp4Muxer.close();
      }
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        AndroidMuxerTestUtil.getExpectedDumpFilePath(
            "sample_batching_and_attempt_streamable_output_disabled_" + H265_HDR10_MP4));
  }
}
