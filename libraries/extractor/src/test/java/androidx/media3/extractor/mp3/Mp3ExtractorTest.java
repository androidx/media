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
package androidx.media3.extractor.mp3;

import static org.junit.Assume.assumeFalse;

import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.ExtractorAsserts.AssertionConfig;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit test for {@link Mp3Extractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class Mp3ExtractorTest {

  private static final class SimulationConfigProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<ExtractorAsserts.SimulationConfig> provideValues(Context context) {
      return ExtractorAsserts.configs();
    }
  }

  private enum XingHeaderFlagConfig {
    NONE(/* flags= */ 0),
    INDEX_SEEKING(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING);

    private final @Mp3Extractor.Flags int flags;

    XingHeaderFlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithXingHeader(
      @TestParameter XingHeaderFlagConfig flagConfig,
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        "media/mp3/bear-vbr-xing-header.mp3",
        /* peekLimit= */ 1300,
        simulationConfig);
  }

  private enum XingHeaderNoTocFlagConfig {
    NONE(/* flags= */ 0),
    INDEX_SEEKING(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
    CBR_SEEKING(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
    CBR_SEEKING_ALWAYS(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS);

    private final @Mp3Extractor.Flags int flags;

    XingHeaderNoTocFlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithXingHeader_noTableOfContents(
      @TestParameter XingHeaderNoTocFlagConfig flagConfig,
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String inputFilePath = "media/mp3/bear-vbr-xing-header-no-toc.mp3";
    String dumpFilePrefix;
    if (!flagConfig.equals(XingHeaderNoTocFlagConfig.NONE)) {
      dumpFilePrefix =
          inputFilePath.replaceFirst("media", "extractordumps")
              + "."
              + Ascii.toLowerCase(flagConfig.name()).replace('_', '-');
    } else {
      dumpFilePrefix = null;
    }
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        inputFilePath,
        /* peekLimit= */ 1300,
        new AssertionConfig.Builder().setDumpFilesPrefix(dumpFilePrefix).build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithInfoHeader(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/test-cbr-info-header.mp3",
        /* peekLimit= */ 1200,
        simulationConfig);
  }

  // https://github.com/androidx/media/issues/1376#issuecomment-2117393653
  @Test
  public void mp3SampleWithInfoHeaderAndPcutFrame(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/test-cbr-info-header-pcut-frame.mp3",
        /* peekLimit= */ 1200,
        simulationConfig);
  }

  // https://github.com/androidx/media/issues/1480
  @Test
  public void mp3SampleWithInfoHeaderAndTrailingGarbage(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    // This test file is test-cbr-info-header.mp3 with 150kB of 0xDEADBEEF garbage appended on the
    // end. The test asserts that the extracted samples are the same as for
    // test-cbr-info-header.mp3.
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/test-cbr-info-header-trailing-garbage.mp3",
        /* peekLimit= */ 1200,
        new AssertionConfig.Builder()
            .setDumpFilesPrefix("extractordumps/mp3/test-cbr-info-header.mp3")
            .build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithVbriHeader(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-vbr-vbri-header.mp3",
        /* peekLimit= */ 1300,
        simulationConfig);
  }

  // https://github.com/androidx/media/issues/1904
  @Test
  public void mp3SampleWithVbriHeaderWithTruncatedToC(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-vbr-vbri-header-truncated-toc.mp3",
        /* peekLimit= */ 1300,
        simulationConfig);
  }

  private enum CbrSeekerFlagConfig {
    DEFAULT(/* flags= */ 0),
    CBR_SEEKING_ALWAYS(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS);

    private final @Mp3Extractor.Flags int flags;

    CbrSeekerFlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithCbrSeeker(
      @TestParameter CbrSeekerFlagConfig flagConfig,
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String dumpFilePrefix =
        flagConfig == CbrSeekerFlagConfig.CBR_SEEKING_ALWAYS
            ? "extractordumps/mp3/bear-cbr_cbr-seeking-always-enabled"
            : null;
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3",
        /* peekLimit= */ 1500,
        new AssertionConfig.Builder().setDumpFilesPrefix(dumpFilePrefix).build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithIndexSeeker(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
        "media/mp3/bear-vbr-no-seek-table.mp3",
        /* peekLimit= */ 1500,
        simulationConfig);
  }

  // https://github.com/androidx/media/issues/1563
  @Test
  public void mp3CbrSampleWithNoSeekTableAndTrailingGarbage(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    assumeFalse(
        "Skipping I/O error testing with unknown length due to b/362727473",
        simulationConfig.simulateIOErrors && simulationConfig.simulateUnknownLength);
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-cbr-no-seek-table-trailing-garbage.mp3",
        /* peekLimit= */ 1500,
        simulationConfig);
  }

  @Test
  public void trimmedMp3Sample(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new, "media/mp3/play-trimmed.mp3", /* peekLimit= */ 1200, simulationConfig);
  }

  private enum Id3FlagConfig {
    ENABLED(/* flags= */ 0),
    DISABLED(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);

    private final @Mp3Extractor.Flags int flags;

    Id3FlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithId3(
      @TestParameter Id3FlagConfig flagConfig,
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String dumpFilePrefix =
        flagConfig == Id3FlagConfig.ENABLED
            ? "extractordumps/mp3/bear-id3-enabled"
            : "extractordumps/mp3/bear-id3-disabled";
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        "media/mp3/bear-id3.mp3",
        /* peekLimit= */ 41_000,
        new AssertionConfig.Builder().setDumpFilesPrefix(dumpFilePrefix).build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithId3NumericGenre(
      @TestParameter(valuesProvider = SimulationConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-id3-numeric-genre.mp3",
        /* peekLimit= */ 41_000,
        simulationConfig);
  }
}
