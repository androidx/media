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
 */
package androidx.media3.test.proguard;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.flac.FlacExtractor;
import java.io.EOFException;
import java.io.IOException;

/**
 * Class exercising reflection code in the Extractor module that relies on a correct proguard
 * config.
 *
 * <p>Note on adding tests: Verify that tests fail without the relevant proguard config. Be careful
 * with adding new direct class references that may let other tests pass without proguard config.
 */
public final class ExtractorModuleProguard {

  private ExtractorModuleProguard() {}

  /** Creates an extension FlacExtractor with {@link DefaultExtractorsFactory}. */
  public static void createLibFlacExtractorWithDefaultExtractorsFactory(Context context)
      throws Exception {
    DataSource flacDataSource = new AssetDataSource(context);
    try {
      long length =
          flacDataSource.open(new DataSpec(Uri.parse("/android_asset/media/flac/bear.flac")));
      ExtractorInput flacExtractorInput =
          new DefaultExtractorInput(flacDataSource, /* position= */ 0, length);
      for (Extractor extractor : new DefaultExtractorsFactory().createExtractors()) {
        try {
          if (extractor.sniff(flacExtractorInput)) {
            if (extractor.getUnderlyingImplementation() instanceof FlacExtractor) {
              // Ignore the bundled extractor, we are only interested in the extension extractor.
              // We can't use instanceof with the extension extractor to prevent including the class
              // in the apk directly.
              continue;
            }
            // Success.
            return;
          }
        } catch (EOFException e) {
          // Ignore. May happen for non-FLAC extractors.
        }
        flacExtractorInput.resetPeekPosition();
      }
      throw new IllegalStateException();
    } finally {
      flacDataSource.close();
    }
  }

  /** Creates an extension MidiExtractor with {@link DefaultExtractorsFactory}. */
  public static void createMidiExtractorWithDefaultExtractorsFactory(Context context)
      throws Exception {
    DataSource dataSource = new AssetDataSource(context);
    try {
      long length =
          dataSource.open(new DataSpec(Uri.parse("/android_asset/media/midi/Twinkle.mid")));
      ExtractorInput extractorInput =
          new DefaultExtractorInput(dataSource, /* position= */ 0, length);
      for (Extractor extractor : new DefaultExtractorsFactory().createExtractors()) {
        try {
          if (extractor.sniff(extractorInput)) {
            // Success.
            return;
          }
        } catch (EOFException e) {
          // Some extractors read past the end of the input.
        } finally {
          extractorInput.resetPeekPosition();
        }
      }
      // Should never reach this point.
      throw new IllegalStateException();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } finally {
      dataSource.close();
    }
  }
}
