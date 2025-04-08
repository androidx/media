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
package androidx.media3.container;

import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;

/** Stores MP4 {@code alternate_group} info parsed from a {@code tkhd} box. */
@UnstableApi
public final class Mp4AlternateGroupData implements Metadata.Entry {

  public final int alternateGroup;

  public Mp4AlternateGroupData(int alternateGroup) {
    this.alternateGroup = alternateGroup;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Mp4AlternateGroupData)) {
      return false;
    }

    Mp4AlternateGroupData other = (Mp4AlternateGroupData) obj;
    return alternateGroup == other.alternateGroup;
  }

  @Override
  public int hashCode() {
    return alternateGroup;
  }

  @Override
  public String toString() {
    return "Mp4AlternateGroup: " + alternateGroup;
  }
}
