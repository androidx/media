/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TeletextCharacterSets}. */
@RunWith(AndroidJUnit4.class)
public final class TeletextCharacterSetsTest {

  @Test
  public void getLanguageFromHint_mapsStandardHints() {
    assertThat(TeletextCharacterSets.getLanguageFromHint(0)).isEqualTo("eng");
    assertThat(TeletextCharacterSets.getLanguageFromHint(1)).isEqualTo("fra");
    assertThat(TeletextCharacterSets.getLanguageFromHint(2)).isEqualTo("swe");
    assertThat(TeletextCharacterSets.getLanguageFromHint(3)).isEqualTo("ces");
    assertThat(TeletextCharacterSets.getLanguageFromHint(4)).isEqualTo("deu");
    assertThat(TeletextCharacterSets.getLanguageFromHint(5)).isEqualTo("spa");
    assertThat(TeletextCharacterSets.getLanguageFromHint(6)).isEqualTo("ita");
    assertThat(TeletextCharacterSets.getLanguageFromHint(7)).isEqualTo("ron");
    assertThat(TeletextCharacterSets.getLanguageFromHint(99)).isEqualTo("eng");
  }

  @Test
  public void getNationalOptions_containsAccentedCharacters() {
    Map<String, Map<Integer, Character>> nationalOptions =
        TeletextCharacterSets.getNationalOptions();

    // German umlauts
    Map<Integer, Character> deu = nationalOptions.get("deu");
    assertThat(deu).isNotNull();
    assertThat(deu.get(0x5B)).isEqualTo('Ä');
    assertThat(deu.get(0x5C)).isEqualTo('Ö');
    assertThat(deu.get(0x5D)).isEqualTo('Ü');
    assertThat(deu.get(0x7E)).isEqualTo('ß');

    // French accents
    Map<Integer, Character> fra = nationalOptions.get("fra");
    assertThat(fra).isNotNull();
    assertThat(fra.get(0x23)).isEqualTo('é');
    assertThat(fra.get(0x40)).isEqualTo('à');
    assertThat(fra.get(0x7E)).isEqualTo('ç');

    // Italian characters
    Map<Integer, Character> ita = nationalOptions.get("ita");
    assertThat(ita).isNotNull();
    assertThat(ita.get(0x23)).isEqualTo('£');
    assertThat(ita.get(0x7B)).isEqualTo('à');
    assertThat(ita.get(0x7C)).isEqualTo('ò');
  }

  @Test
  public void getCyrillic_returnsNonEmptyCharacterSet() {
    String cyrillic = TeletextCharacterSets.getCyrillic();
    assertThat(cyrillic).isNotEmpty();
    assertThat(cyrillic).contains("А");
    assertThat(cyrillic).contains("Я");
  }
}
