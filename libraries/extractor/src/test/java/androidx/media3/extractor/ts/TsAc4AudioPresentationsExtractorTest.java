/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.icu.util.ULocale;
import android.media.AudioPresentation;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TsAc4AudioPresentationsExtractorTest}. */
@RunWith(AndroidJUnit4.class)
public final class TsAc4AudioPresentationsExtractorTest {

  @Test
  public void verifyAc4MultipleAudioPresentations() throws Exception {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri("media/ts/sample_ac4_multiple_presentations.ts");
    List<AudioPresentation> refPresentations = new ArrayList<>();
    Map<ULocale, CharSequence> ulocaleLabels = new HashMap<>();
    ulocaleLabels.put(ULocale.forLocale(Locale.ENGLISH), "Standard");
    refPresentations.add(new AudioPresentation.Builder(10)
        .setProgramId(AudioPresentation.PROGRAM_ID_UNKNOWN)
        .setLocale(ULocale.ENGLISH)
        .setLabels(ulocaleLabels)
        .setMasteringIndication(AudioPresentation.MASTERED_FOR_SURROUND)
        .setHasSpokenSubtitles(false)
        .setHasDialogueEnhancement(true)
        .build());
    ulocaleLabels.put(ULocale.forLocale(Locale.ENGLISH), "Kids' choice");
    refPresentations.add(new AudioPresentation.Builder(11)
        .setProgramId(AudioPresentation.PROGRAM_ID_UNKNOWN)
        .setLocale(ULocale.ENGLISH)
        .setLabels(ulocaleLabels)
        .setMasteringIndication(AudioPresentation.MASTERED_FOR_SURROUND)
        .setHasSpokenSubtitles(false)
        .setHasAudioDescription(true)
        .setHasDialogueEnhancement(true)
        .build());
    ulocaleLabels.put(ULocale.forLocale(Locale.ENGLISH), "Artists' commentary");
    refPresentations.add(new AudioPresentation.Builder(12)
        .setProgramId(AudioPresentation.PROGRAM_ID_UNKNOWN)
        .setLocale(ULocale.FRENCH)
        .setLabels(ulocaleLabels)
        .setMasteringIndication(AudioPresentation.MASTERED_FOR_SURROUND)
        .setHasSpokenSubtitles(false)
        .setHasDialogueEnhancement(true)
        .build());

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    extractor.init(extractorOutput);
    int readResult = Extractor.RESULT_CONTINUE;
    PositionHolder positionHolder = new PositionHolder();
    DefaultDataSource dataSource =
        new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext())
            .createDataSource();
    ExtractorInput input = TestUtil.getExtractorInputFromPosition(dataSource, 0, fileUri);
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      try {
        while (readResult == Extractor.RESULT_CONTINUE) {
          readResult = extractor.read(input, positionHolder);
        }
      } finally {
        DataSourceUtil.closeQuietly(dataSource);
      }
      if (readResult == Extractor.RESULT_SEEK) {
        input =
            TestUtil.getExtractorInputFromPosition(dataSource, positionHolder.position, fileUri);
        readResult = Extractor.RESULT_CONTINUE;
      }
    }
    Format formatFromBundle = null;
    List<AudioPresentation> audioPresentations = null;
    for (int i = 0; i < extractorOutput.numberOfTracks; ++i) {
      int trackId = extractorOutput.trackOutputs.keyAt(i);
      Format format = extractorOutput.trackOutputs.get(trackId).lastFormat;
      if (format != null && format.sampleMimeType.equals(MimeTypes.AUDIO_AC4)) {
        assertThat(format).isNotNull();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          audioPresentations = format.audioPresentations;
        } else {
          Bundle formatAsBundle = format.toBundle();
          assertThat(formatAsBundle).isNotNull();
          formatFromBundle = Format.fromBundle(format.toBundle());
          audioPresentations = formatFromBundle.audioPresentations;
        }
      }
    }
    assertThat(audioPresentations).isNotNull();
    assertThat(refPresentations.size()).isEqualTo(audioPresentations.size());
    for (int i = 0; i < refPresentations.size(); i++) {
      assertThat(refPresentations.get(i)).isEqualTo((audioPresentations.get(i)));
    }
  }
}
