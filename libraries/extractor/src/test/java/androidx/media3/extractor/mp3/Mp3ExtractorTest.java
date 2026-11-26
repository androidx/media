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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assume.assumeFalse;

import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.metadata.id3.ApicFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.ExtractorAsserts.AssertionConfig;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.Id3TestUtil;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit test for {@link Mp3Extractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class Mp3ExtractorTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String file = "media/mp3/bear-vbr-xing-header-no-toc.mp3";
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        file,
        /* peekLimit= */ 1300,
        new AssertionConfig.Builder().setDumpFilesPrefix(getDumpFilePath(file, flagConfig)).build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithInfoHeader(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/test-cbr-info-header.mp3",
        /* peekLimit= */ 1200,
        simulationConfig);
  }

  @Test
  public void mp3SampleWithInfoHeader_usesGaplessDurationAndAverageBitrate() throws Exception {
    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            new Mp3Extractor(),
            ApplicationProvider.getApplicationContext(),
            "media/mp3/test-cbr-info-header.mp3");

    assertThat(output.seekMap.getDurationUs()).isEqualTo(999_977);
    assertThat(output.trackOutputs.get(0).getDurationUs()).isEqualTo(999_977);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(66_874);
  }

  @Test
  public void mp3SampleWithInfoHeader_invalidDataSizeFallsBackToFrameBitrate() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/test-cbr-info-header.mp3");
    int infoTagOffset = Bytes.indexOf(fileBytes, new byte[] {'I', 'n', 'f', 'o'});
    checkState(infoTagOffset >= 0);
    ByteBuffer fileBytesBuffer = ByteBuffer.wrap(fileBytes);
    int infoFramePosition = findMpegFramePositionBeforeTag(fileBytesBuffer, infoTagOffset);
    MpegAudioUtil.Header infoFrameHeader = new MpegAudioUtil.Header();
    checkState(infoFrameHeader.setForHeaderData(fileBytesBuffer.getInt(infoFramePosition)));
    fileBytesBuffer.putInt(infoTagOffset + 12, infoFrameHeader.frameSize);

    FakeExtractorOutput output =
        extractUntilSeekMap(new Mp3Extractor(), fileBytes, /* simulateUnknownLength= */ false);

    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(64_000);
  }

  // https://github.com/androidx/media/issues/1376#issuecomment-2117393653
  @Test
  public void mp3SampleWithInfoHeaderAndPcutFrame(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-vbr-vbri-header-truncated-toc.mp3",
        /* peekLimit= */ 1300,
        simulationConfig);
  }

  private enum CbrSeekerFlagConfig {
    NONE(/* flags= */ 0),
    CBR_SEEKING_ALWAYS(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS);

    private final @Mp3Extractor.Flags int flags;

    CbrSeekerFlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithCbrSeeker(
      @TestParameter CbrSeekerFlagConfig flagConfig,
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String file = "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3";
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        file,
        /* peekLimit= */ 1500,
        new AssertionConfig.Builder().setDumpFilesPrefix(getDumpFilePath(file, flagConfig)).build(),
        simulationConfig);
  }

  private static final class KnowLengthConfigProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<ExtractorAsserts.SimulationConfig> provideValues(
        TestParameterValuesProvider.Context context) {
      return ExtractorAsserts.configs().stream()
          .filter(config -> !config.simulateUnknownLength)
          .collect(toImmutableList());
    }
  }

  @Test
  public void mp3Sample_withIndexSeekingFlag_usesCbrSeekerForKnownLength(
      @TestParameter(valuesProvider = KnowLengthConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
        "media/mp3/bear-vbr-no-seek-table.mp3",
        /* peekLimit= */ 1500,
        simulationConfig);
  }

  @Test
  public void mp3CbrSampleWithIndexSeekingFlagAndUnknownLength_reportsUnsetAverageBitrate()
      throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(),
            "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ true);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void read_disableCbrAssumptionOnly_returnsUnseekableSeekMap() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
    assertThat(output.seekMap.getSeekPoints(/* timeUs= */ 0).first)
        .isEqualTo(new SeekPoint(/* timeUs= */ 0, /* position= */ 224));
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void read_disableCbrAssumptionAndIndexSeeking_usesIndexSeeker() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");
    @Mp3Extractor.Flags
    int flags =
        Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
            | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING;

    FakeExtractorOutput initialOutput =
        extractUntilSeekMap(new Mp3Extractor(flags), fileBytes, /* simulateUnknownLength= */ false);
    FakeExtractorOutput fullOutput =
        TestUtil.extractAllSamplesFromByteArray(new Mp3Extractor(flags), fileBytes);

    assertThat(initialOutput.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(initialOutput.seekMap.isSeekable()).isTrue();
    assertThat(initialOutput.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
    assertThat(fullOutput.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(fullOutput.seekMap.getDurationUs()).isEqualTo(2_807_979);
  }

  @Test
  public void read_disableCbrAssumptionAndCbrSeeking_usesCbrSeeker() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(ConstantBitrateSeeker.class);
    assertThat(output.seekMap.isSeekable()).isTrue();
  }

  @Test
  public void read_disableCbrAssumptionCbrSeekingUnknownLength_unseekable() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ true);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void read_disableCbrAssumptionCbrSeekingAlwaysUnknownLength_usesCbr() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS),
            fileBytes,
            /* simulateUnknownLength= */ true);

    assertThat(output.seekMap).isInstanceOf(ConstantBitrateSeeker.class);
    assertThat(output.seekMap.isSeekable()).isTrue();
  }

  @Test
  public void read_disableCbrAssumptionWithIndexAndCbrSeeking_prefersIndexSeeker()
      throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-vbr-no-seek-table.mp3");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
                    | Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.isSeekable()).isTrue();
  }

  private enum ExplicitMetadataSample {
    XING("media/mp3/bear-vbr-xing-header.mp3", XingSeeker.class),
    VBRI("media/mp3/bear-vbr-vbri-header.mp3", VbriSeeker.class),
    INFO("media/mp3/test-cbr-info-header.mp3", ConstantBitrateSeeker.class),
    MLLT("media/mp3/bear-id3.mp3", MlltSeeker.class);

    private final String assetPath;
    private final Class<? extends Seeker> expectedSeekerClass;

    ExplicitMetadataSample(String assetPath, Class<? extends Seeker> expectedSeekerClass) {
      this.assetPath = assetPath;
      this.expectedSeekerClass = expectedSeekerClass;
    }
  }

  @Test
  public void read_explicitMetadataWithDisableCbrAssumption_usesMetadataSeeker(
      @TestParameter ExplicitMetadataSample sample) throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), sample.assetPath);

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(sample.expectedSeekerClass);
    assertThat(output.seekMap.isSeekable()).isTrue();
  }

  @Test
  public void read_disableCbrAssumptionWithTlen_usesTlenDuration() throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(2_800_000);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(109_029);
  }

  @Test
  public void read_disableCbrAssumptionAndCbrSeekingWithTlen_usesTlenDuration() throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(ConstantBitrateSeeker.class);
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(2_800_000);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(109_029);
  }

  @Test
  public void read_disableCbrAssumptionAndIndexSeekingWithTlen_usesTlenThenRefines()
      throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");
    @Mp3Extractor.Flags
    int flags =
        Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
            | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING;

    FakeExtractorOutput initialOutput =
        extractUntilSeekMap(new Mp3Extractor(flags), fileBytes, /* simulateUnknownLength= */ false);
    FakeExtractorOutput fullOutput =
        TestUtil.extractAllSamplesFromByteArray(new Mp3Extractor(flags), fileBytes);

    assertThat(initialOutput.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(initialOutput.seekMap.getDurationUs()).isEqualTo(2_800_000);
    assertThat(fullOutput.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(fullOutput.seekMap.getDurationUs()).isEqualTo(2_807_979);
  }

  @Test
  public void read_disableCbrAssumptionIndexAndDisableId3WithTlen_stillUsesTlen() throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
                    | Mp3Extractor.FLAG_DISABLE_ID3_METADATA),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.getDurationUs()).isEqualTo(2_800_000);
    assertThat(output.trackOutputs.get(0).lastFormat.metadata).isNull();
  }

  @Test
  public void read_disableCbrAssumptionIndexDisableId3WithId3v22Tle_usesTle() throws Exception {
    byte[] fileBytes =
        prependId3v22TleFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING
                    | Mp3Extractor.FLAG_DISABLE_ID3_METADATA),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.getDurationUs()).isEqualTo(2_800_000);
    assertThat(output.trackOutputs.get(0).lastFormat.metadata).isNull();
  }

  @Test
  public void read_disableCbrAssumptionWithMalformedTlen_ignoresTlen(
      @TestParameter({"1776.4", "", "0", "-100", "invalid"}) String tlenValue) throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            tlenValue);

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(
                Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION
                    | Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void read_unknownLengthIndexSeekingWithTlen_usesTlenAsInitialDuration() throws Exception {
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3"),
            "2821");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ true);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.getDurationUs()).isEqualTo(2_821_000);
  }

  @Test
  public void read_disableCbrAssumptionWithTlenAndFlagDisabled_ignoresTlen() throws Exception {
    Flags.disableFlag(Flags.FLAG_USE_MP3_ID3_TLEN_DURATION);
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-vbr-no-seek-table.mp3"),
            "2800");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_DISABLE_CONSTANT_BITRATE_ASSUMPTION),
            fileBytes,
            /* simulateUnknownLength= */ false);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
    assertThat(output.trackOutputs.get(0).lastFormat.averageBitrate).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void read_unknownLengthIndexSeekingWithTlenAndFlagDisabled_ignoresTlen() throws Exception {
    Flags.disableFlag(Flags.FLAG_USE_MP3_ID3_TLEN_DURATION);
    byte[] fileBytes =
        prependId3v23TlenFrame(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3"),
            "2821");

    FakeExtractorOutput output =
        extractUntilSeekMap(
            new Mp3Extractor(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            fileBytes,
            /* simulateUnknownLength= */ true);

    assertThat(output.seekMap).isInstanceOf(IndexSeeker.class);
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
  }

  // https://github.com/androidx/media/issues/1563
  @Test
  public void mp3CbrSampleWithNoSeekTableAndTrailingGarbage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
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
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new, "media/mp3/play-trimmed.mp3", /* peekLimit= */ 1200, simulationConfig);
  }

  private enum Id3FlagConfig {
    ID3_ENABLED(/* flags= */ 0),
    ID3_DISABLED(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);

    private final @Mp3Extractor.Flags int flags;

    Id3FlagConfig(@Mp3Extractor.Flags int flags) {
      this.flags = flags;
    }
  }

  @Test
  public void mp3SampleWithId3(
      @TestParameter Id3FlagConfig flagConfig,
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    String file = "media/mp3/bear-id3.mp3";
    ExtractorAsserts.assertBehavior(
        () -> new Mp3Extractor(flagConfig.flags),
        file,
        /* peekLimit= */ 41_000,
        new AssertionConfig.Builder().setDumpFilesPrefix(getDumpFilePath(file, flagConfig)).build(),
        simulationConfig);
  }

  @Test
  public void mp3SampleWithId3NumericGenre(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-id3-numeric-genre.mp3",
        /* peekLimit= */ 41_000,
        simulationConfig);
  }

  // https://github.com/androidx/media/issues/2818
  @Test
  public void cbrSeeker_seekToEndOfStreamAfterPartialRead_doesNotIncorrectlyShortenDuration()
      throws Exception {
    String fileName = "media/mp3/bear-cbr-variable-frame-size-no-seek-table.mp3";
    byte[] fileBytes = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    Mp3Extractor extractor = new Mp3Extractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(fileBytes).build();
    PositionHolder positionHolder = new PositionHolder();
    // Read until the seek map is initialized, which also ensures a sample is read in Mp3Extractor.
    while (output.seekMap == null) {
      int unused = extractor.read(input, positionHolder);
    }
    long durationBeforeSeekUs = output.seekMap.getDurationUs();
    SeekPoint seekPoint = output.seekMap.getSeekPoints(durationBeforeSeekUs).first;

    extractor.seek(seekPoint.position, seekPoint.timeUs);
    input.setPosition((int) seekPoint.position);
    while (extractor.read(input, positionHolder) != Extractor.RESULT_END_OF_INPUT) {}

    assertThat(output.seekMap.getDurationUs()).isEqualTo(durationBeforeSeekUs);
  }

  // https://github.com/androidx/media/issues/2713
  @Test
  public void sampleWith100kBGarbagePrefix_sniffsSuccessfully() throws Exception {
    ExtractorAsserts.assertSniff(
        new Mp3Extractor(),
        "media/mp3/100kB-garbage-prefix.mp3",
        /* peekLimit= */ 100_648,
        /* expectedResult= */ true);
  }

  // https://github.com/androidx/media/issues/2713
  @Test
  public void sampleWith200kBGarbagePrefix_sniffingFailsAfterPeeking128kB() throws Exception {
    ExtractorAsserts.assertSniff(
        new Mp3Extractor(),
        "media/mp3/200kB-garbage-prefix.mp3",
        /* peekLimit= */ 131_082,
        /* expectedResult= */ false);
  }

  @Test
  public void sampleWithLameReplayGainFast(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-vbr-xing-header-replaygain-fast.mp3",
        /* peekLimit= */ 1201,
        simulationConfig);
  }

  @Test
  public void sampleWithLameReplayGainAccurate(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        Mp3Extractor::new,
        "media/mp3/bear-vbr-xing-header-replaygain-accurate.mp3",
        /* peekLimit= */ 1201,
        simulationConfig);
  }

  @Test
  public void mp3SampleWithId3_withDisableArtworkFlag_parsesTextButOmitsArtwork() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mp3/bear-id3.mp3");
    Mp3Extractor extractor = new Mp3Extractor(Mp3Extractor.FLAG_DISABLE_ARTWORK_METADATA);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(fileBytes).build();
    PositionHolder positionHolder = new PositionHolder();

    while (output.seekMap == null) {
      int unused = extractor.read(input, positionHolder);
    }
    Format audioFormat = output.trackOutputs.get(0).lastFormat;

    assertThat(audioFormat.metadata).isNotNull();
    assertThat(audioFormat.metadata.length()).isGreaterThan(0);
    boolean foundText = false;
    boolean foundArtwork = false;
    for (int i = 0; i < audioFormat.metadata.length(); i++) {
      Metadata.Entry entry = audioFormat.metadata.get(i);
      if (entry instanceof TextInformationFrame) {
        foundText = true;
      } else if (entry instanceof ApicFrame) {
        foundArtwork = true;
      }
    }
    assertThat(foundText).isTrue();
    assertThat(foundArtwork).isFalse();
  }

  private static @Nullable String getDumpFilePath(String inputFilePath, Enum<?> flagConfig) {
    String configName = flagConfig.name();
    if (configName.equals("NONE")) {
      return null;
    }
    String suffix = "." + Ascii.toLowerCase(configName).replace('_', '-');
    return inputFilePath.replaceFirst("media", "extractordumps") + suffix;
  }

  private static FakeExtractorOutput extractUntilSeekMap(
      Mp3Extractor extractor, byte[] fileBytes, boolean simulateUnknownLength) throws Exception {
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(fileBytes)
            .setSimulateUnknownLength(simulateUnknownLength)
            .build();
    PositionHolder positionHolder = new PositionHolder();

    while (output.seekMap == null) {
      assertThat(extractor.read(input, positionHolder)).isNotEqualTo(Extractor.RESULT_END_OF_INPUT);
    }
    return output;
  }

  private static byte[] prependId3v23TlenFrame(byte[] mp3Bytes, String tlenValue) {
    return Bytes.concat(
        Id3TestUtil.buildSingleFrameTag(
            /* majorVersion= */ 3,
            "TLEN",
            Bytes.concat(new byte[] {0}, tlenValue.getBytes(ISO_8859_1))),
        mp3Bytes);
  }

  private static byte[] prependId3v22TleFrame(byte[] mp3Bytes, String tleValue) {
    return Bytes.concat(
        Id3TestUtil.buildSingleFrameTag(
            /* majorVersion= */ 2,
            "TLE",
            Bytes.concat(new byte[] {0}, tleValue.getBytes(ISO_8859_1))),
        mp3Bytes);
  }

  private static int findMpegFramePositionBeforeTag(ByteBuffer data, int tagOffset) {
    for (int tagOffsetFromFrameStart : new int[] {13, 21, 36}) {
      int framePosition = tagOffset - tagOffsetFromFrameStart;
      if (framePosition >= 0
          && framePosition + 4 <= data.remaining()
          && new MpegAudioUtil.Header().setForHeaderData(data.getInt(framePosition))) {
        return framePosition;
      }
    }
    throw new IllegalArgumentException(
        "No tag found in " + Util.toHexString(data.array(), data.arrayOffset(), data.limit()));
  }
}
