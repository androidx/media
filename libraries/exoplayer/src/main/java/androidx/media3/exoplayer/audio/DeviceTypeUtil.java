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

import android.media.AudioDeviceInfo;

/** Utils related to categorizing values returned by {@link AudioDeviceInfo#getType()}. */
final class DeviceTypeUtil {

  // Non-instantiable.
  private DeviceTypeUtil() {}

  /** Returns whether the value given by {@link AudioDeviceInfo#getType()} is a Bluetooth type. */
  public static boolean isBluetoothDevice(int deviceType) {
    if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
      return true;
    }
    if (SDK_INT >= 31
        && (deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET
            || deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
      return true;
    }
    if (SDK_INT >= 33 && deviceType == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
      return true;
    }
    return false;
  }

  /**
   * Returns whether the value given by {@link AudioDeviceInfo#getType()} is a built-in earpiece.
   */
  public static boolean isBuiltInEarpiece(int deviceType) {
    return deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
  }

  /** Returns whether the value given by {@link AudioDeviceInfo#getType()} is a built-in speaker. */
  public static boolean isBuiltInSpeaker(int deviceType) {
    return deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
  }

  /** Returns whether the value given by {@link AudioDeviceInfo#getType()} is an HDMI ARC. */
  public static boolean isHdmiArc(int deviceType) {
    return deviceType == AudioDeviceInfo.TYPE_HDMI_ARC;
  }

  /** Returns whether the value given by {@link AudioDeviceInfo#getType()} is an HDMI eARC. */
  public static boolean isHdmiEarc(int deviceType) {
    return SDK_INT >= 31 && deviceType == AudioDeviceInfo.TYPE_HDMI_EARC;
  }

  /** Returns whether the value given by {@link AudioDeviceInfo#getType()} is a USB type. */
  public static boolean isUsbDevice(int deviceType) {
    if (deviceType == AudioDeviceInfo.TYPE_USB_DEVICE
        || deviceType == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
      return true;
    }
    if (SDK_INT >= 31 && deviceType == AudioDeviceInfo.TYPE_USB_HEADSET) {
      return true;
    }
    return false;
  }
}
