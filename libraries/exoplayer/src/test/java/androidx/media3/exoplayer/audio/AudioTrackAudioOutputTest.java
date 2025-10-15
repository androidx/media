/*
 * Copyright 2025 The Android Open Source Project
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

import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioOutput.OutputConfig;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AudioTrackAudioOutput}. */
@RunWith(AndroidJUnit4.class)
public final class AudioTrackAudioOutputTest {

  private AudioTrack audioTrack;
  private AudioTrackAudioOutput audioTrackAudioOutput;
  private final FakeClock clock =
      new FakeClock(/* initialTimeMs= */ START_TIME_MS, /* isAutoAdvancing= */ true);
  private static final long TIME_TO_ADVANCE_MS = 1000L;
  private static final long START_TIME_MS = 9999L;
  private static final int ONE_SECOND_BUFFER = 44100 * 2 * 2;

  @Test
  public void getAudioSessionId_returnsAudioTrackSessionId() {
    initializeAudioTrackAudioOutput();
    assertThat(audioTrackAudioOutput.getAudioSessionId()).isEqualTo(audioTrack.getAudioSessionId());
  }

  @Test
  public void write_withPcmData_positionAdvances() throws Exception {
    initializeAudioTrackAudioOutput();
    audioTrackAudioOutput.play();

    ByteBuffer buffer1 = createByteBuffer(ONE_SECOND_BUFFER);
    boolean fullyWritten =
        audioTrackAudioOutput.write(
            buffer1, /* encodedAccessUnitCount= */ 1, /* presentationTimeUs= */ 0);
    assertThat(fullyWritten).isEqualTo(true);
    assertThat(buffer1.remaining()).isEqualTo(0);
    clock.advanceTime(TIME_TO_ADVANCE_MS);

    ByteBuffer buffer2 = createByteBuffer(ONE_SECOND_BUFFER);
    fullyWritten =
        audioTrackAudioOutput.write(
            buffer2, /* encodedAccessUnitCount= */ 1, /* presentationTimeUs= */ 0);
    assertThat(fullyWritten).isEqualTo(true);
    assertThat(buffer2.remaining()).isEqualTo(0);
    clock.advanceTime(TIME_TO_ADVANCE_MS);

    assertThat(audioTrackAudioOutput.getPositionUs()).isEqualTo(2_000_000L);
  }

  private void initializeAudioTrackAudioOutput() {
    initializeAudioTrackAudioOutput(
        /* receiver= */ null,
        /* audioFormatEncoding= */ AudioFormat.ENCODING_PCM_16BIT,
        /* encoding= */ C.ENCODING_PCM_16BIT,
        /* channelMask= */ AudioFormat.CHANNEL_OUT_STEREO,
        /* sampleRate= */ 44100);
  }

  private void initializeAudioTrackAudioOutput(
      @Nullable AudioCapabilitiesReceiver receiver,
      int audioFormatEncoding,
      int encoding,
      int channelMask,
      int sampleRate) {
    audioTrack =
        new AudioTrack.Builder()
            .setAudioAttributes(
                new android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build())
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setEncoding(audioFormatEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build())
            .setBufferSizeInBytes(1024)
            .build();
    audioTrackAudioOutput =
        new AudioTrackAudioOutput(
            audioTrack,
            new OutputConfig.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelConfig(channelMask)
                .setBufferSize(1024)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build())
                .build(),
            receiver,
            /* clock= */ clock);
  }

  private static ByteBuffer createByteBuffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
