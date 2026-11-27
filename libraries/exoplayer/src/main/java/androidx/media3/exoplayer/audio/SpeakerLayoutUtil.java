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

import static android.os.Build.VERSION.SDK_INT;

import android.media.AudioDescriptor;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioProfile;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Encapsulates logic for determining the physical speaker layout for an {@link AudioDeviceInfo}.
 *
 * <p>Attempts to find the best guess at the physical speaker layout for an {@link AudioDeviceInfo},
 * which can be used to configure decoders for immersive audio which are flexible in their output
 * channel layouts.
 */
final class SpeakerLayoutUtil {

  private static final String TAG = "SpeakerLayoutUtil";
  private static final ImmutableList<Integer> DEFAULT_CHANNEL_MASK =
      ImmutableList.of(AudioFormat.CHANNEL_OUT_STEREO);

  private SpeakerLayoutUtil() {}

  /**
   * Returns the best-guess channel mask representing the speaker layout for the given {@link
   * AudioDeviceInfo}.
   *
   * <p>The channel masks are ordered from highest channel count to lowest, also meaning from more
   * likely to represent the complete physical layout to subsets. That is to say, a device with an
   * actual 5.1 layout might also report 3.1 and stereo.
   *
   * <p>The subsequent channel masks are useful when decoders might not be compatible with any
   * arbitrary channel mask.
   */
  public static ImmutableList<Integer> getLoudspeakerLayoutChannelMasks(
      AudioDeviceInfo audioDeviceInfo) {
    if (DeviceTypeUtil.isBluetoothDevice(audioDeviceInfo.getType())) {
      return getChannelMasksForBluetooth();
    }
    if (DeviceTypeUtil.isBuiltInEarpiece(audioDeviceInfo.getType())) {
      return ImmutableList.of(AudioFormat.CHANNEL_OUT_MONO);
    }
    if (DeviceTypeUtil.isBuiltInSpeaker(audioDeviceInfo.getType())) {
      return getChannelMasksForBuiltInSpeakers(audioDeviceInfo);
    }
    if (SDK_INT >= 31 && DeviceTypeUtil.isHdmiArc(audioDeviceInfo.getType())) {
      return getChannelMasksForHdmiArc(audioDeviceInfo);
    }
    if (SDK_INT >= 31 && DeviceTypeUtil.isHdmiEarc(audioDeviceInfo.getType())) {
      return getChannelMasksForHdmiEarc(audioDeviceInfo);
    }
    if (SDK_INT >= 31 && DeviceTypeUtil.isUsbDevice(audioDeviceInfo.getType())) {
      return getChannelMasksForUsb(audioDeviceInfo);
    }

    // Default
    return DEFAULT_CHANNEL_MASK;
  }

  private static ImmutableList<Integer> getChannelMasksForBluetooth() {
    // Bluetooth devices are always max 2 channels.  We assume stereo.
    return DEFAULT_CHANNEL_MASK;
  }

  private static ImmutableList<Integer> getChannelMasksForBuiltInSpeakers(
      AudioDeviceInfo audioDeviceInfo) {
    if (SDK_INT >= 36) {
      // New API in SDK level 36, backed by a new field in
      // media/aidl/android/media/audio/common/AudioPortDeviceExt.aidl
      int builtInChannelMask = audioDeviceInfo.getSpeakerLayoutChannelMask();
      if (builtInChannelMask != AudioFormat.CHANNEL_INVALID
          && builtInChannelMask != AudioFormat.CHANNEL_OUT_DEFAULT) {
        return ImmutableList.of(builtInChannelMask);
      }
    }
    // If the manufacturer has not populated the field in the Audio HAL, fall back to stereo.
    Log.w(TAG, "Built-in speaker's getSpeakerLayoutChannelMask not usable, defaulting to stereo.");
    return DEFAULT_CHANNEL_MASK;
  }

  @RequiresApi(31)
  private static ImmutableList<Integer> getChannelMasksForHdmiArc(AudioDeviceInfo audioDeviceInfo) {
    // First, check AudioProfiles
    ImmutableList<Integer> channelMasksFromAudioProfiles =
        getChannelMasksFromPcmAudioProfiles(audioDeviceInfo);
    if (!channelMasksFromAudioProfiles.isEmpty()) {
      return channelMasksFromAudioProfiles;
    }
    // Fall-back to Short Audio Descriptors (SADs).
    ImmutableList<Integer> channelMasks =
        AudioDescriptorUtil.getAllLpcmChannelMasksFromPcmSads(
            audioDeviceInfo.getAudioDescriptors());
    if (!channelMasks.isEmpty()) {
      return channelMasks;
    }
    // Last resort, return stereo.
    return DEFAULT_CHANNEL_MASK;
  }

  @RequiresApi(31)
  private static ImmutableList<Integer> getChannelMasksForHdmiEarc(
      AudioDeviceInfo audioDeviceInfo) {
    // First, check AudioProfiles
    ImmutableList<Integer> channelMasksFromAudioProfiles =
        getChannelMasksFromPcmAudioProfiles(audioDeviceInfo);
    if (!channelMasksFromAudioProfiles.isEmpty()) {
      return channelMasksFromAudioProfiles;
    }
    // Next check for Speaker Allocation Data Block (SADB).
    List<AudioDescriptor> audioDescriptors = audioDeviceInfo.getAudioDescriptors();
    if (SDK_INT >= 34) {
      ImmutableList<Integer> channelMasksFromSadbs =
          AudioDescriptorUtil.getAllChannelMasksFromSadbs(audioDescriptors);
      if (!channelMasksFromSadbs.isEmpty()) {
        return channelMasksFromSadbs;
      }
    }
    // Then check for Short Audio Descriptors (SADs).
    ImmutableList<Integer> channelMasksFromSads =
        AudioDescriptorUtil.getAllLpcmChannelMasksFromPcmSads(audioDescriptors);
    if (!channelMasksFromSads.isEmpty()) {
      return channelMasksFromSads;
    }
    // Last resort, return stereo.
    return DEFAULT_CHANNEL_MASK;
  }

  @RequiresApi(31)
  private static ImmutableList<Integer> getChannelMasksForUsb(AudioDeviceInfo audioDeviceInfo) {
    // First, check AudioProfiles
    ImmutableList<Integer> channelMasksFromAudioProfiles =
        getChannelMasksFromPcmAudioProfiles(audioDeviceInfo);
    if (!channelMasksFromAudioProfiles.isEmpty()) {
      return channelMasksFromAudioProfiles;
    }
    // Default stereo.
    return DEFAULT_CHANNEL_MASK;
  }

  @RequiresApi(31)
  @SuppressWarnings("WrongConstant") // AudioFormat encoding passed to isEncodingLinearPcm.
  private static ImmutableList<Integer> getChannelMasksFromPcmAudioProfiles(
      AudioDeviceInfo audioDeviceInfo) {
    List<AudioProfile> audioProfiles = audioDeviceInfo.getAudioProfiles();
    TreeSet<Integer> channelMasks =
        new TreeSet<>(Comparator.comparing(Integer::bitCount).reversed());
    for (AudioProfile audioProfile : audioProfiles) {
      if (audioProfile.getEncapsulationType() == AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937) {
        continue;
      }
      if (Util.isEncodingLinearPcm(audioProfile.getFormat())) {
        int[] masks = audioProfile.getChannelMasks();
        for (int mask : masks) {
          channelMasks.add(mask);
        }
      }
    }
    return ImmutableList.copyOf(channelMasks);
  }
}
