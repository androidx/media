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
package androidx.media3.decoder.dav1d;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when a libdav1d decoder error occurs. */
@UnstableApi
public final class Dav1dDecoderException extends DecoderException {

  /**
   * Constructs a {@code Dav1dDecoderException} with the specified message.
   *
   * @param message The error message.
   */
  public Dav1dDecoderException(String message) {
    super(message);
  }

  /**
   * Constructs a {@code Dav1dDecoderException} with the specified message and cause.
   *
   * @param message The error message.
   * @param cause The cause of the exception.
   */
  public Dav1dDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
