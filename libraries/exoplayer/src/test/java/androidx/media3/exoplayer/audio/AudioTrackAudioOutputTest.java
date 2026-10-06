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

import static androidx.media3.exoplayer.audio.DefaultAudioSink.MAX_PLAYBACK_SPEED;
import static com.google.common.truth.Truth.assertThat;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioSystem;

/** Unit tests for {@link AudioTrackAudioOutput}. */
@RunWith(AndroidJUnit4.class)
public final class AudioTrackAudioOutputTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

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

  @Test
  @Config(minSdk = 29)
  public void release_fromApi29WithSkipFlushFlagEnabled_skipsFlushBeforeRelease() throws Exception {
    Flags.enableFlag(Flags.FLAG_SKIP_AUDIO_TRACK_FLUSH_BEFORE_RELEASE);
    initializeAudioTrackAudioOutput();
    audioTrackAudioOutput.play();
    boolean unused =
        audioTrackAudioOutput.write(
            createByteBuffer(ONE_SECOND_BUFFER),
            /* encodedAccessUnitCount= */ 1,
            /* presentationTimeUs= */ 0);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(44100);

    releaseAndAwaitCompletion(audioTrackAudioOutput);

    assertThat(audioTrack.getState()).isEqualTo(AudioTrack.STATE_UNINITIALIZED);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(44100);
  }

  @Test
  @Config(minSdk = 29)
  public void release_fromApi29WithSkipFlushFlagDisabled_flushesBeforeRelease() throws Exception {
    Flags.disableFlag(Flags.FLAG_SKIP_AUDIO_TRACK_FLUSH_BEFORE_RELEASE);
    initializeAudioTrackAudioOutput();
    audioTrackAudioOutput.play();
    boolean unused =
        audioTrackAudioOutput.write(
            createByteBuffer(ONE_SECOND_BUFFER),
            /* encodedAccessUnitCount= */ 1,
            /* presentationTimeUs= */ 0);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(44100);

    releaseAndAwaitCompletion(audioTrackAudioOutput);

    assertThat(audioTrack.getState()).isEqualTo(AudioTrack.STATE_UNINITIALIZED);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(0);
  }

  @Test
  @Config(maxSdk = 28)
  public void release_upToApi28WithSkipFlushFlagEnabled_flushesBeforeRelease() throws Exception {
    Flags.enableFlag(Flags.FLAG_SKIP_AUDIO_TRACK_FLUSH_BEFORE_RELEASE);
    initializeAudioTrackAudioOutput();
    audioTrackAudioOutput.play();
    boolean unused =
        audioTrackAudioOutput.write(
            createByteBuffer(ONE_SECOND_BUFFER),
            /* encodedAccessUnitCount= */ 1,
            /* presentationTimeUs= */ 0);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(44100);

    releaseAndAwaitCompletion(audioTrackAudioOutput);

    assertThat(audioTrack.getState()).isEqualTo(AudioTrack.STATE_UNINITIALIZED);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(0);
  }

  @Test
  @Config(minSdk = 29)
  public void release_offloadedPlaybackWithSkipFlushFlagDisabled_skipsFlushBeforeRelease()
      throws Exception {
    Flags.disableFlag(Flags.FLAG_SKIP_AUDIO_TRACK_FLUSH_BEFORE_RELEASE);
    android.media.AudioAttributes platformAttributes =
        new android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build();
    AudioFormat platformFormat =
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_AAC_LC)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build();
    ShadowAudioSystem.setOffloadSupported(
        platformFormat, platformAttributes, /* supported= */ true);
    ShadowAudioSystem.setOffloadPlaybackSupport(
        platformFormat, platformAttributes, AudioManager.PLAYBACK_OFFLOAD_SUPPORTED);
    ShadowAudioSystem.setDirectPlaybackSupport(
        platformFormat, platformAttributes, AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED);
    audioTrack =
        new AudioTrack.Builder()
            .setAudioAttributes(platformAttributes)
            .setAudioFormat(platformFormat)
            .setBufferSizeInBytes(1024)
            .setOffloadedPlayback(true)
            .build();
    audioTrackAudioOutput =
        new AudioTrackAudioOutput(
            audioTrack,
            new OutputConfig.Builder()
                .setEncoding(C.ENCODING_AAC_LC)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(1024)
                .setIsOffload(true)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build())
                .build(),
            /* capabilityChangeListener= */ null,
            MAX_PLAYBACK_SPEED,
            /* clock= */ clock);
    audioTrackAudioOutput.play();
    boolean unused =
        audioTrackAudioOutput.write(
            createByteBuffer(256), /* encodedAccessUnitCount= */ 1, /* presentationTimeUs= */ 0);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(256);

    releaseAndAwaitCompletion(audioTrackAudioOutput);

    assertThat(audioTrack.getState()).isEqualTo(AudioTrack.STATE_UNINITIALIZED);
    assertThat(audioTrack.getPlaybackHeadPosition()).isEqualTo(256);
  }

  private void initializeAudioTrackAudioOutput() {
    initializeAudioTrackAudioOutput(
        /* listener= */ null,
        /* audioFormatEncoding= */ AudioFormat.ENCODING_PCM_16BIT,
        /* encoding= */ C.ENCODING_PCM_16BIT,
        /* channelMask= */ AudioFormat.CHANNEL_OUT_STEREO,
        /* sampleRate= */ 44100);
  }

  private void initializeAudioTrackAudioOutput(
      @Nullable AudioTrackAudioOutput.CapabilityChangeListener listener,
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
                .setChannelMask(channelMask)
                .setBufferSize(1024)
                .setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build())
                .build(),
            listener,
            MAX_PLAYBACK_SPEED,
            /* clock= */ clock);
  }

  private static void releaseAndAwaitCompletion(AudioTrackAudioOutput output) throws Exception {
    AtomicBoolean released = new AtomicBoolean();
    output.addListener(
        new AudioOutput.Listener() {
          @Override
          public void onPositionAdvancing(long playoutStartSystemTimeMs) {}

          @Override
          public void onOffloadDataRequest() {}

          @Override
          public void onOffloadPresentationEnded() {}

          @Override
          public void onUnderrun() {}

          @Override
          public void onReleased() {
            released.set(true);
          }
        });
    output.release();
    RobolectricUtil.runMainLooperUntil(released::get);
  }

  private static ByteBuffer createByteBuffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
