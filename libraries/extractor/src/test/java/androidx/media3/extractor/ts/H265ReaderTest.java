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

/** Unit tests for {@link H265Reader}. */
@RunWith(AndroidJUnit4.class)
public final class H265ReaderTest {

  private static final byte[] nalStartCode = createByteArray(0x00, 0x00, 0x00, 0x01);

  private static final byte[] vpsNalUnit =
      createByteArray(
          0x40, 0x01, 0x0C, 0x01, 0xFF, 0xFF, 0x22, 0x20, 0x00, 0x00, 0x03, 0x00, 0xB0, 0x00, 0x00,
          0x03, 0x00, 0x00, 0x03, 0x00, 0x99, 0x2C, 0x09);

  private static final byte[] spsNalUnit =
      createByteArray(
          0x42, 0x01, 0x01, 0x22, 0x20, 0x00, 0x00, 0x03, 0x00, 0xB0, 0x00, 0x00, 0x03, 0x00, 0x00,
          0x03, 0x00, 0x99, 0xA0, 0x01, 0xE0, 0x20, 0x02, 0x1C, 0x4D, 0x94, 0xBB, 0xB4, 0xA3, 0x32,
          0xAA, 0xC0, 0x5A, 0x84, 0x89, 0x04, 0x8A, 0x00, 0x00, 0x07, 0xD2, 0x00, 0x00, 0xBB, 0x80,
          0xE4, 0x68, 0x7C, 0x9C, 0x00, 0x01, 0x2E, 0x1F, 0x80, 0x00, 0x21, 0xFD, 0x30, 0x00, 0x02,
          0x5C, 0x3F, 0x00, 0x00, 0x43, 0xFA, 0x62);

  private static final byte[] ppsNalUnit =
      createByteArray(0x44, 0x01, 0xC0, 0xF7, 0xC0, 0xCC, 0x90);

  // H.265 PREFIX_SEI (0x4E, 0x01) with payloadType = 45 (0x2D), payloadSize = 3 (0x03),
  // side-by-side (type = 3)
  private static final byte[] prefixSeiFpaSideBySideNalUnit =
      createByteArray(0x4E, 0x01, 0x2D, 0x03, 0x81, 0x80, 0x00, 0x80);

  // H.265 PREFIX_SEI (0x4E, 0x01) with payloadType = 45 (0x2D), payloadSize = 3 (0x03),
  // top-bottom (type = 4)
  private static final byte[] prefixSeiFpaTopBottomNalUnit =
      createByteArray(0x4E, 0x01, 0x2D, 0x03, 0x82, 0x00, 0x00, 0x80);

  private static final byte[] idrSliceNalUnit = createByteArray(0x26, 0x01, 0xAF, 0x08, 0x40, 0x00);

  private FakeTrackOutput trackOutput;
  private H265Reader reader;

  @Before
  public void setUp() {
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeiReader seiReader = new SeiReader(ImmutableList.of(), MimeTypes.VIDEO_MP2T);
    reader = new H265Reader(seiReader, MimeTypes.VIDEO_MP2T);
    reader.createTracks(extractorOutput, new TrackIdGenerator(/* firstTrackId= */ 0, 1));
    trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
  }

  @Test
  public void consume_withFramePackingPrefixSeiAfterVpsSpsPps_updatesFormatStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            vpsNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            prefixSeiFpaSideBySideNalUnit,
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
  public void consume_withFramePackingPrefixSeiBeforeVpsSpsPps_outputsFormatWithStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            prefixSeiFpaTopBottomNalUnit,
            nalStartCode,
            vpsNalUnit,
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
  public void consume_withoutFramePackingPrefixSei_outputsFormatWithNoValueStereoMode() {
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            vpsNalUnit,
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
  public void consume_withFramePackingPrefixSeiOnSecondSample_updatesFormatStereoMode() {
    byte[] firstSampleData =
        Bytes.concat(
            nalStartCode,
            vpsNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            idrSliceNalUnit);
    byte[] secondSampleData =
        Bytes.concat(nalStartCode, prefixSeiFpaSideBySideNalUnit, nalStartCode, idrSliceNalUnit);

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
  public void consume_withVpsSpsPpsBetweenAudsAndPrefixSei_outputsFormatWithStereoMode() {
    byte[] audNalUnit = createByteArray(0x46, 0x01, 0x50);
    byte[] streamData =
        Bytes.concat(
            nalStartCode,
            audNalUnit,
            nalStartCode,
            vpsNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            audNalUnit,
            nalStartCode,
            prefixSeiFpaSideBySideNalUnit,
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
  public void consume_withFramePackingCancelPrefixSeiAfter3dSample_updatesFormatToMono() {
    // H.265 PREFIX_SEI (0x4E, 0x01) with payloadType = 45 (0x2D), payloadSize = 1 (0x01),
    // cancel_flag = 1 (0xC0)
    byte[] prefixSeiFpaCancelNalUnit = createByteArray(0x4E, 0x01, 0x2D, 0x01, 0xC0, 0x80);
    byte[] firstSampleData =
        Bytes.concat(
            nalStartCode,
            vpsNalUnit,
            nalStartCode,
            spsNalUnit,
            nalStartCode,
            ppsNalUnit,
            nalStartCode,
            prefixSeiFpaSideBySideNalUnit,
            nalStartCode,
            idrSliceNalUnit);
    byte[] secondSampleData =
        Bytes.concat(nalStartCode, prefixSeiFpaCancelNalUnit, nalStartCode, idrSliceNalUnit);

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
