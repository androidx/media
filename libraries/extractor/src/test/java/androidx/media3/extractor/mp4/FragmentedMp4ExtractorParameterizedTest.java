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
package androidx.media3.extractor.mp4;

import static androidx.media3.extractor.mp4.FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.ExtractorAsserts.ExtractorFactory;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/**
 * Tests for {@link FragmentedMp4Extractor} that test behaviours where sniffing must be tested using
 * parameterization and {@link ExtractorAsserts}.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class FragmentedMp4ExtractorParameterizedTest {

  @Parameters(name = "{0},subtitlesParsedDuringExtraction={1},readWithinGopSampleDependencies={2}")
  public static List<Object[]> params() {
    List<Object[]> parameterList = new ArrayList<>();
    for (ExtractorAsserts.SimulationConfig config : ExtractorAsserts.configs()) {
      parameterList.add(
          new Object[] {
            config,
            /* subtitlesParsedDuringExtraction */ true,
            /* readWithinGopSampleDependencies */ false
          });
      parameterList.add(
          new Object[] {
            config,
            /* subtitlesParsedDuringExtraction */ false,
            /* readWithinGopSampleDependencies */ false
          });
      parameterList.add(
          new Object[] {
            config,
            /* subtitlesParsedDuringExtraction */ true,
            /* readWithinGopSampleDependencies */ true
          });
    }
    return parameterList;
  }

  @Parameter(0)
  public ExtractorAsserts.SimulationConfig simulationConfig;

  @Parameter(1)
  public boolean subtitlesParsedDuringExtraction;

  @Parameter(2)
  public boolean readWithinGopSampleDependencies;

  @Test
  public void sample() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_fragmented.mp4");
  }

  @Test
  public void sampleSeekable() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_fragmented_seekable.mp4");
  }

  @Test
  public void sampleWithSeiPayloadParsing() throws Exception {
    // Enabling the CEA-608 track enables SEI payload parsing.
    List<Format> closedCaptions =
        Collections.singletonList(
            new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build());

    assertExtractorBehavior(closedCaptions, "media/mp4/sample_fragmented_sei.mp4");
  }

  @Test
  public void sampleWithAc3Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_ac3_fragmented.mp4");
  }

  @Test
  public void sampleWithAc4Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_ac4_fragmented.mp4");
  }

  @Test
  public void sampleWithProtectedAc4Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_ac4_protected.mp4");
  }

  @Test
  public void sampleWithEac3Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_eac3_fragmented.mp4");
  }

  @Test
  public void sampleWithEac3jocTrack() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_eac3joc_fragmented.mp4");
  }

  @Test
  public void sampleWithOpusTrack() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_opus_fragmented.mp4");
  }

  @Test
  public void samplePartiallyFragmented() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_partially_fragmented.mp4");
  }

  /** https://github.com/google/ExoPlayer/issues/10381 */
  @Test
  public void sampleWithLargeBitrates() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_fragmented_large_bitrates.mp4");
  }

  @Test
  public void sampleWithMhm1BlCicp1Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_mhm1_bl_cicp1_fragmented.mp4");
  }

  @Test
  public void sampleWithMhm1LcblCicp1Track() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_mhm1_lcbl_cicp1_fragmented.mp4");
  }

  @Test
  public void sampleWithMhm1BlConfigChangeTrack() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_mhm1_bl_configchange_fragmented.mp4");
  }

  @Test
  public void sampleWithMhm1LcblConfigChangeTrack() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(),
        "media/mp4/sample_mhm1_lcbl_configchange_fragmented.mp4");
  }

  @Test
  public void sampleWithIamfTrack() throws Exception {
    assertExtractorBehavior(
        /* closedCaptionFormats= */ ImmutableList.of(), "media/mp4/sample_fragmented_iamf.mp4");
  }

  private void assertExtractorBehavior(List<Format> closedCaptionFormats, String file)
      throws IOException {
    ExtractorAsserts.AssertionConfig.Builder assertionConfigBuilder =
        new ExtractorAsserts.AssertionConfig.Builder();
    if (readWithinGopSampleDependencies) {
      String dumpFilesPrefix =
          file.replaceFirst("media", "extractordumps") + ".reading_within_gop_sample_dependencies";
      assertionConfigBuilder.setDumpFilesPrefix(dumpFilesPrefix);
    }
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(
            closedCaptionFormats, subtitlesParsedDuringExtraction, readWithinGopSampleDependencies),
        file,
        assertionConfigBuilder.build(),
        simulationConfig);
  }

  private static ExtractorFactory getExtractorFactory(
      List<Format> closedCaptionFormats,
      boolean subtitlesParsedDuringExtraction,
      boolean readWithinGopSampleDependencies) {
    SubtitleParser.Factory subtitleParserFactory;
    @FragmentedMp4Extractor.Flags int flags;
    if (subtitlesParsedDuringExtraction) {
      subtitleParserFactory = new DefaultSubtitleParserFactory();
      flags = 0;
    } else {
      subtitleParserFactory = SubtitleParser.Factory.UNSUPPORTED;
      flags = FLAG_EMIT_RAW_SUBTITLE_DATA;
    }
    if (readWithinGopSampleDependencies) {
      flags |= FragmentedMp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES;
    }

    @FragmentedMp4Extractor.Flags int finalFlags = flags;
    return () ->
        new FragmentedMp4Extractor(
            subtitleParserFactory,
            finalFlags,
            /* timestampAdjuster= */ null,
            /* sideloadedTrack= */ null,
            closedCaptionFormats,
            /* additionalEmsgTrackOutput= */ null);
  }
}
