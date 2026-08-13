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
import java.util.List;

/**
 * A {@link MediaSource} wrapping a merged content source and its sideloaded subtitle sources, that
 * handles {@link MediaItem} updates affecting the {@linkplain MediaItem.SubtitleConfiguration
 * subtitle configurations}.
 *
 * <p>{@linkplain MediaItem.SubtitleConfiguration.Builder#setTimeOffsetUs(long) Time offset} changes
 * are forwarded to the corresponding {@link TimeOffsetMediaSource} instances without interrupting
 * playback. Updates that change the subtitle configurations in any other way are rejected from
 * {@link #canUpdateMediaItem}, so that the player falls back to re-preparing the item.
 */
/* package */ final class SideloadedSubtitlesMediaSource extends WrappingMediaSource {

  private final TimeOffsetMediaSource[] subtitleSources;

  private List<MediaItem.SubtitleConfiguration> subtitleConfigurations;

  /**
   * Creates the media source.
   *
   * @param mediaSource The wrapped {@link MediaSource} merging the content source with one {@link
   *     TimeOffsetMediaSource} per subtitle configuration.
   * @param subtitleConfigurations The {@link MediaItem.SubtitleConfiguration} instances the
   *     subtitle sources were created from.
   * @param subtitleSources The {@link TimeOffsetMediaSource} instances wrapping the sideloaded
   *     subtitle sources, in the same order as {@code subtitleConfigurations}.
   */
  public SideloadedSubtitlesMediaSource(
      MediaSource mediaSource,
      List<MediaItem.SubtitleConfiguration> subtitleConfigurations,
      TimeOffsetMediaSource[] subtitleSources) {
    super(mediaSource);
    checkArgument(subtitleConfigurations.size() == subtitleSources.length);
    this.subtitleConfigurations = subtitleConfigurations;
    this.subtitleSources = subtitleSources;
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
    for (int i = 0; i < subtitleSources.length; i++) {
      subtitleSources[i].setTimeOffsetUs(newSubtitleConfigurations.get(i).timeOffsetUs);
    }
    subtitleConfigurations = newSubtitleConfigurations;
  }

  private boolean canUpdateSubtitleConfigurations(MediaItem mediaItem) {
    @Nullable MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
    if (localConfiguration == null
        || localConfiguration.subtitleConfigurations.size() != subtitleConfigurations.size()) {
      return false;
    }
    for (int i = 0; i < subtitleConfigurations.size(); i++) {
      if (!equalsIgnoringTimeOffset(
          localConfiguration.subtitleConfigurations.get(i), subtitleConfigurations.get(i))) {
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
