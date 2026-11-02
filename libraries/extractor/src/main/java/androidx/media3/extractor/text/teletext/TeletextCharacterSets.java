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

import androidx.media3.common.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Character set mappings and national option character subsets for Teletext decoding, as defined in
 * ETSI EN 300 706 V1.2.1 Tables 32, 36, 39, and 40.
 */
/* package */ final class TeletextCharacterSets {

  // G0 Cyrillic Option 2 (Russian/Bulgarian), ETSI EN 300 706 Table 39 (0x20..0x7F).
  private static final String CYRILLIC_OPTION_2 =
      " !\"#$%\u044b'()*+,-./0123456789:;<=>?"
          + "\u042e\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041a\u041b\u041c\u041d\u041e"
          + "\u041f\u042f\u0420\u0421\u0422\u0423\u0416\u0412\u042c\u042a\u0417\u0428\u042d\u0429\u0427\u042b"
          + "\u044e\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043a\u043b\u043c\u043d\u043e"
          + "\u043f\u044f\u0440\u0441\u0442\u0443\u0436\u0432\u044c\u044a\u0437\u0448\u044d\u0449\u0447\u25a0";

  // G0 Cyrillic Option 3 (Ukrainian), ETSI EN 300 706 Table 40 (0x20..0x7F).
  private static final String CYRILLIC_OPTION_3 =
      " !\"#$%\u0456'()*+,-./0123456789:;<=>?"
          + "\u042e\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041a\u041b\u041c\u041d\u041e"
          + "\u041f\u042f\u0420\u0421\u0422\u0423\u0416\u0412\u042c\u0407\u0417\u0428\u0404\u0429\u0427\u0406"
          + "\u044e\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043a\u043b\u043c\u043d\u043e"
          + "\u043f\u044f\u0440\u0441\u0442\u0443\u0436\u0432\u044c\u0457\u0437\u0448\u0454\u0449\u0447\u25a0";

  // Latin national option subsets, ETSI EN 300 706 Table 36.
  // Indexed by the 13 national option positions:
  // 0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E.
  private static final String LATIN_CZECH = "#\u016f\u010d\u0165\u017e\u00fd\u00ed\u0159\u00e9\u00e1\u011b\u00fa\u0161";
  private static final String LATIN_ENGLISH = "\u00a3$@\u2190\u00bd\u2192\u2191#\u2014\u00bc\u2016\u00be\u00f7";
  private static final String LATIN_ESTONIAN = "#\u00f5\u0160\u00c4\u00d6\u017d\u00dc\u00d5\u0161\u00e4\u00f6\u017e\u00fc";
  private static final String LATIN_FRENCH = "\u00e9\u00ef\u00e0\u00eb\u00ea\u00f9\u00ee#\u00e8\u00e2\u00f4\u00fb\u00e7";
  private static final String LATIN_GERMAN = "#$\u00a7\u00c4\u00d6\u00dc^_\u00b0\u00e4\u00f6\u00fc\u00df";
  private static final String LATIN_ITALIAN = "\u00a3$\u00e9\u00b0\u00e7\u2192\u2191#\u00f9\u00e0\u00f2\u00e8\u00ec";
  private static final String LATIN_POLISH = "#\u0144\u0105\u01b5\u015a\u0141\u0107\u00f3\u0119\u017c\u015b\u0142\u017a";
  private static final String LATIN_SPANISH = "\u00e7$\u00a1\u00e1\u00e9\u00ed\u00f3\u00fa\u00bf\u00fc\u00f1\u00e8\u00e0";
  private static final String LATIN_ROMANIAN = "#\u00a4\u021a\u00c2\u0218\u0102\u00ce\u0131\u021b\u00e2\u0219\u0103\u00ee";
  private static final String LATIN_SERBIAN_CROATIAN = "#\u00cb\u010c\u0106\u017d\u0110\u0160\u00eb\u010d\u0107\u017e\u0111\u0161";
  private static final String LATIN_SWEDISH = "#\u00a4\u00c9\u00c4\u00d6\u00c5\u00dc_\u00e9\u00e4\u00f6\u00e5\u00fc";
  private static final String LATIN_TURKISH = "\u20ba\u011f\u0130\u015e\u00d6\u00c7\u00dc\u011e\u0131\u015f\u00f6\u00e7\u00fc";

  /**
   * Maps a 7-bit Teletext character code ({@code 0x20..0x7F}) to a Unicode character according to
   * the normalized language code and the 3-bit national option character subset bits ({@code
   * C12..C14}, ETSI EN 300 706 Table 32).
   *
   * @param code The 7-bit character code in range {@code 0x20..0x7F}.
   * @param normalizedLanguage The normalized BCP-47 / ISO 639 language code from {@link
   *     Util#normalizeLanguageCode(String)}, or {@code null}.
   * @param nationalOptionBits The 3-bit national option code {@code (C12 << 2) | (C13 << 1) | C14}.
   * @return The mapped Unicode character.
   */
  public static char mapChar(int code, @Nullable String normalizedLanguage, int nationalOptionBits) {
    if (code < 0x20 || code > 0x7F) {
      return ' ';
    }
    @Nullable String primaryLanguage = getPrimaryLanguage(normalizedLanguage);
    if ("uk".equals(primaryLanguage)) {
      return CYRILLIC_OPTION_3.charAt(code - 0x20);
    }
    if ("ru".equals(primaryLanguage)
        || "bg".equals(primaryLanguage)
        || "be".equals(primaryLanguage)) {
      return CYRILLIC_OPTION_2.charAt(code - 0x20);
    }
    if (code == 0x7F) {
      return '\u25a0';
    }

    int nationalIndex = getNationalOptionIndex(code);
    if (nationalIndex == -1) {
      return (char) code;
    }

    @Nullable String subset = getLatinNationalSubset(primaryLanguage);
    if (subset == null) {
      subset = getLatinNationalSubsetFromBits(nationalOptionBits);
    }
    return subset.charAt(nationalIndex);
  }

  private static @Nullable String getPrimaryLanguage(@Nullable String normalizedLanguage) {
    if (normalizedLanguage == null) {
      return null;
    }
    int dashIndex = normalizedLanguage.indexOf('-');
    return dashIndex != -1 ? normalizedLanguage.substring(0, dashIndex) : normalizedLanguage;
  }

  /**
   * Returns the normalized ISO 639-1 language code corresponding to the 3-bit national option
   * character subset bits {@code (C12 << 2) | (C13 << 1) | C14} (ETSI EN 300 706 Table 32).
   */
  public static String getLanguageFromNationalOptionBits(int nationalOptionBits) {
    switch (nationalOptionBits & 0x07) {
      case 0:
        return "en";
      case 1:
        return "de";
      case 2:
        return "sv";
      case 3:
        return "it";
      case 4:
        return "fr";
      case 5:
        return "es";
      case 6:
        return "cs";
      case 7:
      default:
        return "en";
    }
  }

  private static @Nullable String getLatinNationalSubset(@Nullable String normalizedLanguage) {
    if (normalizedLanguage == null) {
      return null;
    }
    switch (normalizedLanguage) {
      case "en":
        return LATIN_ENGLISH;
      case "de":
        return LATIN_GERMAN;
      case "fr":
        return LATIN_FRENCH;
      case "it":
        return LATIN_ITALIAN;
      case "es":
      case "pt":
        return LATIN_SPANISH;
      case "sv":
      case "fi":
      case "da":
      case "no":
      case "nb":
      case "nn":
      case "hu":
        return LATIN_SWEDISH;
      case "cs":
      case "sk":
        return LATIN_CZECH;
      case "et":
        return LATIN_ESTONIAN;
      case "pl":
        return LATIN_POLISH;
      case "ro":
        return LATIN_ROMANIAN;
      case "hbs":
      case "hr":
      case "sr":
      case "sl":
        return LATIN_SERBIAN_CROATIAN;
      case "tr":
        return LATIN_TURKISH;
      default:
        return null;
    }
  }

  private static String getLatinNationalSubsetFromBits(int nationalOptionBits) {
    switch (nationalOptionBits & 0x07) {
      case 1:
        return LATIN_GERMAN;
      case 2:
        return LATIN_SWEDISH;
      case 3:
        return LATIN_ITALIAN;
      case 4:
        return LATIN_FRENCH;
      case 5:
        return LATIN_SPANISH;
      case 6:
        return LATIN_CZECH;
      case 0:
      case 7:
      default:
        return LATIN_ENGLISH;
    }
  }

  private static int getNationalOptionIndex(int code) {
    switch (code) {
      case 0x23:
        return 0;
      case 0x24:
        return 1;
      case 0x40:
        return 2;
      case 0x5B:
        return 3;
      case 0x5C:
        return 4;
      case 0x5D:
        return 5;
      case 0x5E:
        return 6;
      case 0x5F:
        return 7;
      case 0x60:
        return 8;
      case 0x7B:
        return 9;
      case 0x7C:
        return 10;
      case 0x7D:
        return 11;
      case 0x7E:
        return 12;
      default:
        return -1;
    }
  }

  private TeletextCharacterSets() {}
}
