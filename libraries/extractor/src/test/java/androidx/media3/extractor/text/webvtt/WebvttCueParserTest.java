/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.text.webvtt;

import static androidx.media3.test.utils.truth.SpannedSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Color;
import android.text.Spanned;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link WebvttCueParser}. */
@RunWith(AndroidJUnit4.class)
public final class WebvttCueParserTest {

  @Test
  public void parseStrictValidClassesAndTrailingTokens() throws Exception {
    Spanned text =
        parseCueText(
            "<v.first.loud Esme>This <u.style1.style2 some stuff>is</u> text with"
                + " <b.foo><i.bar>html</i></b> tags");

    assertThat(text.toString()).isEqualTo("This is text with html tags");
    assertThat(text).hasUnderlineSpanBetween("This ".length(), "This is".length());
    assertThat(text)
        .hasBoldItalicSpanBetween("This is text with ".length(), "This is text with html".length());
  }

  @Test
  public void parseStrictValidUnsupportedTagsStrippedOut() throws Exception {
    Spanned text =
        parseCueText(
            "This <unsupported>is</unsupported> text with "
                + "<notsupp><invalid>html</invalid></notsupp> tags");

    assertThat(text.toString()).isEqualTo("This is text with html tags");
    assertThat(text).hasNoSpans();
  }

  @Test
  public void parseDefaultTextColor() throws Exception {
    Spanned text = parseCueText("In this sentence <c.red>this text</c> is red");

    assertThat(text.toString()).isEqualTo("In this sentence this text is red");
    assertThat(text)
        .hasForegroundColorSpanBetween(
            "In this sentence ".length(), "In this sentence this text".length())
        .withColor(Color.RED);
  }

  @Test
  public void parseUnsupportedDefaultTextColor() throws Exception {
    Spanned text = parseCueText("In this sentence <c.papayawhip>this text</c> is not papaya");

    assertThat(text.toString()).isEqualTo("In this sentence this text is not papaya");
    assertThat(text).hasNoSpans();
  }

  @Test
  public void parseDefaultBackgroundColor() throws Exception {
    Spanned text = parseCueText("In this sentence <c.bg_cyan>this text</c> has a cyan background");

    assertThat(text.toString()).isEqualTo("In this sentence this text has a cyan background");
    assertThat(text)
        .hasBackgroundColorSpanBetween(
            "In this sentence ".length(), "In this sentence this text".length())
        .withColor(Color.CYAN);
  }

  @Test
  public void parseUnsupportedDefaultBackgroundColor() throws Exception {
    Spanned text =
        parseCueText(
            "In this sentence <c.bg_papayawhip>this text</c> doesn't have a papaya background");

    assertThat(text.toString())
        .isEqualTo("In this sentence this text doesn't have a papaya background");
    assertThat(text).hasNoSpans();
  }

  @Test
  public void parseWellFormedUnclosedEndAtCueEnd() throws Exception {
    Spanned text =
        parseCueText("An <u some trailing stuff>unclosed u tag with " + "<i>italic</i> inside");

    assertThat(text.toString()).isEqualTo("An unclosed u tag with italic inside");
    assertThat(text)
        .hasUnderlineSpanBetween("An ".length(), "An unclosed u tag with italic inside".length());
    assertThat(text)
        .hasItalicSpanBetween(
            "An unclosed u tag with ".length(), "An unclosed u tag with italic".length());
  }

  @Test
  public void parseWellFormedUnclosedEndAtParent() throws Exception {
    Spanned text = parseCueText("An italic tag with unclosed <i><u>underline</i> inside");

    assertThat(text.toString()).isEqualTo("An italic tag with unclosed underline inside");
    assertThat(text)
        .hasItalicSpanBetween(
            "An italic tag with unclosed ".length(),
            "An italic tag with unclosed underline".length());
    assertThat(text)
        .hasUnderlineSpanBetween(
            "An italic tag with unclosed ".length(),
            "An italic tag with unclosed underline".length());
  }

  @Test
  public void parseMalformedNestedElements() throws Exception {
    Spanned text = parseCueText("<b><u>Overlapping u <i>and</u> i tags</i></b>");

    String expectedText = "Overlapping u and i tags";
    assertThat(text.toString()).isEqualTo(expectedText);
    assertThat(text).hasBoldSpanBetween(0, expectedText.length());
    // Text between the <u> tags is underlined.
    assertThat(text).hasUnderlineSpanBetween(0, "Overlapping u and".length());
    // Only text from <i> to <\\u> is italic (unexpected - but simplifies the parsing).
    assertThat(text).hasItalicSpanBetween("Overlapping u ".length(), "Overlapping u and".length());
  }

  @Test
  public void parseCloseNonExistingTag() throws Exception {
    Spanned text = parseCueText("foo<b>bar</i>baz</b>buzz");
    assertThat(text.toString()).isEqualTo("foobarbazbuzz");

    // endIndex should be 9 when valid (i.e. "foobarbaz".length()
    assertThat(text).hasBoldSpanBetween("foo".length(), "foobar".length());
  }

  @Test
  public void parseEmptyTagName() throws Exception {
    Spanned text = parseCueText("An empty <>tag");
    assertThat(text.toString()).isEqualTo("An empty tag");
  }

  @Test
  public void parseEntities() throws Exception {
    Spanned text = parseCueText("&amp; &gt; &lt; &nbsp;");
    assertThat(text.toString()).isEqualTo("& > <  ");
  }

  @Test
  public void parseEntitiesUnsupported() throws Exception {
    Spanned text = parseCueText("&noway; &sure;");
    assertThat(text.toString()).isEqualTo(" ");
  }

  @Test
  public void parseEntitiesNotTerminated() throws Exception {
    Spanned text = parseCueText("&amp here comes text");
    assertThat(text.toString()).isEqualTo("& here comes text");
  }

  @Test
  public void parseEntitiesNotTerminatedUnsupported() throws Exception {
    Spanned text = parseCueText("&surenot here comes text");
    assertThat(text.toString()).isEqualTo(" here comes text");
  }

  @Test
  public void parseEntitiesNotTerminatedNoSpace() throws Exception {
    Spanned text = parseCueText("&surenot");
    assertThat(text.toString()).isEqualTo("&surenot");
  }

  @Test
  public void parseVoidTag() throws Exception {
    Spanned text = parseCueText("here comes<br/> text<br/>");
    assertThat(text.toString()).isEqualTo("here comes text");
  }

  @Test
  public void parseMultipleTagsOfSameKind() {
    Spanned text = parseCueText("blah <b>blah</b> blah <b>foo</b>");

    assertThat(text.toString()).isEqualTo("blah blah blah foo");
    assertThat(text).hasBoldSpanBetween("blah ".length(), "blah blah".length());
    assertThat(text).hasBoldSpanBetween("blah blah blah ".length(), "blah blah blah foo".length());
  }

  @Test
  public void parseInvalidVoidSlash() {
    Spanned text = parseCueText("blah <b/.st1.st2 trailing stuff> blah");

    assertThat(text.toString()).isEqualTo("blah  blah");
    assertThat(text).hasNoSpans();
  }

  @Test
  public void parseMonkey() throws Exception {
    Spanned text =
        parseCueText(
            "< u>An unclosed u tag with <<<<< i>italic</u></u></u></u    >" + "</i><u><u> inside");
    assertThat(text.toString()).isEqualTo("An unclosed u tag with italic inside");
    text =
        parseCueText(
            ">>>>>>>>>An unclosed u tag with <<<<< italic</u></u></u>" + "</u  ></i><u><u> inside");
    assertThat(text.toString()).isEqualTo(">>>>>>>>>An unclosed u tag with  inside");
  }

  @Test
  public void parseCornerCases() throws Exception {
    Spanned text = parseCueText(">");
    assertThat(text.toString()).isEqualTo(">");

    text = parseCueText("<");
    assertThat(text.toString()).isEmpty();

    text = parseCueText("<b.st1.st2 annotation");
    assertThat(text.toString()).isEmpty();

    text = parseCueText("<<<<<<<<<<<<<<<<");
    assertThat(text.toString()).isEmpty();

    text = parseCueText("<<<<<<>><<<<<<<<<<");
    assertThat(text.toString()).isEqualTo(">");

    text = parseCueText("<>");
    assertThat(text.toString()).isEmpty();

    text = parseCueText("&");
    assertThat(text.toString()).isEqualTo("&");

    text = parseCueText("&&&&&&&");
    assertThat(text.toString()).isEqualTo("&&&&&&&");
  }

  @Test
  public void parseEmptyVoiceSpan() throws Exception {
    Spanned text = parseCueText("<v>Text with a single voice span");

    assertThat(text.toString()).isEqualTo("Text with a single voice span");
    assertThat(text).hasVoiceSpanBetween(0, "Text with a single voice span".length()).withName("");
  }

  @Test
  public void parseVoiceSpanWithName() throws Exception {
    Spanned text = parseCueText("<v Esme>Text with a single voice span");

    assertThat(text.toString()).isEqualTo("Text with a single voice span");
    assertThat(text)
        .hasVoiceSpanBetween(0, "Text with a single voice span".length())
        .withName("Esme");
  }

  @Test
  public void ignoreVoiceSpanClasses() throws Exception {
    Spanned text = parseCueText("<v.first.loud Esme>Text with a single voice span");

    assertThat(text.toString()).isEqualTo("Text with a single voice span");
    assertThat(text)
        .hasVoiceSpanBetween(0, "Text with a single voice span".length())
        .withName("Esme");
  }

  @Test
  public void parseMultipleVoiceSpans() throws Exception {
    Spanned text = parseCueText("<v.loud Esme>Text with </v><v.quiet Mary>multiple voice spans");

    assertThat(text.toString()).isEqualTo("Text with multiple voice spans");
    assertThat(text).hasVoiceSpanBetween(0, "Text with ".length()).withName("Esme");
    assertThat(text)
        .hasVoiceSpanBetween("Text with ".length(), "Text with multiple voice spans".length())
        .withName("Mary");
  }

  private static Spanned parseCueText(String string) {
    return WebvttCueParser.parseCueText(
        /* id= */ null, string, /* styles= */ Collections.emptyList());
  }
}
