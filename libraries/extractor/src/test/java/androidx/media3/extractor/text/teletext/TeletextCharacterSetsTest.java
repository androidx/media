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
package androidx.media3.extractor.text.teletext;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TeletextCharacterSets}. */
@RunWith(AndroidJUnit4.class)
public final class TeletextCharacterSetsTest {

  private static final int[] NATIONAL_POSITIONS = {
    0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E
  };

  @Test
  public void getLanguageFromNationalOptionBits_matchesEtsiTable32() {
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(0)).isEqualTo("en");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(1)).isEqualTo("de");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(2)).isEqualTo("sv");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(3)).isEqualTo("it");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(4)).isEqualTo("fr");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(5)).isEqualTo("es");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(6)).isEqualTo("cs");
    assertThat(TeletextCharacterSets.getLanguageFromNationalOptionBits(7)).isEqualTo("en");
  }

  @Test
  public void mapChar_allLatinNationalSubsetsMatchEtsiTable36() {
    assertNationalSubset("eng", "\u00a3$@\u2190\u00bd\u2192\u2191#\u2014\u00bc\u2016\u00be\u00f7");
    assertNationalSubset("deu", "#$\u00a7\u00c4\u00d6\u00dc^_\u00b0\u00e4\u00f6\u00fc\u00df");
    assertNationalSubset("ger", "#$\u00a7\u00c4\u00d6\u00dc^_\u00b0\u00e4\u00f6\u00fc\u00df");
    assertNationalSubset("fra", "\u00e9\u00ef\u00e0\u00eb\u00ea\u00f9\u00ee#\u00e8\u00e2\u00f4\u00fb\u00e7");
    assertNationalSubset("ita", "\u00a3$\u00e9\u00b0\u00e7\u2192\u2191#\u00f9\u00e0\u00f2\u00e8\u00ec");
    assertNationalSubset("spa", "\u00e7$\u00a1\u00e1\u00e9\u00ed\u00f3\u00fa\u00bf\u00fc\u00f1\u00e8\u00e0");
    assertNationalSubset("por", "\u00e7$\u00a1\u00e1\u00e9\u00ed\u00f3\u00fa\u00bf\u00fc\u00f1\u00e8\u00e0");
    assertNationalSubset("swe", "#\u00a4\u00c9\u00c4\u00d6\u00c5\u00dc_\u00e9\u00e4\u00f6\u00e5\u00fc");
    assertNationalSubset("fin", "#\u00a4\u00c9\u00c4\u00d6\u00c5\u00dc_\u00e9\u00e4\u00f6\u00e5\u00fc");
    assertNationalSubset("ces", "#\u016f\u010d\u0165\u017e\u00fd\u00ed\u0159\u00e9\u00e1\u011b\u00fa\u0161");
    assertNationalSubset("slk", "#\u016f\u010d\u0165\u017e\u00fd\u00ed\u0159\u00e9\u00e1\u011b\u00fa\u0161");
    assertNationalSubset("est", "#\u00f5\u0160\u00c4\u00d6\u017d\u00dc\u00d5\u0161\u00e4\u00f6\u017e\u00fc");
    assertNationalSubset("pol", "#\u0144\u0105\u01b5\u015a\u0141\u0107\u00f3\u0119\u017c\u015b\u0142\u017a");
    assertNationalSubset("ron", "#\u00a4\u021a\u00c2\u0218\u0102\u00ce\u0131\u021b\u00e2\u0219\u0103\u00ee");
    assertNationalSubset("hrv", "#\u00cb\u010c\u0106\u017d\u0110\u0160\u00eb\u010d\u0107\u017e\u0111\u0161");
    assertNationalSubset("tur", "\u20ba\u011f\u0130\u015e\u00d6\u00c7\u00dc\u011e\u0131\u015f\u00f6\u00e7\u00fc");
  }

  @Test
  public void mapChar_usesNationalOptionBitsWhenLanguageIsUnknown() {
    // Bit 1 = German (0x7E -> ß), Bit 4 = French (0x23 -> é), Bit 6 = Czech (0x40 -> č)
    assertThat(TeletextCharacterSets.mapChar(0x7E, /* normalizedLanguage= */ null, 1))
        .isEqualTo('ß');
    assertThat(TeletextCharacterSets.mapChar(0x23, /* normalizedLanguage= */ null, 4))
        .isEqualTo('é');
    assertThat(TeletextCharacterSets.mapChar(0x40, /* normalizedLanguage= */ null, 6))
        .isEqualTo('č');
  }

  @Test
  public void mapChar_cyrillicOption2AndOption3MatchEtsiTables39And40() {
    String ru = Util.normalizeLanguageCode("rus");
    String uk = Util.normalizeLanguageCode("ukr");

    // Table 39 (Russian/Bulgarian)
    assertThat(TeletextCharacterSets.mapChar(0x24, ru, 0)).isEqualTo('$');
    assertThat(TeletextCharacterSets.mapChar(0x26, ru, 0)).isEqualTo('ы');
    assertThat(TeletextCharacterSets.mapChar(0x41, ru, 0)).isEqualTo('А');
    assertThat(TeletextCharacterSets.mapChar(0x59, ru, 0)).isEqualTo('Ъ');
    assertThat(TeletextCharacterSets.mapChar(0x5C, ru, 0)).isEqualTo('Э');
    assertThat(TeletextCharacterSets.mapChar(0x5F, ru, 0)).isEqualTo('Ы');
    assertThat(TeletextCharacterSets.mapChar(0x79, ru, 0)).isEqualTo('ъ');
    assertThat(TeletextCharacterSets.mapChar(0x7C, ru, 0)).isEqualTo('э');

    // Table 40 (Ukrainian)
    assertThat(TeletextCharacterSets.mapChar(0x24, uk, 0)).isEqualTo('$');
    assertThat(TeletextCharacterSets.mapChar(0x26, uk, 0)).isEqualTo('і');
    assertThat(TeletextCharacterSets.mapChar(0x59, uk, 0)).isEqualTo('Ї');
    assertThat(TeletextCharacterSets.mapChar(0x5C, uk, 0)).isEqualTo('Є');
    assertThat(TeletextCharacterSets.mapChar(0x5F, uk, 0)).isEqualTo('І');
    assertThat(TeletextCharacterSets.mapChar(0x79, uk, 0)).isEqualTo('ї');
    assertThat(TeletextCharacterSets.mapChar(0x7C, uk, 0)).isEqualTo('є');
  }

  private static void assertNationalSubset(String language, String expected) {
    String normalized = Util.normalizeLanguageCode(language);
    StringBuilder actual = new StringBuilder();
    for (int code : NATIONAL_POSITIONS) {
      actual.append(TeletextCharacterSets.mapChar(code, normalized, /* nationalOptionBits= */ 0));
    }
    assertThat(actual.toString()).isEqualTo(expected);
  }
}
