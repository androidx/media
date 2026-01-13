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

import static android.media.AudioFormat.CHANNEL_OUT_STEREO;
import static androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_UNSUPPORTED;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.annotation.Config.ALL_SDKS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioOutputProvider.ConfigurationException;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioTrack;

/** Unit tests for {@link AudioTrackAudioOutputProvider}. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = ALL_SDKS)
public class AudioTrackAudioOutputProviderTest {

  private Context context;
  private AudioTrackAudioOutputProvider audioOutputProvider;
  private AudioManager audioManager;

  private static final AudioAttributes TEST_ATTRIBUTES =
      new AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
          .build();

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    audioOutputProvider = new AudioTrackAudioOutputProvider.Builder(context).build();
  }

  @Test
  public void getFormatSupport_pcm16Bit_isSupportedDirectly() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build();

    FormatConfig config =
        new FormatConfig.Builder(format).setAudioAttributes(TEST_ATTRIBUTES).build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getFormatSupport_pcm8BitWithHighResolutionPcmOutputDisabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_8BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(false)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_pcm8BitWithHighResolutionPcmOutputEnabled_isSupportedDirectly() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_8BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getFormatSupport_pcm24BitWithHighResolutionPcmOutputDisabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(false)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Config(minSdk = 31)
  @Test
  public void getFormatSupport_pcm24BitWithHighResolutionPcmOutputEnabled_isSupportedDirectly() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Config(maxSdk = 30)
  @Test
  public void
      getFormatSupport_pcm24BitWithHighResolutionPcmOutputEnabledBeforeIntroduced_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_pcm24BitBigEndianWithHighResolutionPcmOutputEnabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_24BIT_BIG_ENDIAN)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_pcm32BitWithHighResolutionPcmOutputDisabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(false)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Config(minSdk = 31)
  @Test
  public void getFormatSupport_pcm32BitWithHighResolutionPcmOutputEnabled_isSupportedDirectly() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Config(maxSdk = 30)
  @Test
  public void
      getFormatSupport_pcm32BitWithHighResolutionPcmOutputEnabledBeforeIntroduced_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_32BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_pcm32BitBigEndianWithHighResolutionPcmOutputEnabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_32BIT_BIG_ENDIAN)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_floatPcmWithHighResolutionPcmOutputDisabled_isUnsupported() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(false)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_UNSUPPORTED);
  }

  @Test
  public void getFormatSupport_floatPcmWithHighResolutionPcmOutputEnabled_isSupportedDirectly() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThat(audioOutputProvider.getFormatSupport(config).supportLevel)
        .isEqualTo(FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void configure_throwsConfigurationException_withInvalidInput() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    FormatConfig config =
        new FormatConfig.Builder(format)
            .setAudioAttributes(TEST_ATTRIBUTES)
            .setEnableHighResolutionPcmOutput(true)
            .build();

    assertThrows(ConfigurationException.class, () -> audioOutputProvider.getOutputConfig(config));
  }

  @Test
  public void audioCapabilitiesChange_callsListener() throws Exception {
    AudioCapabilitiesListener listener = new AudioCapabilitiesListener();
    audioOutputProvider.addListener(listener);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format).setAudioAttributes(TEST_ATTRIBUTES).build();
    // This initializes and registers AudioCapabilitiesReceiver
    AudioOutputProvider.FormatSupport unused = audioOutputProvider.getFormatSupport(config);
    // Active AudioOutput needed to register for capabilities changes.
    AudioOutput unusedOutput =
        audioOutputProvider.getAudioOutput(
            new OutputConfig.Builder()
                .setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(CHANNEL_OUT_STEREO)
                .setBufferSize(1024)
                .build());

    setOutputDevices(AudioDeviceInfo.TYPE_HDMI);
    configureHdmiConnection(
        /* maxChannelCount= */ 6,
        /* encodings...= */ AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_E_AC3);

    AudioDeviceInfo hdmiDevice =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build();
    ShadowAudioTrack.setRoutedDevice(hdmiDevice);

    runMainLooperUntil(() -> listener.hasFormatChanged);
  }

  @SuppressWarnings("deprecation") // Testing that it uses the deprecated AudioTrackProvider.
  @Test
  public void getAudioOutput_withAudioTrackProvider_usesProvider() throws Exception {
    AtomicBoolean audioTrackProviderCalled = new AtomicBoolean();
    @SuppressLint("WrongConstant") // Converting Media3 constant to AudioTrack constant
    DefaultAudioSink.AudioTrackProvider audioTrackProvider =
        (audioTrackConfig, audioAttributes, audioSessionId, context) -> {
          audioTrackProviderCalled.set(true);
          return new AudioTrack.Builder()
              .setAudioAttributes(audioAttributes.getPlatformAudioAttributes())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setEncoding(audioTrackConfig.encoding)
                      .setSampleRate(audioTrackConfig.sampleRate)
                      .setChannelMask(audioTrackConfig.channelConfig)
                      .build())
              .setBufferSizeInBytes(audioTrackConfig.bufferSize)
              .build();
        };
    audioOutputProvider =
        new AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackProvider(audioTrackProvider)
            .build();
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(2)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build();
    FormatConfig config =
        new FormatConfig.Builder(format).setAudioAttributes(TEST_ATTRIBUTES).build();

    AudioOutput unusedOutput =
        audioOutputProvider.getAudioOutput(audioOutputProvider.getOutputConfig(config));

    assertThat(audioTrackProviderCalled.get()).isTrue();
  }

  // Adding the permission to the test AndroidManifest.xml doesn't work to appease lint.
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  private void configureHdmiConnection(int maxChannelCount, int... encodings) {
    Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
    intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1);
    intent.putExtra(AudioManager.EXTRA_ENCODINGS, encodings);
    intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, maxChannelCount);
    ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);
  }

  /**
   * Sets all the available output devices and uses the first as the default routed device for the
   * given {@link AudioAttributes}
   */
  private void setOutputDevices(int... types) {
    ImmutableList.Builder<AudioDeviceInfo> audioDeviceInfos = ImmutableList.builder();
    for (int type : types) {
      audioDeviceInfos.add(AudioDeviceInfoBuilder.newBuilder().setType(type).build());
    }
    shadowOf(audioManager).setOutputDevices(audioDeviceInfos.build());
  }

  private static final class AudioCapabilitiesListener implements AudioOutputProvider.Listener {
    private boolean hasFormatChanged = false;

    @Override
    public void onFormatSupportChanged() {
      this.hasFormatChanged = true;
    }
  }
}
