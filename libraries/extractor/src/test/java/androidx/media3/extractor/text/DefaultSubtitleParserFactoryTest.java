/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link DefaultSubtitleParserFactory}. */
@RunWith(AndroidJUnit4.class)
public class DefaultSubtitleParserFactoryTest {

  @Test
  public void createStandaloneSubripParser_usesCharsetDetector() {
    Charset charset = Charset.forName("GB18030");
    DefaultSubtitleParserFactory factory =
        new DefaultSubtitleParserFactory((data, offset, length) -> charset);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build();
    String expectedText = "起来 快起来";
    byte[] bytes = createSubripBytes(expectedText, charset);

    List<CuesWithTiming> cues = new ArrayList<>();
    factory.create(format).parse(bytes, OutputOptions.allCues(), cues::add);

    assertThat(cues).hasSize(1);
    assertThat(cues.get(0).cues.get(0).text.toString()).isEqualTo(expectedText);
  }

  @Test
  public void createStandaloneSubripParserWithTextContainerMimeType_usesCharsetDetector() {
    Charset charset = Charset.forName("GB18030");
    DefaultSubtitleParserFactory factory =
        new DefaultSubtitleParserFactory((data, offset, length) -> charset);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setContainerMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build();
    String expectedText = "起来 快起来";
    byte[] bytes = createSubripBytes(expectedText, charset);

    List<CuesWithTiming> cues = new ArrayList<>();
    factory.create(format).parse(bytes, OutputOptions.allCues(), cues::add);

    assertThat(cues).hasSize(1);
    assertThat(cues.get(0).cues.get(0).text.toString()).isEqualTo(expectedText);
  }

  @Test
  public void createEmbeddedSubripParser_doesNotUseCharsetDetector() {
    DefaultSubtitleParserFactory factory =
        new DefaultSubtitleParserFactory(
            (data, offset, length) -> {
              throw new AssertionError("Charset detector should not be called");
            });
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .build();
    String expectedText = "This is an embedded subtitle.";
    byte[] bytes = createSubripBytes(expectedText, StandardCharsets.UTF_8);

    List<CuesWithTiming> cues = new ArrayList<>();
    factory.create(format).parse(bytes, OutputOptions.allCues(), cues::add);

    assertThat(cues).hasSize(1);
    assertThat(cues.get(0).cues.get(0).text.toString()).isEqualTo(expectedText);
  }

  /**
   * This test loops through all the public fields of {@link MimeTypes} and assumes all the static,
   * string fields with a single "/" in them are MIME types - then it uses these to 'fuzz' the
   * {@link DefaultSubtitleParserFactory} to check that, for each MIME type, it either consistently
   * supports or doesn't support it.
   */
  @Test
  public void formatSupportIsConsistent() throws Exception {
    DefaultSubtitleParserFactory factory = new DefaultSubtitleParserFactory();
    for (Field field : MimeTypes.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(String.class)) {
        String fieldValue = (String) field.get(null);
        // Filter to only MIME types (values with exactly one '/')
        if (CharMatcher.is('/').countIn(fieldValue) == 1) {
          Format.Builder formatBuilder = new Format.Builder().setSampleMimeType(fieldValue);
          if (fieldValue.equals(MimeTypes.APPLICATION_DVBSUBS)) {
            formatBuilder.setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}));
          }
          if (fieldValue.equals(MimeTypes.APPLICATION_VOBSUB)) {
            formatBuilder.setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}));
          }
          Format format = formatBuilder.build();
          if (factory.supportsFormat(format)) {
            try {
              assertThat(factory.getCueReplacementBehavior(format))
                  .isEqualTo(factory.create(format).getCueReplacementBehavior());
            } catch (IllegalArgumentException e) {
              throw new AssertionError(
                  "Unexpected error for supported MIME type (" + fieldValue + ")", e);
            }
          } else {
            assertThrows(
                "MIME=" + fieldValue,
                IllegalArgumentException.class,
                () -> factory.getCueReplacementBehavior(format));
            assertThrows(
                "MIME=" + fieldValue, IllegalArgumentException.class, () -> factory.create(format));
          }
        }
      }
    }
  }

  private static byte[] createSubripBytes(String text, Charset charset) {
    return ("1\r\n" + "00:00:00,000 --> 00:00:05,000\r\n" + text + "\r\n").getBytes(charset);
  }
}
