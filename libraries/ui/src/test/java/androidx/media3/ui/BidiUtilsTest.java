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
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import android.text.style.AlignmentSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Locale;
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
    CharSequence wrapped = BidiUtils.wrapText(input, TextDirectionHeuristics.FIRSTSTRONG_LTR);

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

    // Ensure RTL directional embedding (RLE...PDF) is added for RTL lines.
    assertThat((int) lines[0].charAt(1)).isEqualTo(0x202B);
    assertThat((int) lines[0].charAt(lines[0].length() - 2)).isEqualTo(0x202C);
    assertThat((int) lines[1].charAt(1)).isEqualTo(0x202B);
    assertThat((int) lines[1].charAt(lines[1].length() - 2)).isEqualTo(0x202C);
    assertThat((int) lines[2].charAt(1)).isEqualTo(0x202B);
    assertThat((int) lines[2].charAt(lines[2].length() - 2)).isEqualTo(0x202C);
  }

  @Test
  public void wrapText_plainText_wrapsEachLineWithUnicodeWrap_crlf() {
    String input = "נסיון\r\nבחלונות";
    CharSequence wrapped = BidiUtils.wrapText(input, TextDirectionHeuristics.FIRSTSTRONG_LTR);

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

    CharSequence wrapped = BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    // BiDi control characters (\u200E\u202B before and \u202C\u200E after) are added to each RTL
    // line, so the start index increments by 2 (start of first line) and end index increments by 6
    // (start & end of first line, start of second line).
    assertThat((Spanned) wrapped).hasBoldSpanBetween(2, 13);
  }

  @Test
  public void wrapText_crlf_spansArePreserved() {
    SpannableStringBuilder builder = new SpannableStringBuilder("שלום\r\nעולם");
    StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    builder.setSpan(boldSpan, 0, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    CharSequence wrapped = BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    // BiDi control characters are added (2 before and 2 after each line) and \r is removed.
    // Start index increments by 2.
    // End index: original 8 + 6 BiDi control chars - 1 for \r = 13.
    assertThat((Spanned) wrapped).hasBoldSpanBetween(2, 13);
  }

  @Test
  public void wrapText_asymmetricSuffixOnly_spansArePreserved() {
    // Starts with LTR ("Hello") and ends with RTL ("مرحبا"): unicodeWrap in LTR context with
    // FIRSTSTRONG_LTR only appends a trailing LRM (\u200E) with no leading mark.
    SpannableStringBuilder builder = new SpannableStringBuilder("Hello مرحبا");
    builder.setSpan(new StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new UnderlineSpan(), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    Spanned wrapped =
        (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    assertThat(wrapped.toString()).isEqualTo("Hello مرحبا\u200E");
    assertThat(wrapped).hasBoldSpanBetween(0, 5);
    assertThat(wrapped).hasUnderlineSpanBetween(6, 11);
  }

  @Test
  public void wrapText_asymmetricPrefixOnlyMultiline_spansArePreserved() {
    // First line starts with an RTL isolate ("\u2067مرحبا\u2069") followed by LTR (" Hello"):
    // FIRSTSTRONG_LTR ignores characters inside isolate pairs so overall direction is LTR
    // (isRtl == false), while getEntryDir sees the initial RTL characters and prepends a single
    // leading LRM (\u200E) with no trailing mark. Second line ("World") has no marks added.
    SpannableStringBuilder builder = new SpannableStringBuilder("\u2067مرحبا\u2069 Hello\nWorld");
    builder.setSpan(new StyleSpan(Typeface.BOLD), 8, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new UnderlineSpan(), 14, 19, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    Spanned wrapped =
        (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    assertThat(wrapped.toString()).isEqualTo("\u200E\u2067مرحبا\u2069 Hello\nWorld");
    assertThat(wrapped).hasBoldSpanBetween(9, 14);
    assertThat(wrapped).hasUnderlineSpanBetween(15, 20);
  }

  @Test
  public void wrapText_rtlFirstLineThenLtrLine_spansArePreserved() {
    // First line starts with RTL ("مرحبا") and ends with LTR ("Hello"): wrapped with 2-char prefix
    // ("\u200E\u202B") and 2-char suffix ("\u202C\u200E"). Second line ("World") is pure LTR and
    // has no marks added.
    SpannableStringBuilder builder = new SpannableStringBuilder("مرحبا Hello\nWorld");
    builder.setSpan(new StyleSpan(Typeface.BOLD), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new UnderlineSpan(), 12, 17, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    Spanned wrapped =
        (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    assertThat(wrapped.toString()).isEqualTo("\u200E\u202Bمرحبا Hello\u202C\u200E\nWorld");
    assertThat(wrapped).hasBoldSpanBetween(8, 13);
    assertThat(wrapped).hasUnderlineSpanBetween(16, 21);
  }

  @Test
  public void wrapText_mixedCrlfAndLfWithEmptyLine_spansArePreserved() {
    SpannableStringBuilder builder = new SpannableStringBuilder("שלום\r\n\nעולם");
    builder.setSpan(new StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new UnderlineSpan(), 7, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    Spanned wrapped =
        (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    assertThat(wrapped.toString())
        .isEqualTo("\u200E\u202Bשלום\u202C\u200E\n\n\u200E\u202Bעולם\u202C\u200E");
    assertThat(wrapped).hasBoldSpanBetween(2, 6);
    assertThat(wrapped).hasUnderlineSpanBetween(12, 16);
  }

  @Test
  public void
      wrapText_rtlDefaultLocaleAndExplicitDirectionalControls_forcesLtrContextAndPreservesSpans() {
    Locale previousLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("he-IL"));
      // Even when the default locale is RTL, BidiUtils.wrapText forces an LTR context, wrapping
      // RTL lines (including lines that already start with \u202B) with "\u200E\u202B" and
      // "\u202C\u200E" and leaving empty lines unwrapped.
      SpannableStringBuilder builder = new SpannableStringBuilder("\u202Bשלום\u202C\n\nעולם");
      builder.setSpan(new StyleSpan(Typeface.BOLD), 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      builder.setSpan(new UnderlineSpan(), 8, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

      Spanned wrapped =
          (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

      assertThat(wrapped.toString())
          .isEqualTo("\u200E\u202B\u202Bשלום\u202C\u202C\u200E\n\n\u200E\u202Bעולם\u202C\u200E");
      assertThat(wrapped).hasBoldSpanBetween(3, 7);
      assertThat(wrapped).hasUnderlineSpanBetween(14, 18);
    } finally {
      Locale.setDefault(previousLocale);
    }
  }

  @Test
  public void wrapText_paragraphSpans_arePreservedAtParagraphBoundaries() {
    SpannableStringBuilder builder = new SpannableStringBuilder("שלום\r\nעולם");
    AlignmentSpan firstParagraphSpan = new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE);
    AlignmentSpan secondParagraphSpan = new AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL);
    AlignmentSpan fullTextSpan = new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER);
    builder.setSpan(firstParagraphSpan, 0, 6, Spanned.SPAN_PARAGRAPH);
    builder.setSpan(secondParagraphSpan, 6, 10, Spanned.SPAN_PARAGRAPH);
    builder.setSpan(fullTextSpan, 0, 10, Spanned.SPAN_PARAGRAPH);

    Spanned wrapped =
        (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);

    assertThat(wrapped)
        .hasAlignmentSpanBetween(0, 9)
        .withAlignment(Layout.Alignment.ALIGN_OPPOSITE)
        .andFlags(Spanned.SPAN_PARAGRAPH);
    assertThat(wrapped)
        .hasAlignmentSpanBetween(9, wrapped.length())
        .withAlignment(Layout.Alignment.ALIGN_NORMAL)
        .andFlags(Spanned.SPAN_PARAGRAPH);
    assertThat(wrapped)
        .hasAlignmentSpanBetween(0, wrapped.length())
        .withAlignment(Layout.Alignment.ALIGN_CENTER)
        .andFlags(Spanned.SPAN_PARAGRAPH);
  }

  @Test
  public void wrapText_withLtrHeuristic_wrapsRtlLinesWithLrmMarksOnly() {
    SpannableStringBuilder builder = new SpannableStringBuilder("!اركضي\r\n؟ماذا");
    builder.setSpan(new StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new UnderlineSpan(), 8, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    Spanned wrapped = (Spanned) BidiUtils.wrapText(builder, TextDirectionHeuristics.LTR);

    assertThat(wrapped.toString()).isEqualTo("\u200E!اركضي\u200E\n\u200E؟ماذا\u200E");
    assertThat(wrapped).hasBoldSpanBetween(1, 7);
    assertThat(wrapped).hasUnderlineSpanBetween(10, 15);
  }
}
