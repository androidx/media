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
package androidx.media3.container;

import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;

/**
 * Stores the raw payload of a format-specific box so it can be re-written during transmuxing
 * (stream copy) without re-encoding.
 *
 * <p>Some codecs store codec configuration in a container box whose bytes are not otherwise exposed
 * on {@link androidx.media3.common.Format} (for example the E-AC-3 {@code dec3} / EC3SpecificBox).
 * The extractor preserves that box's payload here so a muxer can reproduce the box verbatim.
 *
 * <p>The {@link #data} holds the box body only, i.e. excluding the 4-byte size and 4-byte type
 * header, and {@link #boxType} identifies which box the payload belongs to (for example {@code
 * dec3}).
 */
@UnstableApi
public final class FormatSpecificTransmuxingData implements Metadata.Entry {

  /** The four-character type of the box the {@link #data} belongs to, for example {@code dec3}. */
  public final String boxType;

  /** The raw box payload (the box body, excluding its 4-byte size and 4-byte type header). */
  public final byte[] data;

  /** Creates an instance. */
  public FormatSpecificTransmuxingData(String boxType, byte[] data) {
    this.boxType = boxType;
    this.data = data;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    FormatSpecificTransmuxingData other = (FormatSpecificTransmuxingData) obj;
    return boxType.equals(other.boxType) && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return 31 * boxType.hashCode() + Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return "FormatSpecificTransmuxingData: boxType=" + boxType;
  }
}
