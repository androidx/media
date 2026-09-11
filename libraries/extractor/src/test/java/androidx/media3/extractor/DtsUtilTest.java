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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DtsUtil}. */
@RunWith(AndroidJUnit4.class)
public final class DtsUtilTest {

  private static final int DTS_CD_FRAME_SIZE = 4_096;
  private static final byte[] DTS_CD_14_BIT_LITTLE_ENDIAN_HEADER =
      Util.getBytesFromHexString("FF1F00E8F107E0FC980000FAEAF40900DEFB");
  private static final byte[] DTS_CD_14_BIT_BIG_ENDIAN_HEADER =
      Util.getBytesFromHexString("1FFFE80007F1FCE0009800FAF4EA0009FBDE");
  private static final byte[] DTS_14_BIT_HEADER_WITH_EXACT_WORD_CONVERSION =
      Util.getBytesFromHexString("FF1F00E8F107DFFC983C00FAEAF40900DEFB");

  @Test
  public void getDtsFrameSize_wordAligned14BitLittleEndian_returnsPhysicalFrameSize() {
    assertThat(DtsUtil.getDtsFrameSize(DTS_CD_14_BIT_LITTLE_ENDIAN_HEADER))
        .isEqualTo(DTS_CD_FRAME_SIZE);
  }

  @Test
  public void getDtsFrameSize_wordAligned14BitBigEndian_returnsPhysicalFrameSize() {
    assertThat(DtsUtil.getDtsFrameSize(DTS_CD_14_BIT_BIG_ENDIAN_HEADER))
        .isEqualTo(DTS_CD_FRAME_SIZE);
  }

  @Test
  public void getDtsFrameSize_exact14BitWordConversion_returnsUnchangedFrameSize() {
    assertThat(DtsUtil.getDtsFrameSize(DTS_14_BIT_HEADER_WITH_EXACT_WORD_CONVERSION))
        .isEqualTo(DTS_CD_FRAME_SIZE);
  }
}
