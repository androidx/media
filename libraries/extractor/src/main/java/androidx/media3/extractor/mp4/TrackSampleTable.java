/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.mp4;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/** Sample table for a track in an MP4 file. */
@UnstableApi
public final class TrackSampleTable {

  /** The track corresponding to this sample table. */
  public final Track track;

  /** Number of samples. */
  public final int sampleCount;

  /** Sample offsets in bytes. */
  public final long[] offsets;

  /** Sample sizes in bytes. */
  public final int[] sizes;

  /** Maximum sample size in {@link #sizes}. */
  public final int maximumSize;

  /** Sample timestamps in microseconds. */
  public final long[] timestampsUs;

  /** Sample flags. */
  public final int[] flags;

  /**
   * The indices of sync samples, sorted in ascending order. This array is only populated if {@link
   * #hasOnlySyncSamples} is {@code false}.
   */
  public final int[] syncSampleIndices;

  /** The duration of the track sample table in microseconds. */
  public final long durationUs;

  /** Whether all samples in the track are sync samples. */
  public final boolean hasOnlySyncSamples;

  public TrackSampleTable(
      Track track,
      long[] offsets,
      int[] sizes,
      int maximumSize,
      long[] timestampsUs,
      int[] flags,
      int[] syncSampleIndices,
      boolean hasOnlySyncSamples,
      long durationUs,
      int sampleCount) {
    checkArgument(sizes.length == timestampsUs.length);
    checkArgument(offsets.length == timestampsUs.length);
    checkArgument(flags.length == timestampsUs.length);

    this.track = track;
    this.offsets = offsets;
    this.sizes = sizes;
    this.maximumSize = maximumSize;
    this.timestampsUs = timestampsUs;
    this.flags = flags;
    this.syncSampleIndices = syncSampleIndices;
    this.hasOnlySyncSamples = hasOnlySyncSamples;
    this.durationUs = durationUs;
    this.sampleCount = sampleCount;
    if (flags.length > 0) {
      flags[flags.length - 1] |= C.BUFFER_FLAG_LAST_SAMPLE;
    }
  }

  /**
   * Returns the sample index of the closest synchronization sample at or before the given
   * timestamp, if one is available.
   *
   * @param timeUs Timestamp adjacent to which to find a synchronization sample.
   * @return Index of the synchronization sample, or {@link C#INDEX_UNSET} if none.
   */
  public int getIndexOfEarlierOrEqualSynchronizationSample(long timeUs) {
    if (hasOnlySyncSamples) {
      return Util.binarySearchFloor(
          timestampsUs, timeUs, /* inclusive= */ true, /* stayInBounds= */ false);
    }

    int low = 0;
    int high = syncSampleIndices.length - 1;
    int index = C.INDEX_UNSET;

    while (low <= high) {
      int mid = low + ((high - low) / 2);
      long currentTimestamp = timestampsUs[syncSampleIndices[mid]];

      if (currentTimestamp <= timeUs) {
        index = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }

    if (index == C.INDEX_UNSET) {
      return C.INDEX_UNSET;
    }

    long targetTimestamp = timestampsUs[syncSampleIndices[index]];
    // Only scan backwards if the found sample is an EXACT match for the search time.
    if (targetTimestamp == timeUs) {
      while (index > 0 && timestampsUs[syncSampleIndices[index - 1]] == targetTimestamp) {
        index--;
      }
    }

    return syncSampleIndices[index];
  }

  /**
   * Returns the sample index of the closest synchronization sample at or after the given timestamp,
   * if one is available.
   *
   * @param timeUs Timestamp adjacent to which to find a synchronization sample.
   * @return index Index of the synchronization sample, or {@link C#INDEX_UNSET} if none.
   */
  public int getIndexOfLaterOrEqualSynchronizationSample(long timeUs) {
    if (hasOnlySyncSamples) {
      return Util.binarySearchCeil(
          timestampsUs, timeUs, /* inclusive= */ true, /* stayInBounds= */ false);
    }

    int low = 0;
    int high = syncSampleIndices.length - 1;
    int index = C.INDEX_UNSET;

    while (low <= high) {
      int mid = low + ((high - low) / 2);
      long currentTimestamp = timestampsUs[syncSampleIndices[mid]];

      if (currentTimestamp >= timeUs) {
        index = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }

    if (index == C.INDEX_UNSET) {
      return C.INDEX_UNSET;
    }

    long targetTimestamp = timestampsUs[syncSampleIndices[index]];
    // Only scan forwards if the found sample is an EXACT match for the search time.
    if (targetTimestamp == timeUs) {
      while (index < syncSampleIndices.length - 1
          && timestampsUs[syncSampleIndices[index + 1]] == targetTimestamp) {
        index++;
      }
    }

    return syncSampleIndices[index];
  }
}
