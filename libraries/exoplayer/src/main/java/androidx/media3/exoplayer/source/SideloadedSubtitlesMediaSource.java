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
import androidx.media3.common.Timeline;
import androidx.media3.datasource.TransferListener;
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
/* package */ final class SideloadedSubtitlesMediaSource extends CompositeMediaSource<Integer> {

  private static final int CONTENT_MEDIA_SOURCE_INDEX = 0;
  private static final int FIRST_SUBTITLE_MEDIA_SOURCE_INDEX = 1;

  private final MediaSource contentMediaSource;
  private final MediaSource[] subtitleMediaSources;
  private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;
  private final long[] timeOffsetsUs;
  private final List<List<TimeOffsetMediaPeriod>> activeMediaPeriods;

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
    checkArgument(subtitleConfigurations.size() == subtitleMediaSources.length);
    this.contentMediaSource = contentMediaSource;
    this.subtitleMediaSources = subtitleMediaSources;
    this.compositeSequenceableLoaderFactory = new DefaultCompositeSequenceableLoaderFactory();
    this.timeOffsetsUs = new long[subtitleConfigurations.size()];
    this.activeMediaPeriods = new ArrayList<>(subtitleMediaSources.length);
    for (int i = 0; i < subtitleConfigurations.size(); i++) {
      this.timeOffsetsUs[i] = subtitleConfigurations.get(i).timeOffsetUs;
      this.activeMediaPeriods.add(new ArrayList<>());
    }
  }

  @Override
  public MediaItem getMediaItem() {
    return contentMediaSource.getMediaItem();
  }

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return contentMediaSource.canUpdateMediaItem(mediaItem)
        && canUpdateSubtitleConfigurations(mediaItem);
  }

  @Override
  public void updateMediaItem(MediaItem mediaItem) {
    contentMediaSource.updateMediaItem(mediaItem);
    ImmutableList<MediaItem.SubtitleConfiguration> newSubtitleConfigurations =
        checkNotNull(mediaItem.localConfiguration).subtitleConfigurations;
    for (int i = 0; i < timeOffsetsUs.length; i++) {
      long newTimeOffsetUs = newSubtitleConfigurations.get(i).timeOffsetUs;
      timeOffsetsUs[i] = newTimeOffsetUs;
      for (int j = 0; j < activeMediaPeriods.get(i).size(); j++) {
        activeMediaPeriods.get(i).get(j).updateTimeOffsetUs(newTimeOffsetUs);
      }
    }
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    super.prepareSourceInternal(mediaTransferListener);
    for (int i = 0; i < subtitleMediaSources.length; i++) {
      prepareChildSource(FIRST_SUBTITLE_MEDIA_SOURCE_INDEX + i, subtitleMediaSources[i]);
    }
    prepareChildSource(CONTENT_MEDIA_SOURCE_INDEX, contentMediaSource);
  }

  @Override
  protected void onChildSourceInfoRefreshed(
      Integer childSourceId, MediaSource mediaSource, Timeline newTimeline) {
    if (childSourceId == CONTENT_MEDIA_SOURCE_INDEX) {
      refreshSourceInfo(newTimeline);
    }
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    MediaPeriod[] periods = new MediaPeriod[subtitleMediaSources.length + 1];
    periods[CONTENT_MEDIA_SOURCE_INDEX] =
        contentMediaSource.createPeriod(id, allocator, startPositionUs);
    for (int i = 0; i < subtitleMediaSources.length; i++) {
      TimeOffsetMediaPeriod period =
          new TimeOffsetMediaPeriod(
              subtitleMediaSources[i].createPeriod(
                  id, allocator, startPositionUs - timeOffsetsUs[i]),
              timeOffsetsUs[i]);
      periods[FIRST_SUBTITLE_MEDIA_SOURCE_INDEX + i] = period;
      activeMediaPeriods.get(i).add(period);
    }
    return new MergingMediaPeriod(
        compositeSequenceableLoaderFactory, new long[periods.length], periods);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    MergingMediaPeriod mergingMediaPeriod = (MergingMediaPeriod) mediaPeriod;
    contentMediaSource.releasePeriod(mergingMediaPeriod.getChildPeriod(CONTENT_MEDIA_SOURCE_INDEX));
    for (int i = 0; i < subtitleMediaSources.length; i++) {
      TimeOffsetMediaPeriod timeOffsetMediaPeriod =
          (TimeOffsetMediaPeriod)
              mergingMediaPeriod.getChildPeriod(FIRST_SUBTITLE_MEDIA_SOURCE_INDEX + i);
      activeMediaPeriods.get(i).remove(timeOffsetMediaPeriod);
      subtitleMediaSources[i].releasePeriod(timeOffsetMediaPeriod.getWrappedMediaPeriod());
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
}
