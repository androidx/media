/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/** Decodes and renders audio using the native IAMF decoder. */
public class LibiamfAudioRenderer extends DecoderAudioRenderer<IamfDecoder> {
  public LibiamfAudioRenderer() {}

  @Override
  protected int supportsFormatInternal(Format format) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected IamfDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws DecoderException {
    throw new UnsupportedOperationException();
  }

  @Override
  protected Format getOutputFormat(IamfDecoder decoder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getName() {
    throw new UnsupportedOperationException();
  }
}
