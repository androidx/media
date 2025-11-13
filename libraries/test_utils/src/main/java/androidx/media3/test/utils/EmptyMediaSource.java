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
package androidx.media3.test.utils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.upstream.Allocator;

/** MediaSource producing an empty {@link Timeline}. */
@UnstableApi
public final class EmptyMediaSource extends BaseMediaSource {

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    refreshSourceInfo(Timeline.EMPTY);
  }

  @Override
  protected void releaseSourceInternal() {}

  @Override
  public Timeline getInitialTimeline() {
    return Timeline.EMPTY;
  }

  @Override
  public boolean isSingleWindow() {
    return false;
  }

  @Override
  public MediaItem getMediaItem() {
    return MediaItem.EMPTY;
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() {}

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return false;
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    throw new IllegalStateException();
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    throw new IllegalStateException();
  }
}
