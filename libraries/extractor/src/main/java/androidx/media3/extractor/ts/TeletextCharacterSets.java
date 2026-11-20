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

import androidx.media3.common.util.UnstableApi;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines character set mappings and national option character subsets for Teletext decoding, as
 * defined in ETSI EN 300 706 V1.2.1 Section 15.
 */
@UnstableApi
public final class TeletextCharacterSets {

  private static final String CYRILLIC =
      " !\"#\u00a4%\u044b'()*+,-./0123456789:;<=>?"
          + "\u042e\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041a\u041b\u041c\u041d\u041e"
          + "\u041f\u042f\u0420\u0421\u0422\u0423\u0416\u0412\u042c\u042a\u0417\u0428\u042d\u0429\u0427\u042b"
          + "\u044e\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043a\u043b\u043c\u043d\u043e"
          + "\u043f\u044f\u0440\u0441\u0442\u0443\u0436\u0432\u044c\u044a\u0437\u0448\u044d\u0449\u0447\u25a0";

  private static final Map<String, Map<Integer, Character>> NATIONAL_OPTIONS;

  static {
    Map<String, Map<Integer, Character>> nationalOptions = new HashMap<>();

    // Italian
    Map<Integer, Character> ita = new HashMap<>();
    ita.put(0x23, '£');
    ita.put(0x24, '$');
    ita.put(0x40, 'é');
    ita.put(0x5B, '°');
    ita.put(0x5C, 'ç');
    ita.put(0x5D, 'è');
    ita.put(0x5E, '^');
    ita.put(0x5F, '#');
    ita.put(0x60, 'ù');
    ita.put(0x7B, 'à');
    ita.put(0x7C, 'ò');
    ita.put(0x7D, 'è');
    ita.put(0x7E, 'ì');
    nationalOptions.put("ita", ita);
    nationalOptions.put("it", ita);

    // German
    Map<Integer, Character> deu = new HashMap<>();
    deu.put(0x23, '#');
    deu.put(0x24, '$');
    deu.put(0x40, '§');
    deu.put(0x5B, 'Ä');
    deu.put(0x5C, 'Ö');
    deu.put(0x5D, 'Ü');
    deu.put(0x5E, '^');
    deu.put(0x5F, '_');
    deu.put(0x60, '`');
    deu.put(0x7B, 'ä');
    deu.put(0x7C, 'ö');
    deu.put(0x7D, 'ü');
    deu.put(0x7E, 'ß');
    nationalOptions.put("deu", deu);
    nationalOptions.put("ger", deu);
    nationalOptions.put("de", deu);

    // French
    Map<Integer, Character> fra = new HashMap<>();
    fra.put(0x23, 'é');
    fra.put(0x24, 'ï');
    fra.put(0x40, 'à');
    fra.put(0x5B, 'ë');
    fra.put(0x5C, 'ê');
    fra.put(0x5D, 'ù');
    fra.put(0x5E, 'î');
    fra.put(0x5F, '#');
    fra.put(0x60, 'è');
    fra.put(0x7B, 'â');
    fra.put(0x7C, 'ô');
    fra.put(0x7D, 'û');
    fra.put(0x7E, 'ç');
    nationalOptions.put("fra", fra);
    nationalOptions.put("fre", fra);
    nationalOptions.put("fr", fra);

    // English
    Map<Integer, Character> eng = new HashMap<>();
    eng.put(0x23, '£');
    nationalOptions.put("eng", eng);
    nationalOptions.put("en", eng);

    // Scandinavian (Swedish, Finnish, Danish, Norwegian) + Hungarian
    Map<Integer, Character> sca = new HashMap<>();
    sca.put(0x23, '#');
    sca.put(0x24, '¤');
    sca.put(0x40, 'É');
    sca.put(0x5B, 'Ä');
    sca.put(0x5C, 'Ö');
    sca.put(0x5D, 'Å');
    sca.put(0x5E, 'Ü');
    sca.put(0x5F, '_');
    sca.put(0x60, 'é');
    sca.put(0x7B, 'ä');
    sca.put(0x7C, 'ö');
    sca.put(0x7D, 'å');
    sca.put(0x7E, 'ü');
    nationalOptions.put("swe", sca);
    nationalOptions.put("sv", sca);
    nationalOptions.put("fin", sca);
    nationalOptions.put("fi", sca);
    nationalOptions.put("dan", sca);
    nationalOptions.put("da", sca);
    nationalOptions.put("nor", sca);
    nationalOptions.put("no", sca);
    nationalOptions.put("hun", sca);
    nationalOptions.put("hu", sca);

    // Czech / Slovak
    Map<Integer, Character> ces = new HashMap<>();
    ces.put(0x23, '#');
    ces.put(0x24, '$');
    ces.put(0x40, 'Č');
    ces.put(0x5B, 'Ť');
    ces.put(0x5C, 'Ž');
    ces.put(0x5D, 'Ý');
    ces.put(0x5E, 'Š');
    ces.put(0x5F, 'Ď');
    ces.put(0x60, 'č');
    ces.put(0x7B, 'ť');
    ces.put(0x7C, 'ž');
    ces.put(0x7D, 'ý');
    ces.put(0x7E, 'š');
    nationalOptions.put("ces", ces);
    nationalOptions.put("cze", ces);
    nationalOptions.put("cs", ces);
    nationalOptions.put("slk", ces);
    nationalOptions.put("slo", ces);
    nationalOptions.put("sk", ces);

    // Polish
    Map<Integer, Character> pol = new HashMap<>();
    pol.put(0x23, '#');
    pol.put(0x24, 'ń');
    pol.put(0x40, 'ą');
    pol.put(0x5B, 'Z');
    pol.put(0x5C, 'Ś');
    pol.put(0x5D, 'Ł');
    pol.put(0x5E, 'ć');
    pol.put(0x5F, 'ó');
    pol.put(0x60, 'ę');
    pol.put(0x7B, 'ż');
    pol.put(0x7C, 'ś');
    pol.put(0x7D, 'ł');
    pol.put(0x7E, 'ź');
    nationalOptions.put("pol", pol);
    nationalOptions.put("pl", pol);

    // Turkish
    Map<Integer, Character> tur = new HashMap<>();
    tur.put(0x23, '£');
    tur.put(0x24, '$');
    tur.put(0x40, 'İ');
    tur.put(0x5B, 'Ş');
    tur.put(0x5C, 'Ö');
    tur.put(0x5D, 'Ç');
    tur.put(0x5E, 'Ü');
    tur.put(0x5F, 'ğ');
    tur.put(0x60, 'ı');
    tur.put(0x7B, 'ş');
    tur.put(0x7C, 'ö');
    tur.put(0x7D, 'ç');
    tur.put(0x7E, 'ü');
    nationalOptions.put("tur", tur);
    nationalOptions.put("tr", tur);

    // Serbian / Croatian / Slovenian
    Map<Integer, Character> hrv = new HashMap<>();
    hrv.put(0x23, '#');
    hrv.put(0x24, '$');
    hrv.put(0x40, 'Č');
    hrv.put(0x5B, 'Š');
    hrv.put(0x5C, 'Đ');
    hrv.put(0x5D, 'Ć');
    hrv.put(0x5E, 'Ž');
    hrv.put(0x5F, '_');
    hrv.put(0x60, 'č');
    hrv.put(0x7B, 'š');
    hrv.put(0x7C, 'đ');
    hrv.put(0x7D, 'ć');
    hrv.put(0x7E, 'ž');
    nationalOptions.put("hrv", hrv);
    nationalOptions.put("hr", hrv);
    nationalOptions.put("srp", hrv);
    nationalOptions.put("sr", hrv);
    nationalOptions.put("slv", hrv);
    nationalOptions.put("sl", hrv);

    // Estonian
    Map<Integer, Character> est = new HashMap<>();
    est.put(0x23, '#');
    est.put(0x24, '¤');
    est.put(0x40, 'Š');
    est.put(0x5B, 'Ä');
    est.put(0x5C, 'Ö');
    est.put(0x5D, 'Õ');
    est.put(0x5E, 'Ü');
    est.put(0x5F, 'Š');
    est.put(0x60, 'š');
    est.put(0x7B, 'ä');
    est.put(0x7C, 'ö');
    est.put(0x7D, 'õ');
    est.put(0x7E, 'ü');
    nationalOptions.put("est", est);
    nationalOptions.put("et", est);

    // Romanian
    Map<Integer, Character> ron = new HashMap<>();
    ron.put(0x23, '#');
    ron.put(0x24, '¤');
    ron.put(0x40, 'Ț');
    ron.put(0x5B, 'Â');
    ron.put(0x5C, 'Ș');
    ron.put(0x5D, 'Î');
    ron.put(0x5E, 'Ă');
    ron.put(0x5F, 'Ț');
    ron.put(0x60, 'ț');
    ron.put(0x7B, 'â');
    ron.put(0x7C, 'ș');
    ron.put(0x7D, 'î');
    ron.put(0x7E, 'ă');
    nationalOptions.put("ron", ron);
    nationalOptions.put("rum", ron);
    nationalOptions.put("ro", ron);

    // Spanish / Portuguese
    Map<Integer, Character> spa = new HashMap<>();
    spa.put(0x23, '£');
    spa.put(0x24, '$');
    spa.put(0x40, '@');
    spa.put(0x5B, '¡');
    spa.put(0x5C, 'Ñ');
    spa.put(0x5D, '¿');
    spa.put(0x5E, '^');
    spa.put(0x5F, '_');
    spa.put(0x60, '°');
    spa.put(0x7B, 'á');
    spa.put(0x7C, 'ñ');
    spa.put(0x7D, 'í');
    spa.put(0x7E, 'ó');
    nationalOptions.put("spa", spa);
    nationalOptions.put("es", spa);
    nationalOptions.put("por", spa);
    nationalOptions.put("pt", spa);

    NATIONAL_OPTIONS = Collections.unmodifiableMap(nationalOptions);
  }

  /**
   * Returns the ISO 639-2 language code corresponding to the 3-bit national option character subset
   * hint from the Teletext page header (bits C12-C14).
   */
  public static String getLanguageFromHint(int hint) {
    switch (hint) {
      case 0:
        return "eng";
      case 1:
        return "fra";
      case 2:
        return "swe";
      case 3:
        return "ces";
      case 4:
        return "deu";
      case 5:
        return "spa";
      case 6:
        return "ita";
      case 7:
        return "ron";
      default:
        return "eng";
    }
  }

  /** Returns the G0 Cyrillic primary character set string. */
  public static String getCyrillic() {
    return CYRILLIC;
  }

  /** Returns the national option character subsets mapped by ISO 639 language codes. */
  public static Map<String, Map<Integer, Character>> getNationalOptions() {
    return NATIONAL_OPTIONS;
  }

  private TeletextCharacterSets() {}
}
