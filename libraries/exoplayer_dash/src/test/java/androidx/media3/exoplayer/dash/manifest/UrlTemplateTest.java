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
package androidx.media3.exoplayer.dash.manifest;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link UrlTemplate}. */
@RunWith(AndroidJUnit4.class)
public class UrlTemplateTest {

  @Test
  public void realExamples() {
    String template = "QualityLevels($Bandwidth$)/Fragments(video=$Time$,format=mpd-time-csf)";
    UrlTemplate urlTemplate = UrlTemplate.compile(template);
    String url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("QualityLevels(650000)/Fragments(video=5000,format=mpd-time-csf)");

    template = "$RepresentationID$/$Number$";
    urlTemplate = UrlTemplate.compile(template);
    url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("abc1/10");

    template = "chunk_ctvideo_cfm4s_rid$RepresentationID$_cn$Number$_w2073857842_mpd.m4s";
    urlTemplate = UrlTemplate.compile(template);
    url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("chunk_ctvideo_cfm4s_ridabc1_cn10_w2073857842_mpd.m4s");
  }

  @Test
  public void full() {
    String template = "$Bandwidth$_a_$RepresentationID$_b_$Time$_c_$Number$";
    UrlTemplate urlTemplate = UrlTemplate.compile(template);
    String url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("650000_a_abc1_b_5000_c_10");
  }

  @Test
  public void fullWithDollarEscaping() {
    String template = "$$$Bandwidth$$$_a$$_$RepresentationID$_b_$Time$_c_$Number$$$";
    UrlTemplate urlTemplate = UrlTemplate.compile(template);
    String url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("$650000$_a$_abc1_b_5000_c_10$");
  }

  @Test
  public void invalidSubstitution() {
    String template = "$IllegalId$";
    try {
      UrlTemplate.compile(template);
      fail();
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void fullWithMultipleOccurrences() {
    String template =
        "$Bandwidth$_a1_$RepresentationID$_b1_$Time$_c1_$Number$_$Bandwidth$_a2_$RepresentationID$_b2_$Time$_c2_$Number$";
    UrlTemplate urlTemplate = UrlTemplate.compile(template);
    String url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("650000_a1_abc1_b1_5000_c1_10_650000_a2_abc1_b2_5000_c2_10");
  }

  @Test
  public void fullWithMultipleOccurrencesAndDollarEscaping() {
    String template =
        "$$$Bandwidth$$$_a1$$_$RepresentationID$_b1_$Time$_c1_$Number$$$_$$$Bandwidth$$$_a2$$_$RepresentationID$_b2_$Time$_c2_$Number$$$";
    UrlTemplate urlTemplate = UrlTemplate.compile(template);
    String url = urlTemplate.buildUri("abc1", 10, 650000, 5000);
    assertThat(url).isEqualTo("$650000$_a1$_abc1_b1_5000_c1_10$_$650000$_a2$_abc1_b2_5000_c2_10$");
  }
}
