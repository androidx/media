/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioProfile;
import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.AudioProfileBuilder;

@RunWith(AndroidJUnit4.class)
public final class SpeakerLayoutUtilTest {

  @Test
  public void getLoudspeakerLayoutChannelMask_bluetooth_returnsStereo() {
    AudioDeviceInfo device = createSimpleAudioDeviceInfo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
    ImmutableList<Integer> expectedChannelMasks = ImmutableList.of(AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  public void getLoudspeakerLayoutChannelMask_earpiece_returnsMono() {
    AudioDeviceInfo device = createSimpleAudioDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
    ImmutableList<Integer> expectedChannelMasks = ImmutableList.of(AudioFormat.CHANNEL_OUT_MONO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  @Config(minSdk = 31)
  // The AudioDeviceInfo shadow does not have a setter for the new API,
  // getSpeakerLayoutChannelMasks(), but we can test the default behaviour.
  public void getLoudspeakerLayoutChannelMask_builtInSpeaker_fallbackToStereo() {
    AudioDeviceInfo device = createSimpleAudioDeviceInfo(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
    ImmutableList<Integer> expectedChannelMasks = ImmutableList.of(AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  @Config(minSdk = 31)
  public void getLoudspeakerLayoutChannelMask_hdmiArc_returnsChannelMasksFromAudioProfiles() {
    AudioProfile stereoProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_STEREO);
    AudioProfile surroundProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_5POINT1);
    AudioProfile ac3Profile = createNonPcmAudioProfile(AudioFormat.ENCODING_AC3);
    AudioDeviceInfo device =
        createAudioDeviceInfoWithProfiles(
            AudioDeviceInfo.TYPE_HDMI_ARC, stereoProfile, surroundProfile, ac3Profile);
    ImmutableList<Integer> expectedChannelMasks =
        ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  // TODO(b/415108693): Create tests for SADs and SADB fallback logic for ARC/eARC when
  // AudioDeviceInfoBuilder has support for setting AudioDescriptors.

  @Test
  @Config(minSdk = 31)
  public void getLoudspeakerLayoutChannelMask_hdmiArc_fallsBackToStereo() {
    AudioProfile dtsProfile = createNonPcmAudioProfile(AudioFormat.ENCODING_DTS);
    AudioDeviceInfo device =
        createAudioDeviceInfoWithProfiles(AudioDeviceInfo.TYPE_HDMI_ARC, dtsProfile);
    ImmutableList<Integer> expectedChannelMasks = ImmutableList.of(AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  @Config(minSdk = 31)
  public void getLoudspeakerLayoutChannelMask_hdmiEarc_returnsChannelMasksFromAudioProfiles() {
    AudioProfile stereoProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_STEREO);
    AudioProfile surroundProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND);
    AudioProfile ac3Profile = createNonPcmAudioProfile(AudioFormat.ENCODING_AC3);
    AudioDeviceInfo device =
        createAudioDeviceInfoWithProfiles(
            AudioDeviceInfo.TYPE_HDMI_EARC, stereoProfile, surroundProfile, ac3Profile);
    ImmutableList<Integer> expectedChannelMasks =
        ImmutableList.of(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  @Config(minSdk = 31)
  public void getLoudspeakerLayoutChannelMask_usb_returnsChannelMasksFromAudioProfiles() {
    AudioProfile stereoProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_STEREO);
    AudioProfile surroundProfile = createPcmAudioProfile(AudioFormat.CHANNEL_OUT_5POINT1);
    AudioProfile ac3Profile = createNonPcmAudioProfile(AudioFormat.ENCODING_AC3);
    AudioDeviceInfo device =
        createAudioDeviceInfoWithProfiles(
            AudioDeviceInfo.TYPE_USB_DEVICE, stereoProfile, surroundProfile, ac3Profile);
    ImmutableList<Integer> expectedChannelMasks =
        ImmutableList.of(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @Test
  @Config(minSdk = 29)
  public void getLoudspeakerLayoutChannelMask_unknownType_returnsStereo() {
    AudioDeviceInfo device = createSimpleAudioDeviceInfo(AudioDeviceInfo.TYPE_AUX_LINE);
    ImmutableList<Integer> expectedChannelMasks = ImmutableList.of(AudioFormat.CHANNEL_OUT_STEREO);

    assertThat(SpeakerLayoutUtil.getLoudspeakerLayoutChannelMasks(device))
        .isEqualTo(expectedChannelMasks);
  }

  @RequiresApi(31)
  public static AudioDeviceInfo createSimpleAudioDeviceInfo(int type) {
    return AudioDeviceInfoBuilder.newBuilder().setType(type).build();
  }

  @RequiresApi(31)
  public static AudioDeviceInfo createAudioDeviceInfoWithProfiles(
      int type, AudioProfile... profiles) {
    return AudioDeviceInfoBuilder.newBuilder()
        .setType(type)
        .setProfiles(ImmutableList.copyOf(profiles))
        .build();
  }

  @RequiresApi(31)
  public static AudioProfile createPcmAudioProfile(int channelMask) {
    return AudioProfileBuilder.newBuilder()
        .setFormat(AudioFormat.ENCODING_PCM_16BIT)
        .setSamplingRates(new int[] {48_000})
        .setChannelMasks(new int[] {channelMask})
        .setChannelIndexMasks(new int[] {})
        .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
        .build();
  }

  @RequiresApi(31)
  public static AudioProfile createNonPcmAudioProfile(int encoding) {
    return AudioProfileBuilder.newBuilder()
        .setFormat(encoding)
        .setSamplingRates(new int[] {48_000})
        .setChannelMasks(new int[] {AudioFormat.CHANNEL_OUT_STEREO})
        .setChannelIndexMasks(new int[] {})
        .setEncapsulationType(AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE)
        .build();
  }
}
