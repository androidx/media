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
package androidx.media3.extractor.ogg;

import static androidx.media3.test.utils.TestUtil.getByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link OggPacket}. */
@RunWith(AndroidJUnit4.class)
public final class OggPacketTest {

  private static final String TEST_FILE = "media/ogg/bear.opus";

  private final Random random = new Random(/* seed= */ 0);
  private final OggPacket oggPacket = new OggPacket();

  @Test
  public void readPacketsWithEmptyPage() throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(8, random);
    byte[] secondPacket = TestUtil.buildTestData(272, random);
    byte[] thirdPacket = TestUtil.buildTestData(256, random);
    byte[] fourthPacket = TestUtil.buildTestData(271, random);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/four_packets_with_empty_page"));

    assertReadPacket(input, firstPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertThat((oggPacket.getPageHeader().type & 0x02) == 0x02).isTrue();
    assertThat((oggPacket.getPageHeader().type & 0x04) == 0x04).isFalse();
    assertThat(oggPacket.getPageHeader().type).isEqualTo(0x02);
    assertThat(oggPacket.getPageHeader().headerSize).isEqualTo(27 + 1);
    assertThat(oggPacket.getPageHeader().bodySize).isEqualTo(8);
    assertThat(oggPacket.getPageHeader().revision).isEqualTo(0x00);
    assertThat(oggPacket.getPageHeader().pageSegmentCount).isEqualTo(1);
    assertThat(oggPacket.getPageHeader().pageSequenceNumber).isEqualTo(1000);
    assertThat(oggPacket.getPageHeader().streamSerialNumber).isEqualTo(4096);
    assertThat(oggPacket.getPageHeader().granulePosition).isEqualTo(0);

    assertReadPacket(input, secondPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertThat((oggPacket.getPageHeader().type & 0x02) == 0x02).isFalse();
    assertThat((oggPacket.getPageHeader().type & 0x04) == 0x04).isFalse();
    assertThat(oggPacket.getPageHeader().type).isEqualTo(0);
    assertThat(oggPacket.getPageHeader().headerSize).isEqualTo(27 + 2);
    assertThat(oggPacket.getPageHeader().bodySize).isEqualTo(255 + 17);
    assertThat(oggPacket.getPageHeader().pageSegmentCount).isEqualTo(2);
    assertThat(oggPacket.getPageHeader().pageSequenceNumber).isEqualTo(1001);
    assertThat(oggPacket.getPageHeader().granulePosition).isEqualTo(16);

    assertReadPacket(input, thirdPacket);
    assertThat(oggPacket.isLastPacketInPage()).isFalse();
    assertThat((oggPacket.getPageHeader().type & 0x02) == 0x02).isFalse();
    assertThat((oggPacket.getPageHeader().type & 0x04) == 0x04).isTrue();
    assertThat(oggPacket.getPageHeader().type).isEqualTo(4);
    assertThat(oggPacket.getPageHeader().headerSize).isEqualTo(27 + 4);
    assertThat(oggPacket.getPageHeader().bodySize).isEqualTo(255 + 1 + 255 + 16);
    assertThat(oggPacket.getPageHeader().pageSegmentCount).isEqualTo(4);
    // Page 1002 is empty, so current page is 1003.
    assertThat(oggPacket.getPageHeader().pageSequenceNumber).isEqualTo(1003);
    assertThat(oggPacket.getPageHeader().granulePosition).isEqualTo(128);

    assertReadPacket(input, fourthPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();

    assertReadEof(input);
  }

  @Test
  public void readPacketWithZeroSizeTerminator() throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(255, random);
    byte[] secondPacket = TestUtil.buildTestData(8, random);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/packet_with_zero_size_terminator"));

    assertReadPacket(input, firstPacket);
    assertReadPacket(input, secondPacket);
    assertReadEof(input);
  }

  @Test
  public void
      populate_packetWithZeroSizeTerminatorOnNextPage_readsPacketsAndUpdatesLastPacketInPage()
          throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(255, random);
    byte[] secondPacket = TestUtil.buildTestData(8, random);
    // Page 1: 1 segment of 255 bytes (packet continues onto Page 2).
    byte[] page1Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 1, 255);
    // Page 2: continued packet (0x01) with 0-byte terminator segment followed by 8-byte packet.
    byte[] page2Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x05, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
            0, 0, 2, 0, 8);
    byte[] data = Bytes.concat(page1Header, firstPacket, page2Header, secondPacket);

    FakeExtractorInput input = createInput(data);

    assertReadPacket(input, firstPacket);
    assertThat(oggPacket.isLastPacketInPage()).isFalse();
    assertReadPacket(input, secondPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadEof(input);
  }

  @Test
  public void
      populate_completedPacketFollowedByIncompleteContinuedPacket_isLastPacketInPageReturnsTrue()
          throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(8, random);
    byte[] secondPacket = TestUtil.buildTestData(16, random);
    byte[] thirdPacketPart1 = TestUtil.buildTestData(255, random);
    byte[] thirdPacketPart2 = TestUtil.buildTestData(10, random);
    // Page 1: 8-byte packet, 16-byte packet, and 255-byte start of third packet (continues onto
    // Page 2).
    byte[] page1Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x02, 24, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 3, 8, 16, 255);
    // Page 2: continued packet (0x01 | 0x04 = 0x05) finishing third packet with 10-byte segment.
    byte[] page2Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x05, 40, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0,
            0, 0, 1, 10);
    byte[] data =
        Bytes.concat(
            page1Header,
            firstPacket,
            secondPacket,
            thirdPacketPart1,
            page2Header,
            thirdPacketPart2);
    byte[] expectedThirdPacket = Bytes.concat(thirdPacketPart1, thirdPacketPart2);

    FakeExtractorInput input = createInput(data);

    assertReadPacket(input, firstPacket);
    assertThat(oggPacket.isLastPacketInPage()).isFalse();
    assertReadPacket(input, secondPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadPacket(input, expectedThirdPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadEof(input);
  }

  @Test
  public void readContinuedPacketOverTwoPages() throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(518);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/continued_packet_over_two_pages"));

    assertReadPacket(input, firstPacket);
    assertThat((oggPacket.getPageHeader().type & 0x04) == 0x04).isTrue();
    assertThat((oggPacket.getPageHeader().type & 0x02) == 0x02).isFalse();
    assertThat(oggPacket.getPageHeader().pageSequenceNumber).isEqualTo(1001);

    assertReadEof(input);
  }

  @Test
  public void readContinuedPacketOverFourPages() throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(1028);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/continued_packet_over_four_pages"));

    assertReadPacket(input, firstPacket);
    assertThat((oggPacket.getPageHeader().type & 0x04) == 0x04).isTrue();
    assertThat((oggPacket.getPageHeader().type & 0x02) == 0x02).isFalse();
    assertThat(oggPacket.getPageHeader().pageSequenceNumber).isEqualTo(1003);

    assertReadEof(input);
  }

  @Test
  public void readDiscardContinuedPacketAtStart() throws Exception {
    byte[] pageBody = TestUtil.buildTestData(256 + 8);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/continued_packet_at_start"));

    // Expect the first partial packet to be discarded.
    assertReadPacket(input, Arrays.copyOfRange(pageBody, 256, 256 + 8));
    assertThat(oggPacket.getDiscardedPacketGranulePosition()).isEqualTo(C.INDEX_UNSET);
    assertReadEof(input);
  }

  @Test
  public void
      populate_continuedPageWithOnlyDiscardedPacketCompleted_setsDiscardedPacketGranulePosition()
          throws Exception {
    byte[] discardedRemainder = TestUtil.buildTestData(100, random);
    byte[] secondPacketPart1 = TestUtil.buildTestData(255, random);
    byte[] secondPacketPart2 = TestUtil.buildTestData(50, random);
    byte[] thirdPacket = TestUtil.buildTestData(80, random);
    // Page 1: continued page (0x01) with granulePosition = 1000 (0xE8, 0x03), containing a
    // 100-byte discarded remainder and the 255-byte start of the second packet.
    byte[] page1Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x01, 0xE8, 0x03, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            0, 0, 0, 2, 100, 255);
    // Page 2: continued EOS page (0x01 | 0x04 = 0x05) with granulePosition = 2000 (0xD0, 0x07),
    // finishing the second packet (50 bytes) followed by the third packet (80 bytes).
    byte[] page2Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x05, 0xD0, 0x07, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0,
            0, 0, 0, 2, 50, 80);
    byte[] data =
        Bytes.concat(
            page1Header,
            discardedRemainder,
            secondPacketPart1,
            page2Header,
            secondPacketPart2,
            thirdPacket);
    byte[] expectedSecondPacket = Bytes.concat(secondPacketPart1, secondPacketPart2);
    FakeExtractorInput input = createInput(data);

    assertReadPacket(input, expectedSecondPacket);
    assertThat(oggPacket.getDiscardedPacketGranulePosition()).isEqualTo(1000);
    assertReadPacket(input, thirdPacket);
    assertThat(oggPacket.getDiscardedPacketGranulePosition()).isEqualTo(C.INDEX_UNSET);
    assertReadEof(input);
  }

  @Test
  public void
      populate_continuedPageWithDiscardedAndCompletedAndContinuedPackets_doesNotSetDiscardedGranule()
          throws Exception {
    byte[] discardedRemainder = TestUtil.buildTestData(100, random);
    byte[] completedPacket = TestUtil.buildTestData(80, random);
    byte[] continuedPacketPart1 = TestUtil.buildTestData(255, random);
    byte[] continuedPacketPart2 = TestUtil.buildTestData(50, random);
    // Page 1: continued page (0x01) with granulePosition = 1000 (0xE8, 0x03), containing a
    // 100-byte discarded remainder, an 80-byte complete packet, and the 255-byte start of the next
    // packet.
    byte[] page1Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x01, 0xE8, 0x03, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0,
            0, 0, 0, 3, 100, 80, 255);
    // Page 2: continued EOS page (0x01 | 0x04 = 0x05) with granulePosition = 2000 (0xD0, 0x07),
    // finishing the continued packet (50 bytes).
    byte[] page2Header =
        TestUtil.createByteArray(
            'O', 'g', 'g', 'S', 0x00, 0x05, 0xD0, 0x07, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0,
            0, 0, 0, 1, 50);
    byte[] data =
        Bytes.concat(
            page1Header,
            discardedRemainder,
            completedPacket,
            continuedPacketPart1,
            page2Header,
            continuedPacketPart2);
    byte[] expectedContinuedPacket = Bytes.concat(continuedPacketPart1, continuedPacketPart2);
    FakeExtractorInput input = createInput(data);

    assertReadPacket(input, completedPacket);
    assertThat(oggPacket.getDiscardedPacketGranulePosition()).isEqualTo(C.INDEX_UNSET);
    assertReadPacket(input, expectedContinuedPacket);
    assertThat(oggPacket.getDiscardedPacketGranulePosition()).isEqualTo(C.INDEX_UNSET);
    assertReadEof(input);
  }

  @Test
  public void readZeroSizedPacketsAtEndOfStream() throws Exception {
    byte[] firstPacket = TestUtil.buildTestData(8, random);
    byte[] secondPacket = TestUtil.buildTestData(8, random);
    byte[] thirdPacket = TestUtil.buildTestData(8, random);
    FakeExtractorInput input =
        createInput(
            getByteArray(
                ApplicationProvider.getApplicationContext(),
                "media/ogg/zero_sized_packets_at_end_of_stream"));

    assertReadPacket(input, firstPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadPacket(input, secondPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadPacket(input, thirdPacket);
    assertThat(oggPacket.isLastPacketInPage()).isTrue();
    assertReadEof(input);
  }

  @Test
  public void parseRealFile() throws IOException {
    byte[] data = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), TEST_FILE);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    int packetCounter = 0;
    while (readPacket(input)) {
      packetCounter++;
    }
    assertThat(packetCounter).isEqualTo(277);
  }

  private static FakeExtractorInput createInput(byte[] data) {
    return new FakeExtractorInput.Builder()
        .setData(data)
        .setSimulateIOErrors(true)
        .setSimulateUnknownLength(true)
        .setSimulatePartialReads(true)
        .build();
  }

  private void assertReadPacket(FakeExtractorInput extractorInput, byte[] expected)
      throws IOException {
    assertThat(readPacket(extractorInput)).isTrue();
    ParsableByteArray payload = oggPacket.getPayload();
    assertThat(Arrays.copyOf(payload.getData(), payload.limit())).isEqualTo(expected);
  }

  private void assertReadEof(FakeExtractorInput extractorInput) throws IOException {
    assertThat(readPacket(extractorInput)).isFalse();
  }

  private boolean readPacket(FakeExtractorInput input) throws IOException {
    while (true) {
      try {
        return oggPacket.populate(input);
      } catch (FakeExtractorInput.SimulatedIOException e) {
        // Ignore.
      }
    }
  }
}
