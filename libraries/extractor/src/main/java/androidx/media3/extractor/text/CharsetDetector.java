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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.nio.charset.Charset;

/** Detects the character encoding of subtitle byte data. */
@UnstableApi
public interface CharsetDetector {

  /**
   * Detects the character encoding of the requested range of {@code data}.
   *
   * <p>The requested range is not guaranteed to contain a complete subtitle file.
   *
   * @param data The subtitle byte data.
   * @param offset The start offset in {@code data}.
   * @param length The number of bytes to inspect.
   * @return The detected character encoding, or {@code null} if it could not be determined.
   */
  @Nullable
  Charset detect(byte[] data, int offset, int length);
}
