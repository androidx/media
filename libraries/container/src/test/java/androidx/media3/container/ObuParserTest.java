/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.container;

import static androidx.media3.container.ObuParser.OBU_FRAME;
import static androidx.media3.container.ObuParser.OBU_FRAME_HEADER;
import static androidx.media3.container.ObuParser.OBU_METADATA;
import static androidx.media3.container.ObuParser.OBU_PADDING;
import static androidx.media3.container.ObuParser.OBU_SEQUENCE_HEADER;
import static androidx.media3.container.ObuParser.OBU_TEMPORAL_DELIMITER;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ObuParser} */
@RunWith(AndroidJUnit4.class)
public class ObuParserTest {
  private static final ByteBuffer SEQUENCE_HEADER_AND_FRAME =
      ByteBuffer.wrap(
          createByteArray(
              0x0A, 0x0E, 0x00, 0x00, 0x00, 0x24, 0xC6, 0xAB, 0xDF, 0x3E, 0xFE, 0x24, 0x04, 0x04,
              0x04, 0x10, 0x32, 0x32, 0x10, 0x00, 0xC8, 0xC6, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00,
              0x12, 0x03, 0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x34, 0x88, 0x82, 0x3E, 0x0D, 0x3E,
              0xC2, 0x98, 0x91, 0x6A, 0x5C, 0x80, 0x03, 0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x48,
              0x35, 0x54, 0xD8, 0x9D, 0x6C, 0x37, 0xD3, 0x4C, 0x4E, 0xD4, 0x6F, 0xF4));

  private static final ByteBuffer DELIMITER_AND_HEADER_AND_PADDING_WITH_EXTENSION_AND_MISSING_SIZE =
      ByteBuffer.wrap(createByteArray(0x16, 0x00, 0x00, 0x1A, 0x01, 0xC8, 0x78, 0xFF, 0xFF, 0xFF));

  private static final ByteBuffer NON_REFERENCE_FRAME =
      ByteBuffer.wrap(
          createByteArray(
              0x32, 0x1A, 0x30, 0xC0, 0x00, 0x1D, 0x66, 0x68, 0x46, 0xC9, 0x38, 0x00, 0x60, 0x10,
              0x20, 0x80, 0x20, 0x00, 0x00, 0x01, 0x8B, 0x7A, 0x87, 0xF9, 0xAA, 0x2D, 0x0F, 0x2C));

  private static final ByteBuffer TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_METADATA =
      ByteBuffer.wrap(
          createByteArray(
              0x12, 0x00, 0x0a, 0x0e, 0x00, 0x00, 0x00, 0x2d, 0x4c, 0xff, 0xb3, 0xcc, 0xaf, 0x95,
              0x09, 0x12, 0x09, 0x04, 0x2a, 0x34, 0x04, 0xb5, 0x00, 0x90, 0x00, 0x01, 0xe0, 0x40,
              0x59, 0xdc, 0x1b, 0x00, 0x00, 0x00, 0x28, 0x03, 0xe8, 0x04, 0xe2, 0x06, 0xd6, 0x09,
              0xc4, 0x0f, 0xa0, 0x13, 0x3e, 0x27, 0x10, 0x2a, 0x08, 0x33, 0x48, 0x3f, 0x71, 0x51,
              0x60, 0x59, 0xdc, 0x46, 0x50, 0x33, 0x93, 0x32, 0xf2, 0x36, 0x71, 0x3b, 0x19, 0x3c,
              0xcc, 0x80));

  @Test
  public void split_sequenceHeaderAndFrame_parsesCorrectTypesAndSizes() {
    List<ObuParser.Obu> obuList = ObuParser.split(SEQUENCE_HEADER_AND_FRAME);

    assertThat(obuList).hasSize(2);
    assertThat(obuList.get(0).type).isEqualTo(OBU_SEQUENCE_HEADER);
    assertThat(obuList.get(0).payload.remaining()).isEqualTo(14);
    assertThat(obuList.get(1).type).isEqualTo(OBU_FRAME);
    assertThat(obuList.get(1).payload.remaining()).isEqualTo(50);
    reconstructAndVerify(SEQUENCE_HEADER_AND_FRAME, obuList);
  }

  @Test
  public void split_delimiterAndHeaderAndPadding_parsesCorrectTypesAndSizes() {
    List<ObuParser.Obu> obuList =
        ObuParser.split(DELIMITER_AND_HEADER_AND_PADDING_WITH_EXTENSION_AND_MISSING_SIZE);

    assertThat(obuList).hasSize(3);
    assertThat(obuList.get(0).type).isEqualTo(OBU_TEMPORAL_DELIMITER);
    assertThat(obuList.get(0).payload.remaining()).isEqualTo(0);
    assertThat(obuList.get(1).type).isEqualTo(OBU_FRAME_HEADER);
    assertThat(obuList.get(1).payload.remaining()).isEqualTo(1);
    assertThat(obuList.get(2).type).isEqualTo(OBU_PADDING);
    assertThat(obuList.get(2).payload.remaining()).isEqualTo(3);
    reconstructAndVerify(DELIMITER_AND_HEADER_AND_PADDING_WITH_EXTENSION_AND_MISSING_SIZE, obuList);
  }

  @Test
  public void split_truncatedSequenceHeaderAndFrame_failsToGetByte_returnsFullObuOnly() {
    ByteBuffer truncatedSample = SEQUENCE_HEADER_AND_FRAME.duplicate();
    truncatedSample.limit(17);

    List<ObuParser.Obu> obuList = ObuParser.split(truncatedSample);

    assertThat(obuList).hasSize(1);
    assertThat(obuList.get(0).type).isEqualTo(OBU_SEQUENCE_HEADER);
    assertThat(obuList.get(0).payload.remaining()).isEqualTo(14);
  }

  @Test
  public void split_truncatedSequenceHeaderAndFrame_failsToSetLimit_returnsFullObuOnly() {
    ByteBuffer truncatedSample = SEQUENCE_HEADER_AND_FRAME.asReadOnlyBuffer();
    truncatedSample.limit(18);

    List<ObuParser.Obu> obuList = ObuParser.split(truncatedSample);

    assertThat(obuList).hasSize(1);
    assertThat(obuList.get(0).type).isEqualTo(OBU_SEQUENCE_HEADER);
    assertThat(obuList.get(0).payload.remaining()).isEqualTo(14);
  }

  @Test
  public void split_truncatedSequenceHeaderAndFrame_failsToGetByte_returnsNoObu() {
    ByteBuffer truncatedSample = SEQUENCE_HEADER_AND_FRAME.asReadOnlyBuffer();
    truncatedSample.limit(1);

    List<ObuParser.Obu> obuList = ObuParser.split(truncatedSample);

    assertThat(obuList).isEmpty();
  }

  @Test
  public void split_temporalDelimiterSequenceHeaderAndMetadata() {
    List<ObuParser.Obu> obuList = ObuParser.split(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_METADATA);

    assertThat(obuList).hasSize(3);
    assertThat(obuList.get(0).type).isEqualTo(OBU_TEMPORAL_DELIMITER);
    assertThat(obuList.get(0).payload.remaining()).isEqualTo(0);
    assertThat(obuList.get(1).type).isEqualTo(OBU_SEQUENCE_HEADER);
    assertThat(obuList.get(1).payload.remaining()).isEqualTo(14);
    assertThat(obuList.get(2).type).isEqualTo(OBU_METADATA);
    assertThat(obuList.get(2).payload.remaining()).isEqualTo(52);
    reconstructAndVerify(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_METADATA, obuList);
  }

  @Test
  public void sequenceHeader_parses() {
    ObuParser.Obu sequenceHeaderObu = ObuParser.split(SEQUENCE_HEADER_AND_FRAME).get(0);

    ObuParser.SequenceHeader sequenceHeader = ObuParser.SequenceHeader.parse(sequenceHeaderObu);

    assertThat(sequenceHeader.reducedStillPictureHeader).isFalse();
    assertThat(sequenceHeader.decoderModelInfoPresentFlag).isFalse();
    assertThat(sequenceHeader.frameIdNumbersPresentFlag).isFalse();
    assertThat(sequenceHeader.seqForceScreenContentTools).isTrue();
    assertThat(sequenceHeader.seqForceIntegerMv).isTrue();
    assertThat(sequenceHeader.orderHintBits).isEqualTo(7);
    assertThat(sequenceHeader.seqProfile).isEqualTo(0);
    assertThat(sequenceHeader.seqLevelIdx0).isEqualTo(4);
    assertThat(sequenceHeader.seqTier0).isEqualTo(0);
    assertThat(sequenceHeader.initialDisplayDelayPresentFlag).isFalse();
    assertThat(sequenceHeader.initialDisplayDelayMinus1).isEqualTo(0);
    assertThat(sequenceHeader.highBitdepth).isFalse();
    assertThat(sequenceHeader.twelveBit).isFalse();
    assertThat(sequenceHeader.monochrome).isFalse();
    assertThat(sequenceHeader.subsamplingX).isTrue();
    assertThat(sequenceHeader.subsamplingY).isTrue();
    assertThat(sequenceHeader.chromaSamplePosition).isEqualTo(0);
    assertThat(sequenceHeader.colorPrimaries).isEqualTo(1);
    assertThat(sequenceHeader.transferCharacteristics).isEqualTo(1);
    assertThat(sequenceHeader.matrixCoefficients).isEqualTo(1);
  }

  @Test
  public void parseFrameHeader_fromFrame_returnsIsDependedOn() {
    List<ObuParser.Obu> obuList = ObuParser.split(SEQUENCE_HEADER_AND_FRAME);
    ObuParser.Obu sequenceHeaderObu = obuList.get(0);
    ObuParser.SequenceHeader sequenceHeader = ObuParser.SequenceHeader.parse(sequenceHeaderObu);
    ObuParser.Obu frameObu = obuList.get(1);

    ObuParser.FrameHeader frameHeader = ObuParser.FrameHeader.parse(sequenceHeader, frameObu);

    assertThat(frameHeader.isDependedOn()).isTrue();
  }

  @Test
  public void parseFrameHeader_fromShowExistingFrameHeader_returnsIsNotDependedOn() {
    ObuParser.Obu sequenceHeaderObu = ObuParser.split(SEQUENCE_HEADER_AND_FRAME).get(0);
    ObuParser.SequenceHeader sequenceHeader = ObuParser.SequenceHeader.parse(sequenceHeaderObu);
    ObuParser.Obu frameHeaderObu =
        ObuParser.split(DELIMITER_AND_HEADER_AND_PADDING_WITH_EXTENSION_AND_MISSING_SIZE).get(1);

    ObuParser.FrameHeader frameHeader = ObuParser.FrameHeader.parse(sequenceHeader, frameHeaderObu);

    assertThat(frameHeader.isDependedOn()).isFalse();
  }

  @Test
  public void parseFrameHeader_fromNonReferenceFrame_returnsNotDependedOn() {
    ObuParser.Obu sequenceHeaderObu = ObuParser.split(SEQUENCE_HEADER_AND_FRAME).get(0);
    ObuParser.SequenceHeader sequenceHeader = ObuParser.SequenceHeader.parse(sequenceHeaderObu);
    ObuParser.Obu frameObu = ObuParser.split(NON_REFERENCE_FRAME).get(0);

    ObuParser.FrameHeader frameHeader = ObuParser.FrameHeader.parse(sequenceHeader, frameObu);

    assertThat(frameHeader.isDependedOn()).isFalse();
  }

  @Test
  public void metadata_parses() {
    ObuParser.Obu metadataObu =
        ObuParser.split(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_METADATA).get(2);
    ObuParser.Metadata metadata = ObuParser.Metadata.parse(metadataObu);
    assertThat(metadata.type).isEqualTo(ObuParser.Metadata.METADATA_TYPE_ITUT_T35);
    assertThat(metadata.payload.remaining()).isEqualTo(51);
  }

  private static void reconstructAndVerify(ByteBuffer expectedObuBytes, List<ObuParser.Obu> obus) {
    ByteBuffer buffer = ByteBuffer.allocate(expectedObuBytes.remaining());
    for (ObuParser.Obu obu : obus) {
      buffer.put(obu.header);
      buffer.put(obu.payload);
    }
    buffer.flip();
    assertThat(buffer).isEqualTo(expectedObuBytes);
  }
}
