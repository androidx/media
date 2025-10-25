/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.*;
import org.junit.Test;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

/** Tests for {@link BidiUtils}. */
@RunWith(RobolectricTestRunner.class)
public class BidiUtilsTest {

  @Test
  public void containsRTL_nullInput_returnsFalse() {
    assertFalse(BidiUtils.containsRTL(null));
  }

  @Test
  public void containsRTL_emptyString_returnsFalse() {
    assertFalse(BidiUtils.containsRTL(""));
  }

  @Test
  public void containsRTL_ltrOnly_returnsFalse() {
    assertFalse(BidiUtils.containsRTL("Hello, world!"));
  }

  @Test
  public void containsRTL_rtlOnly_returnsTrue() {
    // Hebrew "שלום"
    assertTrue(BidiUtils.containsRTL("שלום"));
  }

  @Test
  public void containsRTL_mixedText_returnsTrue() {
    // Mixed English and Arabic
    assertTrue(BidiUtils.containsRTL("Hello مرحبا"));
  }

  @Test
  public void wrapText_plainText_wrapsEachLineWithUnicodeWrap() {
    String input = "להתראות.\nשלום\nשלום!";
    CharSequence wrapped = BidiUtils.wrapText(input);

    String[] lines = wrapped.toString().split("\n");

    assertEquals(3, lines.length);
    assertTrue(lines[0].contains("להתראות."));
    assertTrue(lines[1].contains("שלום"));
    assertTrue(lines[2].contains("שלום!"));

    // Ensure wrapping occurred (Unicode control characters are added)
    assertTrue(lines[0].length() > "להתראות.".length()
        || lines[1].length() > "שלום".length()
        || lines[2].length() > "שלום!".length());
  }

  @Test
  public void wrapText_plainText_wrapsEachLineWithUnicodeWrap_CRLF() {
    String input = "נסיון" + "\r\n" + "בחלונות";
    CharSequence wrapped = BidiUtils.wrapText(input);

    String[] lines = wrapped.toString().split("\n");

    assertEquals(2, lines.length);
    assertTrue(lines[0].contains("נסיון"));
    assertTrue(lines[1].contains("בחלונות"));

    // Ensure wrapping occurred (Unicode control characters are added)
    assertTrue(lines[0].length() > "נסיון".length()
        || lines[1].length() > "בחלונות".length());
  }

  @Test
  public void wrapText_spansArePreserved() {
    SpannableStringBuilder builder = new SpannableStringBuilder("שלום\nעולם");
    StyleSpan boldSpan = new StyleSpan(android.graphics.Typeface.BOLD);
    builder.setSpan(boldSpan, 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    CharSequence wrapped = BidiUtils.wrapText(builder);

    assertTrue(wrapped instanceof Spanned);
    Spanned spanned = (Spanned) wrapped;

    int start = spanned.getSpanStart(boldSpan);
    int end = spanned.getSpanEnd(boldSpan);

    assertTrue(start >= 0 && end > start);
  }
}