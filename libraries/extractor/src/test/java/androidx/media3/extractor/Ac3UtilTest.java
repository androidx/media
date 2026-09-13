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
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Ac3Util}. */
@RunWith(AndroidJUnit4.class)
public final class Ac3UtilTest {

  private static final int AUDIO_SAMPLES_PER_AUDIO_BLOCK = 256;
  private static final int TRUEHD_SYNCFRAME_SAMPLE_COUNT = 40;
  private static final byte[] TRUEHD_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("C07504D8F8726FBA0097C00FB7520000");
  private static final byte[] TRUEHD_NON_SYNCFRAME_HEADER =
      Util.getBytesFromHexString("A025048860224E6F6DEDB6D5B6DBAFE6");

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
  public void parseAc3SyncframeAudioSampleCount_eac3SingleSyncframe_returnsSyncframeSampleCount() {
    byte[] syncframe =
        buildEac3Syncframe(
            Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
            /* substreamId= */ 0,
            /* numblkscod= */ 2);

    int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(syncframe));

    assertThat(sampleCount).isEqualTo(3 * AUDIO_SAMPLES_PER_AUDIO_BLOCK);
  }

  @Test
  public void parseAc3SyncframeAudioSampleCount_eac3SplitSyncframes_sumsSameSubstream() {
    byte[] accessUnit =
        Bytes.concat(
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 0,
                /* numblkscod= */ 2),
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 0,
                /* numblkscod= */ 2));

    int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(accessUnit));

    assertThat(sampleCount).isEqualTo(6 * AUDIO_SAMPLES_PER_AUDIO_BLOCK);
  }

  @Test
  public void parseAc3SyncframeAudioSampleCount_eac3TruncatedSecondSyncframe_ignoresSecondFrame() {
    byte[] secondSyncframe =
        buildEac3Syncframe(
            Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
            /* substreamId= */ 0,
            /* numblkscod= */ 2);
    byte[] accessUnit =
        Bytes.concat(
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 0,
                /* numblkscod= */ 2),
            Arrays.copyOf(secondSyncframe, 6));

    int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(accessUnit));

    assertThat(sampleCount).isEqualTo(3 * AUDIO_SAMPLES_PER_AUDIO_BLOCK);
  }

  @Test
  public void parseAc3SyncframeAudioSampleCount_eac3DependentSubstream_doesNotAddDuration() {
    byte[] accessUnit =
        Bytes.concat(
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 0,
                /* numblkscod= */ 3),
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE1,
                /* substreamId= */ 0,
                /* numblkscod= */ 3));

    int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(accessUnit));

    assertThat(sampleCount).isEqualTo(6 * AUDIO_SAMPLES_PER_AUDIO_BLOCK);
  }

  @Test
  public void parseAc3SyncframeAudioSampleCount_eac3IndependentSubstreams_usesLongestTimeline() {
    byte[] accessUnit =
        Bytes.concat(
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 0,
                /* numblkscod= */ 3),
            buildEac3Syncframe(
                Ac3Util.SyncFrameInfo.STREAM_TYPE_TYPE0,
                /* substreamId= */ 1,
                /* numblkscod= */ 3));

    int sampleCount = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(accessUnit));

    assertThat(sampleCount).isEqualTo(6 * AUDIO_SAMPLES_PER_AUDIO_BLOCK);
  }

  private static byte[] buildEac3Syncframe(
      @Ac3Util.SyncFrameInfo.StreamType int streamType, int substreamId, int numblkscod) {
    int frameSize = 8;
    int frmsiz = frameSize / 2 - 1;
    byte[] syncframe = new byte[frameSize];
    syncframe[0] = 0x0B;
    syncframe[1] = 0x77;
    syncframe[2] = (byte) ((streamType << 6) | (substreamId << 3) | ((frmsiz >> 8) & 0x07));
    syncframe[3] = (byte) (frmsiz & 0xFF);
    syncframe[4] = (byte) (numblkscod << 4);
    syncframe[5] = (byte) 0x80;
    return syncframe;
  }
}
