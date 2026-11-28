/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.Util;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Ac3Util}. */
@RunWith(AndroidJUnit4.class)
public final class Ac3UtilTest {

  private static final int TRUEHD_SYNCFRAME_SAMPLE_COUNT = 40;
  private static final byte[] TRUEHD_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("C07504D8F8726FBA0097C00FB7520000");
  private static final byte[] TRUEHD_NON_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("A025048860224E6F6DEDB6D5B6DBAFE6");

  private static final int EAC3_SYNCFRAME_SIZE = 16;
  // Independent substream syncframes with a frmsiz of 7 (16 bytes) and 3 or 6 audio blocks.
  private static final byte[] EAC3_INDEPENDENT_3_BLOCKS_HEADER =
      Util.getBytesFromHexString("0B7700072F80");
  private static final byte[] EAC3_INDEPENDENT_6_BLOCKS_HEADER =
      Util.getBytesFromHexString("0B7700073F80");
  // Dependent substream (strmtyp 1) syncframe with 3 audio blocks.
  private static final byte[] EAC3_DEPENDENT_3_BLOCKS_HEADER =
      Util.getBytesFromHexString("0B7740072F80");
  private static final byte[] AC3_HEADER = Util.getBytesFromHexString("0B7700000040");

  @Test
  public void parseTrueHdSyncframeAudioSampleCount_nonSyncframe() {
    assertThat(Ac3Util.parseTrueHdSyncframeAudioSampleCount(TRUEHD_NON_SYNCFRAME_HEADER))
        .isEqualTo(0);
  }

  @Test
  public void parseTrueHdSyncframeAudioSampleCount_syncframe() {
    assertThat(Ac3Util.parseTrueHdSyncframeAudioSampleCount(TRUEHD_SYNCFRAME_HEADER))
        .isEqualTo(TRUEHD_SYNCFRAME_SAMPLE_COUNT);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_ac3Syncframe() {
    ByteBuffer buffer = ByteBuffer.wrap(createSyncframe(AC3_HEADER));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(1536);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_singleEac3Syncframe() {
    ByteBuffer buffer = ByteBuffer.wrap(createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(768);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_twoEac3Syncframes_sumsSyncframes() {
    ByteBuffer buffer =
        ByteBuffer.wrap(
            Bytes.concat(
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER),
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER)));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(1536);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_doesNotModifyBufferPosition() {
    ByteBuffer buffer =
        ByteBuffer.wrap(
            Bytes.concat(
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER),
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER)));
    buffer.position(EAC3_SYNCFRAME_SIZE);

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(768);
    assertThat(buffer.position()).isEqualTo(EAC3_SYNCFRAME_SIZE);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_dependentSubstreams_ignoresDependentSyncframes() {
    byte[] independentAndDependent =
        Bytes.concat(
            createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER),
            createSyncframe(EAC3_DEPENDENT_3_BLOCKS_HEADER));
    ByteBuffer buffer =
        ByteBuffer.wrap(Bytes.concat(independentAndDependent, independentAndDependent));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(1536);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_sixBlocksWithDependentSyncframe() {
    ByteBuffer buffer =
        ByteBuffer.wrap(
            Bytes.concat(
                createSyncframe(EAC3_INDEPENDENT_6_BLOCKS_HEADER),
                createSyncframe(EAC3_DEPENDENT_3_BLOCKS_HEADER)));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(1536);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_trailingBytesAreNotSyncframe() {
    ByteBuffer buffer =
        ByteBuffer.wrap(
            Bytes.concat(
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER),
                Util.getBytesFromHexString("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(768);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_truncatedSecondSyncframe() {
    byte[] secondSyncframe = createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER);
    ByteBuffer buffer =
        ByteBuffer.wrap(
            Bytes.concat(
                createSyncframe(EAC3_INDEPENDENT_3_BLOCKS_HEADER),
                Arrays.copyOf(secondSyncframe, EAC3_SYNCFRAME_SIZE - 1)));

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(768);
  }

  @Test
  public void parseAc3AccessUnitAudioSampleCount_oneBlockSyncframesFromFile() throws Exception {
    byte[] data =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/ts/sample.eac3");
    // Each syncframe in the file is 4000 bytes and contains one audio block.
    ByteBuffer buffer = ByteBuffer.wrap(data, /* offset= */ 0, /* length= */ 6 * 4000);

    assertThat(Ac3Util.parseAc3AccessUnitAudioSampleCount(buffer)).isEqualTo(1536);
  }

  private static byte[] createSyncframe(byte[] header) {
    return Arrays.copyOf(header, EAC3_SYNCFRAME_SIZE);
  }
}
