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
import androidx.media3.decoder.vp9.VpxDecoder;

/** Class exercising native code in the VP9 module that relies on a correct proguard config. */
@VisibleForTesting(otherwise = NONE)
public final class Vp9ModuleProguard {

  private Vp9ModuleProguard() {}

  /**
   * Creates a {@link VpxDecoder} that relies on unobfuscated native method names and native code
   * calls to VideoDecoderOutputBuffer.
   */
  public static void createVpxDecoder() throws Exception {
    VpxDecoder decoder =
        new VpxDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 1,
            /* initialInputBufferSize= */ 1024,
            /* cryptoConfig= */ null,
            /* threads= */ 1);
    decoder.release();
  }
}
