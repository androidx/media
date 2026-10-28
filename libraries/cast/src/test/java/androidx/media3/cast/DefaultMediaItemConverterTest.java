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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.common.collect.ImmutableMap;
import java.util.Calendar;
import org.json.JSONObject;
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
                    .setReleaseYear(2024)
                    .setReleaseMonth(5)
                    .setReleaseDay(27)
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
  public void
      toMediaItem_noCustomDataAndNonUriContentIdAndNoContentUrl_returnsWithoutLocalConfiguration() {
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

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("just_an_id");
    assertThat(mediaItem.localConfiguration).isNull();
    assertThat(mediaItem.mediaMetadata.title.toString()).isEqualTo("fallbackTitle");
  }

  @Test
  public void toMediaItem_noCustomDataAndEmptyContentUrl_fallsBackToContentIdAsUri() {
    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/id")
            .setContentUrl("")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("http://example.com/id");
    assertThat(mediaItem.localConfiguration.uri.toString()).isEqualTo("http://example.com/id");
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
  }

  @Test
  public void toMediaQueueItem_withLiveConfiguration_setsStreamTypeLive() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com/live.m3u8")
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10000).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getStreamType()).isEqualTo(MediaInfo.STREAM_TYPE_LIVE);
  }

  @Test
  public void serialize_deserialize_liveConfiguration() {
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com/live.m3u8")
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(10000)
                    .setMinOffsetMs(5000)
                    .setMaxOffsetMs(20000)
                    .setMinPlaybackSpeed(0.9f)
                    .setMaxPlaybackSpeed(1.1f)
                    .build())
            .build();

    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);
    assertThat(reconstructedItem.liveConfiguration).isEqualTo(item.liveConfiguration);
  }

  @Test
  public void toMediaQueueItem_withoutLiveConfiguration_setsStreamTypeInvalid() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem = new MediaItem.Builder().setUri("http://example.com/live.m3u8").build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);

    assertThat(queueItem.getMedia().getStreamType()).isEqualTo(MediaInfo.STREAM_TYPE_INVALID);
  }

  @Test
  public void toMediaQueueItem_withReleaseDate_setsKeyReleaseDate() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(
                new MediaMetadata.Builder()
                    .setReleaseYear(2024)
                    .setReleaseMonth(5)
                    .setReleaseDay(27)
                    .build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);
    com.google.android.gms.cast.MediaMetadata metadata = queueItem.getMedia().getMetadata();

    assertThat(metadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE))
        .isTrue();
    Calendar calendar =
        metadata.getDate(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE);
    assertThat(calendar).isNotNull();
    assertThat(calendar.get(Calendar.YEAR)).isEqualTo(2024);
    assertThat(calendar.get(Calendar.MONTH)).isEqualTo(Calendar.MAY);
    assertThat(calendar.get(Calendar.DAY_OF_MONTH)).isEqualTo(27);
    assertThat(metadata.getDateAsString(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE))
        .isEqualTo("20240527");
  }

  @Test
  public void toMediaQueueItem_withReleaseYearOnly_defaultsMonthAndDay() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(new MediaMetadata.Builder().setReleaseYear(2024).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);
    com.google.android.gms.cast.MediaMetadata metadata = queueItem.getMedia().getMetadata();

    assertThat(metadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE))
        .isTrue();
    assertThat(metadata.getDateAsString(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE))
        .isEqualTo("20240101");
  }

  @Test
  public void toMediaItem_withReleaseDate_setsReleaseDate() {
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE);
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(2024, Calendar.MAY, 27);
    gmsMetadata.putDate(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE, calendar);
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

    assertThat(mediaItem.mediaMetadata.releaseYear).isEqualTo(2024);
    assertThat(mediaItem.mediaMetadata.releaseMonth).isEqualTo(5);
    assertThat(mediaItem.mediaMetadata.releaseDay).isEqualTo(27);
  }

  @Test
  public void toMediaQueueItem_withPredefinedExtras_setsCastMetadata() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Test Series");
    extras.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, 3);
    extras.putInt(com.google.android.gms.cast.MediaMetadata.KEY_EPISODE_NUMBER, 7);
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO, "Test Studio");

    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);
    com.google.android.gms.cast.MediaMetadata metadata = queueItem.getMedia().getMetadata();

    assertThat(metadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Test Series");
    assertThat(metadata.getInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo(3);
    assertThat(metadata.getInt(com.google.android.gms.cast.MediaMetadata.KEY_EPISODE_NUMBER))
        .isEqualTo(7);
    assertThat(metadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO))
        .isEqualTo("Test Studio");
    assertThat(queueItem.getMedia().getCustomData().has("m3-extras")).isFalse();
  }

  @Test
  public void toMediaQueueItem_withCustomExtras_setsCustomData() throws Exception {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString("customString", "customValue");
    extras.putInt("customInt", 123);
    extras.putBoolean("customBool", true);
    Bundle nestedBundle = new Bundle();
    nestedBundle.putString("nestedKey", "nestedVal");
    extras.putBundle("nestedBundle", nestedBundle);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);
    com.google.android.gms.cast.MediaMetadata metadata = queueItem.getMedia().getMetadata();
    JSONObject customData = queueItem.getMedia().getCustomData();

    assertThat(customData.has("m3-extras")).isTrue();
    JSONObject customExtras = customData.getJSONObject("m3-extras");
    assertThat(customExtras.getString("customString")).isEqualTo("customValue");
    assertThat(customExtras.getInt("customInt")).isEqualTo(123);
    assertThat(customExtras.getBoolean("customBool")).isTrue();
    assertThat(customExtras.getJSONObject("nestedBundle").getString("nestedKey"))
        .isEqualTo("nestedVal");
    assertThat(metadata.containsKey("customString")).isFalse();
    assertThat(metadata.containsKey("customInt")).isFalse();
  }

  @Test
  public void toMediaQueueItem_withMixedExtras_segregatesPredefinedAndCustom() throws Exception {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Test Series");
    extras.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, 2);
    extras.putString("customKey", "customVal");
    extras.putBoolean("customBool", false);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(mediaItem);
    com.google.android.gms.cast.MediaMetadata metadata = queueItem.getMedia().getMetadata();
    JSONObject customData = queueItem.getMedia().getCustomData();

    assertThat(metadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Test Series");
    assertThat(metadata.getInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo(2);
    assertThat(metadata.containsKey("customKey")).isFalse();
    assertThat(metadata.containsKey("customBool")).isFalse();

    assertThat(customData.has("m3-extras")).isTrue();
    JSONObject customExtras = customData.getJSONObject("m3-extras");
    assertThat(customExtras.getString("customKey")).isEqualTo("customVal");
    assertThat(customExtras.getBoolean("customBool")).isFalse();
    assertThat(customExtras.has(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isFalse();
    assertThat(customExtras.has(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isFalse();
  }

  @Test
  public void toMediaItem_withPredefinedCastMetadata_setsExtras() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW);
    gmsMetadata.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Test Series");
    gmsMetadata.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, 4);
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO, "Test Studio");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaMetadata.extras).isNotNull();
    Bundle extras = mediaItem.mediaMetadata.extras;
    assertThat(extras.getString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Test Series");
    assertThat(extras.getInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo(4);
    assertThat(extras.getString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO))
        .isEqualTo("Test Studio");
  }

  @Test
  public void toMediaItem_withCustomDataExtras_setsExtras() throws Exception {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    JSONObject customExtras = new JSONObject();
    customExtras.put("customString", "customValue");
    customExtras.put("customInt", 456);
    customExtras.put("customBool", true);
    JSONObject nestedJson = new JSONObject();
    nestedJson.put("nestedKey", "nestedVal");
    customExtras.put("nestedBundle", nestedJson);
    JSONObject customData = new JSONObject();
    customData.put("m3-extras", customExtras);
    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaMetadata.extras).isNotNull();
    Bundle extras = mediaItem.mediaMetadata.extras;
    assertThat(extras.getString("customString")).isEqualTo("customValue");
    assertThat(extras.getInt("customInt")).isEqualTo(456);
    assertThat(extras.getBoolean("customBool")).isTrue();
    Bundle nestedBundle = extras.getBundle("nestedBundle");
    assertThat(nestedBundle).isNotNull();
    assertThat(nestedBundle.getString("nestedKey")).isEqualTo("nestedVal");
  }

  @Test
  public void toMediaItem_rejoiningExternalSessionWithoutCustomData_setsPredefinedExtras() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW);
    gmsMetadata.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "External Series");
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO, "External Studio");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/video.mp4")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.localConfiguration.uri.toString())
        .isEqualTo("http://example.com/video.mp4");
    assertThat(mediaItem.mediaMetadata.extras).isNotNull();
    assertThat(
            mediaItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("External Series");
    assertThat(
            mediaItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_STUDIO))
        .isEqualTo("External Studio");
  }

  @Test
  public void toMediaItem_withFirstClassAndPredefinedMetadata_doesNotDuplicateFirstClassInExtras() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    com.google.android.gms.cast.MediaMetadata gmsMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW);
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "Show Title");
    gmsMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, "Show Artist");
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(2024, Calendar.MAY, 27);
    gmsMetadata.putDate(com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE, calendar);
    gmsMetadata.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Series Title");

    MediaInfo mediaInfo =
        new MediaInfo.Builder("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setMetadata(gmsMetadata)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    // 1:1 first-class properties should be set
    assertThat(mediaItem.mediaMetadata.title.toString()).isEqualTo("Show Title");
    assertThat(mediaItem.mediaMetadata.artist.toString()).isEqualTo("Show Artist");
    assertThat(mediaItem.mediaMetadata.releaseYear).isEqualTo(2024);
    assertThat(mediaItem.mediaMetadata.releaseMonth).isEqualTo(5);
    assertThat(mediaItem.mediaMetadata.releaseDay).isEqualTo(27);

    // extras should only contain unmapped fields
    assertThat(mediaItem.mediaMetadata.extras).isNotNull();
    assertThat(
            mediaItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Series Title");
    assertThat(
            mediaItem.mediaMetadata.extras.containsKey(
                com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
        .isFalse();
    assertThat(
            mediaItem.mediaMetadata.extras.containsKey(
                com.google.android.gms.cast.MediaMetadata.KEY_ARTIST))
        .isFalse();
    assertThat(
            mediaItem.mediaMetadata.extras.containsKey(
                com.google.android.gms.cast.MediaMetadata.KEY_RELEASE_DATE))
        .isFalse();
  }

  @Test
  public void toMediaQueueItemAndToMediaItem_withPredefinedAndCustomExtras_preservesAllExtras() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Sci-Fi Show");
    extras.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, 2);
    extras.putInt("appCustomId", 999);
    extras.putFloat("appCustomFloat", 0.1f);
    extras.putBoolean("appCustomFlag", true);
    Bundle nestedBundle = new Bundle();
    nestedBundle.putString("nestedKey", "nestedVal");
    nestedBundle.putFloat("nestedFloat", 1.25f);
    extras.putBundle("nestedBundle", nestedBundle);
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(
                new MediaMetadata.Builder().setTitle("Episode Title").setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    assertThat(reconstructedItem.mediaMetadata.title.toString()).isEqualTo("Episode Title");
    assertThat(reconstructedItem.mediaMetadata.extras).isNotNull();
    Bundle reconstructedExtras = reconstructedItem.mediaMetadata.extras;
    assertThat(
            reconstructedExtras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Sci-Fi Show");
    assertThat(
            reconstructedExtras.getInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo(2);
    assertThat(reconstructedExtras.getInt("appCustomId")).isEqualTo(999);
    assertThat(reconstructedExtras.getDouble("appCustomFloat")).isEqualTo(0.1);
    assertThat(reconstructedExtras.getBoolean("appCustomFlag")).isTrue();
    Bundle reconstructedNested = reconstructedExtras.getBundle("nestedBundle");
    assertThat(reconstructedNested).isNotNull();
    assertThat(reconstructedNested.getString("nestedKey")).isEqualTo("nestedVal");
    assertThat(reconstructedNested.getDouble("nestedFloat")).isEqualTo(1.25);
  }

  @Test
  public void toMediaQueueItemAndToMediaItem_withBasicIso8601DateExtra_setsCastDateMetadata() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE, "20240527");
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    com.google.android.gms.cast.MediaMetadata castMetadata = queueItem.getMedia().getMetadata();
    assertThat(
            castMetadata.getDateAsString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("20240527");
    assertThat(queueItem.getMedia().getCustomData().has("m3-extras")).isFalse();
    assertThat(reconstructedItem.mediaMetadata.extras).isNotNull();
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("20240527");
  }

  @Test
  public void toMediaQueueItemAndToMediaItem_withBasicIso8601DateTimeExtras_setsCastDateMetadata() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE, "20240527T103045Z");
    extras.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE, "20240527T123045+0200");
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    com.google.android.gms.cast.MediaMetadata castMetadata = queueItem.getMedia().getMetadata();
    assertThat(
            castMetadata.getDateAsString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("20240527T103045Z");
    assertThat(
            castMetadata.getDateAsString(
                com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isEqualTo("20240527T123045+0200");
    assertThat(queueItem.getMedia().getCustomData().has("m3-extras")).isFalse();
    assertThat(reconstructedItem.mediaMetadata.extras).isNotNull();
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("20240527T103045Z");
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isEqualTo("20240527T123045+0200");
  }

  @Test
  public void
      toMediaQueueItemAndToMediaItem_withNonBasicOrMidnightDateExtras_preservesStringsInCustomData() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE, "2024-05-27T10:30:45Z");
    extras.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE, "20240527T000000Z");
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    com.google.android.gms.cast.MediaMetadata castMetadata = queueItem.getMedia().getMetadata();
    assertThat(
            castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isFalse();
    assertThat(
            castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isFalse();
    assertThat(queueItem.getMedia().getCustomData().has("m3-extras")).isTrue();
    assertThat(reconstructedItem.mediaMetadata.extras).isNotNull();
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("2024-05-27T10:30:45Z");
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isEqualTo("20240527T000000Z");
  }

  @Test
  public void toMediaItemAndToMediaQueueItem_withCastMetadataAndCustomData_preservesAllMetadata()
      throws Exception {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    com.google.android.gms.cast.MediaMetadata originalMetadata =
        new com.google.android.gms.cast.MediaMetadata(
            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW);
    originalMetadata.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_TITLE, "Original Title");
    originalMetadata.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, "Action Series");
    originalMetadata.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, 5);
    originalMetadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO, "Paramount");
    JSONObject customExtras = new JSONObject();
    customExtras.put("customKey", "customValue");
    JSONObject customData = new JSONObject();
    customData.put("m3-extras", customExtras);
    MediaInfo originalMediaInfo =
        new MediaInfo.Builder("http://example.com/video.mp4")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setMetadata(originalMetadata)
            .setCustomData(customData)
            .build();
    MediaQueueItem originalQueueItem = new MediaQueueItem.Builder(originalMediaInfo).build();

    MediaItem reconstructedMediaItem = converter.toMediaItem(originalQueueItem);
    MediaQueueItem reconstructedQueueItem = converter.toMediaQueueItem(reconstructedMediaItem);

    com.google.android.gms.cast.MediaMetadata resultMetadata =
        reconstructedQueueItem.getMedia().getMetadata();
    assertThat(resultMetadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
        .isEqualTo("Original Title");
    assertThat(resultMetadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo("Action Series");
    assertThat(resultMetadata.getInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo(5);
    assertThat(resultMetadata.getString(com.google.android.gms.cast.MediaMetadata.KEY_STUDIO))
        .isEqualTo("Paramount");
    JSONObject resultCustomData = reconstructedQueueItem.getMedia().getCustomData();
    assertThat(resultCustomData.has("m3-extras")).isTrue();
    assertThat(resultCustomData.getJSONObject("m3-extras").getString("customKey"))
        .isEqualTo("customValue");
  }

  @Test
  public void
      toMediaQueueItemAndToMediaItem_withMismatchedPredefinedExtraTypes_preservesValuesInExtras() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    Bundle extras = new Bundle();
    extras.putInt(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, 42);
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, "Season Three");
    extras.putString(com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE, "20240230");
    extras.putString(
        com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE, "20240527T103045+0000");
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
            .build();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    com.google.android.gms.cast.MediaMetadata castMetadata = queueItem.getMedia().getMetadata();
    assertThat(castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isFalse();
    assertThat(
            castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isFalse();
    assertThat(
            castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isFalse();
    assertThat(
            castMetadata.containsKey(com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isFalse();
    assertThat(reconstructedItem.mediaMetadata.extras).isNotNull();
    assertThat(
            reconstructedItem.mediaMetadata.extras.getInt(
                com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE))
        .isEqualTo(42);
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER))
        .isEqualTo("Season Three");
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_BROADCAST_DATE))
        .isEqualTo("20240230");
    assertThat(
            reconstructedItem.mediaMetadata.extras.getString(
                com.google.android.gms.cast.MediaMetadata.KEY_CREATION_DATE))
        .isEqualTo("20240527T103045+0000");
  }

  @Test
  public void toMediaQueueItemAndToMediaItem_withNullExtras_leavesExtrasNullAndOmitsCustomData() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem itemWithNullExtras =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(new MediaMetadata.Builder().setTitle("Title").build())
            .build();

    MediaQueueItem queueItemNull = converter.toMediaQueueItem(itemWithNullExtras);
    MediaItem reconstructedNull = converter.toMediaItem(queueItemNull);

    assertThat(reconstructedNull.mediaMetadata.extras).isNull();
    assertThat(queueItemNull.getMedia().getCustomData().has("m3-extras")).isFalse();
  }

  @Test
  public void toMediaQueueItemAndToMediaItem_withEmptyExtras_leavesExtrasNullAndOmitsCustomData() {
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaItem itemWithEmptyExtras =
        new MediaItem.Builder()
            .setUri("http://example.com")
            .setMediaMetadata(
                new MediaMetadata.Builder().setTitle("Title").setExtras(new Bundle()).build())
            .build();

    MediaQueueItem queueItemEmpty = converter.toMediaQueueItem(itemWithEmptyExtras);
    MediaItem reconstructedEmpty = converter.toMediaItem(queueItemEmpty);

    assertThat(reconstructedEmpty.mediaMetadata.extras).isNull();
    assertThat(queueItemEmpty.getMedia().getCustomData().has("m3-extras")).isFalse();
  }

  @Test
  public void toMediaItem_drmConfigurationWithoutLicenseUri_preservesDrmConfiguration() {
    MediaItem item =
        new MediaItem.Builder()
            .setMediaId("drmId")
            .setUri("http://example.com/video.mpd")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(
                new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseRequestHeaders(ImmutableMap.of("Authorization", "Bearer token"))
                    .build())
            .build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();
    MediaQueueItem queueItem = converter.toMediaQueueItem(item);

    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    assertThat(reconstructedItem).isEqualTo(item);
  }

  @Test
  public void toMediaItem_nonObjectMediaItemInCustomData_fallsBackToMediaInfo() throws Exception {
    JSONObject customData = new JSONObject("{\"mediaItem\": \"not_an_object\"}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("contentId");
    assertThat(mediaItem.localConfiguration.uri).isEqualTo(Uri.parse("http://example.com/url"));
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
  }

  @Test
  public void toMediaItem_missingUriInCustomData_fallsBackToMediaInfo() throws Exception {
    JSONObject customData = new JSONObject("{\"mediaItem\": {\"mediaId\": \"customId\"}}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("contentId");
    assertThat(mediaItem.localConfiguration.uri).isEqualTo(Uri.parse("http://example.com/url"));
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
  }

  @Test
  public void toMediaItem_missingMediaIdInCustomData_fallsBackToMediaInfo() throws Exception {
    JSONObject customData =
        new JSONObject("{\"mediaItem\": {\"uri\": \"http://example.com/custom.mp4\"}}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/url")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("contentId");
    assertThat(mediaItem.localConfiguration.uri).isEqualTo(Uri.parse("http://example.com/url"));
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.VIDEO_MP4);
  }

  @Test
  public void
      toMediaItem_invalidDrmUuidInCustomData_ignoresDrmConfigurationAndPreservesOtherFields()
          throws Exception {
    JSONObject customData =
        new JSONObject(
            "{\"mediaItem\": {"
                + "\"uri\": \"http://example.com/custom.mpd\","
                + "\"mediaId\": \"customId\","
                + "\"mimeType\": \""
                + MimeTypes.APPLICATION_MPD
                + "\","
                + "\"drmConfiguration\": {\"uuid\": \"not-a-valid-uuid\"},"
                + "\"m3-liveConfiguration\": {\"m3-targetOffsetMs\": 5000}"
                + "}}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/fallback.mp4")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("customId");
    assertThat(mediaItem.localConfiguration.uri)
        .isEqualTo(Uri.parse("http://example.com/custom.mpd"));
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.APPLICATION_MPD);
    assertThat(mediaItem.localConfiguration.drmConfiguration).isNull();
    assertThat(mediaItem.liveConfiguration.targetOffsetMs).isEqualTo(5000);
  }

  @Test
  public void
      toMediaItem_malformedLiveConfigurationInCustomData_ignoresLiveConfigurationAndPreservesOtherFields()
          throws Exception {
    JSONObject customData =
        new JSONObject(
            "{\"mediaItem\": {"
                + "\"uri\": \"http://example.com/live.m3u8\","
                + "\"mediaId\": \"customId\","
                + "\"mimeType\": \""
                + MimeTypes.APPLICATION_M3U8
                + "\","
                + "\"drmConfiguration\": {"
                + "\"uuid\": \""
                + C.WIDEVINE_UUID
                + "\","
                + "\"licenseUri\": \"http://example.com/license\","
                + "\"requestHeaders\": {}"
                + "},"
                + "\"m3-liveConfiguration\": {\"m3-targetOffsetMs\": \"not_a_number\"}"
                + "}}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/fallback.mp4")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.mediaId).isEqualTo("customId");
    assertThat(mediaItem.localConfiguration.uri)
        .isEqualTo(Uri.parse("http://example.com/live.m3u8"));
    assertThat(mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8);
    assertThat(mediaItem.localConfiguration.drmConfiguration.scheme).isEqualTo(C.WIDEVINE_UUID);
    assertThat(mediaItem.localConfiguration.drmConfiguration.licenseUri)
        .isEqualTo(Uri.parse("http://example.com/license"));
    assertThat(mediaItem.liveConfiguration).isEqualTo(MediaItem.LiveConfiguration.UNSET);
  }

  @Test
  public void toMediaQueueItem_nonFiniteLivePlaybackSpeed_omitsCustomDataAndFallsBackToMediaInfo() {
    MediaItem item =
        new MediaItem.Builder()
            .setMediaId("liveId")
            .setUri("http://example.com/live.m3u8")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(10000)
                    .setMinPlaybackSpeed(Float.NaN)
                    .build())
            .build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaQueueItem queueItem = converter.toMediaQueueItem(item);
    MediaItem reconstructedItem = converter.toMediaItem(queueItem);

    assertThat(reconstructedItem.mediaId).isEqualTo("liveId");
    assertThat(reconstructedItem.localConfiguration.uri)
        .isEqualTo(Uri.parse("http://example.com/live.m3u8"));
    assertThat(reconstructedItem.localConfiguration.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8);
    assertThat(reconstructedItem.liveConfiguration).isEqualTo(MediaItem.LiveConfiguration.UNSET);
  }

  @Test
  public void toMediaItem_nullMediaInfo_returnsEmptyMediaItem() {
    MediaQueueItem queueItem = mock(MediaQueueItem.class);
    when(queueItem.getMedia()).thenReturn(null);
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem).isSameInstanceAs(MediaItem.EMPTY);
  }

  @Test
  public void
      toMediaItem_drmConfigurationWithoutRequestHeadersInCustomData_preservesDrmConfiguration()
          throws Exception {
    JSONObject customData =
        new JSONObject(
            "{\"mediaItem\": {"
                + "\"uri\": \"http://example.com/custom.mpd\","
                + "\"mediaId\": \"customId\","
                + "\"mimeType\": \""
                + MimeTypes.APPLICATION_MPD
                + "\","
                + "\"drmConfiguration\": {"
                + "\"uuid\": \""
                + C.WIDEVINE_UUID
                + "\","
                + "\"licenseUri\": \"http://example.com/license\""
                + "}"
                + "}}");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("contentId")
            .setContentUrl("http://example.com/fallback.mp4")
            .setContentType(MimeTypes.VIDEO_MP4)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).build();
    DefaultMediaItemConverter converter = new DefaultMediaItemConverter();

    MediaItem mediaItem = converter.toMediaItem(queueItem);

    assertThat(mediaItem.localConfiguration.drmConfiguration.scheme).isEqualTo(C.WIDEVINE_UUID);
    assertThat(mediaItem.localConfiguration.drmConfiguration.licenseUri)
        .isEqualTo(Uri.parse("http://example.com/license"));
    assertThat(mediaItem.localConfiguration.drmConfiguration.licenseRequestHeaders).isEmpty();
  }
}
