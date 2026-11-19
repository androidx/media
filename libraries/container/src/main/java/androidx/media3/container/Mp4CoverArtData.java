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
package androidx.media3.container;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;

/**
 * Stores cover art for an MP4 file.
 *
 * <p>The image is written as an iTunes-style {@code covr} atom, the location that {@link
 * android.media.MediaMetadataRetriever#getEmbeddedPicture()} reads. Platform thumbnail generators
 * prefer this picture over a frame decoded from the video.
 */
@UnstableApi
public final class Mp4CoverArtData implements Metadata.Entry {

  /** The {@code data} atom type indicator for a JPEG image. */
  public static final int TYPE_JPEG = 13;

  /** The {@code data} atom type indicator for a PNG image. */
  public static final int TYPE_PNG = 14;

  /** The encoded image bytes. */
  public final byte[] data;

  /** The {@code data} atom type indicator, either {@link #TYPE_JPEG} or {@link #TYPE_PNG}. */
  public final int dataType;

  /**
   * Creates an instance.
   *
   * @param data The encoded image bytes.
   * @param dataType Either {@link #TYPE_JPEG} or {@link #TYPE_PNG}.
   */
  public Mp4CoverArtData(byte[] data, int dataType) {
    checkArgument(data.length > 0);
    checkArgument(dataType == TYPE_JPEG || dataType == TYPE_PNG);
    this.data = data;
    this.dataType = dataType;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Mp4CoverArtData other = (Mp4CoverArtData) obj;
    return dataType == other.dataType && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(data) + dataType;
  }

  @Override
  public String toString() {
    return "Mp4CoverArtData: type=" + dataType + ", size=" + data.length;
  }
}
