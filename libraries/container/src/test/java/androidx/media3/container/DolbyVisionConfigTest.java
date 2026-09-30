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
package androidx.media3.container;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.ParsableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DolbyVisionConfig}. */
@RunWith(AndroidJUnit4.class)
public final class DolbyVisionConfigTest {

  @Test
  public void parse_profile8_returnsExpectedConfig() {
    ParsableByteArray data = buildDolbyVisionConfigData(/* profile= */ 8, /* level= */ 3);

    DolbyVisionConfig config = DolbyVisionConfig.parse(data);

    assertThat(config.profile).isEqualTo(8);
    assertThat(config.level).isEqualTo(3);
    assertThat(config.codecs).isEqualTo("dvhe.08.03");
  }

  @Test
  public void parse_profile9_returnsExpectedConfig() {
    ParsableByteArray data = buildDolbyVisionConfigData(/* profile= */ 9, /* level= */ 2);

    DolbyVisionConfig config = DolbyVisionConfig.parse(data);

    assertThat(config.profile).isEqualTo(9);
    assertThat(config.level).isEqualTo(2);
    assertThat(config.codecs).isEqualTo("dvav.09.02");
  }

  @Test
  public void parse_profile10_returnsExpectedConfig() {
    ParsableByteArray data = buildDolbyVisionConfigData(/* profile= */ 10, /* level= */ 9);

    DolbyVisionConfig config = DolbyVisionConfig.parse(data);

    assertThat(config.profile).isEqualTo(10);
    assertThat(config.level).isEqualTo(9);
    assertThat(config.codecs).isEqualTo("dav1.10.09");
  }

  @Test
  public void parse_profile20_returnsExpectedConfig() {
    ParsableByteArray data = buildDolbyVisionConfigData(/* profile= */ 20, /* level= */ 10);

    DolbyVisionConfig config = DolbyVisionConfig.parse(data);

    assertThat(config.profile).isEqualTo(20);
    assertThat(config.level).isEqualTo(10);
    assertThat(config.codecs).isEqualTo("dvh1.20.10");
  }

  @Test
  public void parse_unrecognizedProfile_returnsConfigWithNullCodecs() {
    ParsableByteArray data = buildDolbyVisionConfigData(/* profile= */ 12, /* level= */ 1);

    DolbyVisionConfig config = DolbyVisionConfig.parse(data);

    assertThat(config.profile).isEqualTo(12);
    assertThat(config.level).isEqualTo(1);
    assertThat(config.codecs).isNull();
  }

  private static ParsableByteArray buildDolbyVisionConfigData(int profile, int level) {
    byte byte2 = (byte) ((profile << 1) | ((level >> 5) & 0x1));
    byte byte3 = (byte) ((level & 0x1F) << 3);
    return new ParsableByteArray(new byte[] {1, 0, byte2, byte3});
  }
}
