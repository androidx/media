/*
 * Copyright 2026 The Android Open Source Project
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
import static androidx.media3.test.utils.AssetInfo.RAW_WAV_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_24LE_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_32LE_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_8BIT_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_FLOAT32_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_FLOAT64_PCM_ASSET;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import androidx.media3.extractor.wav.WavExtractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** End to end parameterized tests for {@link WavMuxer}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class WavMuxerEndToEndParameterizedTest {
  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        WAV_8BIT_PCM_ASSET.uri,
        WAV_ASSET.uri,
        RAW_WAV_ASSET.uri,
        WAV_24LE_PCM_ASSET.uri,
        WAV_32LE_PCM_ASSET.uri,
        WAV_FLOAT32_PCM_ASSET.uri,
        WAV_FLOAT64_PCM_ASSET.uri);
  }

  @Parameter public @MonotonicNonNull String inputFile;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createWavFile_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      feedInputDataToMuxer(context, wavMuxer, checkNotNull(inputFile));
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new WavExtractor(), outputFilePath);
    DumpFileAsserts.assertOutput(
        context, fakeExtractorOutput, MuxerTestUtil.getExpectedDumpFilePath(inputFile));
  }
}
