/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.wav;

import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.ExtractorAsserts.AssertionConfig;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;

/** Unit test for {@link WavExtractor}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class WavExtractorTest {

  @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
  public static ImmutableList<ExtractorAsserts.SimulationConfig> params() {
    return ExtractorAsserts.configs();
  }

  @ParameterizedRobolectricTestRunner.Parameter(0)
  public ExtractorAsserts.SimulationConfig simulationConfig;

  @Test
  public void sample() throws Exception {
    ExtractorAsserts.assertBehavior(WavExtractor::new, "media/wav/sample.wav", simulationConfig);
  }

  @Test
  public void sample_withTrailingBytes_extractsSameData() throws Exception {
    ExtractorAsserts.assertBehavior(
        WavExtractor::new,
        "media/wav/sample_with_trailing_bytes.wav",
        new AssertionConfig.Builder().setDumpFilesPrefix("extractordumps/wav/sample.wav").build(),
        simulationConfig);
  }

  @Test
  public void sample_withOddMetadataChunkSize_extractsSameData() throws Exception {
    ExtractorAsserts.assertBehavior(
        WavExtractor::new,
        "media/wav/sample_with_odd_metadata_chunk_size.wav",
        new AssertionConfig.Builder().setDumpFilesPrefix("extractordumps/wav/sample.wav").build(),
        simulationConfig);
  }

  @Test
  public void sample_imaAdpcm() throws Exception {
    ExtractorAsserts.assertBehavior(
        WavExtractor::new, "media/wav/sample_ima_adpcm.wav", simulationConfig);
  }

  @Test
  public void sample_rf64() throws Exception {
    ExtractorAsserts.assertBehavior(
        WavExtractor::new, "media/wav/sample_rf64.wav", simulationConfig);
  }
}
