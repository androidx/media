/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static androidx.media3.common.C.MICROS_PER_SECOND;
import static androidx.media3.exoplayer.audio.DefaultAudioSink.OUTPUT_MODE_PCM;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.ceil;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Tests for {@link DefaultAudioTrackBufferSizeProvider} for PCM audio. */
@RunWith(Parameterized.class)
public class DefaultAudioTrackBufferSizeProviderPcmTest {

  private static final DefaultAudioTrackBufferSizeProvider DEFAULT =
      new DefaultAudioTrackBufferSizeProvider.Builder().build();

  @SuppressWarnings("deprecation") // Testing deprecated logic
  private static final DefaultAudioTrackBufferSizeProvider DYNAMIC =
      new DefaultAudioTrackBufferSizeProvider.Builder()
          .setMinPcmBufferDurationUs(250_000)
          .setMaxPcmBufferDurationUs(750_000)
          .setPcmBufferMultiplicationFactor(4) // Explicitly set to trigger dynamic mode
          .build();

  @Parameter(0)
  public @C.PcmEncoding int encoding;

  @Parameter(1)
  public int channelCount;

  @Parameter(2)
  public int sampleRate;

  @Parameters(name = "{index}: encoding={0}, channelCount={1}, sampleRate={2}")
  public static List<Integer[]> data() {
    return Sets.cartesianProduct(
            ImmutableList.of(
                /* encoding */ ImmutableSet.of(
                    C.ENCODING_PCM_8BIT,
                    C.ENCODING_PCM_16BIT,
                    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                    C.ENCODING_PCM_24BIT,
                    C.ENCODING_PCM_32BIT,
                    C.ENCODING_PCM_FLOAT),
                /* channelCount */ ImmutableSet.of(1, 2, 3, 4, 6, 8),
                /* sampleRate*/ ImmutableSet.of(
                    8000, 11025, 16000, 22050, 44100, 48000, 88200, 96000)))
        .stream()
        .map(s -> s.toArray(new Integer[0]))
        .collect(Collectors.toList());
  }

  private int getPcmFrameSize() {
    return Util.getPcmFrameSize(encoding, channelCount);
  }

  private int roundUpToFrame(int buffer) {
    int pcmFrameSize = getPcmFrameSize();
    return (int) ceil((double) buffer / pcmFrameSize) * pcmFrameSize;
  }

  private int durationUsToBytes(int durationUs) {
    return (int) ((long) durationUs * getPcmFrameSize() * sampleRate / MICROS_PER_SECOND);
  }

  @Test
  public void getBufferSizeInBytes_veryBigMinBufferSize_isMinBufferSize() {
    int bufferSize =
        DEFAULT.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 1234567890,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    assertThat(bufferSize).isEqualTo(roundUpToFrame(1234567890));
  }

  @Test
  public void getBufferSizeInBytes_customTarget_isTargetBufferSize() {
    DefaultAudioTrackBufferSizeProvider provider =
        new DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(600_000)
            .build();
    int bufferSize =
        provider.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(600_000)));
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated logic
  public void getBufferSizeInBytes_fixedMode_ignoresMinConstraint() {
    DefaultAudioTrackBufferSizeProvider provider =
        new DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(500_000)
            .setMinPcmBufferDurationUs(600_000)
            .build();
    int bufferSize =
        provider.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // Target 500ms is NOT clamped to min 600ms in fixed mode
    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(500_000)));
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated logic
  public void getBufferSizeInBytes_fixedMode_ignoresMaxConstraint() {
    DefaultAudioTrackBufferSizeProvider provider =
        new DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(500_000)
            .setMaxPcmBufferDurationUs(400_000)
            .build();
    int bufferSize =
        provider.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // Target 500ms is NOT clamped to max 400ms in fixed mode
    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(500_000)));
  }

  @Test
  public void getBufferSizeInBytes_default_isDefaultBufferDuration() {
    int bufferSize =
        DEFAULT.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(500_000)));
  }

  @Test
  public void getBufferSizeInBytes_defaultLowPlaybackSpeed_isScaled() {
    int bufferSize =
        DEFAULT.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1 / 5F);

    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(500_000) / 5));
  }

  @Test
  public void getBufferSizeInBytes_defaultHighPlaybackSpeed_isScaled() {
    int bufferSize =
        DEFAULT.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 8F);

    int expected = roundUpToFrame(durationUsToBytes(500_000) * 8);
    assertThat(bufferSize).isEqualTo(expected);
  }

  // Dynamic Mode Tests (when factor is explicitly set)

  @Test
  public void getBufferSizeInBytes_dynamicModeNoMinBufferSize_isMinBufferDuration() {
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // In dynamic mode, minPcmBufferDurationUs (250ms) applies as a minimum constraint
    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(250_000)));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeTooSmallMinBufferSize_isMinBufferDuration() {
    int minBufferSizeInBytes = durationUsToBytes(250_000 / 4) - 1;
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ minBufferSizeInBytes,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // In dynamic mode, minPcmBufferDurationUs (250ms) applies
    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(250_000)));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeLowMinBufferSize_multipliesMinBufferSize() {
    int minBufferSizeInBytes = durationUsToBytes(250_000 / 4) + 1;
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ minBufferSizeInBytes,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // In dynamic mode, returns minBufferSizeInBytes * 4
    assertThat(bufferSize).isEqualTo(roundUpToFrame(minBufferSizeInBytes * 4));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeHighMinBufferSize_multipliesMinBufferSize() {
    int minBufferSizeInBytes = durationUsToBytes(750_000 / 4) - 1;
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ minBufferSizeInBytes,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // In dynamic mode, returns minBufferSizeInBytes * 4
    assertThat(bufferSize).isEqualTo(roundUpToFrame(minBufferSizeInBytes * 4));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeTooHighMinBufferSize_isMaxBufferDuration() {
    int minBufferSizeInBytes = durationUsToBytes(750_000 / 4) + 1;
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ minBufferSizeInBytes,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1);

    // In dynamic mode, maxPcmBufferDurationUs (750ms) applies as a maximum constraint
    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(750_000)));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeLowPlaybackSpeed_isScaled() {
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 1 / 5F);

    assertThat(bufferSize).isEqualTo(roundUpToFrame(durationUsToBytes(250_000) / 5));
  }

  @Test
  public void getBufferSizeInBytes_dynamicModeHighPlaybackSpeed_isScaled() {
    int bufferSize =
        DYNAMIC.getBufferSizeInBytes(
            /* minBufferSizeInBytes= */ 0,
            /* encoding= */ encoding,
            /* outputMode= */ OUTPUT_MODE_PCM,
            /* pcmFrameSize= */ getPcmFrameSize(),
            /* sampleRate= */ sampleRate,
            /* bitrate= */ Format.NO_VALUE,
            /* maxAudioTrackPlaybackSpeed= */ 8F);

    int expected = roundUpToFrame(durationUsToBytes(250_000) * 8);
    assertThat(bufferSize).isEqualTo(expected);
  }
}
