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

import static androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_UNSUPPORTED;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.AudioProfileBuilder;
import org.robolectric.shadows.ShadowAudioTrack;

/** Unit tests for {@link TrueHdMatAudioOutputProvider}. */
@RunWith(AndroidJUnit4.class)
public final class TrueHdMatAudioOutputProviderTest {

  private static final Format TRUEHD_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
          .setChannelCount(8)
          .setSampleRate(48_000)
          .build();

  private static final Format PCM_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_RAW)
          .setPcmEncoding(C.ENCODING_PCM_16BIT)
          .setChannelCount(2)
          .setSampleRate(48_000)
          .build();

  private Context context;
  private AudioManager audioManager;
  private TrueHdMatAudioOutputProvider provider;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(audioManager).setOutputDevices(ImmutableList.of());
    ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_IEC61937);
    provider = new TrueHdMatAudioOutputProvider(context);
  }

  @Test
  public void getFormatSupport_pcm_isDelegated() {
    assertThat(provider.getFormatSupport(new FormatConfig.Builder(PCM_FORMAT).build()).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getOutputConfigAndAudioOutput_pcm_isDelegated() throws Exception {
    OutputConfig outputConfig =
        provider.getOutputConfig(new FormatConfig.Builder(PCM_FORMAT).build());
    AudioOutput audioOutput = provider.getAudioOutput(outputConfig);

    assertThat(outputConfig.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(outputConfig.channelMask)
        .isNotEqualTo(TrueHdMatAudioOutputProvider.CHANNEL_MASK_IEC61937);
    assertThat(audioOutput).isInstanceOf(AudioTrackAudioOutput.class);
    audioOutput.release();
  }

  @Test
  public void getFormatSupport_trueHdWithoutDeviceSupport_isNotSupportedDirectly() {
    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getFormatSupport_trueHdWithoutDeviceSupport_isDelegated() {
    AudioOutputProvider delegate = new AudioTrackAudioOutputProvider.Builder(context).build();
    FormatConfig formatConfig = new FormatConfig.Builder(TRUEHD_FORMAT).build();

    assertThat(provider.getFormatSupport(formatConfig).supportLevel)
        .isEqualTo(delegate.getFormatSupport(formatConfig).supportLevel);
  }

  @Test
  @Config(minSdk = 31, maxSdk = 32)
  public void getFormatSupport_deviceWithTrueHdAndIec61937Encodings_isSupportedDirectly() {
    addOutputDevice(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_IEC61937);

    FormatSupport formatSupport =
        provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build());

    assertThat(formatSupport.supportLevel).isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
    assertThat(formatSupport.isFormatSupportedForOffload).isFalse();
  }

  @Test
  @Config(minSdk = 31, maxSdk = 32)
  public void getFormatSupport_deviceWithTrueHdEncodingOnly_isNotSupportedDirectly() {
    addOutputDevice(AudioFormat.ENCODING_DOLBY_TRUEHD);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 31, maxSdk = 32)
  public void getFormatSupport_deviceWithIec61937EncodingOnly_isNotSupportedDirectly() {
    addOutputDevice(AudioFormat.ENCODING_IEC61937);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_trueHdIec61937DirectProfile_isSupportedDirectly() {
    addDeviceWithDirectProfile(
        AudioFormat.ENCODING_DOLBY_TRUEHD, AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937);

    FormatSupport formatSupport =
        provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build());

    assertThat(formatSupport.supportLevel).isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
    assertThat(formatSupport.isFormatSupportedForOffload).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_trueHdDirectProfileWithoutEncapsulation_isNotSupportedDirectly() {
    addDeviceWithDirectProfile(
        AudioFormat.ENCODING_DOLBY_TRUEHD, AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_otherIec61937DirectProfile_isNotSupportedDirectly() {
    addDeviceWithDirectProfile(
        AudioFormat.ENCODING_E_AC3, AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_api33WithOnlyDeviceEncodings_isNotSupportedDirectly() {
    // From API 33 only direct profiles are checked, not device encodings.
    addOutputDevice(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_IEC61937);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isNotEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 31)
  public void getOutputConfig_trueHd_returnsIec61937Config() throws Exception {
    addDeviceSupport();
    FormatConfig formatConfig =
        new FormatConfig.Builder(TRUEHD_FORMAT)
            .setAudioSessionId(42)
            .setEnablePlaybackParameters(true)
            .setEnableOffload(true)
            .build();

    OutputConfig outputConfig = provider.getOutputConfig(formatConfig);

    assertThat(outputConfig.encoding).isEqualTo(C.ENCODING_DOLBY_TRUEHD);
    assertThat(outputConfig.sampleRate).isEqualTo(48_000);
    assertThat(outputConfig.channelMask)
        .isEqualTo(TrueHdMatAudioOutputProvider.CHANNEL_MASK_IEC61937);
    assertThat(outputConfig.bufferSize).isEqualTo(12 * TrueHdMatPacker.MAT_FRAME_SIZE);
    assertThat(outputConfig.audioSessionId).isEqualTo(42);
    assertThat(outputConfig.isTunneling).isFalse();
    assertThat(outputConfig.isOffload).isFalse();
    assertThat(outputConfig.usePlaybackParameters).isFalse();
  }

  @Test
  @Config(minSdk = 31)
  public void getOutputConfig_trueHdWithPreferredBufferSize_usesPreferredBufferSize()
      throws Exception {
    addDeviceSupport();
    FormatConfig formatConfig =
        new FormatConfig.Builder(TRUEHD_FORMAT).setPreferredBufferSize(500_000).build();

    assertThat(provider.getOutputConfig(formatConfig).bufferSize).isEqualTo(500_000);
  }

  @Test
  @Config(minSdk = 31)
  public void getOutputConfig_trueHdWithTunneling_returnsTunnelingConfig() throws Exception {
    addDeviceSupport();
    FormatConfig formatConfig =
        new FormatConfig.Builder(TRUEHD_FORMAT)
            .setEnableTunneling(true)
            .setAudioSessionId(42)
            .build();

    OutputConfig outputConfig = provider.getOutputConfig(formatConfig);

    assertThat(outputConfig.isTunneling).isTrue();
    assertThat(outputConfig.audioSessionId).isEqualTo(42);
  }

  @Test
  public void getOutputConfig_trueHdWithoutDeviceSupport_doesNotUseIec61937ChannelMask()
      throws Exception {
    OutputConfig outputConfig;
    try {
      outputConfig = provider.getOutputConfig(new FormatConfig.Builder(TRUEHD_FORMAT).build());
    } catch (AudioOutputProvider.ConfigurationException e) {
      // The delegate may reject TrueHD altogether, which is also fine.
      return;
    }

    assertThat(outputConfig.channelMask)
        .isNotEqualTo(TrueHdMatAudioOutputProvider.CHANNEL_MASK_IEC61937);
  }

  @Test
  @Config(minSdk = 31)
  public void getAudioOutput_trueHd_returnsTrueHdMatAudioOutput() throws Exception {
    addDeviceSupport();
    OutputConfig outputConfig =
        provider.getOutputConfig(new FormatConfig.Builder(TRUEHD_FORMAT).build());

    AudioOutput audioOutput = provider.getAudioOutput(outputConfig);

    assertThat(audioOutput).isInstanceOf(TrueHdMatAudioOutput.class);
    assertThat(audioOutput.getSampleRate()).isEqualTo(48_000);
    audioOutput.release();
  }

  @Test
  @Config(minSdk = 31)
  public void getAudioOutput_trueHdWithTunneling_returnsTrueHdMatAudioOutput() throws Exception {
    addDeviceSupport();
    OutputConfig outputConfig =
        provider.getOutputConfig(
            new FormatConfig.Builder(TRUEHD_FORMAT)
                .setEnableTunneling(true)
                .setAudioSessionId(42)
                .build());

    AudioOutput audioOutput = provider.getAudioOutput(outputConfig);

    assertThat(audioOutput).isInstanceOf(TrueHdMatAudioOutput.class);
    assertThat(audioOutput.getAudioSessionId()).isEqualTo(42);
    audioOutput.release();
  }

  /** Makes TrueHD IEC 61937 passthrough supported on the current API level. */
  private void addDeviceSupport() {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      addDeviceWithDirectProfile(
          AudioFormat.ENCODING_DOLBY_TRUEHD, AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937);
    } else {
      addOutputDevice(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_IEC61937);
    }
  }

  /** Adds an HDMI output device whose {@link AudioDeviceInfo#getEncodings()} returns encodings. */
  private void addOutputDevice(int... encodings) {
    // Devices created by AudioDeviceInfoBuilder return null encodings before API 33, so mock it.
    AudioDeviceInfo device = mock(AudioDeviceInfo.class);
    when(device.getType()).thenReturn(AudioDeviceInfo.TYPE_HDMI);
    when(device.isSink()).thenReturn(true);
    when(device.getEncodings()).thenReturn(encodings);
    shadowOf(audioManager).setOutputDevices(ImmutableList.of(device));
  }

  private void addDeviceWithDirectProfile(int encoding, int encapsulationType) {
    AudioDeviceInfo device =
        AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_HDMI)
            .setProfiles(ImmutableList.of(createProfile(encoding, encapsulationType)))
            .build();
    shadowOf(audioManager).addOutputDeviceWithDirectProfiles(device);
  }

  private static AudioProfile createProfile(int encoding, int encapsulationType) {
    return AudioProfileBuilder.newBuilder()
        .setFormat(encoding)
        .setSamplingRates(new int[] {48_000, 192_000})
        .setChannelMasks(new int[] {AudioFormat.CHANNEL_OUT_7POINT1_SURROUND})
        .setChannelIndexMasks(new int[] {})
        .setEncapsulationType(encapsulationType)
        .build();
  }
}
