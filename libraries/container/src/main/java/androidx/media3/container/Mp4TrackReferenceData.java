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

import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Stores MP4 track reference information parsed from a {@code TrackReferenceTypeBox} inside a
 * {@code tref} box (ISO/IEC 14496-12 section 8.3.3).
 */
@UnstableApi
public final class Mp4TrackReferenceData implements Metadata.Entry {

  /**
   * The fourcc integer representing the track reference type (for example, {@link
   * Mp4Box#TYPE_rndr}).
   */
  public final int referenceType;

  /**
   * The non-empty list of {@code track_ID} values from the {@code tkhd} boxes of the referenced
   * tracks.
   */
  public final ImmutableList<Integer> referencedTrackIds;

  /**
   * Creates an instance.
   *
   * @param referenceType The fourcc integer representing the track reference type (for example,
   *     {@link Mp4Box#TYPE_rndr}).
   * @param referencedTrackIds The non-empty list of {@code track_ID} values from the {@code tkhd}
   *     boxes of the referenced tracks.
   */
  public Mp4TrackReferenceData(int referenceType, List<Integer> referencedTrackIds) {
    checkArgument(!referencedTrackIds.isEmpty(), "referencedTrackIds must not be empty");
    this.referenceType = referenceType;
    this.referencedTrackIds = ImmutableList.copyOf(referencedTrackIds);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Mp4TrackReferenceData)) {
      return false;
    }
    Mp4TrackReferenceData other = (Mp4TrackReferenceData) obj;
    return referenceType == other.referenceType
        && referencedTrackIds.equals(other.referencedTrackIds);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + referenceType;
    result = 31 * result + referencedTrackIds.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "Mp4TrackReference: type="
        + Mp4Box.getBoxTypeString(referenceType)
        + ", trackIds="
        + referencedTrackIds;
  }
}
