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
  private static final String ASSET_DIRECTORY = "asset:///media/";
  private static final String PCM_8_BIT_MONO = "wav/sample_8bit.wav";
  private static final String PCM_16_BIT_MONO = "wav/sample.wav";
  private static final String PCM_16_BIT_STEREO = "wav/bbb_2ch_44kHz.wav";
  private static final String PCM_24_BIT_MONO = "wav/sine_24le.wav";
  private static final String PCM_32_BIT_STEREO = "wav/sine_32le.wav";
  private static final String PCM_FLOAT_32_MONO = "wav/sample_float32.wav";
  private static final String PCM_FLOAT_64_MONO = "wav/sample_float64.wav";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        PCM_8_BIT_MONO,
        PCM_16_BIT_MONO,
        PCM_16_BIT_STEREO,
        PCM_24_BIT_MONO,
        PCM_32_BIT_STEREO,
        PCM_FLOAT_32_MONO,
        PCM_FLOAT_64_MONO);
  }

  @Parameter public @MonotonicNonNull String inputFile;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createWavFile_fromInputFileSampleData_matchesExpected() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      feedInputDataToMuxer(context, wavMuxer, ASSET_DIRECTORY + inputFile);
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(new WavExtractor(), outputFilePath);
    DumpFileAsserts.assertOutput(
        context, fakeExtractorOutput, MuxerTestUtil.getExpectedDumpFilePath(inputFile));
  }
}
