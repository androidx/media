/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.mpegh;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.AudioProfileBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowAudioSystem;
import org.robolectric.shadows.ShadowAudioTrack;

/** Unit tests for {@link MpeghAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public final class MpeghAudioRendererTest {

  private static final int DEFAULT_MAX_CHANNEL_COUNT = 8;

  private static final Format MHM1_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_MPEGH_MHM1)
          .setSampleRate(48000)
          .setChannelCount(2)
          .build();

  @Test
  public void constructor_setsOffloadModeEnabledOnAudioSink() {
    AudioSink mockSink = mock(AudioSink.class);

    new MpeghAudioRenderer(/* eventHandler= */ null, /* eventListener= */ null, mockSink);

    verify(mockSink).setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED);
  }

  @Test
  @Config(minSdk = 31, shadows = {ShadowMpeghLibrary.class})
  public void createDecoder_withMpeghBl3DirectSupport_selectsMpeghPassThroughDecoder()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    setupMpeghDirectPlaybackShadow(context);

    DefaultAudioSink audioSink = new DefaultAudioSink.Builder(context).build();
    MpeghAudioRenderer renderer = new MpeghAudioRenderer(null, null, audioSink);

    renderer.supportsFormat(MHM1_FORMAT);
    MpeghBaseDecoder decoder = renderer.createDecoder(MHM1_FORMAT, null);

    assertThat(decoder.getName()).isEqualTo("MpeghPassThroughDecoder");
    assertThat(decoder).isInstanceOf(MpeghPassThroughDecoder.class);
  }

  @Test
  @Config(minSdk = 31, shadows = {ShadowMpeghDecoderJni.class, ShadowMpeghLibrary.class})
  public void createDecoder_withoutMpeghDirectSupport_selectsMpeghDecoder() throws Exception {
    // No shadow setup — direct playback is not supported by default.
    Context context = ApplicationProvider.getApplicationContext();

    DefaultAudioSink audioSink = new DefaultAudioSink.Builder(context).build();
    MpeghAudioRenderer renderer = new MpeghAudioRenderer(null, null, audioSink);

    renderer.supportsFormat(MHM1_FORMAT);
    MpeghBaseDecoder decoder = renderer.createDecoder(MHM1_FORMAT, null);

    assertThat(decoder.getName()).isEqualTo("libmpegh");
    assertThat(decoder).isInstanceOf(MpeghDecoder.class);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Configures the Robolectric audio system so that {@link DefaultAudioSink#getFormatSupport}
   * returns {@link AudioSink#SINK_FORMAT_SUPPORTED_DIRECTLY} for MPEGH BL L3.
   *
   * <p>On API 33+, {@link androidx.media3.exoplayer.audio.AudioCapabilities} queries
   * {@code AudioManager.getDirectProfilesForAttributes()} only for TV/automotive devices. We
   * therefore set TV mode via {@link UiModeManager} and register a virtual output device whose
   * profiles include {@code ENCODING_MPEGH_BL_L3} via {@link ShadowAudioManager}.
   */
  @RequiresApi(31)
  @SuppressWarnings({"StickyBroadcast", "MissingPermission"})
  private static void setupMpeghDirectPlaybackShadow(Context context) {
    UiModeManager uiModeManager =
        (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
    shadowOf(uiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);

    AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    ShadowAudioManager shadowAudioManager = shadowOf(audioManager);

    AudioProfile mpeghProfile =
        AudioProfileBuilder.newBuilder()
            .setFormat(AudioFormat.ENCODING_MPEGH_BL_L3)
            .setSamplingRates(new int[]{48_000})
            .setChannelMasks(new int[]{AudioFormat.CHANNEL_OUT_STEREO})
            .setChannelIndexMasks(new int[]{})
            .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
            .build();
    AudioProfile pcmProfile =
        AudioProfileBuilder.newBuilder()
            .setFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setSamplingRates(new int[]{48_000})
            .setChannelMasks(new int[]{AudioFormat.CHANNEL_OUT_STEREO})
            .setChannelIndexMasks(new int[]{})
            .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
            .build();
    AudioDeviceInfo device =
        AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_HDMI)
            .setProfiles(ImmutableList.of(mpeghProfile, pcmProfile))
            .build();

    shadowAudioManager.addOutputDevice(device, /* notifyAudioDeviceCallbacks= */ false);
    shadowAudioManager.addOutputDeviceWithDirectProfiles(device);

    if (SDK_INT < 33) {
      AudioFormat audioFormat =
          new AudioFormat.Builder()
              .setSampleRate(48000)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .setEncoding(AudioFormat.ENCODING_MPEGH_BL_L3)
              .build();
      AudioAttributes audioAttributes =
          new AudioAttributes.Builder()
              .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
              .setUsage(C.USAGE_MEDIA)
              .build();

      ShadowAudioSystem.setOffloadSupported(audioFormat, audioAttributes.getPlatformAudioAttributes(),
          true);
      ShadowAudioSystem.setOffloadPlaybackSupport(
          audioFormat,
          audioAttributes.getPlatformAudioAttributes(),
          AudioManager.PLAYBACK_OFFLOAD_SUPPORTED);

      ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_MPEGH_BL_L3);
      ShadowAudioTrack.addDirectPlaybackSupport(
          audioFormat,
          audioAttributes.getPlatformAudioAttributes());
      Intent intent = new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG);
      intent.putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1);
      intent.putExtra(
          AudioManager.EXTRA_ENCODINGS,
          new int[] {MimeTypes.getEncoding(MimeTypes.AUDIO_MPEGH_MHM1, /* codec= */ null)});
      intent.putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, DEFAULT_MAX_CHANNEL_COUNT);
      ApplicationProvider.getApplicationContext().sendStickyBroadcast(intent);
    }
  }

  /**
   * Robolectric shadow that stubs the necessary JNI methods of {@link MpeghDecoderJni}, allowing {@link
   * MpeghDecoder} to be instantiated in unit tests without the native library present.
   */
  @Implements(value = MpeghDecoderJni.class, callThroughByDefault = false)
  public static final class ShadowMpeghDecoderJni {
    public ShadowMpeghDecoderJni() {}

    @Implementation
    protected void init(int cicpIndex, byte[] mhaConfig, int mhaConfigLength) {}
  }

  /**
   * Robolectric shadow that makes {@link MpeghLibrary#isAvailable()} return {@code true} without
   * loading the native library.
   */
  @Implements(value = MpeghLibrary.class)
  public static final class ShadowMpeghLibrary {
    @Implementation
    public static boolean isAvailable() {
      return true;
    }
  }
}
