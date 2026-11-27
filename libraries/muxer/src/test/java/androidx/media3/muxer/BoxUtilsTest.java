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
package androidx.media3.muxer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BoxUtilsTest {

  @Test
  public void getUleb128Bytes_negativeValueThrows() {
    assertThrows(IllegalArgumentException.class, () -> BoxUtils.getUleb128Bytes(-1));
  }

  @Test
  public void getUleb128Bytes_matchesExpected() {
    assertThat(BoxUtils.getUleb128Bytes(0)).isEqualTo(new byte[] {0x00});
    assertThat(BoxUtils.getUleb128Bytes(127)).isEqualTo(new byte[] {0x7F});
    assertThat(BoxUtils.getUleb128Bytes(128)).isEqualTo(new byte[] {(byte) 0x80, 0x01});
    assertThat(BoxUtils.getUleb128Bytes(16383)).isEqualTo(new byte[] {(byte) 0xFF, 0x7F});
    assertThat(BoxUtils.getUleb128Bytes(16384))
        .isEqualTo(new byte[] {(byte) 0x80, (byte) 0x80, 0x01});
  }
}
