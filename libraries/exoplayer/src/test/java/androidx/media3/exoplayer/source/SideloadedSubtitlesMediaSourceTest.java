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

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SideloadedSubtitlesMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class SideloadedSubtitlesMediaSourceTest {

  private static final String URI_MEDIA = "http://example.com/media.mp4";
  private static final String URI_TEXT = "http://example.com/subtitles.vtt";

  @Test
  public void canUpdateMediaItem_withChangedSubtitleTimeOffset_returnsTrue() {
    MediaItem.SubtitleConfiguration subtitleConfiguration =
        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(URI_TEXT))
            .setMimeType(MimeTypes.APPLICATION_TTML)
            .setLanguage("en")
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(URI_MEDIA)
            .setSubtitleConfigurations(ImmutableList.of(subtitleConfiguration))
            .build();
    FakeMediaSource contentMediaSource = new FakeMediaSource();
    contentMediaSource.setCanUpdateMediaItems(true);
    contentMediaSource.updateMediaItem(mediaItem);
    MediaSource sideloadedSubtitlesMediaSource =
        new SideloadedSubtitlesMediaSource(
            contentMediaSource,
            ImmutableList.of(subtitleConfiguration),
            new MediaSource[] {new FakeMediaSource()});

    MediaItem updatedMediaItem =
        mediaItem
            .buildUpon()
            .setSubtitleConfigurations(
                ImmutableList.of(
                    subtitleConfiguration.buildUpon().setTimeOffsetUs(1_000_000).build()))
            .build();

    assertThat(sideloadedSubtitlesMediaSource.canUpdateMediaItem(updatedMediaItem)).isTrue();
  }

  @Test
  public void canUpdateMediaItem_withStructurallyChangedSubtitleConfigurations_returnsFalse() {
    MediaItem.SubtitleConfiguration subtitleConfiguration =
        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(URI_TEXT))
            .setMimeType(MimeTypes.APPLICATION_TTML)
            .setLanguage("en")
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(URI_MEDIA)
            .setSubtitleConfigurations(ImmutableList.of(subtitleConfiguration))
            .build();
    FakeMediaSource contentMediaSource = new FakeMediaSource();
    contentMediaSource.setCanUpdateMediaItems(true);
    contentMediaSource.updateMediaItem(mediaItem);
    MediaSource sideloadedSubtitlesMediaSource =
        new SideloadedSubtitlesMediaSource(
            contentMediaSource,
            ImmutableList.of(subtitleConfiguration),
            new MediaSource[] {new FakeMediaSource()});

    MediaItem mediaItemWithChangedLanguage =
        mediaItem
            .buildUpon()
            .setSubtitleConfigurations(
                ImmutableList.of(subtitleConfiguration.buildUpon().setLanguage("de").build()))
            .build();
    MediaItem mediaItemWithoutSubtitles =
        mediaItem.buildUpon().setSubtitleConfigurations(ImmutableList.of()).build();

    assertThat(sideloadedSubtitlesMediaSource.canUpdateMediaItem(mediaItemWithChangedLanguage))
        .isFalse();
    assertThat(sideloadedSubtitlesMediaSource.canUpdateMediaItem(mediaItemWithoutSubtitles))
        .isFalse();
  }
}
