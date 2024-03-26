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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.muxer.AndroidMuxerTestUtil.feedInputDataToMuxer;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.DumpableMp4Box;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end instrumentation tests for {@link FragmentedMp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public class FragmentedMp4MuxerEndToEndAndroidTest {
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
  public void createFragmentedMp4File_fromInputFileSampleData_matchesExpected() throws IOException {
    @Nullable Muxer fragmentedMp4Muxer = null;

    try {
      fragmentedMp4Muxer = new FragmentedMp4Muxer(checkNotNull(outputStream));
      fragmentedMp4Muxer.addMetadata(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, fragmentedMp4Muxer, H265_HDR10_MP4);
    } finally {
      if (fragmentedMp4Muxer != null) {
        fragmentedMp4Muxer.close();
      }
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        AndroidMuxerTestUtil.getExpectedDumpFilePath(H265_HDR10_MP4 + "_fragmented"));
  }

  @Test
  public void createFragmentedMp4File_fromInputFileSampleData_matchesExpectedBoxStructure()
      throws IOException {
    @Nullable Muxer fragmentedMp4Muxer = null;

    try {
      fragmentedMp4Muxer = new FragmentedMp4Muxer(checkNotNull(outputStream));
      fragmentedMp4Muxer.addMetadata(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(context, fragmentedMp4Muxer, H265_HDR10_MP4);
    } finally {
      if (fragmentedMp4Muxer != null) {
        fragmentedMp4Muxer.close();
      }
    }

    DumpableMp4Box dumpableMp4Box =
        new DumpableMp4Box(
            ByteBuffer.wrap(TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath))));
    DumpFileAsserts.assertOutput(
        context,
        dumpableMp4Box,
        AndroidMuxerTestUtil.getExpectedDumpFilePath(H265_HDR10_MP4 + "_fragmented_box_structure"));
  }
}
