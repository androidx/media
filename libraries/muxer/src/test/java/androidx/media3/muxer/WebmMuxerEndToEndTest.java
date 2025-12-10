/*
 * Copyright (C) 2025 The Android Open Source Project
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
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.FileOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** End to end parameterized tests for {@link WebmMuxer}. */
@RunWith(RobolectricTestParameterInjector.class)
public class WebmMuxerEndToEndTest {

  private enum TestFile {
    VP8_WEBM("asset:///media/mkv/", "bbb_960x540_60fps_vp8.webm"),
    VP9_WEBM("asset:///media/mp4/", "bbb_642x642_768kbps_30fps_vp9.webm"),
    OPUS_WEBM("asset:///media/mkv/", "bbb_1ch_48kHz_q10_opus.webm"),
    VORBIS_WEBM("asset:///media/mkv/", "bbb_1ch_12kHz_q10_vorbis.webm"),
    VP9_MP4("asset:///media/mp4/", "bbb_800x640_768kbps_30fps_vp9.mp4");

    final String directoryName;
    final String fileName;

    TestFile(String directoryName, String fileName) {
      this.directoryName = directoryName;
      this.fileName = fileName;
    }
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @TestParameter private TestFile testFile;

  @Test
  public void createWebmFile_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.webm").getPath();

    try (WebmMuxer webmMuxer =
        new WebmMuxer.Builder(SeekableMuxerOutput.of(new FileOutputStream(outputPath))).build()) {
      feedInputDataToMuxer(
          context, webmMuxer, checkNotNull(testFile.directoryName + testFile.fileName));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new MatroskaExtractor(new DefaultSubtitleParserFactory()), outputPath);
    DumpFileAsserts.assertOutput(
        context,
        fakeExtractorOutput,
        MuxerTestUtil.getExpectedDumpFilePath("webm/" + testFile.fileName));
  }
}
