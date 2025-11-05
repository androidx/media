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

import static com.google.common.base.Preconditions.checkNotNull;

import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/**
 * Utility class for handling bidirectional (BiDi) text rendering.
 *
 * <p>This class provides methods to check for right-to-left (RTL) characters in a text and to wrap
 * text lines for proper BiDi rendering using {@link BidiFormatter}.
 */
final class BidiUtils {

  private static final Splitter LF_SPLITTER = Splitter.on("\n");
  private static final Splitter CRLF_SPLITTER = Splitter.on("\r\n");
  private static final Joiner LF_JOINER = Joiner.on("\n");
  private static final String TAG = "BidiUtils";

  /**
   * Checks whether the given {@link CharSequence} contains any characters with right-to-left (RTL)
   * directionality.
   *
   * <p>This method inspects each character's Unicode directionality and returns {@code true} if at
   * least one character is classified as RTL. This includes characters with the following
   * directionalities:
   *
   * <ul>
   *   <li>{@link Character#DIRECTIONALITY_RIGHT_TO_LEFT}
   *   <li>{@link Character#DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC}
   *   <li>{@link Character#DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING}
   *   <li>{@link Character#DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE}
   * </ul>
   *
   * @param input the input {@link CharSequence} to analyze
   * @return {@code true} if the input contains at least one RTL character; {@code false} otherwise
   */
  @EnsuresNonNullIf(result = true, expression = "#1")
  static boolean containsRtl(@Nullable CharSequence input) {
    if (input == null) {
      return false;
    }
    int length = input.length();
    for (int offset = 0; offset < length; ) {
      int codePoint = Character.codePointAt(input, offset);
      byte dir = Character.getDirectionality(codePoint);
      if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
          || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
        return true;
      }
      offset += Character.charCount(codePoint);
    }
    return false;
  }

  /**
   * Applies bidirectional (BiDi) Unicode wrapping to each line of the given {@link CharSequence}.
   *
   * <p>This method ensures that text containing both left-to-right (LTR) and right-to-left (RTL)
   * scripts is displayed correctly by wrapping each line using {@link BidiFormatter#unicodeWrap}.
   * It forces LTR context for wrapping and preserves spans and line breaks.
   *
   * @param input the input text as a {@link CharSequence}, possibly containing mixed-direction text
   * @return a {@link CharSequence} with each line wrapped for proper bidi rendering
   */
  public static CharSequence wrapText(CharSequence input) {
    BidiFormatter bidiFormatter = BidiFormatter.getInstance();
    Spanned spannedInput = null;
    Object[] spans = null;
    int[] spanStarts = null;
    int[] spanEnds = null;

    if (input instanceof Spanned) {
      // Preserve span in the input text.
      spannedInput = (Spanned) input;
      spans = spannedInput.getSpans(0, input.length(), Object.class);
      // Create arrays to track the start and end of each span after wrapping.
      spanStarts = new int[spans.length];
      spanEnds = new int[spans.length];
      Arrays.fill(spanStarts, -1);
      Arrays.fill(spanEnds, -1);
    }

    // Determine the end-of-line (EOL) sequence for splitting the input text.
    List<String> lines;
    int eolLength;
    if (input.toString().contains("\r\n")) {
      lines = CRLF_SPLITTER.splitToList(input);
      eolLength = 2;
    } else {
      lines = LF_SPLITTER.splitToList(input);
      eolLength = 1;
    }

    List<String> wrappedLines = new ArrayList<>(lines.size());

    // Calculate the offset of each span after wrapping
    int spanUpdate = 0;
    int lineStart = 0;
    for (String line : lines) {
      // According to unicodeWrap documentation, this will either add 2 more characters or none
      String wrappedLine = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.LTR);
      if (spans != null) {
        checkNotNull(spannedInput);
        checkNotNull(spanStarts);
        checkNotNull(spanEnds);
        int diff = wrappedLine.length() - line.length();
        if (diff > 0) {
          spanUpdate++;
        }
        for (int j = 0; j < spans.length; j++) {
          // Each span start or end is updated only once
          if ((spanStarts[j] < 0)
              && (spannedInput.getSpanStart(spans[j]) >= lineStart)
              && (spannedInput.getSpanStart(spans[j]) < lineStart + line.length())) {
            spanStarts[j] = spanUpdate;
          }
          if ((spanEnds[j] < 0)
              && ((spannedInput.getSpanEnd(spans[j]) - 1) >= lineStart)
              && ((spannedInput.getSpanEnd(spans[j]) - 1) < lineStart + line.length())) {
            spanEnds[j] = spanUpdate;
          }
        }
        lineStart += line.length() + eolLength;
        if (diff > 0) {
          spanUpdate++;
        }
      }
      wrappedLines.add(wrappedLine);
    }

    // Create a new SpannableStringBuilder with the wrapped lines.
    SpannableStringBuilder wrapped = new SpannableStringBuilder(LF_JOINER.join(wrappedLines));

    if (spans != null) {
      checkNotNull(spannedInput);
      checkNotNull(spanStarts);
      checkNotNull(spanEnds);
      // Reapply original spans to the wrapped lines.
      for (int i = 0; i < spans.length; i++) {
        int start = spannedInput.getSpanStart(spans[i]) + spanStarts[i];
        int end = spannedInput.getSpanEnd(spans[i]) + spanEnds[i];
        int flags = spannedInput.getSpanFlags(spans[i]);
        if ((start >= 0) && (start < wrapped.length()) && (end >= 0) && (end <= wrapped.length())) {
          // Only set the span if the start and end are within bounds of the wrapped text.
          wrapped.setSpan(spans[i], start, end, flags);
        } else {
          Log.w(
              TAG,
              "Span out of bounds: start=" + start + ",end=" + end + ",len=" + wrapped.length());
        }
      }
    }

    return wrapped;
  }
}
