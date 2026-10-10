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
package androidx.media3.muxer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for fragment boundaries in {@link FragmentedMp4Muxer}. */
@RunWith(RobolectricTestRunner.class)
public class FragmentedMp4MuxerTest {

  private static final byte[] SPS = hex("000000016742c00ad9047b0110000003001000000303c0f1226480");
  private static final byte[] PPS = hex("0000000168cb83cb20");
  private static final byte[] KEY_FRAME = hex("0000000165888400ffee");
  private static final byte[] NON_KEY_FRAME = hex("00000001419a0011");
  private static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(64)
          .setHeight(48)
          .setMaxNumReorderSamples(0)
          .setInitializationData(ImmutableList.of(SPS, PPS))
          .build();

  private static final long FRAME_DURATION_US = 33_333;
  private static final int FRAME_COUNT = 90;
  private static final long FRAGMENT_DURATION_MS = 1_000;
  private static final int SYNC_SAMPLE_FLAGS = 0x02000000;
  private static final int NON_SYNC_SAMPLE_FLAGS = 0x01010000;

  @Test
  public void writeSampleData_singleKeyFrame_writesFragmentsWhileSamplesArrive() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FragmentedMp4Muxer muxer = buildMuxer(output, /* fragmentsBetweenKeyFrames= */ true);

    writeVideo(muxer, presentationOrderTimestampsUs());
    int fragmentsBeforeClose = children(output.toByteArray(), 0, output.size(), "moof").size();
    muxer.close();
    byte[] file = output.toByteArray();

    assertThat(fragmentsBeforeClose).isEqualTo(2);
    assertThat(firstSampleFlags(file))
        .containsExactly(SYNC_SAMPLE_FLAGS, NON_SYNC_SAMPLE_FLAGS, NON_SYNC_SAMPLE_FLAGS)
        .inOrder();
    assertThat(sampleCount(file)).isEqualTo(FRAME_COUNT);
  }

  @Test
  public void writeSampleData_disabled_writesSingleKeyFrameVideoOnClose() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FragmentedMp4Muxer muxer = buildMuxer(output, /* fragmentsBetweenKeyFrames= */ false);

    writeVideo(muxer, presentationOrderTimestampsUs());
    int fragmentsBeforeClose = children(output.toByteArray(), 0, output.size(), "moof").size();
    muxer.close();
    byte[] file = output.toByteArray();

    assertThat(fragmentsBeforeClose).isEqualTo(0);
    assertThat(firstSampleFlags(file)).containsExactly(SYNC_SAMPLE_FLAGS);
    assertThat(sampleCount(file)).isEqualTo(FRAME_COUNT);
  }

  @Test
  public void writeSampleData_bFrames_waitsForKeyFrame() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FragmentedMp4Muxer muxer = buildMuxer(output, /* fragmentsBetweenKeyFrames= */ true);
    Format reorderedVideoFormat =
        VIDEO_FORMAT.buildUpon().setMaxNumReorderSamples(/* maxNumReorderSamples= */ 2).build();
    long[] timestampsUs = new long[FRAME_COUNT];
    for (int i = 0; i < FRAME_COUNT; i++) {
      int frameIndex = i == 0 ? 0 : i % 3 == 1 ? i + 2 : i - 1;
      timestampsUs[i] = frameIndex * FRAME_DURATION_US;
    }

    writeVideo(muxer, reorderedVideoFormat, timestampsUs);
    muxer.close();
    byte[] file = output.toByteArray();

    assertThat(firstSampleFlags(file)).containsExactly(SYNC_SAMPLE_FLAGS);
    assertThat(sampleCount(file)).isEqualTo(FRAME_COUNT);
  }

  @Test
  public void writeSampleData_emptySampleBeforeKeyFrame_doesNotFlushEmptySample() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FragmentedMp4Muxer muxer = buildMuxer(output, /* fragmentsBetweenKeyFrames= */ true);
    int trackId = muxer.addTrack(VIDEO_FORMAT);
    for (int i = 0; i < 32; i++) {
      writeVideoSample(muxer, trackId, i * FRAME_DURATION_US, /* isKeyFrame= */ i == 0);
    }
    muxer.writeSampleData(
        trackId,
        ByteBuffer.allocate(0),
        new BufferInfo(/* presentationTimeUs= */ 2_000_000, /* size= */ 0, /* flags= */ 0));

    writeVideoSample(muxer, trackId, /* presentationTimeUs= */ 2_100_000, /* isKeyFrame= */ true);
    int fragmentsBeforeClose = children(output.toByteArray(), 0, output.size(), "moof").size();
    muxer.close();
    byte[] file = output.toByteArray();

    assertThat(fragmentsBeforeClose).isEqualTo(1);
    assertThat(firstSampleFlags(file)).containsExactly(SYNC_SAMPLE_FLAGS, SYNC_SAMPLE_FLAGS);
    assertThat(sampleCount(file)).isEqualTo(33);
  }

  private static FragmentedMp4Muxer buildMuxer(
      ByteArrayOutputStream output, boolean fragmentsBetweenKeyFrames) {
    return new FragmentedMp4Muxer.Builder(Channels.newChannel(output))
        .setFragmentDurationMs(FRAGMENT_DURATION_MS)
        .setFragmentsBetweenKeyFramesEnabled(fragmentsBetweenKeyFrames)
        .build();
  }

  private static long[] presentationOrderTimestampsUs() {
    long[] timestampsUs = new long[FRAME_COUNT];
    for (int i = 0; i < FRAME_COUNT; i++) {
      timestampsUs[i] = i * FRAME_DURATION_US;
    }
    return timestampsUs;
  }

  private static void writeVideo(FragmentedMp4Muxer muxer, long[] timestampsUs) throws Exception {
    writeVideo(muxer, VIDEO_FORMAT, timestampsUs);
  }

  private static void writeVideo(FragmentedMp4Muxer muxer, Format videoFormat, long[] timestampsUs)
      throws Exception {
    int trackId = muxer.addTrack(videoFormat);
    for (int i = 0; i < timestampsUs.length; i++) {
      writeVideoSample(muxer, trackId, timestampsUs[i], /* isKeyFrame= */ i == 0);
    }
  }

  private static void writeVideoSample(
      FragmentedMp4Muxer muxer, int trackId, long presentationTimeUs, boolean isKeyFrame)
      throws Exception {
    byte[] sample = isKeyFrame ? KEY_FRAME : NON_KEY_FRAME;
    muxer.writeSampleData(
        trackId,
        ByteBuffer.wrap(sample),
        new BufferInfo(
            presentationTimeUs, sample.length, isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0));
  }

  private static List<Integer> firstSampleFlags(byte[] file) {
    List<Integer> flags = new ArrayList<>();
    for (int[] trun : truns(file)) {
      int trunFlags = readInt(file, trun[0]) & 0xFFFFFF;
      assertThat(trunFlags & 0x400).isNotEqualTo(0);
      int position = trun[0] + 8;
      if ((trunFlags & 0x1) != 0) {
        position += 4;
      }
      if ((trunFlags & 0x100) != 0) {
        position += 4;
      }
      if ((trunFlags & 0x200) != 0) {
        position += 4;
      }
      flags.add(readInt(file, position));
    }
    return flags;
  }

  private static int sampleCount(byte[] file) {
    int count = 0;
    for (int[] trun : truns(file)) {
      count += readInt(file, trun[0] + 4);
    }
    return count;
  }

  private static List<int[]> truns(byte[] file) {
    List<int[]> truns = new ArrayList<>();
    for (int[] moof : children(file, 0, file.length, "moof")) {
      for (int[] traf : children(file, moof[0], moof[1], "traf")) {
        truns.addAll(children(file, traf[0], traf[1], "trun"));
      }
    }
    return truns;
  }

  private static List<int[]> children(byte[] file, int start, int end, String type) {
    List<int[]> boxes = new ArrayList<>();
    int position = start;
    while (position + 8 <= end) {
      int size = readInt(file, position);
      if (new String(file, position + 4, 4, StandardCharsets.US_ASCII).equals(type)) {
        boxes.add(new int[] {position + 8, position + size});
      }
      position += size;
    }
    return boxes;
  }

  private static int readInt(byte[] data, int position) {
    return ByteBuffer.wrap(data, position, 4).getInt();
  }

  private static byte[] hex(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return bytes;
  }
}
