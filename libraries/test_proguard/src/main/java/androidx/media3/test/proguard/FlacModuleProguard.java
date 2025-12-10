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

import static androidx.annotation.VisibleForTesting.NONE;

import androidx.annotation.VisibleForTesting;
import androidx.media3.decoder.flac.FlacDecoder;
import com.google.common.collect.ImmutableList;

/** Class exercising native code in the FLAC module that relies on a correct proguard config. */
@VisibleForTesting(otherwise = NONE)
public final class FlacModuleProguard {

  private FlacModuleProguard() {}

  private static final ImmutableList<byte[]> FLAC_INITIALIZATION_DATA =
      ImmutableList.of(
          new byte[] {
            102, 76, 97, 67, -128, 0, 0, 34, 18, 0, 18, 0, 0, 20, -96, 0, 48, -109, 10, -66, 2, -16,
            0, 2, 3, -96, 86, -108, -21, -34, -3, 96, -43, -73, -78, 78, -101, -98, 52, -33, 26, 61
          });

  /**
   * Creates a {@link FlacDecoder} that relies on unobfuscated native method names and native code
   * calls to FlacDecoderJni, FlacStreamMetadata and PictureFrame.
   */
  public static void createFlacDecoder() throws Exception {
    FlacDecoder decoder =
        new FlacDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 1,
            /* maxInputBufferSize= */ 1024,
            FLAC_INITIALIZATION_DATA);
    decoder.release();
  }
}
