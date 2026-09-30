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
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link H264Reader}. */
@RunWith(AndroidJUnit4.class)
public final class H264ReaderTest {

  private static final byte[] nalStartCode = createByteArray(0x00, 0x00, 0x00, 0x01);

  private static final byte[] audNalUnit = createByteArray(0x09, 0xF0);

  private static final byte[] spsNalUnit =
      createByteArray(
          0x67, 0x4D, 0x40, 0x16, 0xEC, 0xA0, 0x50, 0x17, 0xFC, 0xB8, 0x0A, 0x90, 0x91, 0x00, 0x00,
          0x7E, 0xA0);

  private static final byte[] ppsNalUnit = createByteArray(0x68, 0xEE, 0x3C, 0x80);

  // H.264 SEI (0x06) with payloadType = 45 (0x2D), payloadSize = 3 (0x03), side-by-side (type = 3)
  private static final byte[] seiFpaSideBySideNalUnit =
      createByteArray(0x06, 0x2D, 0x03, 0x81, 0x80, 0x00, 0x80);

  // H.264 SEI (0x06) with payloadType = 45 (0x2D), payloadSize = 3 (0x03), top-bottom (type = 4)
  private static final byte[] seiFpaTopBottomNalUnit =
      createByteArray(0x06, 0x2D, 0x03, 0x82, 0x00, 0x00, 0x80);

  private static final byte[] idrSliceNalUnit = createByteArray(0x65, 0x88, 0x84, 0x00, 0x33, 0xFF);

  private FakeTrackOutput trackOutput;
  private H264Reader reader;

  @Before
  public void setUp() {
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeiReader seiReader = new SeiReader(ImmutableList.of(), MimeTypes.VIDEO_MP2T);
    reader =
        new H264Reader(
            seiReader,
            /* allowNonIdrKeyframes= */ false,
            /* detectAccessUnits= */ false,
            MimeTypes.VIDEO_MP2T);
    reader.createTracks(extractorOutput, new TrackIdGenerator(/* firstTrackId= */ 0, 1));
    trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
  }

  @Test
  public void consume_withFramePackingSeiAfterSpsPps_updatesFormatStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            seiFpaSideBySideNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(streamData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_LEFT_RIGHT);
  }

  @Test
  public void consume_withFramePackingSeiBeforeSpsPps_outputsFormatWithStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            seiFpaTopBottomNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(streamData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_TOP_BOTTOM);
  }

  @Test
  public void consume_withoutFramePackingSei_outputsFormatWithNoValueStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(streamData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void consume_withFramePackingSeiOnSecondSample_updatesFormatStereoMode() {
    byte[] firstSampleData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            idrSliceNalUnit);
    byte[] secondSampleData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            seiFpaSideBySideNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(firstSampleData));

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(Format.NO_VALUE);

    reader.packetStarted(/* pesTimeUs= */ 2_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(secondSampleData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_LEFT_RIGHT);
  }

  @Test
  public void consume_withDetectAccessUnitsAndSeiOnSecondSample_updatesFormatAfterFirstSample() {
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeiReader seiReader = new SeiReader(ImmutableList.of(), MimeTypes.VIDEO_MP2T);
    H264Reader accessUnitReader =
        new H264Reader(
            seiReader,
            /* allowNonIdrKeyframes= */ false,
            /* detectAccessUnits= */ true,
            MimeTypes.VIDEO_MP2T);
    accessUnitReader.createTracks(extractorOutput, new TrackIdGenerator(/* firstTrackId= */ 0, 1));
    FakeTrackOutput accessUnitTrackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
    byte[] firstSampleData =
        Bytes.concat(
            nalStartCode, spsNalUnit, nalStartCode, ppsNalUnit, nalStartCode, idrSliceNalUnit);
    byte[] secondSampleData =
        Bytes.concat(nalStartCode, seiFpaSideBySideNalUnit, nalStartCode, idrSliceNalUnit);

    accessUnitReader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    accessUnitReader.consume(new ParsableByteArray(firstSampleData));

    assertThat(accessUnitTrackOutput.lastFormat).isNotNull();
    assertThat(accessUnitTrackOutput.lastFormat.stereoMode).isEqualTo(Format.NO_VALUE);

    accessUnitReader.packetStarted(/* pesTimeUs= */ 2_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    accessUnitReader.consume(new ParsableByteArray(secondSampleData));
    accessUnitReader.endOfInputReached();

    assertThat(accessUnitTrackOutput.getSampleCount()).isEqualTo(2);
    assertThat(accessUnitTrackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_LEFT_RIGHT);
  }

  @Test
  public void consume_withSpsPpsBeforeAudAndFramePackingSei_outputsFormatWithStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            audNalUnit,
            nalStartCode,
            seiFpaSideBySideNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(streamData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_LEFT_RIGHT);
  }

  @Test
  public void consume_withFramePackingCancelSeiAfter3dSample_updatesFormatToMono() {
    // H.264 SEI (0x06) with payloadType = 45 (0x2D), payloadSize = 1 (0x01), cancel_flag = 1 (0xC0)
    byte[] seiFpaCancelNalUnit = createByteArray(0x06, 0x2D, 0x01, 0xC0, 0x80);
    byte[] firstSampleData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            seiFpaSideBySideNalUnit,
            nalStartCode,
            idrSliceNalUnit);
    byte[] secondSampleData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            seiFpaCancelNalUnit,
            nalStartCode,
            idrSliceNalUnit);

    reader.packetStarted(/* pesTimeUs= */ 1_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(firstSampleData));

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_LEFT_RIGHT);

    reader.packetStarted(/* pesTimeUs= */ 2_000_000L, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(secondSampleData));
    reader.endOfInputReached();

    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.lastFormat.stereoMode).isEqualTo(C.STEREO_MODE_MONO);
  }
}
