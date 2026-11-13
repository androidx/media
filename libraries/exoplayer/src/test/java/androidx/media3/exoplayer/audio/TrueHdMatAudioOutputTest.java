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
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.NO_OUTPUT_TIMING;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.RATEBITS_48_KHZ;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.createAccessUnit;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.createMajorSyncAccessUnit;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.slice;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.toBigEndianBytes;
import static androidx.media3.exoplayer.audio.TrueHdTestUtil.toInputBuffer;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowAudioTrack;

/** Unit tests for {@link TrueHdMatAudioOutput}. */
@RunWith(AndroidJUnit4.class)
public final class TrueHdMatAudioOutputTest {

  private static final int ACCESS_UNIT_SIZE = 100;
  private static final int ACCESS_UNITS_PER_BUFFER = 16;

  private final FakeClock clock =
      new FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ true);
  private final ByteArrayOutputStream writtenData = new ByteArrayOutputStream();
  private final ShadowAudioTrack.OnAudioDataWrittenListener audioDataListener =
      (audioTrack, audioData, format) -> {
        if (format.getEncoding() == AudioFormat.ENCODING_IEC61937) {
          writtenData.write(audioData, 0, audioData.length);
        }
      };

  private int nextAccessUnitIndex;

  @Before
  public void setUp() {
    ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_IEC61937);
    ShadowAudioTrack.addAudioDataListener(audioDataListener);
  }

  @After
  public void tearDown() {
    ShadowAudioTrack.removeAudioDataListener(audioDataListener);
  }

  @Test
  public void create_withNonTrueHdEncoding_throws() {
    OutputConfig config =
        createTrueHdConfig(/* sampleRate= */ 48_000).buildUpon().setEncoding(C.ENCODING_AC3).build();

    assertThrows(
        IllegalArgumentException.class,
        () -> TrueHdMatAudioOutput.create(config, /* capabilityChangeListener= */ null, clock));
  }

  @Test
  public void create_48KhzStream_usesIec61937TrackAt192Khz() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);

    AudioFormat format = writeUntilFirstMatFrame(output);

    assertThat(format.getEncoding()).isEqualTo(AudioFormat.ENCODING_IEC61937);
    assertThat(format.getSampleRate()).isEqualTo(192_000);
    assertThat(format.getChannelCount()).isEqualTo(8);
    output.release();
  }

  @Test
  public void create_44_1KhzStream_usesIec61937TrackAt176_4Khz() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 44_100);

    AudioFormat format = writeUntilFirstMatFrame(output);

    assertThat(format.getSampleRate()).isEqualTo(176_400);
    output.release();
  }

  @Test
  public void getSampleRate_returnsTrueHdSampleRate() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 96_000);

    assertThat(output.getSampleRate()).isEqualTo(96_000);
    output.release();
  }

  @Test
  public void isOffloadedPlayback_returnsFalse() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);

    assertThat(output.isOffloadedPlayback()).isFalse();
    output.release();
  }

  @Test
  public void write_consumesWholeBufferOfAccessUnits() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();
    ByteBuffer buffer = createNextInputBuffer();

    boolean fullyHandled =
        output.write(buffer, ACCESS_UNITS_PER_BUFFER, /* presentationTimeUs= */ 0);

    assertThat(fullyHandled).isTrue();
    assertThat(buffer.remaining()).isEqualTo(0);
    output.release();
  }

  @Test
  public void write_writesCompleteMatFramesToTrack() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();

    // 6 buffers of 16 access units are 96 access units, which complete 3 MAT frames.
    for (int i = 0; i < 6; i++) {
      assertThat(output.write(createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, i * 13_333L))
          .isTrue();
    }

    byte[] written = toBigEndianBytes(writtenData.toByteArray());
    assertThat(written).hasLength(3 * TrueHdMatPacker.MAT_FRAME_SIZE);
    for (int i = 0; i < 3; i++) {
      byte[] frame =
          slice(written, i * TrueHdMatPacker.MAT_FRAME_SIZE, TrueHdMatPacker.MAT_FRAME_SIZE);
      assertThat(slice(frame, 0, IEC61937_TRUEHD_PREAMBLE.length))
          .isEqualTo(IEC61937_TRUEHD_PREAMBLE);
      assertThat(slice(frame, MAT_END_CODE_OFFSET, MAT_END_CODE.length)).isEqualTo(MAT_END_CODE);
    }
    assertThat(slice(written, MAT_FIRST_DATA_OFFSET, ACCESS_UNIT_SIZE))
        .isEqualTo(createMajorSyncAccessUnit(ACCESS_UNIT_SIZE, /* frameTime= */ 0));
    output.release();
  }

  @Test
  public void write_withInvalidAccessUnitLength_dropsRestOfBuffer() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();
    byte[] invalidAccessUnit = createAccessUnit(ACCESS_UNIT_SIZE, /* frameTime= */ 0);
    // Claims to be longer than the buffer.
    invalidAccessUnit[0] = (byte) 0xFF;
    invalidAccessUnit[1] = (byte) 0xFF;
    ByteBuffer buffer =
        toInputBuffer(
            createMajorSyncAccessUnit(ACCESS_UNIT_SIZE, /* frameTime= */ 0), invalidAccessUnit);

    boolean fullyHandled = output.write(buffer, /* encodedAccessUnitCount= */ 2, 0);

    assertThat(fullyHandled).isTrue();
    assertThat(buffer.remaining()).isEqualTo(0);
    output.release();
  }

  @Test
  public void stop_writesPaddedPartialMatFrame() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();
    output.write(createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, /* presentationTimeUs= */ 0);
    assertThat(writtenData.size()).isEqualTo(0);

    output.stop();

    byte[] written = toBigEndianBytes(writtenData.toByteArray());
    assertThat(written).hasLength(TrueHdMatPacker.MAT_FRAME_SIZE);
    assertThat(slice(written, MAT_END_CODE_OFFSET, MAT_END_CODE.length)).isEqualTo(MAT_END_CODE);
    output.release();
  }

  @Test
  public void flush_discardsPartialMatFrame() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();
    output.write(createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, /* presentationTimeUs= */ 0);

    output.flush();
    output.stop();

    assertThat(writtenData.size()).isEqualTo(0);
    output.release();
  }

  @Test
  public void flush_requiresMajorSyncToRestart() throws Exception {
    TrueHdMatAudioOutput output = createOutput(/* sampleRate= */ 48_000);
    output.play();
    output.write(createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, /* presentationTimeUs= */ 0);

    output.flush();
    byte[][] accessUnits = new byte[ACCESS_UNITS_PER_BUFFER][];
    for (int i = 0; i < accessUnits.length; i++) {
      accessUnits[i] = createAccessUnit(ACCESS_UNIT_SIZE, /* frameTime= */ i * 40);
    }
    output.write(toInputBuffer(accessUnits), ACCESS_UNITS_PER_BUFFER, /* presentationTimeUs= */ 0);
    output.stop();

    assertThat(writtenData.size()).isEqualTo(0);
    output.release();
  }

  @Test
  public void write_tunneling_writesMatFrames() throws Exception {
    OutputConfig config =
        createTrueHdConfig(/* sampleRate= */ 48_000)
            .buildUpon()
            .setIsTunneling(true)
            .setAudioSessionId(1234)
            .build();
    TrueHdMatAudioOutput output =
        TrueHdMatAudioOutput.create(config, /* capabilityChangeListener= */ null, clock);
    output.play();

    for (int i = 0; i < 2; i++) {
      assertThat(
              output.write(
                  createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, 1_000_000 + i * 13_333L))
          .isTrue();
    }

    // 32 access units complete one MAT frame, preceded by the platform's AV sync header (version 2,
    // padded to the 16 byte IEC 61937 frame size).
    byte[] written = writtenData.toByteArray();
    int headerSize = 32;
    assertThat(written).hasLength(headerSize + TrueHdMatPacker.MAT_FRAME_SIZE);
    ByteBuffer header = ByteBuffer.wrap(written, 0, headerSize).order(ByteOrder.BIG_ENDIAN);
    assertThat(header.getInt(0)).isEqualTo(0x55550002);
    assertThat(header.getInt(4)).isEqualTo(TrueHdMatPacker.MAT_FRAME_SIZE);
    // The MAT frame starts with the first access unit.
    assertThat(header.getLong(8)).isEqualTo(1_000_000_000L);
    byte[] frame =
        toBigEndianBytes(slice(written, headerSize, TrueHdMatPacker.MAT_FRAME_SIZE));
    assertThat(slice(frame, 0, IEC61937_TRUEHD_PREAMBLE.length))
        .isEqualTo(IEC61937_TRUEHD_PREAMBLE);
    output.release();
  }

  @Test
  public void getBufferSizeInFrames_isInTrueHdFrames() throws Exception {
    AudioTrack audioTrack =
        new AudioTrack.Builder()
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_IEC61937)
                    .setSampleRate(192_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
                    .build())
            .setBufferSizeInBytes(12 * TrueHdMatPacker.MAT_FRAME_SIZE)
            .build();
    OutputConfig iecConfig =
        createTrueHdConfig(/* sampleRate= */ 192_000)
            .buildUpon()
            .setEncoding(C.ENCODING_PCM_16BIT)
            .build();
    TrueHdMatAudioOutput output =
        new TrueHdMatAudioOutput(
            new AudioTrackAudioOutput(
                audioTrack,
                iecConfig,
                /* capabilityChangeListener= */ null,
                /* maxPlaybackSpeed= */ 1f,
                clock),
            /* trueHdSampleRate= */ 48_000);

    assertThat(output.getBufferSizeInFrames()).isEqualTo(audioTrack.getBufferSizeInFrames() / 4);
    output.release();
  }

  private TrueHdMatAudioOutput createOutput(int sampleRate) throws Exception {
    return TrueHdMatAudioOutput.create(
        createTrueHdConfig(sampleRate), /* capabilityChangeListener= */ null, clock);
  }

  private static OutputConfig createTrueHdConfig(int sampleRate) {
    return new OutputConfig.Builder()
        .setEncoding(C.ENCODING_DOLBY_TRUEHD)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
        .setBufferSize(12 * TrueHdMatPacker.MAT_FRAME_SIZE)
        .setAudioAttributes(AudioAttributes.DEFAULT)
        .build();
  }

  /** Writes buffers until the first MAT frame is written and returns the track format. */
  private AudioFormat writeUntilFirstMatFrame(TrueHdMatAudioOutput output) throws Exception {
    AudioFormat[] format = new AudioFormat[1];
    ShadowAudioTrack.OnAudioDataWrittenListener formatListener =
        (audioTrack, audioData, audioFormat) -> format[0] = audioFormat;
    ShadowAudioTrack.addAudioDataListener(formatListener);
    try {
      output.play();
      for (int i = 0; i < 4 && format[0] == null; i++) {
        output.write(createNextInputBuffer(), ACCESS_UNITS_PER_BUFFER, i * 13_333L);
      }
    } finally {
      ShadowAudioTrack.removeAudioDataListener(formatListener);
    }
    assertThat(format[0]).isNotNull();
    return format[0];
  }

  /** Creates an input buffer with the next access units of a continuous stream. */
  private ByteBuffer createNextInputBuffer() {
    byte[][] accessUnits = new byte[ACCESS_UNITS_PER_BUFFER][];
    for (int i = 0; i < accessUnits.length; i++) {
      int index = nextAccessUnitIndex++;
      accessUnits[i] =
          createAccessUnit(
              ACCESS_UNIT_SIZE,
              /* frameTime= */ (index * 40) & 0xFFFF,
              /* isMajorSync= */ index % 8 == 0,
              RATEBITS_48_KHZ,
              NO_OUTPUT_TIMING);
    }
    return toInputBuffer(accessUnits);
  }
}
