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

package androidx.media3.transformer;

import androidx.media3.common.Timeline;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.WrappingMediaSource;
import androidx.media3.exoplayer.upstream.Allocator;

/** A {@link MediaSource} that applies a {@link SpeedProvider} to all timestamps. */
/* package */ final class SpeedChangingMediaSource extends WrappingMediaSource {

  private final SpeedProvider speedProvider;
  private final long durationUs;

  public SpeedChangingMediaSource(
      MediaSource mediaSource, SpeedProvider speedProvider, long durationUs) {
    super(mediaSource);
    this.speedProvider = speedProvider;
    this.durationUs = durationUs;
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    return new SpeedProviderMediaPeriod(
        super.createPeriod(id, allocator, startPositionUs), speedProvider);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    MediaPeriod wrappedPeriod = ((SpeedProviderMediaPeriod) mediaPeriod).getWrappedMediaPeriod();
    super.releasePeriod(wrappedPeriod);
  }

  @Override
  protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
    Timeline timeline =
        new ForwardingTimeline(newTimeline) {
          @Override
          public Window getWindow(
              int windowIndex, Window window, long defaultPositionProjectionUs) {
            Window wrappedWindow =
                newTimeline.getWindow(windowIndex, window, defaultPositionProjectionUs);
            wrappedWindow.durationUs = durationUs;
            return wrappedWindow;
          }

          @Override
          public Period getPeriod(int periodIndex, Period period, boolean setIds) {
            Period wrappedPeriod = newTimeline.getPeriod(periodIndex, period, setIds);
            wrappedPeriod.durationUs = durationUs;
            return wrappedPeriod;
          }
        };
    super.onChildSourceInfoRefreshed(timeline);
  }
}
