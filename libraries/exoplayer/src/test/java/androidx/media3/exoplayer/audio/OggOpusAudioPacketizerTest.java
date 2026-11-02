/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.test.utils.BindFlag;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.TestUtil;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link OggOpusAudioPacketizer}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class OggOpusAudioPacketizerTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  @BindFlag(Flags.FLAG_ENABLE_OGG_OPUS_IN_PLACE_PACKETIZATION)
  @TestParameter
  public boolean enableFlag;

  @Test
  public void packetize_firstSample_writesHeaders() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(100);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    List<byte[]> initData = new ArrayList<>();
    initData.add(new byte[] {1, 2, 3});

    packetizer.packetize(inputBuffer, initData);

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, -63, 91, -126,
          1, 3, 1, 2, 3, 79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11,
          -103, 87, 83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103,
          103, 83, 0, 0, -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3,
          32, 33, 34
        };
    assertThat(inputBuffer.data.limit()).isGreaterThan(0);
    assertThat(inputBuffer.data.position()).isEqualTo(0);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_emptyBuffer_preservesFirstSampleState() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(100);
    data.flip();
    inputBuffer.data = data;

    packetizer.packetize(inputBuffer, new ArrayList<>());

    assertThat(inputBuffer.data.remaining()).isEqualTo(0);

    inputBuffer.clear();
    inputBuffer.data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    inputBuffer.data.flip();
    List<byte[]> initData = new ArrayList<>();
    initData.add(new byte[] {1, 2, 3});

    packetizer.packetize(inputBuffer, initData);

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, -63, 91, -126,
          1, 3, 1, 2, 3, 79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11,
          -103, 87, 83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103,
          103, 83, 0, 0, -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3,
          32, 33, 34
        };
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_multipleSamples_shiftsInPlace() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(1000);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    packetizer.packetize(inputBuffer, new ArrayList<>());
    inputBuffer.clear();
    inputBuffer.data.put(new byte[] {(byte) 0x23, 0x24, 0x25});
    inputBuffer.data.flip();

    packetizer.packetize(inputBuffer, new ArrayList<>());

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 0, 96, 69, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 53, 55, 49,
          -103, 1, 3, 35, 36, 37
        };
    assertThat(inputBuffer.data).isSameInstanceAs(data);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withPositionGreaterThanPayloadShiftOffset_packetizesSuccessfully() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(1000);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    packetizer.packetize(inputBuffer, new ArrayList<>());
    inputBuffer.clear();
    inputBuffer.data.position(29);
    inputBuffer.data.put(new byte[] {(byte) 0x23, 0x24, 0x25});
    inputBuffer.data.position(29);
    inputBuffer.data.limit(32);

    packetizer.packetize(inputBuffer, new ArrayList<>());

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 0, 96, 69, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 53, 55, 49,
          -103, 1, 3, 35, 36, 37
        };
    assertThat(inputBuffer.data).isSameInstanceAs(data);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withPositionLessThanPayloadShiftOffset_packetizesSuccessfully() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(1000);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    packetizer.packetize(inputBuffer, new ArrayList<>());
    inputBuffer.clear();
    inputBuffer.data.position(27);
    inputBuffer.data.put(new byte[] {(byte) 0x23, 0x24, 0x25});
    inputBuffer.data.position(27);
    inputBuffer.data.limit(30);

    packetizer.packetize(inputBuffer, new ArrayList<>());

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 0, 96, 69, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 53, 55, 49,
          -103, 1, 3, 35, 36, 37
        };
    assertThat(inputBuffer.data).isSameInstanceAs(data);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withPositionEqualToPayloadShiftOffset_packetizesWithoutShifting() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(1000);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    packetizer.packetize(inputBuffer, new ArrayList<>());
    inputBuffer.clear();
    inputBuffer.data.position(28);
    inputBuffer.data.put(new byte[] {(byte) 0x23, 0x24, 0x25});
    inputBuffer.data.position(28);
    inputBuffer.data.limit(31);

    packetizer.packetize(inputBuffer, new ArrayList<>());

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 0, 96, 69, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 53, 55, 49,
          -103, 1, 3, 35, 36, 37
        };
    assertThat(inputBuffer.data).isSameInstanceAs(data);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_firstSampleWithoutInitData_writesDefaultHeaders() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(100);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    List<byte[]> initData = new ArrayList<>();

    packetizer.packetize(inputBuffer, initData);

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 28, -43, -59, -9,
          1, 19, 79, 112, 117, 115, 72, 101, 97, 100, 1, 2, 56, 1, -128, -69, 0, 0, 0, 0, 0, 79,
          103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11, -103, 87, 83, 1,
          16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103, 103, 83, 0, 0,
          -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3, 32, 33, 34
        };
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_afterReset_writesHeadersAgain() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(100);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    List<byte[]> initData = new ArrayList<>();
    initData.add(new byte[] {1, 2, 3});
    packetizer.packetize(inputBuffer, initData);
    packetizer.reset();
    inputBuffer.clear();
    inputBuffer.data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    inputBuffer.data.flip();

    packetizer.packetize(inputBuffer, initData);

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, -63, 91, -126,
          1, 3, 1, 2, 3, 79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11,
          -103, 87, 83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103,
          103, 83, 0, 0, -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3,
          32, 33, 34
        };
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withInsufficientCapacity_allocatesNewBufferAndShiftsInPlace() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    inputBuffer.ensureSpaceForWrite(3);
    inputBuffer.data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    inputBuffer.flip();
    List<byte[]> initData = new ArrayList<>();
    initData.add(new byte[] {1, 2, 3});

    packetizer.packetize(inputBuffer, initData);

    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, -63, 91, -126,
          1, 3, 1, 2, 3, 79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11,
          -103, 87, 83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103,
          103, 83, 0, 0, -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3,
          32, 33, 34
        };
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withSupplementalData_clearsSupplementalDataAndFlags() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer data = ByteBuffer.allocateDirect(100);
    data.put(new byte[] {(byte) 0x20, 0x21, 0x22});
    data.flip();
    inputBuffer.data = data;
    inputBuffer.resetSupplementalData(8);
    inputBuffer.supplementalData.putLong(1000L);
    inputBuffer.supplementalData.flip();
    inputBuffer.setFlags(C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    List<byte[]> initData = new ArrayList<>();
    initData.add(new byte[] {1, 2, 3});

    packetizer.packetize(inputBuffer, initData);

    assertThat(inputBuffer.hasSupplementalData()).isFalse();
    assertThat(inputBuffer.isKeyFrame()).isFalse();
    assertThat(inputBuffer.supplementalData.remaining()).isEqualTo(0);
    byte[] expectedBytes =
        new byte[] {
          79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 18, -63, 91, -126,
          1, 3, 1, 2, 3, 79, 103, 103, 83, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 11,
          -103, 87, 83, 1, 16, 79, 112, 117, 115, 84, 97, 103, 115, 0, 0, 0, 0, 0, 0, 0, 0, 79, 103,
          103, 83, 0, 0, -32, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, -120, 66, -61, 36, 1, 3,
          32, 33, 34
        };
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(actualBytes).isEqualTo(expectedBytes);
  }

  @Test
  public void packetize_withPayloadMultipleOf255Bytes_writesMultiSegmentLacingTable() {
    OggOpusAudioPacketizer packetizer = new OggOpusAudioPacketizer();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    byte[] payload = TestUtil.buildTestData(255);
    ByteBuffer data = ByteBuffer.allocateDirect(500);
    data.put(payload);
    data.flip();
    inputBuffer.data = data;

    packetizer.packetize(inputBuffer, new ArrayList<>());

    // Default ID header (47 bytes) + comment header (44 bytes) + audio page header (27 + 2 = 29
    // bytes) + 255-byte payload = 375 bytes.
    assertThat(inputBuffer.data.remaining()).isEqualTo(375);
    assertThat(inputBuffer.data.get(91 + 26)).isEqualTo((byte) 2);
    assertThat(inputBuffer.data.get(91 + 27)).isEqualTo((byte) 255);
    assertThat(inputBuffer.data.get(91 + 28)).isEqualTo((byte) 0);
    byte[] actualBytes = new byte[inputBuffer.data.remaining()];
    inputBuffer.data.get(actualBytes);
    assertThat(Arrays.copyOfRange(actualBytes, 120, 375)).isEqualTo(payload);
  }
}
