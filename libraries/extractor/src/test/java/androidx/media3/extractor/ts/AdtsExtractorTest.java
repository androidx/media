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
package androidx.media3.extractor.ts;

import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.ExtractorAsserts.AssertionConfig;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** Unit test for {@link AdtsExtractor}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class AdtsExtractorTest {

  @Parameters(name = "{0}")
  public static ImmutableList<ExtractorAsserts.SimulationConfig> params() {
    return ExtractorAsserts.configs();
  }

  @Parameter public ExtractorAsserts.SimulationConfig simulationConfig;

  @Test
  public void sample() throws Exception {
    ExtractorAsserts.assertBehavior(AdtsExtractor::new, "media/ts/sample.adts", simulationConfig);
  }

  @Test
  public void sample_with_id3() throws Exception {
    ExtractorAsserts.assertBehavior(
        AdtsExtractor::new, "media/ts/sample_with_id3.adts", simulationConfig);
  }

  // https://github.com/androidx/media/issues/3464
  @Test
  public void sample_midFrameStart() throws Exception {
    // sample_mid_frame_start.adts is sample.adts with its first 31485 bytes moved to the end, so
    // that the stream starts 100 bytes into frame 142, as when joining a live stream part way
    // through a frame. The payload of that partial frame contains a false sync word (at offset 62)
    // whose frame length points beyond the first 2 KiB packet, so it must not be mistaken for the
    // first frame.
    ExtractorAsserts.assertBehavior(
        AdtsExtractor::new, "media/ts/sample_mid_frame_start.adts", simulationConfig);
  }

  @Test
  public void sample_withSeeking() throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new AdtsExtractor(/* flags= */ AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
        "media/ts/sample.adts",
        new AssertionConfig.Builder()
            .setDumpFilesPrefix("extractordumps/ts/sample_cbs.adts")
            .build(),
        simulationConfig);
  }

  // https://github.com/google/ExoPlayer/issues/6700
  @Test
  public void sample_withSeekingAndTruncatedFile() throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new AdtsExtractor(/* flags= */ AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
        "media/ts/sample_cbs_truncated.adts",
        simulationConfig);
  }
}
