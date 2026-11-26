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
package androidx.media3.extractor.text;

import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.util.UnstableApi;

/**
 * A forwarding {@link SubtitleParser.Factory} that forwards all methods to the underlying
 * implementation.
 */
@UnstableApi
public class ForwardingSubtitleParserFactory implements SubtitleParser.Factory {

  private final SubtitleParser.Factory factory;

  /**
   * Creates a forwarding {@link SubtitleParser.Factory}.
   *
   * @param factory The {@link SubtitleParser.Factory} to forward to.
   */
  public ForwardingSubtitleParserFactory(SubtitleParser.Factory factory) {
    this.factory = factory;
  }

  @Override
  public boolean supportsFormat(Format format) {
    return factory.supportsFormat(format);
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior(Format format) {
    return factory.getCueReplacementBehavior(format);
  }

  @Override
  public SubtitleParser create(Format format) {
    return factory.create(format);
  }
}
