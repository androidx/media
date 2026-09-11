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
package androidx.media3.exoplayer.source;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.upstream.Allocator;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MediaSource} merging a content source with its sideloaded subtitle sources, applying the
 * {@linkplain MediaItem.SubtitleConfiguration.Builder#setTimeOffsetUs(long) time offsets} of the
 * subtitle configurations and handling {@link MediaItem} updates that change them.
 *
 * <p>Time offset changes are forwarded to the affected subtitle sources without interrupting
 * playback. Updates that change the subtitle configurations in any other way are rejected from
 * {@link #canUpdateMediaItem}, so that the player falls back to re-preparing the item.
 */
/* package */ final class SideloadedSubtitlesMediaSource extends WrappingMediaSource {

  private final TimeOffsetMediaSource[] subtitleSources;

  /**
   * Creates the media source.
   *
   * @param contentMediaSource The content {@link MediaSource}.
   * @param subtitleConfigurations The {@link MediaItem.SubtitleConfiguration} instances of the
   *     sideloaded subtitles.
   * @param subtitleMediaSources The sideloaded subtitle {@link MediaSource} instances, in the same
   *     order as {@code subtitleConfigurations}.
   */
  public SideloadedSubtitlesMediaSource(
      MediaSource contentMediaSource,
      List<MediaItem.SubtitleConfiguration> subtitleConfigurations,
      MediaSource[] subtitleMediaSources) {
    this(createTimeOffsetSources(subtitleConfigurations, subtitleMediaSources), contentMediaSource);
  }

  private SideloadedSubtitlesMediaSource(
      TimeOffsetMediaSource[] subtitleSources, MediaSource contentMediaSource) {
    super(createMergingMediaSource(contentMediaSource, subtitleSources));
    this.subtitleSources = subtitleSources;
  }

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return super.canUpdateMediaItem(mediaItem) && canUpdateSubtitleConfigurations(mediaItem);
  }

  @Override
  public void updateMediaItem(MediaItem mediaItem) {
    super.updateMediaItem(mediaItem);
    ImmutableList<MediaItem.SubtitleConfiguration> newSubtitleConfigurations =
        checkNotNull(mediaItem.localConfiguration).subtitleConfigurations;
    for (int i = 0; i < subtitleSources.length; i++) {
      subtitleSources[i].setTimeOffsetUs(newSubtitleConfigurations.get(i).timeOffsetUs);
    }
  }

  private boolean canUpdateSubtitleConfigurations(MediaItem mediaItem) {
    @Nullable MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
    @Nullable
    MediaItem.LocalConfiguration currentLocalConfiguration = getMediaItem().localConfiguration;
    if (localConfiguration == null
        || currentLocalConfiguration == null
        || localConfiguration.subtitleConfigurations.size()
            != currentLocalConfiguration.subtitleConfigurations.size()) {
      return false;
    }
    for (int i = 0; i < currentLocalConfiguration.subtitleConfigurations.size(); i++) {
      if (!equalsIgnoringTimeOffset(
          localConfiguration.subtitleConfigurations.get(i),
          currentLocalConfiguration.subtitleConfigurations.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean equalsIgnoringTimeOffset(
      MediaItem.SubtitleConfiguration subtitleConfiguration,
      MediaItem.SubtitleConfiguration other) {
    return subtitleConfiguration
        .buildUpon()
        .setTimeOffsetUs(other.timeOffsetUs)
        .build()
        .equals(other);
  }

  private static TimeOffsetMediaSource[] createTimeOffsetSources(
      List<MediaItem.SubtitleConfiguration> subtitleConfigurations,
      MediaSource[] subtitleMediaSources) {
    checkArgument(subtitleConfigurations.size() == subtitleMediaSources.length);
    TimeOffsetMediaSource[] timeOffsetSources =
        new TimeOffsetMediaSource[subtitleMediaSources.length];
    for (int i = 0; i < subtitleMediaSources.length; i++) {
      timeOffsetSources[i] =
          new TimeOffsetMediaSource(
              subtitleMediaSources[i], subtitleConfigurations.get(i).timeOffsetUs);
    }
    return timeOffsetSources;
  }

  private static MergingMediaSource createMergingMediaSource(
      MediaSource contentMediaSource, TimeOffsetMediaSource[] subtitleSources) {
    MediaSource[] mediaSources = new MediaSource[subtitleSources.length + 1];
    mediaSources[0] = contentMediaSource;
    System.arraycopy(subtitleSources, 0, mediaSources, 1, subtitleSources.length);
    return new MergingMediaSource(mediaSources);
  }

  /**
   * A {@link WrappingMediaSource} that applies a time offset to the media periods of the wrapped
   * source.
   */
  private static final class TimeOffsetMediaSource extends WrappingMediaSource {

    private final List<TimeOffsetMediaPeriod> activeMediaPeriods;
    private long timeOffsetUs;

    private TimeOffsetMediaSource(MediaSource mediaSource, long timeOffsetUs) {
      super(mediaSource);
      this.timeOffsetUs = timeOffsetUs;
      this.activeMediaPeriods = new ArrayList<>();
    }

    private void setTimeOffsetUs(long timeOffsetUs) {
      this.timeOffsetUs = timeOffsetUs;
      for (int i = 0; i < activeMediaPeriods.size(); i++) {
        activeMediaPeriods.get(i).updateTimeOffsetUs(timeOffsetUs);
      }
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
      TimeOffsetMediaPeriod mediaPeriod =
          new TimeOffsetMediaPeriod(
              super.createPeriod(id, allocator, startPositionUs - timeOffsetUs), timeOffsetUs);
      activeMediaPeriods.add(mediaPeriod);
      return mediaPeriod;
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
      TimeOffsetMediaPeriod timeOffsetMediaPeriod = (TimeOffsetMediaPeriod) mediaPeriod;
      activeMediaPeriods.remove(timeOffsetMediaPeriod);
      super.releasePeriod(timeOffsetMediaPeriod.getWrappedMediaPeriod());
    }
  }
}
