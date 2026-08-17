/*
 * Copyright 2025 The Android Open Source Project
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
 *
 */
package androidx.media3.ui;

import static androidx.media3.test.utils.truth.SpannedSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link BidiUtils}. */
@RunWith(AndroidJUnit4.class)
public class BidiUtilsTest {

  @Test
  public void containsRtl_nullInput_returnsFalse() {
    assertThat(BidiUtils.containsRtl(null)).isFalse();
  }

  @Test
  public void containsRtl_emptyString_returnsFalse() {
    assertThat(BidiUtils.containsRtl("")).isFalse();
  }

  @Test
  public void containsRtl_ltrOnly_returnsFalse() {
    assertThat(BidiUtils.containsRtl("Hello, world!")).isFalse();
  }

  @Test
  public void containsRtl_rtlOnly_returnsTrue() {
    // Hebrew "שלום"
    assertThat(BidiUtils.containsRtl("שלום")).isTrue();
  }

  @Test
  public void containsRtl_mixedText_returnsTrue() {
    // Mixed English and Arabic
    assertThat(BidiUtils.containsRtl("Hello مرحبا")).isTrue();
  }

  @Test
  public void wrapText_plainText_wrapsEachLineWithUnicodeWrap() {
    String input = "להתראות.\nשלום\nשלום!";
    CharSequence wrapped = BidiUtils.wrapText(input);

    String[] lines = Util.split(wrapped.toString(), "\n");

    assertThat(lines).hasLength(3);
    assertThat(lines[0]).contains("להתראות.");
    assertThat(lines[1]).contains("שלום");
    assertThat(lines[2]).contains("שלום!");

    // Ensure wrapping occurred (the Unicode LRM control characters are added).
    assertThat((int) lines[0].charAt(0)).isEqualTo(0x200E);
    assertThat((int) lines[0].charAt(lines[0].length() - 1)).isEqualTo(0x200E);
    assertThat((int) lines[1].charAt(0)).isEqualTo(0x200E);
    assertThat((int) lines[1].charAt(lines[1].length() - 1)).isEqualTo(0x200E);
    assertThat((int) lines[2].charAt(0)).isEqualTo(0x200E);
    assertThat((int) lines[2].charAt(lines[2].length() - 1)).isEqualTo(0x200E);
  }

  @Test
  public void wrapText_plainText_wrapsEachLineWithUnicodeWrap_crlf() {
    String input = "נסיון\r\nבחלונות";
    CharSequence wrapped = BidiUtils.wrapText(input);

    String[] lines = Util.split(wrapped.toString(), "\n");

    assertThat(lines).hasLength(2);
    assertThat(lines[0]).contains("נסיון");
    assertThat(lines[1]).contains("בחלונות");

    // Ensure wrapping occurred (the Unicode LRM control characters are added).
    assertThat((int) lines[0].charAt(0)).isEqualTo(0x200E);
    assertThat((int) lines[0].charAt(lines[0].length() - 1)).isEqualTo(0x200E);
    assertThat((int) lines[1].charAt(0)).isEqualTo(0x200E);
    assertThat((int) lines[1].charAt(lines[1].length() - 1)).isEqualTo(0x200E);
  }

  @Test
  public void wrapText_spansArePreserved() {
    SpannableStringBuilder builder = new SpannableStringBuilder("שלום\nעולם");
    StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    builder.setSpan(boldSpan, 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    CharSequence wrapped = BidiUtils.wrapText(builder);

    // BiDi control characters are added at the start and end of each line, so the start index
    // increments by 1 (start of first line) and end index increments by 3 (start & end of first
    // line, start of second line).
    assertThat((Spanned) wrapped).hasBoldSpanBetween(1, 10);
  }
}
