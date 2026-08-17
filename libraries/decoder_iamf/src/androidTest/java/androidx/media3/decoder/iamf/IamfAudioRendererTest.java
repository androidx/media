/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.media.AudioFormat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.IamfUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests different configurations of {@link IamfAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public final class IamfAudioRendererTest {

  // Sample configOBUs data from sample_iamf.mp4 file.
  private static final byte[] iacbObus = {
    -8, 6, 105, 97, 109, 102, 0, 0, 0, 15, -56, 1, 105, 112, 99, 109, 64, 0, 0, 1, 16, 0, 0, 62,
    -128, 8, 12, -84, 2, 0, -56, 1, 1, 0, 0, 32, 16, 1, 1, 16, 78, 42, 1, 101, 110, 45, 117, 115, 0,
    116, 101, 115, 116, 95, 109, 105, 120, 95, 112, 114, 101, 115, 0, 1, 1, -84, 2, 116, 101, 115,
    116, 95, 115, 117, 98, 95, 109, 105, 120, 95, 48, 95, 97, 117, 100, 105, 111, 95, 101, 108, 101,
    109, 101, 110, 116, 95, 48, 0, 0, 0, 100, -128, 125, -128, 0, 0, 100, -128, 125, -128, 0, 0, 1,
    -128, 0, -54, 81, -51, -79
  };

  @Test
  @SuppressWarnings("ForOverride")
  public void createDecoder_basicCase() throws Exception {
    AudioCapabilitiesAudioSink audioSink =
        new AudioCapabilitiesAudioSink(
            ApplicationProvider.getApplicationContext(),
            AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES); // Stereo speakers.
    IamfAudioRenderer renderer = new IamfAudioRenderer.Builder(audioSink).build();

    IamfDecoder decoder =
        renderer.createDecoder(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_IAMF)
                .setInitializationData(ImmutableList.of(iacbObus))
                .build(),
            /* cryptoConfig= */ null);

    assertThat(decoder.getSelectedOutputLayout())
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0);
    Format outputFormat = renderer.getOutputFormat(decoder);
    assertThat(outputFormat.channelCount).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("ForOverride")
  public void createDecoder_acceptsRequestedOutputLayout() throws Exception {
    AudioCapabilitiesAudioSink audioSink =
        new AudioCapabilitiesAudioSink(
            ApplicationProvider.getApplicationContext(),
            AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES);
    IamfAudioRenderer renderer =
        new IamfAudioRenderer.Builder(audioSink)
            .setRequestedOutputLayout(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0)
            .build();

    IamfDecoder decoder =
        renderer.createDecoder(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_IAMF)
                .setInitializationData(ImmutableList.of(iacbObus))
                .build(),
            /* cryptoConfig= */ null);

    assertThat(decoder.getSelectedOutputLayout())
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0);
  }

  // IamfAudioRenderer uses built-in binaural when the Spatializer channel masks are non-empty and
  // enable_integrated_binaural is true (the default value).
  @Test
  @SuppressWarnings("ForOverride")
  public void createDecoder_usesBuiltInBinauralWhenAppropriate() throws Exception {
    AudioCapabilities audioCapabilities =
        getAudioCapabilitiesWithSpatializerMasks(ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1));
    AudioCapabilitiesAudioSink audioSink =
        new AudioCapabilitiesAudioSink(
            ApplicationProvider.getApplicationContext(), audioCapabilities);
    IamfAudioRenderer renderer = new IamfAudioRenderer.Builder(audioSink).build();

    IamfDecoder decoder =
        renderer.createDecoder(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_IAMF)
                .setInitializationData(ImmutableList.of(iacbObus))
                .build(),
            /* cryptoConfig= */ null);

    assertThat(decoder.getSelectedOutputLayout()).isEqualTo(IamfUtil.OUTPUT_LAYOUT_BINAURAL);
  }

  @Test
  @SuppressWarnings("ForOverride")
  public void createDecoder_usesSpatializerWhenIntegratedBinauralDisabled() throws Exception {
    AudioCapabilities audioCapabilities =
        getAudioCapabilitiesWithSpatializerMasks(ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1));
    AudioCapabilitiesAudioSink audioSink =
        new AudioCapabilitiesAudioSink(
            ApplicationProvider.getApplicationContext(), audioCapabilities);
    IamfAudioRenderer renderer =
        new IamfAudioRenderer.Builder(audioSink).setEnableIntegratedBinaural(false).build();

    IamfDecoder decoder =
        renderer.createDecoder(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_IAMF)
                .setInitializationData(ImmutableList.of(iacbObus))
                .build(),
            /* cryptoConfig= */ null);

    assertThat(decoder.getSelectedOutputLayout())
        .isEqualTo(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0);
  }

  /** Helper just to get AudioCapabilities with non-empty spatializer channel masks. */
  private static AudioCapabilities getAudioCapabilitiesWithSpatializerMasks(
      ImmutableList<Integer> spatializerChannelMasks) {
    return AudioCapabilities.getCapabilities(
        ApplicationProvider.getApplicationContext(),
        AudioAttributes.DEFAULT,
        /* routedDevice= */ null,
        spatializerChannelMasks);
  }

  /** Wraps a {@link DefaultAudioSink} that allows us to set the {@link AudioCapabilities}. */
  private static class AudioCapabilitiesAudioSink extends ForwardingAudioSink {

    private final AudioCapabilities audioCapabilities;

    private AudioCapabilitiesAudioSink(Context context, AudioCapabilities audioCapabilities) {
      super(new DefaultAudioSink.Builder(context).build());
      this.audioCapabilities = audioCapabilities;
    }

    @Override
    public AudioCapabilities getAudioCapabilities() {
      return audioCapabilities;
    }
  }
}
