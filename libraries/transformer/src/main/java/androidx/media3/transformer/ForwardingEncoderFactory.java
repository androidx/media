/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import android.media.metrics.LogSessionId;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.transformer.Codec.EncoderFactory;

/**
 * An {@link EncoderFactory} implementation that forwards all calls to another {@link
 * EncoderFactory} instance.
 */
/* package */ class ForwardingEncoderFactory implements EncoderFactory {

  private final EncoderFactory factory;

  public ForwardingEncoderFactory(EncoderFactory factory) {
    this.factory = factory;
  }

  @Override
  public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId)
      throws ExportException {
    return factory.createForAudioEncoding(format, logSessionId);
  }

  @Override
  public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
      throws ExportException {
    return factory.createForVideoEncoding(format, logSessionId);
  }

  @Override
  public boolean isVideoFormatSupported(Format format) {
    return factory.isVideoFormatSupported(format);
  }

  @Override
  public boolean audioNeedsEncoding() {
    return factory.audioNeedsEncoding();
  }

  @Override
  public boolean videoNeedsEncoding() {
    return factory.videoNeedsEncoding();
  }
}
