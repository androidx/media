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

  private final long[] timeOffsetsUs;
  private final ArrayList<TimeOffsetMediaPeriod>[] activeMediaPeriods;

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
    this(
        contentMediaSource,
        subtitleConfigurations,
        subtitleMediaSources,
        createTimeOffsetsUs(subtitleConfigurations),
        createActiveMediaPeriodsArray(subtitleMediaSources.length));
  }

  private SideloadedSubtitlesMediaSource(
      MediaSource contentMediaSource,
      List<MediaItem.SubtitleConfiguration> subtitleConfigurations,
      MediaSource[] subtitleMediaSources,
      long[] timeOffsetsUs,
      ArrayList<TimeOffsetMediaPeriod>[] activeMediaPeriods) {
    super(
        createMergingMediaSource(
            contentMediaSource, subtitleMediaSources, timeOffsetsUs, activeMediaPeriods));
    this.timeOffsetsUs = timeOffsetsUs;
    this.activeMediaPeriods = activeMediaPeriods;
  }

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return super.canUpdateMediaItem(mediaItem) && canUpdateSubtitleConfigurations(mediaItem);
  }

  @Override
  public void updateMediaItem(MediaItem mediaItem) {
    super.updateMediaItem(mediaItem);
    List<MediaItem.SubtitleConfiguration> newSubtitleConfigurations =
        checkNotNull(mediaItem.localConfiguration).subtitleConfigurations;
    for (int i = 0; i < timeOffsetsUs.length; i++) {
      long newTimeOffsetUs = newSubtitleConfigurations.get(i).timeOffsetUs;
      timeOffsetsUs[i] = newTimeOffsetUs;
      for (int j = 0; j < activeMediaPeriods[i].size(); j++) {
        activeMediaPeriods[i].get(j).updateTimeOffsetUs(newTimeOffsetUs);
      }
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

  private static long[] createTimeOffsetsUs(
      List<MediaItem.SubtitleConfiguration> subtitleConfigurations) {
    long[] timeOffsetsUs = new long[subtitleConfigurations.size()];
    for (int i = 0; i < subtitleConfigurations.size(); i++) {
      timeOffsetsUs[i] = subtitleConfigurations.get(i).timeOffsetUs;
    }
    return timeOffsetsUs;
  }

  @SuppressWarnings("unchecked")
  private static ArrayList<TimeOffsetMediaPeriod>[] createActiveMediaPeriodsArray(int length) {
    ArrayList<TimeOffsetMediaPeriod>[] array = new ArrayList[length];
    for (int i = 0; i < length; i++) {
      array[i] = new ArrayList<>();
    }
    return array;
  }

  private static MergingMediaSource createMergingMediaSource(
      MediaSource contentMediaSource,
      MediaSource[] subtitleMediaSources,
      long[] timeOffsetsUs,
      ArrayList<TimeOffsetMediaPeriod>[] activeMediaPeriods) {
    checkArgument(subtitleMediaSources.length == timeOffsetsUs.length);
    MediaSource[] mediaSources = new MediaSource[subtitleMediaSources.length + 1];
    mediaSources[0] = contentMediaSource;
    for (int i = 0; i < subtitleMediaSources.length; i++) {
      int subtitleIndex = i;
      mediaSources[i + 1] =
          new WrappingMediaSource(subtitleMediaSources[i]) {
            @Override
            public MediaPeriod createPeriod(
                MediaPeriodId id, Allocator allocator, long startPositionUs) {
              TimeOffsetMediaPeriod mediaPeriod =
                  new TimeOffsetMediaPeriod(
                      mediaSource.createPeriod(
                          id, allocator, startPositionUs - timeOffsetsUs[subtitleIndex]),
                      timeOffsetsUs[subtitleIndex]);
              activeMediaPeriods[subtitleIndex].add(mediaPeriod);
              return mediaPeriod;
            }

            @Override
            public void releasePeriod(MediaPeriod mediaPeriod) {
              TimeOffsetMediaPeriod timeOffsetMediaPeriod = (TimeOffsetMediaPeriod) mediaPeriod;
              activeMediaPeriods[subtitleIndex].remove(timeOffsetMediaPeriod);
              mediaSource.releasePeriod(timeOffsetMediaPeriod.getWrappedMediaPeriod());
            }
          };
    }
    return new MergingMediaSource(mediaSources);
  }
}
