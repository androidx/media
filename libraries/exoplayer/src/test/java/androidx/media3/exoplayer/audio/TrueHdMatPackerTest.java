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

import static androidx.media3.exoplayer.audio.TrueHdTestUtil.IEC61937_TRUEHD_PREAMBLE;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_END_CODE;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_END_CODE_OFFSET;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_FIRST_DATA_OFFSET;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_MIDDLE_CODE;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_MIDDLE_CODE_OFFSET;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_START_CODE;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.MAT_START_CODE_OFFSET;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.NO_OUTPUT_TIMING;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.PAYLOAD_FILL;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.RATEBITS_44_1_KHZ;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.RATEBITS_48_KHZ;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.createAccessUnit;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.createMajorSyncAccessUnit;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.slice;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.toBigEndianBytes;
import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TrueHdMatPacker.MatFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TrueHdMatPacker}. */
@RunWith(AndroidJUnit4.class)
public final class TrueHdMatPackerTest {

  // Samples per access unit at 48 kHz, which is also the input timing increment.
  private static final int ACCESS_UNIT_SAMPLES = 40;
  // Small enough to always fit in the 2560 byte slot of an access unit.
  private static final int SMALL_ACCESS_UNIT_SIZE = 100;
  // Every 8th access unit is a major sync, like in real streams.
  private static final int MAJOR_SYNC_INTERVAL = 8;

  @Test
  public void packTrueHd_tooSmallAccessUnit_isIgnored() {
    TrueHdMatPacker packer = new TrueHdMatPacker();

    boolean hasOutput =
        packer.packTrueHd(
            createMajorSyncAccessUnit(/* size= */ 40, /* frameTime= */ 0),
            /* offset= */ 0,
            /* size= */ 8,
            /* timeUs= */ 0);

    assertThat(hasOutput).isFalse();
    packer.finish();
    assertThat(packer.pollOutputFrame()).isNull();
  }

  @Test
  public void packTrueHd_withoutPrecedingMajorSync_isIgnored() {
    TrueHdMatPacker packer = new TrueHdMatPacker();

    for (int i = 0; i < 100; i++) {
      byte[] accessUnit =
          createAccessUnit(SMALL_ACCESS_UNIT_SIZE, /* frameTime= */ i * ACCESS_UNIT_SAMPLES);
      assertThat(packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 0)).isFalse();
    }
    packer.finish();

    assertThat(packer.pollOutputFrame()).isNull();
  }

  @Test
  public void packTrueHd_firstFrame_hasPreambleMatCodesAndData() {
    TrueHdMatPacker packer = new TrueHdMatPacker();

    List<MatFrame> frames =
        packStream(packer, /* accessUnitCount= */ 30, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);

    assertThat(frames).hasSize(1);
    byte[] frame = toBigEndianBytes(frames.get(0).data);
    assertThat(frame).hasLength(TrueHdMatPacker.MAT_FRAME_SIZE);
    assertThat(slice(frame, 0, IEC61937_TRUEHD_PREAMBLE.length))
        .isEqualTo(IEC61937_TRUEHD_PREAMBLE);
    assertThat(slice(frame, MAT_START_CODE_OFFSET, MAT_START_CODE.length))
        .isEqualTo(MAT_START_CODE);
    assertThat(slice(frame, MAT_MIDDLE_CODE_OFFSET, MAT_MIDDLE_CODE.length))
        .isEqualTo(MAT_MIDDLE_CODE);
    assertThat(slice(frame, MAT_END_CODE_OFFSET, MAT_END_CODE.length)).isEqualTo(MAT_END_CODE);
    // The first access unit directly follows the start code, and the next one is padded to the
    // 2560 byte slot of the first.
    byte[] firstAccessUnit = createMajorSyncAccessUnit(SMALL_ACCESS_UNIT_SIZE, /* frameTime= */ 0);
    assertThat(slice(frame, MAT_FIRST_DATA_OFFSET, SMALL_ACCESS_UNIT_SIZE))
        .isEqualTo(firstAccessUnit);
    assertThat(frame[MAT_FIRST_DATA_OFFSET + SMALL_ACCESS_UNIT_SIZE]).isEqualTo(0);
    byte[] secondAccessUnit =
        createAccessUnit(SMALL_ACCESS_UNIT_SIZE, /* frameTime= */ ACCESS_UNIT_SAMPLES);
    assertThat(slice(frame, MAT_FIRST_DATA_OFFSET + 2560, SMALL_ACCESS_UNIT_SIZE))
        .isEqualTo(secondAccessUnit);
  }

  @Test
  public void packTrueHd_outputsOneFramePer24AccessUnits() {
    // The first frame starts with a 28 byte header, after which each access unit takes 2560 bytes.
    // The tenth frame completes once 28 + 240 * 2560 > 10 * 61440 bytes are written, which is the
    // case once the 241st access unit is written.
    assertThat(
            packStream(
                    new TrueHdMatPacker(),
                    /* accessUnitCount= */ 240,
                    SMALL_ACCESS_UNIT_SIZE,
                    /* startTimeUs= */ 0)
                .size())
        .isEqualTo(9);
    assertThat(
            packStream(
                    new TrueHdMatPacker(),
                    /* accessUnitCount= */ 241,
                    SMALL_ACCESS_UNIT_SIZE,
                    /* startTimeUs= */ 0)
                .size())
        .isEqualTo(10);
  }

  @Test
  public void packTrueHd_largeAccessUnits_framesKeepMatCodesInPlace() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    List<MatFrame> frames = new ArrayList<>();
    int frameTime = 0;
    for (int i = 0; i < 400; i++) {
      // Alternate between access units that use more and less than the average slot size.
      boolean isLarge = i % 2 == 0;
      int size = isLarge ? 4000 : 500;
      byte[] accessUnit =
          createAccessUnit(
              size, frameTime, i % MAJOR_SYNC_INTERVAL == 0, RATEBITS_48_KHZ, NO_OUTPUT_TIMING);
      packer.packTrueHd(accessUnit, 0, size, /* timeUs= */ 0);
      // The large access unit needs 4000 / 64 = 62.5 samples worth of space.
      frameTime += isLarge ? 64 : 16;
      pollAll(packer, frames);
    }

    assertThat(frames.size()).isAtLeast(10);
    for (MatFrame matFrame : frames) {
      byte[] frame = toBigEndianBytes(matFrame.data);
      assertThat(slice(frame, 0, IEC61937_TRUEHD_PREAMBLE.length))
          .isEqualTo(IEC61937_TRUEHD_PREAMBLE);
      assertThat(slice(frame, MAT_START_CODE_OFFSET, MAT_START_CODE.length))
          .isEqualTo(MAT_START_CODE);
      assertThat(slice(frame, MAT_MIDDLE_CODE_OFFSET, MAT_MIDDLE_CODE.length))
          .isEqualTo(MAT_MIDDLE_CODE);
      assertThat(slice(frame, MAT_END_CODE_OFFSET, MAT_END_CODE.length)).isEqualTo(MAT_END_CODE);
    }
  }

  @Test
  public void packTrueHd_writesAllAccessUnitData() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    int accessUnitCount = 241;

    List<MatFrame> frames =
        packStream(packer, accessUnitCount, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);

    // Count the payload fill bytes, which are only written as audio data. Each access unit has
    // four length and frame time bytes, and a major sync has seven more bytes (sync word,
    // ratebits, substream count and extension flags) that aren't fill. The last access unit is
    // still pending in the next frame, so isn't counted.
    int completedAccessUnitCount = accessUnitCount - 1;
    int majorSyncCount =
        (completedAccessUnitCount + MAJOR_SYNC_INTERVAL - 1) / MAJOR_SYNC_INTERVAL;
    int expectedFillBytes =
        completedAccessUnitCount * (SMALL_ACCESS_UNIT_SIZE - 4) - majorSyncCount * 7;
    int fillBytes = 0;
    for (MatFrame matFrame : frames) {
      for (byte b : toBigEndianBytes(matFrame.data)) {
        if (b == PAYLOAD_FILL) {
          fillBytes++;
        }
      }
    }
    assertThat(fillBytes).isEqualTo(expectedFillBytes);
  }

  @Test
  public void packTrueHd_timestampsFramesFromFirstAccessUnit() {
    TrueHdMatPacker packer = new TrueHdMatPacker();

    List<MatFrame> frames =
        packStream(
            packer,
            /* accessUnitCount= */ 241,
            SMALL_ACCESS_UNIT_SIZE,
            /* startTimeUs= */ 1_000_000);

    assertThat(frames).hasSize(10);
    for (int i = 0; i < frames.size(); i++) {
      assertThat(frames.get(i).timeUs).isEqualTo(1_000_000 + i * 20_000L);
    }
  }

  @Test
  public void packTrueHd_44_1KhzStream_timestampsFramesWith44_1KhzFrameDuration() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    List<MatFrame> frames = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
      byte[] accessUnit =
          createAccessUnit(
              SMALL_ACCESS_UNIT_SIZE,
              /* frameTime= */ i * ACCESS_UNIT_SAMPLES,
              /* isMajorSync= */ i % MAJOR_SYNC_INTERVAL == 0,
              RATEBITS_44_1_KHZ,
              NO_OUTPUT_TIMING);
      packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 500_000);
      pollAll(packer, frames);
    }

    assertThat(frames).hasSize(4);
    // 960 samples at 44.1 kHz, rounded down.
    assertThat(frames.get(0).timeUs).isEqualTo(500_000);
    assertThat(frames.get(1).timeUs).isEqualTo(500_000 + 21_768);
    assertThat(frames.get(2).timeUs).isEqualTo(500_000 + 43_537);
    assertThat(frames.get(3).timeUs).isEqualTo(500_000 + 65_306);
  }

  @Test
  public void packTrueHd_withUnknownTime_outputsUnsetTimestamps() {
    TrueHdMatPacker packer = new TrueHdMatPacker();

    List<MatFrame> frames =
        packStream(
            packer, /* accessUnitCount= */ 60, SMALL_ACCESS_UNIT_SIZE, C.TIME_UNSET);

    assertThat(frames).hasSize(2);
    assertThat(frames.get(0).timeUs).isEqualTo(C.TIME_UNSET);
    assertThat(frames.get(1).timeUs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void packTrueHd_largeFrameTimeJump_reinitializesUntilNextMajorSync() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    List<MatFrame> frames =
        packStream(packer, /* accessUnitCount= */ 10, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);
    assertThat(frames).isEmpty();

    // A jump of more than 5 MAT frames worth of padding (5 * 61440 / 64 = 4800 samples).
    int frameTime = 10 * ACCESS_UNIT_SAMPLES + 10_000;
    for (int i = 0; i < 100; i++) {
      byte[] accessUnit = createAccessUnit(SMALL_ACCESS_UNIT_SIZE, frameTime);
      packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 0);
      frameTime += ACCESS_UNIT_SAMPLES;
    }
    packer.finish();

    // The partial frame was discarded and access units without a major sync were ignored.
    assertThat(packer.pollOutputFrame()).isNull();
  }

  @Test
  public void packTrueHd_seamlessBranch_carriesOnPacking() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    List<MatFrame> frames = new ArrayList<>();
    int frameTime = 0;
    for (int i = 0; i < 200; i++) {
      if (i == 100) {
        // Branch point: both the input and the output timing jump.
        frameTime += 10_000;
      }
      // The output timing is one access unit ahead of the input timing.
      byte[] accessUnit =
          createAccessUnit(
              SMALL_ACCESS_UNIT_SIZE,
              frameTime & 0xFFFF,
              /* isMajorSync= */ true,
              RATEBITS_48_KHZ,
              /* outputTiming= */ (frameTime + ACCESS_UNIT_SAMPLES) & 0xFFFF);
      packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 0);
      frameTime += ACCESS_UNIT_SAMPLES;
      pollAll(packer, frames);
    }

    // Without reinitializing, 200 access units fill 8 frames, same as a continuous stream.
    assertThat(frames).hasSize(8);
  }

  @Test
  public void finish_padsAndOutputsPartialFrame() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    packStream(packer, /* accessUnitCount= */ 10, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);

    packer.finish();

    @Nullable MatFrame matFrame = packer.pollOutputFrame();
    assertThat(matFrame).isNotNull();
    assertThat(packer.pollOutputFrame()).isNull();
    byte[] frame = toBigEndianBytes(matFrame.data);
    assertThat(frame).hasLength(TrueHdMatPacker.MAT_FRAME_SIZE);
    assertThat(slice(frame, MAT_MIDDLE_CODE_OFFSET, MAT_MIDDLE_CODE.length))
        .isEqualTo(MAT_MIDDLE_CODE);
    assertThat(slice(frame, MAT_END_CODE_OFFSET, MAT_END_CODE.length)).isEqualTo(MAT_END_CODE);
    assertThat(matFrame.timeUs).isEqualTo(0);
  }

  @Test
  public void finish_requiresMajorSyncToRestart() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    packStream(packer, /* accessUnitCount= */ 10, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);
    packer.finish();
    packer.pollOutputFrame();

    for (int i = 0; i < 100; i++) {
      byte[] accessUnit =
          createAccessUnit(SMALL_ACCESS_UNIT_SIZE, /* frameTime= */ (10 + i) * ACCESS_UNIT_SAMPLES);
      packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 0);
    }
    packer.finish();

    assertThat(packer.pollOutputFrame()).isNull();
  }

  @Test
  public void finish_restartsTimestampsFromNextMajorSync() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    packStream(packer, /* accessUnitCount= */ 10, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 0);
    packer.finish();
    packer.pollOutputFrame();

    List<MatFrame> frames =
        packStream(
            packer, /* accessUnitCount= */ 30, SMALL_ACCESS_UNIT_SIZE, /* startTimeUs= */ 5_000_000);

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).timeUs).isEqualTo(5_000_000);
  }

  @Test
  public void reset_discardsPendingFrames() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    for (int i = 0; i < 60; i++) {
      byte[] accessUnit = createStreamAccessUnit(i, SMALL_ACCESS_UNIT_SIZE);
      packer.packTrueHd(accessUnit, 0, accessUnit.length, /* timeUs= */ 0);
    }

    packer.reset();
    packer.finish();

    assertThat(packer.pollOutputFrame()).isNull();
  }

  @Test
  public void packTrueHd_withOffset_readsAccessUnitAtOffset() {
    TrueHdMatPacker packer = new TrueHdMatPacker();
    byte[] accessUnit = createMajorSyncAccessUnit(SMALL_ACCESS_UNIT_SIZE, /* frameTime= */ 0);
    byte[] data = new byte[SMALL_ACCESS_UNIT_SIZE + 10];
    System.arraycopy(accessUnit, 0, data, 10, SMALL_ACCESS_UNIT_SIZE);

    packer.packTrueHd(data, /* offset= */ 10, SMALL_ACCESS_UNIT_SIZE, /* timeUs= */ 0);
    packer.finish();

    @Nullable MatFrame matFrame = packer.pollOutputFrame();
    assertThat(matFrame).isNotNull();
    assertThat(slice(toBigEndianBytes(matFrame.data), MAT_FIRST_DATA_OFFSET, accessUnit.length))
        .isEqualTo(accessUnit);
  }

  /**
   * Packs a continuous 48 kHz stream with a major sync every {@link #MAJOR_SYNC_INTERVAL} access
   * units and returns the output frames.
   */
  private static List<MatFrame> packStream(
      TrueHdMatPacker packer, int accessUnitCount, int accessUnitSize, long startTimeUs) {
    List<MatFrame> frames = new ArrayList<>();
    for (int i = 0; i < accessUnitCount; i++) {
      byte[] accessUnit = createStreamAccessUnit(i, accessUnitSize);
      long timeUs = startTimeUs == C.TIME_UNSET ? C.TIME_UNSET : startTimeUs + i * 833L;
      packer.packTrueHd(accessUnit, 0, accessUnit.length, timeUs);
      pollAll(packer, frames);
    }
    return frames;
  }

  private static byte[] createStreamAccessUnit(int index, int size) {
    return createAccessUnit(
        size,
        /* frameTime= */ (index * ACCESS_UNIT_SAMPLES) & 0xFFFF,
        /* isMajorSync= */ index % MAJOR_SYNC_INTERVAL == 0,
        RATEBITS_48_KHZ,
        NO_OUTPUT_TIMING);
  }

  private static void pollAll(TrueHdMatPacker packer, List<MatFrame> frames) {
    @Nullable MatFrame frame;
    while ((frame = packer.pollOutputFrame()) != null) {
      frames.add(frame);
    }
  }
}
