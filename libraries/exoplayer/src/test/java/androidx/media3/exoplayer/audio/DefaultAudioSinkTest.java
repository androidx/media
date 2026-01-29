/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.annotation.Config.ALL_SDKS;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessorChain;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.AudioProfileBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowAudioSystem;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowUIModeManager;

/** Unit tests for {@link DefaultAudioSink}. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = ALL_SDKS)
public final class DefaultAudioSinkTest {

  private static final long TIMEOUT_MS = 10_000;

  private static final int CHANNEL_COUNT_MONO = 1;
  private static final int CHANNEL_COUNT_STEREO = 2;
  private static final int DEFAULT_MAX_CHANNEL_COUNT = 8;
  private static final int BYTES_PER_FRAME_16_BIT = 2;
  private static final int SAMPLE_RATE_44_1 = 44100;
  private static final int TRIM_100_MS_FRAME_COUNT = 4410;
  private static final int TRIM_10_MS_FRAME_COUNT = 441;
  private static final Format STEREO_44_1_FORMAT =
      new Format.Builder()
          .setChannelCount(CHANNEL_COUNT_STEREO)
          .setSampleRate(SAMPLE_RATE_44_1)
          .build();

  private DefaultAudioSink defaultAudioSink;
  private ArrayAudioBufferSink arrayAudioBufferSink;

  @Nullable private AudioDeviceInfo hdmiDevice;
  @Nullable private AudioDeviceInfo bluetoothDevice;

  @Before
  public void setUp() {
    // For capturing output.
    arrayAudioBufferSink = new ArrayAudioBufferSink();
    TeeAudioProcessor teeAudioProcessor = new TeeAudioProcessor(arrayAudioBufferSink);
    defaultAudioSink =
        new DefaultAudioSink.Builder()
            .setAudioProcessorChain(new DefaultAudioProcessorChain(teeAudioProcessor))
            .build();
  }

  @After
  public void tearDown() {
    removeBluetoothDevice();
    removeHdmiDevice();
  }

  @Test
  public void handlesSpecializedAudioProcessorArray() {
    defaultAudioSink =
        new DefaultAudioSink.Builder().setAudioProcessors(new TeeAudioProcessor[0]).build();
  }

  @Test
  public void handlesBuffer_updatesPositionUsingAudioProcessorChain() throws Exception {
    defaultAudioSink =
        new DefaultAudioSink.Builder()
            .setAudioProcessorChain(
                new AudioProcessorChain() {
                  @Override
                  public AudioProcessor[] getAudioProcessors() {
                    return new AudioProcessor[0];
                  }

                  @Override
                  public PlaybackParameters applyPlaybackParameters(
                      PlaybackParameters playbackParameters) {
                    return playbackParameters;
                  }

                  @Override
                  public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
                    return false;
                  }

                  @Override
                  public long getMediaDuration(long playoutDuration) {
                    return playoutDuration * 2;
                  }

                  @Override
                  public long getSkippedOutputFrameCount() {
                    return 441; // 0.01 seconds at 44.1 kHz
                  }
                })
            .build();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
    defaultAudioSink.play();
    ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS);

    long currentPositionUs = defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false);

    // Based on audio processor chain: 1 second * 2 + 0.01 seconds
    assertThat(currentPositionUs).isEqualTo(2_010_000);
  }

  @Test
  public void handlesBufferAfterReset() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
  }

  @Test
  public void handlesBufferAfterReset_withPlaybackSpeed() throws Exception {
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f));
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getPlaybackParameters())
        .isEqualTo(new PlaybackParameters(/* speed= */ 1.5f));
  }

  @Test
  public void handlesBufferAfterReset_withFormatChange() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_MONO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
  }

  @Test
  public void handlesBufferAfterReset_withFormatChangeAndPlaybackSpeed() throws Exception {
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f));
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_MONO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getPlaybackParameters())
        .isEqualTo(new PlaybackParameters(/* speed= */ 1.5f));
  }

  @Test
  public void trimsStartFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ TRIM_100_MS_FRAME_COUNT,
        /* trimEndFrames= */ 0);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_100_MS_FRAME_COUNT)));
  }

  @Test
  public void trimsEndFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ 0,
        /* trimEndFrames= */ TRIM_10_MS_FRAME_COUNT);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_10_MS_FRAME_COUNT)));
  }

  @Test
  public void trimsStartAndEndFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ TRIM_100_MS_FRAME_COUNT,
        /* trimEndFrames= */ TRIM_10_MS_FRAME_COUNT);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_100_MS_FRAME_COUNT - TRIM_10_MS_FRAME_COUNT)));
  }

  @Test
  public void getCurrentPosition_returnsPositionFromFirstBuffer() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 5 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    assertThat(defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(5 * C.MICROS_PER_SECOND);

    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 8 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(8 * C.MICROS_PER_SECOND);
  }

  @Test
  public void getFormatSupport_pcm16BitFloatOutputDisabled_supportedDirectly() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getFormatSupport_pcm16BitFloatOutputEnabled_supportedDirectly() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
            .setEnableFloatOutput(true)
            .build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void getFormatSupport_pcm24BitFloatOutputDisabled_supportedWithTranscoding() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_WITH_TRANSCODING);
  }

  @Test
  public void getFormatSupport_pcm24BitFloatOutputEnabled_supportedDirectly() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
            .setEnableFloatOutput(true)
            .build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_WITH_TRANSCODING);
  }

  @Test
  public void getFormatSupport_floatPcmFloatOutputDisabled_supportedWithTranscoding() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_WITH_TRANSCODING);
  }

  @Test
  public void getFormatSupport_floatPcmFloatOutputEnabled_supportedDirectly() {
    defaultAudioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
            .setEnableFloatOutput(true)
            .build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();

    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void audioSinkWithAacAudioCapabilitiesWithoutOffload_doesNotSupportAac() {
    DefaultAudioSink defaultAudioSink =
        new DefaultAudioSink.Builder()
            .setAudioCapabilities(new AudioCapabilities(new int[] {C.ENCODING_AAC_LC}, 2))
            .build();
    Format aacLcFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs("mp4a.40.2")
            .build();

    assertThat(MimeTypes.getEncoding(checkNotNull(aacLcFormat.sampleMimeType), aacLcFormat.codecs))
        .isEqualTo(C.ENCODING_AAC_LC);
    assertThat(defaultAudioSink.supportsFormat(aacLcFormat)).isFalse();
  }

  @Test
  @Config(minSdk = Config.OLDEST_SDK)
  public void audioSinkWithNonNullContext_audioCapabilitiesObtainedFromContext() {
    // Set UI mode to TV.
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    addHdmiDevice();

    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();

    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated builder methods.
  public void audioSinkWithNullContext_audioCapabilitiesObtainedFromBuilder() {
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder()
            .setAudioCapabilities(
                new AudioCapabilities(
                    new int[] {MimeTypes.getEncoding(MimeTypes.AUDIO_DTS_HD, /* codec= */ null)},
                    DEFAULT_MAX_CHANNEL_COUNT))
            .build();

    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated builder methods.
  public void audioSinkWithNullContext_audioCapabilitiesObtainedFromBuilder_defaultCapabilities() {
    DefaultAudioSink audioSink = new DefaultAudioSink.Builder().build();

    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();
  }

  @Test
  @Config(minSdk = Config.OLDEST_SDK)
  public void bluetoothDeviceAddedAndRemoved_audioCapabilitiesUpdated() {
    // Set UI mode to TV.
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    // Initially setup the audio sink with HDMI device connected.
    addHdmiDevice();
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();

    // Add a bluetooth device.
    addBluetoothDevice();
    // When the bluetooth device is connected, the audio sink should change to the default PCM
    // capabilities, thus the surrounded format shouldn't be reported to support.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();

    // Remove the bluetooth device.
    removeBluetoothDevice();
    // When the bluetooth device is disconnected, the audio sink should change to the capabilities
    // reported by the HDMI device, as that device is still connected.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();
  }

  @Test
  @Config(minSdk = Config.OLDEST_SDK)
  public void hdmiDeviceAddedAndRemoved_audioCapabilitiesUpdated() {
    // Set UI mode to TV.
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    // Initially setup the audio sink.
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();

    // Add an HDMI device.
    addHdmiDevice();
    // When the HDMI device is connected, the audio sink should change to the capabilities reported
    // by the HDMI device.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();

    // Remove the HDMI device.
    removeHdmiDevice();
    // When the HDMI device is disconnected, the audio sink should change to the default PCM
    // capabilities. We are verifying the surround format reported by the HDMI device before is no
    // longer supported.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();
  }

  // TODO: b/320191198 - Disable the test for API 33, as the
  // ShadowAudioManager.getDirectProfilesForAttributes(AudioAttributes) hasn't really considered
  // the AudioAttributes yet.
  @Test
  @Config(minSdk = 29, maxSdk = 32)
  public void setAudioAttributes_audioCapabilitiesUpdated() {
    Context context = ApplicationProvider.getApplicationContext();
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    ShadowAudioTrack.addDirectPlaybackSupport(
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_E_AC3_JOC)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build(),
        new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build());
    DefaultAudioSink audioSink = new DefaultAudioSink.Builder(context).build();
    Format expectedFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build();
    AudioAttributes expectedAttributes =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();
    AudioAttributes otherAttributes =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build();

    // Format supported with right attributes.
    audioSink.setAudioAttributes(expectedAttributes);
    assertThat(audioSink.supportsFormat(expectedFormat)).isTrue();
    // Format unsupported with other attributes.
    audioSink.setAudioAttributes(otherAttributes);
    assertThat(audioSink.supportsFormat(expectedFormat)).isFalse();
  }

  // Adding the permission to the test AndroidManifest.xml doesn't work to appease lint.
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  @Test
  @Config(minSdk = Config.OLDEST_SDK, maxSdk = 32)
  public void setPreferredDevice_audioCapabilitiesUpdated() {
    // Initially setup the audio sink with Bluetooth and HDMI device connected.
    AudioDeviceInfo hdmiDevice =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build();
    AudioDeviceInfo bluetoothDevice =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build();
    getShadowAudioManager().setOutputDevices(ImmutableList.of(hdmiDevice, bluetoothDevice));
    Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
    intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1);
    intent.putExtra(
        AudioManager.EXTRA_ENCODINGS,
        new int[] {MimeTypes.getEncoding(MimeTypes.AUDIO_DTS_HD, /* codec= */ null)});
    intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, DEFAULT_MAX_CHANNEL_COUNT);
    ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    // Verify that surround sound is not supported assuming that Bluetooth is used.
    Format surroundFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
            .build();
    assertThat(audioSink.supportsFormat(surroundFormat)).isFalse();

    // Set the preferred device to HDMI and assert that the surround sound is now supported.
    audioSink.setPreferredDevice(hdmiDevice);

    assertThat(audioSink.supportsFormat(surroundFormat)).isTrue();
  }

  // Adding the permission to the test AndroidManifest.xml doesn't work to appease lint.
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  @Test
  @Config(minSdk = 24, maxSdk = 32) // OnRoutingChangedListener is supported from API 24.
  public void onRoutingChanged_onActiveAudioTrack_audioCapabilitiesUpdated() throws Exception {
    // Initially setup the audio sink with Bluetooth and HDMI device connected.
    AudioDeviceInfo hdmiDevice =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build();
    AudioDeviceInfo bluetoothDevice =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build();
    getShadowAudioManager().setOutputDevices(ImmutableList.of(hdmiDevice, bluetoothDevice));
    Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
    intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1);
    intent.putExtra(
        AudioManager.EXTRA_ENCODINGS,
        new int[] {MimeTypes.getEncoding(MimeTypes.AUDIO_DTS_HD, /* codec= */ null)});
    intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, DEFAULT_MAX_CHANNEL_COUNT);
    ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    // Routing changes are only expected to work on active audio tracks.
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build();
    audioSink.configure(format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
    ByteBuffer silenceBuffer =
        ByteBuffer.allocateDirect(/* sample rate * bit depth * channels */ 44100 * 2 * 2)
            .order(ByteOrder.nativeOrder());
    audioSink.handleBuffer(
        silenceBuffer, /* presentationTimeUs= */ 0, /* encodedAccessUnitCount= */ 1);
    // Verify that surround sound is not supported assuming that Bluetooth is used.
    Format surroundFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
            .build();
    assertThat(audioSink.supportsFormat(surroundFormat)).isFalse();

    // Changed the routing to HDMI and assert that the surround sound is now supported.
    ShadowAudioTrack.setRoutedDevice(hdmiDevice);

    runMainLooperUntil(() -> audioSink.supportsFormat(surroundFormat));
  }

  @Test
  @Config(minSdk = Config.OLDEST_SDK)
  public void afterRelease_bluetoothDeviceAdded_audioCapabilitiesShouldNotBeUpdated() {
    // Set UI mode to TV.
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    // Initially setup the audio sink with HDMI device connected.
    addHdmiDevice();
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();

    audioSink.release();
    // Add a bluetooth device after release.
    addBluetoothDevice();

    // The audio sink should not change its capabilities.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isTrue();
  }

  @Test
  @Config(minSdk = Config.OLDEST_SDK)
  public void afterRelease_hdmiDeviceAdded_audioCapabilitiesShouldNotBeUpdated() {
    // Set UI mode to TV.
    getShadowUiModeManager().setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    // Initially setup the audio sink.
    DefaultAudioSink audioSink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();

    audioSink.release();
    // Add an HDMI device after release.
    addHdmiDevice();

    // The audio sink should not change its capabilities.
    assertThat(
            audioSink.supportsFormat(
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                    .setChannelCount(DEFAULT_MAX_CHANNEL_COUNT)
                    .build()))
        .isFalse();
  }

  @Test
  public void configure_throwsConfigurationException_withInvalidInput() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    AudioSink.ConfigurationException thrown =
        Assert.assertThrows(
            AudioSink.ConfigurationException.class,
            () ->
                defaultAudioSink.configure(
                    format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null));
    assertThat(thrown.format).isEqualTo(format);
  }

  @Test
  public void setPlaybackParameters_doesNothingWhenTunnelingIsEnabled() throws Exception {
    defaultAudioSink.setAudioSessionId(1);
    defaultAudioSink.enableTunnelingV21();
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(2));
    configureDefaultAudioSink(/* channelCount= */ 2);
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 5 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(defaultAudioSink.getPlaybackParameters().speed).isEqualTo(1);
  }

  @Test
  public void build_calledTwice_throwsIllegalStateException() throws Exception {
    DefaultAudioSink.Builder defaultAudioSinkBuilder =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext());
    defaultAudioSinkBuilder.build();

    assertThrows(IllegalStateException.class, defaultAudioSinkBuilder::build);
  }

  @Test
  public void handleBuffer_audioOutputInitializationError_iterativelyReduceBufferSizeAndRetry()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ArrayList<Integer> outputBufferSizes = new ArrayList<>();
    AudioOutputProvider audioOutputProvider =
        new ForwardingAudioOutputProvider(
            new AudioTrackAudioOutputProvider.Builder(context).build()) {
          @Override
          public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
            outputBufferSizes.add(config.bufferSize);
            if (outputBufferSizes.size() >= 3) {
              return super.getAudioOutput(config);
            }
            throw new InitializationException();
          }
        };
    defaultAudioSink =
        new DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(audioOutputProvider)
            .setEnableAudioOutputPlaybackParameters(true)
            .build();
    // Specifies a large buffer size.
    configureDefaultAudioSink(/* channelCount= */ 8, /* specifiedBufferSize= */ 2_822_400);

    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    assertThat(outputBufferSizes).containsExactly(2_822_400, 1_411_200, 705_600).inOrder();
  }

  @Test
  public void
      handleBuffer_audioOutputInitializationError_iterativelyRetryUntilBufferSizeBelowThreshold()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ArrayList<Integer> outputBufferSizes = new ArrayList<>();
    AudioOutputProvider audioOutputProvider =
        new ForwardingAudioOutputProvider(
            new AudioTrackAudioOutputProvider.Builder(context).build()) {
          @Override
          public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
            outputBufferSizes.add(config.bufferSize);
            throw new InitializationException();
          }
        };
    defaultAudioSink =
        new DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(audioOutputProvider)
            .setEnableAudioOutputPlaybackParameters(true)
            .build();
    // Specifies a large buffer size.
    configureDefaultAudioSink(/* channelCount= */ 8, /* specifiedBufferSize= */ 2_822_400);

    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isFalse();
    assertThat(outputBufferSizes).containsExactly(2_822_400, 1_411_200, 705_600).inOrder();
  }

  @Test
  public void handleBuffer_recoverableWriteError_throwsWriteException() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    AtomicBoolean writeShouldFail = new AtomicBoolean();
    AudioOutputProvider audioOutputProvider =
        new ForwardingAudioOutputProvider(
            new AudioTrackAudioOutputProvider.Builder(context).build()) {
          @Override
          public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
            return new ForwardingAudioOutput(super.getAudioOutput(config)) {
              @Override
              public boolean write(
                  ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
                  throws WriteException {
                if (writeShouldFail.get()) {
                  throw new WriteException(/* errorCode= */ 1234, /* isRecoverable= */ true);
                }
                return super.write(buffer, encodedAccessUnitCount, presentationTimeUs);
              }
            };
          }
        };
    defaultAudioSink =
        new DefaultAudioSink.Builder(context).setAudioOutputProvider(audioOutputProvider).build();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    // First write succeeds
    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // Second write fails with recoverable error
    writeShouldFail.set(true);
    AudioSink.WriteException e =
        assertThrows(
            AudioSink.WriteException.class,
            () ->
                defaultAudioSink.handleBuffer(
                    create1Sec44100HzSilenceBuffer(),
                    /* presentationTimeUs= */ 1_000_000,
                    /* encodedAccessUnitCount= */ 1));
    assertThat(e.isRecoverable).isTrue();
  }

  @Config(sdk = ALL_SDKS)
  @Test
  public void getAudioTrackBufferDurationUs_withPcm_calculatesCorrectValue() throws Exception {
    configureDefaultAudioSink(/* channelCount= */ 2);

    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    assertThat(defaultAudioSink.getAudioTrackBufferSizeUs()).isEqualTo(250_000L);
  }

  @Config(minSdk = 30)
  @Test
  public void getAudioTrackBufferDurationUs_withNonPcm_returnsTimeUnset() throws Exception {
    AudioFormat audioFormat =
        new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_44_1)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_AAC_LC)
            .build();
    AudioAttributes audioAttributes =
        new AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
            .setUsage(C.USAGE_MEDIA)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build();
    ShadowAudioSystem.setOffloadSupported(
        audioFormat, audioAttributes.getPlatformAudioAttributes(), true);
    ShadowAudioSystem.setOffloadPlaybackSupport(
        audioFormat,
        audioAttributes.getPlatformAudioAttributes(),
        AudioManager.PLAYBACK_OFFLOAD_SUPPORTED);
    ShadowAudioSystem.setDirectPlaybackSupport(
        audioFormat,
        audioAttributes.getPlatformAudioAttributes(),
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED);

    configureDefaultAudioSinkWithOffload();

    assertThat(
            defaultAudioSink.handleBuffer(
                create1Sec44100HzSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    assertThat(defaultAudioSink.getAudioTrackBufferSizeUs()).isEqualTo(400_000_000L);
  }

  private void configureDefaultAudioSink(int channelCount) throws AudioSink.ConfigurationException {
    configureDefaultAudioSink(channelCount, /* trimStartFrames= */ 0, /* trimEndFrames= */ 0);
  }

  private void configureDefaultAudioSink(int channelCount, int specifiedBufferSize)
      throws AudioSink.ConfigurationException {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(channelCount)
            .setSampleRate(SAMPLE_RATE_44_1)
            .setEncoderDelay(0)
            .setEncoderPadding(0)
            .build();
    defaultAudioSink.configure(
        format, /* specifiedBufferSize= */ specifiedBufferSize, /* outputChannels= */ null);
  }

  private void configureDefaultAudioSink(int channelCount, int trimStartFrames, int trimEndFrames)
      throws AudioSink.ConfigurationException {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(channelCount)
            .setSampleRate(SAMPLE_RATE_44_1)
            .setEncoderDelay(trimStartFrames)
            .setEncoderPadding(trimEndFrames)
            .build();
    defaultAudioSink.configure(format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
  }

  private void configureDefaultAudioSinkWithOffload() throws AudioSink.ConfigurationException {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(/* channelCount= */ 2)
            .setSampleRate(SAMPLE_RATE_44_1)
            .setCodecs("mp4a.40.02")
            .build();

    defaultAudioSink.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED);
    defaultAudioSink.configure(format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
  }

  // Adding the permission to the test AndroidManifest.xml doesn't work to appease lint.
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  private void addHdmiDevice() {
    ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_DTS_HD);
    ShadowAudioTrack.addDirectPlaybackSupport(
        new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_DTS_HD)
            .setSampleRate(AudioCapabilities.DEFAULT_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build(),
        new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)
            .build());
    AudioDeviceInfoBuilder hdmiDeviceBuilder =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI);
    if (SDK_INT >= 33) {
      ImmutableList<AudioProfile> expectedProfiles =
          ImmutableList.of(
              AudioProfileBuilder.newBuilder()
                  .setFormat(AudioFormat.ENCODING_DTS_HD)
                  .setSamplingRates(new int[] {48_000})
                  .setChannelMasks(
                      new int[] {Util.getAudioTrackChannelConfig(DEFAULT_MAX_CHANNEL_COUNT)})
                  .setChannelIndexMasks(new int[] {})
                  .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
                  .build(),
              AudioProfileBuilder.newBuilder()
                  .setFormat(AudioFormat.ENCODING_PCM_16BIT)
                  .setSamplingRates(new int[] {48_000})
                  .setChannelMasks(new int[] {AudioFormat.CHANNEL_OUT_STEREO})
                  .setChannelIndexMasks(new int[] {})
                  .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
                  .build());
      hdmiDeviceBuilder.setProfiles(expectedProfiles);
    }
    hdmiDevice = hdmiDeviceBuilder.build();
    getShadowAudioManager()
        .addOutputDevice(checkNotNull(hdmiDevice), /* notifyAudioDeviceCallbacks= */ true);
    getShadowAudioManager().addOutputDeviceWithDirectProfiles(checkNotNull(hdmiDevice));
    Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
    intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1);
    intent.putExtra(
        AudioManager.EXTRA_ENCODINGS,
        new int[] {MimeTypes.getEncoding(MimeTypes.AUDIO_DTS_HD, /* codec= */ null)});
    intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, DEFAULT_MAX_CHANNEL_COUNT);
    ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);

    shadowOf(Looper.getMainLooper()).idle();
  }

  // Adding the permission to the test AndroidManifest.xml doesn't work to appease lint.
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  private void removeHdmiDevice() {
    if (hdmiDevice != null) {
      ShadowAudioTrack.clearAllowedNonPcmEncodings();
      ShadowAudioTrack.clearDirectPlaybackSupportedFormats();
      getShadowAudioManager().removeOutputDeviceWithDirectProfiles(hdmiDevice);
      getShadowAudioManager()
          .removeOutputDevice(checkNotNull(hdmiDevice), /* notifyAudioDeviceCallbacks= */ true);

      getShadowAudioManager().removeOutputDeviceWithDirectProfiles(hdmiDevice);
      hdmiDevice = null;
    }
    Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
    intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0);
    ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);

    shadowOf(Looper.getMainLooper()).idle();
  }

  private void addBluetoothDevice() {
    // For API 33+, AudioManager.getDirectProfilesForAttributes returns the AudioProfile for the
    // routed device. To simulate the Bluetooth is connected and routed, we need to remove the
    // profile of the HDMI device, which means that the HDMI device is no longer routed, but
    // still be connected.
    removeHdmiDevice();
    AudioDeviceInfoBuilder bluetoothDeviceBuilder =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
    if (SDK_INT >= 33) {
      bluetoothDeviceBuilder.setProfiles(ImmutableList.of(createPcmProfile()));
    }
    bluetoothDevice = bluetoothDeviceBuilder.build();
    getShadowAudioManager()
        .addOutputDevice(checkNotNull(bluetoothDevice), /* notifyAudioDeviceCallbacks= */ true);
    getShadowAudioManager().addOutputDeviceWithDirectProfiles(checkNotNull(bluetoothDevice));

    shadowOf(Looper.getMainLooper()).idle();
  }

  private void removeBluetoothDevice() {
    if (bluetoothDevice != null) {
      // Add back the HDMI device back as the routed device to simulate that the bluetooth device
      // has gone and is no longer routed.
      addHdmiDevice();
      getShadowAudioManager().removeOutputDeviceWithDirectProfiles(checkNotNull(bluetoothDevice));
      getShadowAudioManager()
          .removeOutputDevice(
              checkNotNull(bluetoothDevice), /* notifyAudioDeviceCallbacks= */ true);
      bluetoothDevice = null;
    }

    shadowOf(Looper.getMainLooper()).idle();
  }

  private static ShadowUIModeManager getShadowUiModeManager() {
    return shadowOf(
        (UiModeManager)
            ApplicationProvider.getApplicationContext().getSystemService(Context.UI_MODE_SERVICE));
  }

  private static ShadowAudioManager getShadowAudioManager() {
    return shadowOf(
        (AudioManager)
            ApplicationProvider.getApplicationContext().getSystemService(Context.AUDIO_SERVICE));
  }

  @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
  @RequiresApi(33)
  private static AudioProfile createPcmProfile() {
    return AudioProfileBuilder.newBuilder()
        .setFormat(AudioFormat.ENCODING_PCM_16BIT)
        .setSamplingRates(new int[] {48_000})
        .setChannelMasks(new int[] {AudioFormat.CHANNEL_OUT_STEREO})
        .setChannelIndexMasks(new int[] {})
        .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
        .build();
  }

  /** Creates a one second silence buffer for 44.1 kHz stereo 16-bit audio. */
  private static ByteBuffer create1Sec44100HzSilenceBuffer() {
    return ByteBuffer.allocateDirect(
            SAMPLE_RATE_44_1 * CHANNEL_COUNT_STEREO * BYTES_PER_FRAME_16_BIT)
        .order(ByteOrder.nativeOrder());
  }

  private interface ThrowingBooleanMethod {
    boolean run() throws Exception;
  }

  private static void retryUntilTrue(ThrowingBooleanMethod booleanMethod) throws Exception {
    long timeoutTimeMs = System.currentTimeMillis() + TIMEOUT_MS;
    while (!booleanMethod.run()) {
      if (System.currentTimeMillis() >= timeoutTimeMs) {
        throw new TimeoutException();
      }
    }
  }

  private static final class ArrayAudioBufferSink implements TeeAudioProcessor.AudioBufferSink {

    private byte[] output;

    public ArrayAudioBufferSink() {
      output = new byte[0];
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, int encoding) {
      output = new byte[0];
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      int position = buffer.position();
      int remaining = buffer.remaining();
      output = Arrays.copyOf(output, output.length + remaining);
      buffer.get(output, 0, remaining);
      buffer.position(position);
    }
  }
}
