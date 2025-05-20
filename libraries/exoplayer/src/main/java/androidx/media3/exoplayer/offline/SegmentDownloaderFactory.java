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
package androidx.media3.exoplayer.offline;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import java.util.concurrent.Executor;

/** A factory to create {@linkplain SegmentDownloader segment downloaders}. */
@UnstableApi
/* package */ interface SegmentDownloaderFactory {

  /**
   * Sets the {@link Executor} used to make requests for the media being downloaded. Providing an
   * {@link Executor} that uses multiple threads will speed up the download by allowing parts of it
   * to be executed in parallel.
   */
  SegmentDownloaderFactory setExecutor(Executor executor);

  /**
   * Sets the maximum difference of the start time of two segments, up to which the segments (of the
   * same URI) should be merged into a single download segment, in milliseconds.
   */
  SegmentDownloaderFactory setMaxMergedSegmentStartTimeDiffMs(long maxMergedSegmentStartTimeDiffMs);

  /** Sets the start position in microseconds that the download should start from. */
  SegmentDownloaderFactory setStartPositionUs(long startPositionUs);

  /**
   * Sets the duration in microseconds from the {@code startPositionUs} to be downloaded, or {@link
   * C#TIME_UNSET} if the media should be downloaded to the end.
   */
  SegmentDownloaderFactory setDurationUs(long durationUs);

  /**
   * Creates the segment downloader.
   *
   * @param mediaItem The {@link MediaItem} to be downloaded.
   */
  SegmentDownloader<?> create(MediaItem mediaItem);
}
