/*
 * Copyright 2023 The Android Open Source Project
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
import android.media.AudioManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioSystem;

/** Unit tests for {@link DefaultAudioOffloadSupportProvider}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultAudioOffloadSupportProviderTest {

  @Test
  public void
      getAudioOffloadSupport_withoutSampleRate_returnsAudioOffloadSupportDefaultUnsupported() {
    Format formatWithoutSampleRate =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).build();
    DefaultAudioOffloadSupportProvider audioOffloadSupportProvider =
        new DefaultAudioOffloadSupportProvider();

    AudioOffloadSupport audioOffloadSupport =
        audioOffloadSupportProvider.getAudioOffloadSupport(
            formatWithoutSampleRate, AudioAttributes.DEFAULT);

    assertThat(audioOffloadSupport.isFormatSupported).isFalse();
  }

  @Test
  @Config(maxSdk = 29)
  public void
      getAudioOffloadSupport_withOpusAndSdkUnder30_returnsAudioOffloadSupportDefaultUnsupported() {
    Format formatOpus =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setSampleRate(48_000).build();
    DefaultAudioOffloadSupportProvider audioOffloadSupportProvider =
        new DefaultAudioOffloadSupportProvider();

    AudioOffloadSupport audioOffloadSupport =
        audioOffloadSupportProvider.getAudioOffloadSupport(formatOpus, AudioAttributes.DEFAULT);

    assertThat(audioOffloadSupport.isFormatSupported).isFalse();
  }

  @Test
  @Config(maxSdk = 33)
  public void
      getAudioOffloadSupport_withDtsXAndSdkUnder34_returnsAudioOffloadSupportDefaultUnsupported() {
    Format formatDtsX =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_X).setSampleRate(48_000).build();
    DefaultAudioOffloadSupportProvider audioOffloadSupportProvider =
        new DefaultAudioOffloadSupportProvider();

    AudioOffloadSupport audioOffloadSupport =
        audioOffloadSupportProvider.getAudioOffloadSupport(formatDtsX, AudioAttributes.DEFAULT);

    assertThat(audioOffloadSupport.isFormatSupported).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void getAudioOffloadSupport_onApi33_correctlyInterpretsDirectPlaybackBitmask() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
            .setSampleRate(48_000)
            .setChannelCount(2)
            .build();
    AudioAttributes attributes = AudioAttributes.DEFAULT;
    AudioFormat expectedAudioFormat =
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_MP3)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build();
    DefaultAudioOffloadSupportProvider provider =
        new DefaultAudioOffloadSupportProvider(ApplicationProvider.getApplicationContext());

    // 1. Verify DIRECT_PLAYBACK_NOT_SUPPORTED
    AudioOffloadSupport audioOffloadSupport = provider.getAudioOffloadSupport(format, attributes);

    assertThat(audioOffloadSupport.isFormatSupported).isFalse();

    // 2. Verify DIRECT_PLAYBACK_OFFLOAD_SUPPORTED
    ShadowAudioSystem.setDirectPlaybackSupport(
        expectedAudioFormat,
        attributes.getPlatformAudioAttributes(),
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED);

    audioOffloadSupport = provider.getAudioOffloadSupport(format, attributes);

    assertThat(audioOffloadSupport.isFormatSupported).isTrue();
    assertThat(audioOffloadSupport.isGaplessSupported).isFalse();

    // 3. Verify DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED
    ShadowAudioSystem.setDirectPlaybackSupport(
        expectedAudioFormat,
        attributes.getPlatformAudioAttributes(),
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED
            | AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED);

    audioOffloadSupport = provider.getAudioOffloadSupport(format, attributes);

    assertThat(audioOffloadSupport.isFormatSupported).isTrue();
    assertThat(audioOffloadSupport.isGaplessSupported).isTrue();
  }
}
