/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.List;
import java.util.Map;

/**
 * A forwarding {@link ExtractorsFactory} that forwards all methods to the underlying
 * implementation.
 */
@UnstableApi
public class ForwardingExtractorsFactory implements ExtractorsFactory {

  private final ExtractorsFactory factory;

  /**
   * Creates a forwarding {@link ExtractorsFactory}.
   *
   * @param factory The {@link ExtractorsFactory} to forward to.
   */
  public ForwardingExtractorsFactory(ExtractorsFactory factory) {
    this.factory = factory;
  }

  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  @Override
  public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(
      boolean textTrackTranscodingEnabled) {
    return factory.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled);
  }

  @Override
  public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
    return factory.setSubtitleParserFactory(subtitleParserFactory);
  }

  @Override
  public ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(
      @C.VideoCodecFlags int codecsToParseWithinGopSampleDependencies) {
    return factory.experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies);
  }

  @Override
  public Extractor[] createExtractors() {
    return factory.createExtractors();
  }

  @Override
  public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
    return factory.createExtractors(uri, responseHeaders);
  }
}
