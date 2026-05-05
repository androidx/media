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
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MetadataUtil}. */
@RunWith(AndroidJUnit4.class)
public final class MetadataUtilTest {

  @Test
  public void parseIlstElement_withEmptyTagPayload_returnsNullAndAdvancesPosition() {
    ParsableByteArray ilst =
        new ParsableByteArray(
            new byte[] {
              0,
              0,
              0,
              8, // Size = 8 bytes
              (byte) 0xA9,
              'n',
              'a',
              'm' // Type = ©nam
            });

    Metadata.Entry entry = MetadataUtil.parseIlstElement(ilst);

    assertThat(entry).isNull();
    assertThat(ilst.getPosition()).isEqualTo(8);
  }

  @Test
  public void parseIlstElement_withBoxSizeZero_returnsNullAndAdvancesPosition() {
    ParsableByteArray ilst =
        new ParsableByteArray(
            new byte[] {
                0, 0, 0, 0 // Size = 0 bytes
            });

    Metadata.Entry entry = MetadataUtil.parseIlstElement(ilst);

    assertThat(entry).isNull();
    assertThat(ilst.getPosition()).isEqualTo(4);
  }

  @Test
  public void parseIlstElement_withBoxSizeSmallerThanHeaderSize_returnsNullAndAdvancesPosition() {
    ParsableByteArray ilst =
        new ParsableByteArray(
            new byte[] {
                0, 0, 0, 4 // Size = 4 bytes
            });

    Metadata.Entry entry = MetadataUtil.parseIlstElement(ilst);

    assertThat(entry).isNull();
    assertThat(ilst.getPosition()).isEqualTo(4);
  }

  @Test
  public void
  parseIlstElement_withRemainingPayloadSmallerThanAtomHeader_returnsNullAndAdvancesPosition() {
    ParsableByteArray ilst =
        new ParsableByteArray(
            new byte[] {
                0,
                0,
                0,
                15, // Size = 15 bytes
                (byte) 0xA9,
                'n',
                'a',
                'm', // Type = ©nam
                0,
                0,
                0,
                0,
                0,
                0,
                0 // 7 trailing bytes
            });

    Metadata.Entry entry = MetadataUtil.parseIlstElement(ilst);

    assertThat(entry).isNull();
    assertThat(ilst.getPosition()).isEqualTo(15);
  }
}
