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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.BinaryFrame;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.test.utils.Id3TestUtil.FrameSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Id3TestUtil}. */
@RunWith(AndroidJUnit4.class)
public final class Id3TestUtilTest {

  @Test
  public void buildSingleFrameTag_v22_decodesWithId3Decoder() {
    byte[] payload = Bytes.concat(new byte[] {0}, "2800".getBytes(ISO_8859_1));

    byte[] rawTag = Id3TestUtil.buildSingleFrameTag(/* majorVersion= */ 2, "TLE", payload);
    Metadata metadata = new Id3Decoder().decode(rawTag, rawTag.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame frame = (TextInformationFrame) metadata.get(0);
    assertThat(frame.id).isEqualTo("TLE");
    assertThat(frame.values).containsExactly("2800");
  }

  @Test
  public void buildSingleFrameTag_v22WithMultiBytePayload_encodes24BitFrameSize() {
    byte[] payload = new byte[0x010203];
    Arrays.fill(payload, (byte) 'z');

    byte[] rawTag = Id3TestUtil.buildSingleFrameTag(/* majorVersion= */ 2, "XXX", payload);
    Metadata metadata = new Id3Decoder().decode(rawTag, rawTag.length);

    assertThat(Arrays.copyOfRange(rawTag, 10, 16)).isEqualTo(new byte[] {'X', 'X', 'X', 1, 2, 3});
    assertThat(metadata.length()).isEqualTo(1);
    BinaryFrame frame = (BinaryFrame) metadata.get(0);
    assertThat(frame.id).isEqualTo("XXX");
    assertThat(frame.data).isEqualTo(payload);
  }

  @Test
  public void buildSingleFrameTag_v23WithLargePayload_encodesNonSynchSafeFrameSize() {
    byte[] payload = new byte[200];
    Arrays.fill(payload, (byte) 'a');

    byte[] rawTag = Id3TestUtil.buildSingleFrameTag(/* majorVersion= */ 3, "XXXX", payload);
    Metadata metadata = new Id3Decoder().decode(rawTag, rawTag.length);

    assertThat(Arrays.copyOfRange(rawTag, 10, 20))
        .isEqualTo(new byte[] {'X', 'X', 'X', 'X', 0, 0, 0, (byte) 200, 0, 0});
    assertThat(metadata.length()).isEqualTo(1);
    BinaryFrame frame = (BinaryFrame) metadata.get(0);
    assertThat(frame.id).isEqualTo("XXXX");
    assertThat(frame.data).isEqualTo(payload);
  }

  @Test
  public void buildTag_v24WithLargePayload_encodesSynchSafeFrameSizeAndDecodes() {
    byte[] largePayload = new byte[200];
    Arrays.fill(largePayload, (byte) 'b');
    byte[] textPayload = Bytes.concat(new byte[] {0}, "5000".getBytes(ISO_8859_1));

    byte[] rawTag =
        Id3TestUtil.buildTag(
            new FrameSpec("XXXX", largePayload), new FrameSpec("TLEN", textPayload));
    Metadata metadata = new Id3Decoder().decode(rawTag, rawTag.length);

    // 200 encoded as a 28-bit synchsafe integer is (1 << 7) + 72 -> [0, 0, 1, 72].
    assertThat(Arrays.copyOfRange(rawTag, 10, 20))
        .isEqualTo(new byte[] {'X', 'X', 'X', 'X', 0, 0, 1, 72, 0, 0});
    assertThat(metadata.length()).isEqualTo(2);
    assertThat(((BinaryFrame) metadata.get(0)).data).isEqualTo(largePayload);
    assertThat(((TextInformationFrame) metadata.get(1)).values).containsExactly("5000");
  }

  @Test
  public void buildFrames_withNonZero16BitFlags_encodesHighAndLowFlagBytes() {
    FrameSpec frame = new FrameSpec("XXXX", new byte[] {1, 2}, /* flags= */ 0x1234);

    byte[] v3Frame = Id3TestUtil.buildFrames(/* majorVersion= */ 3, frame);
    byte[] v4Frame = Id3TestUtil.buildFrames(/* majorVersion= */ 4, frame);

    byte[] expected = new byte[] {'X', 'X', 'X', 'X', 0, 0, 0, 2, 0x12, 0x34, 1, 2};
    assertThat(v3Frame).isEqualTo(expected);
    assertThat(v4Frame).isEqualTo(expected);
  }

  @Test
  public void buildTag_withFlagsAndPayload_encodesSynchSafeHeader() {
    byte[] payload = new byte[129];

    byte[] rawTag = Id3TestUtil.buildTag(/* majorVersion= */ 3, /* flags= */ 0x80, payload);

    assertThat(rawTag).hasLength(10 + payload.length);
    assertThat(Arrays.copyOf(rawTag, 10))
        .isEqualTo(new byte[] {'I', 'D', '3', 3, 0, (byte) 0x80, 0, 0, 1, 1});
  }

  @Test
  public void buildTag_invalidMajorVersion_throwsIllegalArgumentException() {
    byte[] emptyPayload = new byte[0];

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildTag(/* majorVersion= */ 1, /* flags= */ 0, emptyPayload));
    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildTag(/* majorVersion= */ 5, /* flags= */ 0, emptyPayload));
  }

  @Test
  public void buildFrames_invalidMajorVersion_throwsIllegalArgumentException() {
    FrameSpec frame = new FrameSpec("XXXX", new byte[] {0, '1'});

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildFrames(/* majorVersion= */ 1, frame));
    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildFrames(/* majorVersion= */ 5, frame));
  }

  @Test
  public void buildTag_outOfRangeFlags_throwsIllegalArgumentException() {
    byte[] emptyPayload = new byte[0];

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildTag(/* majorVersion= */ 4, /* flags= */ -1, emptyPayload));
    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildTag(/* majorVersion= */ 4, /* flags= */ 0x100, emptyPayload));
  }

  @Test
  public void frameSpec_outOfRangeFlags_throwsIllegalArgumentException() {
    byte[] payload = new byte[] {0, '1'};

    assertThrows(
        IllegalArgumentException.class, () -> new FrameSpec("XXXX", payload, /* flags= */ -1));
    assertThrows(
        IllegalArgumentException.class, () -> new FrameSpec("XXXX", payload, /* flags= */ 0x10000));
  }

  @Test
  public void frameSpec_nonAsciiFrameId_throwsIllegalArgumentException() {
    byte[] payload = new byte[] {0, '1'};

    assertThrows(IllegalArgumentException.class, () -> new FrameSpec("T\u00e9ST", payload));
    assertThrows(IllegalArgumentException.class, () -> new FrameSpec("T\u2603ST", payload));
  }

  @Test
  public void buildFrames_v22WithFourByteId_throwsIllegalArgumentException() {
    FrameSpec fourByteIdFrame = new FrameSpec("TLEN", new byte[] {0, '1'});

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildFrames(/* majorVersion= */ 2, fourByteIdFrame));
  }

  @Test
  public void buildFrames_v22WithNonZeroFlags_throwsIllegalArgumentException() {
    FrameSpec flaggedFrame = new FrameSpec("TLE", new byte[] {0, '1'}, /* flags= */ 0x0001);

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildFrames(/* majorVersion= */ 2, flaggedFrame));
  }

  @Test
  public void buildFrames_v23WithThreeByteId_throwsIllegalArgumentException() {
    FrameSpec threeByteIdFrame = new FrameSpec("TLE", new byte[] {0, '1'});

    assertThrows(
        IllegalArgumentException.class,
        () -> Id3TestUtil.buildFrames(/* majorVersion= */ 3, threeByteIdFrame));
  }
}
