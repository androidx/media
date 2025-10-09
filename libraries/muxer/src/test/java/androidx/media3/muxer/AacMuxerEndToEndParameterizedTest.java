/*
 * Copyright 2025 The Android Open Source Project
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
import androidx.media3.extractor.ts.AdtsExtractor;
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

/** End to end parameterized tests for {@link AacMuxer}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class AacMuxerEndToEndParameterizedTest {
  private static final String AAC_M4A = "bbb_1ch_8kHz_aac_lc.m4a";
  private static final String AAC_MP4 = "bbb_1ch_16kHz_aac.mp4";
  private static final String RAW_AAC = "bbb_1ch_8kHz_aac_lc.aac";

  public static final String MP4_FILE_ASSET_DIRECTORY = "asset:///media/mp4/";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(AAC_M4A, AAC_MP4, RAW_AAC);
  }

  @Parameter public @MonotonicNonNull String inputFile;
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createAacFile_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.aac").getPath();

    try (AacMuxer muxer = new AacMuxer(new FileOutputStream(outputPath))) {
      feedInputDataToMuxer(context, muxer, checkNotNull(MP4_FILE_ASSET_DIRECTORY + inputFile));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new AdtsExtractor(), checkNotNull(outputPath));
    DumpFileAsserts.assertOutput(
        context, fakeExtractorOutput, MuxerTestUtil.getExpectedDumpFilePath(inputFile));
  }
}
