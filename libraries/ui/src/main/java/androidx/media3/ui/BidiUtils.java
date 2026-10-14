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

import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristics;
import androidx.annotation.Nullable;
import com.google.common.base.Splitter;
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
    BidiFormatter bidiFormatter = BidiFormatter.getInstance(/* rtlContext= */ false);
    SpannableStringBuilder wrapped = new SpannableStringBuilder(input);

    List<String> lines = LF_SPLITTER.splitToList(input);

    int lineEnd = input.length();
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i);
      boolean hasCr = line.endsWith("\r");
      if (hasCr) {
        line = line.substring(0, line.length() - 1);
      }
      int lineLength = line.length();
      int lineStart = lineEnd - lineLength - (hasCr ? 1 : 0);

      if (hasCr) {
        wrapped.delete(lineStart + lineLength, lineStart + lineLength + 1);
      }

      String wrappedLine = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.FIRSTSTRONG_LTR);
      int diff = wrappedLine.length() - lineLength;
      if (diff > 0) {
        // BidiFormatter.unicodeWrap adds either:
        // - 1 mark on one side only (diff == 1) when line direction matches context direction, or
        // - symmetric characters on both sides (diff == 2 for 1 mark each, or diff == 4 for
        //   embedding + Pop Directional Formatting + mark when directions differ).
        int prefixLength = diff == 1 ? (wrappedLine.startsWith(line) ? 0 : 1) : diff / 2;
        if (prefixLength + lineLength < wrappedLine.length()) {
          wrapped.insert(
              lineStart + lineLength,
              wrappedLine.subSequence(prefixLength + lineLength, wrappedLine.length()));
        }
        if (prefixLength > 0) {
          wrapped.insert(lineStart, wrappedLine.subSequence(0, prefixLength));
        }
      }
      if (i > 0) {
        lineEnd = lineStart - 1;
      }
    }

    return wrapped;
  }
}
