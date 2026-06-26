/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link DefaultMediaItemConverter}. */
@RunWith(AndroidJUnit4.class)
public class DefaultMediaItemConverterTest {

  @Test
  public void serialize_deserialize_minimal() {
    MediaItem.Builder builder = new MediaItem.Builder();
    MediaItem item =
        builder.setUri("http://example.com").setMimeType(MimeTypes.APPLICATION_MPD).build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    assertThat(reconstructedItem).isEqualTo(item);
  }

  @Test
  public void serialize_deserialize_complete() {
    MediaItem.Builder builder = new MediaItem.Builder();
    MediaItem item =
        builder
            .setMediaId("fooBar")
            .setUri(Uri.parse("http://example.com"))
            .setMediaMetadata(
                new MediaMetadata.Builder()
                    .setTitle("testTitle")
                    .setSubtitle("testSubtitle")
                    .setArtist("testArtist")
                    .setAlbumArtist("testAlbumArtist")
                    .setArtworkUri(Uri.parse("http://testArtworkUri"))
                    .setComposer("testComposer")
                    .setDiscNumber(42)
                    .setTrackNumber(23)
                    .build())
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(
                new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri("http://license.com")
                    .setLicenseRequestHeaders(ImmutableMap.of("key", "value"))
                    .build())
            .build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    assertThat(reconstructedItem).isEqualTo(item);
  }

  @Test
  public void toMediaQueueItem_nonDefaultMediaId_usedAsContentId() {
    MediaItem.Builder builder = new MediaItem.Builder();
    MediaItem item =
        builder
            .setMediaId("fooBar")
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaQueueItem queueItem = converter.toMediaQueueItem(item);

    assertThat(queueItem.getMedia().getContentId()).isEqualTo("fooBar");
  }

  @Test
  public void toMediaQueueItem_defaultMediaId_uriAsContentId() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getContentId()).isEqualTo("http://example.com");

    MediaItem secondMediaItem =
        new MediaItem.Builder()
            .setMediaId(MediaItem.DEFAULT_MEDIA_ID)
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build();

    MediaQueueItem secondQueueItem = converter.toMediaQueueItem(secondMediaItem);

    assertThat(secondQueueItem.getMedia().getContentId()).isEqualTo("http://example.com");
  }

  @Test
  public void toMediaQueueItem_withNoMimeType_usesCastMediaTypeMovie() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem = new MediaItem.Builder().setUri("http://example.com").build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getMetadata().getMediaType())
        .isEqualTo(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
  }

  @Test
  public void toMediaQueueItem_withAudioMimeType_usesMediaTypeMusicTrack() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getMetadata().getMediaType())
        .isEqualTo(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
  }

  @Test
  public void toMediaQueueItem_withMimeTypeAndMediaType_usesMediaType() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(
                new MediaMetadata.Builder().setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getMetadata().getMediaType())
        .isEqualTo(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
  }

  @Test
  public void toMediaItem_noCustomData_fallbackWithContentUrl() {
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "fallbackTitle");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("contentId");
    assertThat(mediaItem.localConfiguration.uri.toString()).isEqualTo("http://example.com/url");
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
    assertThat(mediaItem.mediaMetadata.title.toString()).isEqualTo("fallbackTitle");
  }

  @Test
  public void toMediaItem_noCustomData_fallbackWithContentIdAsUri() {
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "fallbackTitle");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/id")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("http://example.com/id");
    assertThat(mediaItem.localConfiguration.uri.toString()).isEqualTo("http://example.com/id");
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
    assertThat(mediaItem.mediaMetadata.title.toString()).isEqualTo("fallbackTitle");
  }

  @Test
  public void toMediaItem_noCustomData_fallbackWithNonUriContentIdAndNoContentUrl() {
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "fallbackTitle");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("just_an_id")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    assertThrows(IllegalArgumentException.class, () -> converter.toMediaItem(queueItem));
  }
}
