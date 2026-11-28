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
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Mp4CoverArtData}. */
@RunWith(AndroidJUnit4.class)
public class Mp4CoverArtDataTest {
  @Test
  public void constructor_withEmptyData_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Mp4CoverArtData(new byte[0], Mp4CoverArtData.TYPE_JPEG));
  }

  @Test
  public void constructor_withInvalidType_throws() {
    assertThrows(IllegalArgumentException.class, () -> new Mp4CoverArtData(new byte[] {1}, 0));
    assertThrows(IllegalArgumentException.class, () -> new Mp4CoverArtData(new byte[] {1}, 15));
  }

  @Test
  public void equals_withSameBytesAndType_isEqualWithSameHashCode() {
    Mp4CoverArtData first = new Mp4CoverArtData(new byte[] {1, 2}, Mp4CoverArtData.TYPE_JPEG);
    Mp4CoverArtData second = new Mp4CoverArtData(new byte[] {1, 2}, Mp4CoverArtData.TYPE_JPEG);
    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  public void equals_withDifferentBytesOrType_isNotEqual() {
    Mp4CoverArtData first = new Mp4CoverArtData(new byte[] {1, 2}, Mp4CoverArtData.TYPE_JPEG);
    assertThat(first)
        .isNotEqualTo(new Mp4CoverArtData(new byte[] {1, 3}, Mp4CoverArtData.TYPE_JPEG));
    assertThat(first)
        .isNotEqualTo(new Mp4CoverArtData(new byte[] {1, 2}, Mp4CoverArtData.TYPE_PNG));
    assertThat(first).isNotEqualTo(null);
    assertThat(first).isNotEqualTo("cover");
  }
}
