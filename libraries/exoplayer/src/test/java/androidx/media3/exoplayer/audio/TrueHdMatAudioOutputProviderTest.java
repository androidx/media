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
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import androidx.media3.common.AudioAttributes;
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
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build();

    assertThat(provider.getFormatSupport(new FormatConfig.Builder(format).build()).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getOutputConfig_pcm_isDelegated() throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build();

    OutputConfig outputConfig = provider.getOutputConfig(new FormatConfig.Builder(format).build());
    AudioOutput audioOutput = provider.getAudioOutput(outputConfig);

    assertThat(outputConfig.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(audioOutput).isInstanceOf(AudioTrackAudioOutput.class);
    audioOutput.release();
  }

  @Test
  public void getFormatSupport_trueHdWithoutDeviceSupport_isUnsupported() {
    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  @Config(minSdk = 29, maxSdk = 30)
  public void getFormatSupport_trueHdBeforeApi31_isUnsupported() {
    // 8 channel IEC 61937 tracks can't be created before API 31.
    addHighBitrateIec61937Support(/* sampleRate= */ 192_000);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  @Config(minSdk = 31, maxSdk = 32)
  public void getFormatSupport_trueHdWithHighBitrateIec61937Support_isSupportedDirectly() {
    addHighBitrateIec61937Support(/* sampleRate= */ 192_000);

    FormatSupport formatSupport =
        provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build());

    assertThat(formatSupport.supportLevel).isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
    assertThat(formatSupport.isFormatSupportedForOffload).isFalse();
  }

  @Test
  @Config(minSdk = 31, maxSdk = 32)
  public void getFormatSupport_44_1KhzTrueHd_checksIec61937SupportAt176_4Khz() {
    Format format = TRUEHD_FORMAT.buildUpon().setSampleRate(44_100).build();
    addHighBitrateIec61937Support(/* sampleRate= */ 192_000);

    assertThat(provider.getFormatSupport(new FormatConfig.Builder(format).build()).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);

    addHighBitrateIec61937Support(/* sampleRate= */ 176_400);

    assertThat(provider.getFormatSupport(new FormatConfig.Builder(format).build()).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_trueHdWithTrueHdDirectProfile_isSupportedDirectly() {
    addDeviceWithDirectProfile(AudioFormat.ENCODING_DOLBY_TRUEHD);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  @Config(minSdk = 33)
  public void getFormatSupport_trueHdWithOtherDirectProfiles_isUnsupported() {
    addDeviceWithDirectProfile(AudioFormat.ENCODING_E_AC3);

    assertThat(
            provider.getFormatSupport(new FormatConfig.Builder(TRUEHD_FORMAT).build()).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_trueHdWithoutSampleRate_isUnsupported() {
    addDeviceSupport();
    Format format = TRUEHD_FORMAT.buildUpon().setSampleRate(Format.NO_VALUE).build();

    assertThat(provider.getFormatSupport(new FormatConfig.Builder(format).build()).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  @Config(minSdk = 31)
  public void getOutputConfig_trueHd_returnsTrueHdConfig() throws Exception {
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
    assertThat(outputConfig.channelMask).isEqualTo(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND);
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

  /** Makes TrueHD supported on the current API level. */
  private void addDeviceSupport() {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      addDeviceWithDirectProfile(AudioFormat.ENCODING_DOLBY_TRUEHD);
    } else {
      addHighBitrateIec61937Support(/* sampleRate= */ 192_000);
    }
  }

  private static void addHighBitrateIec61937Support(int sampleRate) {
    ShadowAudioTrack.addDirectPlaybackSupport(
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_IEC61937)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
            .build(),
        AudioAttributes.DEFAULT.getPlatformAudioAttributes());
  }

  private void addDeviceWithDirectProfile(int encoding) {
    AudioDeviceInfo device =
        AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_HDMI)
            .setProfiles(
                ImmutableList.of(
                    AudioProfileBuilder.newBuilder()
                        .setFormat(encoding)
                        .setSamplingRates(new int[] {48_000})
                        .setChannelMasks(new int[] {AudioFormat.CHANNEL_OUT_7POINT1_SURROUND})
                        .setChannelIndexMasks(new int[] {})
                        .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
                        .build()))
            .build();
    shadowOf(audioManager).addOutputDeviceWithDirectProfiles(device);
  }
}
