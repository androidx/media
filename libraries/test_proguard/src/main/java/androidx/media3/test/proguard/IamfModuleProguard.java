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
import androidx.media3.decoder.iamf.IamfDecoder;
import com.google.common.collect.ImmutableList;

/** Class executing native code in the IAMF module that relies on a correct proguard config. */
@VisibleForTesting(otherwise = NONE)
public final class IamfModuleProguard {

  private IamfModuleProguard() {}

  // This is a valid initialization data for the IAMF decoder taken from a test file.
  private static final ImmutableList<byte[]> IAMF_INITIALIZATION_DATA =
      ImmutableList.of(
          new byte[] {
            -8, 6, 105, 97, 109, 102, 0, 0, 0, 15, -56, 1, 105, 112, 99, 109, 64, 0, 0, 1, 16, 0, 0,
            62, -128, 8, 12, -84, 2, 0, -56, 1, 1, 0, 0, 32, 16, 1, 1, 16, 78, 42, 1, 101, 110, 45,
            117, 115, 0, 116, 101, 115, 116, 95, 109, 105, 120, 95, 112, 114, 101, 115, 0, 1, 1,
            -84, 2, 116, 101, 115, 116, 95, 115, 117, 98, 95, 109, 105, 120, 95, 48, 95, 97, 117,
            100, 105, 111, 95, 101, 108, 101, 109, 101, 110, 116, 95, 48, 0, 0, 0, 100, -128, 125,
            -128, 0, 0, 100, -128, 125, -128, 0, 0, 1, -128, 0, -54, 81, -51, -79
          });

  /**
   * Creates an {@link IamfDecoder} that relies on unobfuscated native method names and native code
   * calls.
   */
  public static void createIamfDecoder() throws Exception {
    IamfDecoder decoder =
        new IamfDecoder(IAMF_INITIALIZATION_DATA, /* spatializationSupported= */ false);
    decoder.release();
  }
}
