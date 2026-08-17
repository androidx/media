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
package androidx.media3.extractor.metadata;

import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import com.google.common.primitives.Longs;

/** Stores the presentation timestamp of a thumbnail. */
@UnstableApi
public final class ThumbnailMetadata implements Metadata.Entry {

  /** The presentation timestamp of the thumbnail, in microseconds. */
  public final long presentationTimeUs;

  /**
   * Creates an instance.
   *
   * @param presentationTimeUs The presentation timestamp of the thumbnail, in microseconds.
   */
  public ThumbnailMetadata(long presentationTimeUs) {
    this.presentationTimeUs = presentationTimeUs;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ThumbnailMetadata that = (ThumbnailMetadata) o;
    return presentationTimeUs == that.presentationTimeUs;
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + Longs.hashCode(presentationTimeUs);
    return result;
  }

  @Override
  public String toString() {
    return "ThumbnailMetadata: presentationTimeUs=" + presentationTimeUs;
  }
}
