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
import androidx.media3.decoder.opus.OpusDecoder;
import com.google.common.collect.ImmutableList;

/** Class exercising native code in the OPUS module that relies on a correct proguard config. */
@VisibleForTesting(otherwise = NONE)
public final class OpusModuleProguard {

  private OpusModuleProguard() {}

  private static final ImmutableList<byte[]> OPUS_INITIALIZATION_DATA =
      ImmutableList.of(
          new byte[] {79, 112, 117, 115, 72, 101, 97, 100, 0, 2, 1, 56, 0, 0, -69, -128, 0, 0, 0});

  /**
   * Creates an {@link OpusDecoder} that relies on unobfuscated native method names and native code
   * calls to SimpleOutputBuffer.
   */
  public static void createOpusDecoder() throws Exception {
    OpusDecoder decoder =
        new OpusDecoder(
            /* numInputBuffers= */ 1,
            /* numOutputBuffers= */ 1,
            /* initialInputBufferSize= */ 1024,
            OPUS_INITIALIZATION_DATA,
            /* cryptoConfig= */ null,
            /* outputFloat= */ false);
    decoder.release();
  }
}
