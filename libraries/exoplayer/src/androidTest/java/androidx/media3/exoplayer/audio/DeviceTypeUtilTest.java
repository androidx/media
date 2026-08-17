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
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the {@link DeviceTypeUtil} class. */
@RunWith(AndroidJUnit4.class)
public final class DeviceTypeUtilTest {

  @Test
  public void isBluetoothDevice_returnsTrueForAllApiLevelAgnosticValues() {
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isTrue();
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isTrue();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void isBluetoothDevice_returnsTrueForApi31Values() {
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)).isTrue();
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER)).isTrue();
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void isBluetoothDevice_returnsTrueForApi33Values() {
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BLE_BROADCAST)).isTrue();
  }

  @Test
  public void isBluetoothDevice_returnsFalseForNonBluetoothDevices() {
    assertThat(DeviceTypeUtil.isBluetoothDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isFalse();
  }

  @Test
  public void isBuiltInEarpiece_returnsTrueForBuiltInEarpiece() {
    assertThat(DeviceTypeUtil.isBuiltInEarpiece(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isTrue();
  }

  @Test
  public void isBuiltInEarpiece_returnsFalseForNonBuiltInEarpiece() {
    assertThat(DeviceTypeUtil.isBuiltInEarpiece(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isFalse();
  }

  @Test
  public void isBuiltInSpeaker_returnsTrueForBuiltInSpeaker() {
    assertThat(DeviceTypeUtil.isBuiltInSpeaker(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isTrue();
  }

  @Test
  public void isBuiltInSpeaker_returnsFalseForNonBuiltInSpeaker() {
    assertThat(DeviceTypeUtil.isBuiltInSpeaker(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isFalse();
  }

  @Test
  public void isHdmiArc_returnsTrueForHdmiArc() {
    assertThat(DeviceTypeUtil.isHdmiArc(AudioDeviceInfo.TYPE_HDMI_ARC)).isTrue();
  }

  @Test
  public void isHdmiArc_returnsFalseForNonHdmiArc() {
    assertThat(DeviceTypeUtil.isHdmiArc(AudioDeviceInfo.TYPE_HDMI)).isFalse();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void isHdmiEarc_returnsTrueForHdmiEarc() {
    assertThat(DeviceTypeUtil.isHdmiEarc(AudioDeviceInfo.TYPE_HDMI_EARC)).isTrue();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void isHdmiEarc_returnsFalseForNonHdmiEarc() {
    assertThat(DeviceTypeUtil.isHdmiEarc(AudioDeviceInfo.TYPE_HDMI_ARC)).isFalse();
  }

  @Test
  public void isUsbDevice_returnsTrueForAllApiLevelAgnosticValues() {
    assertThat(DeviceTypeUtil.isUsbDevice(AudioDeviceInfo.TYPE_USB_DEVICE)).isTrue();
    assertThat(DeviceTypeUtil.isUsbDevice(AudioDeviceInfo.TYPE_USB_ACCESSORY)).isTrue();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void isUsbDevice_returnsTrueForApi31Values() {
    assertThat(DeviceTypeUtil.isUsbDevice(AudioDeviceInfo.TYPE_USB_HEADSET)).isTrue();
  }

  @Test
  public void isUsbDevice_returnsFalseForNonUsbDevices() {
    assertThat(DeviceTypeUtil.isUsbDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isFalse();
  }
}
