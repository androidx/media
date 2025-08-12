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

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.common.images.WebImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Default {@link MediaItemConverter} implementation. */
@UnstableApi
public final class DefaultMediaItemConverter implements MediaItemConverter {

  // Shortened to fit in 25 character limit for logcat tags.
  private static final String TAG = "DefltMediaItemConverter";
  private static final String KEY_MEDIA_ITEM = "mediaItem";
  private static final String KEY_PLAYER_CONFIG = "exoPlayerConfig";
  private static final String KEY_MEDIA_ID = "mediaId";
  private static final String KEY_URI = "uri";
  private static final String KEY_TITLE = "title";
  private static final String KEY_MIME_TYPE = "mimeType";
  private static final String KEY_DRM_CONFIGURATION = "drmConfiguration";
  private static final String KEY_UUID = "uuid";
  private static final String KEY_LICENSE_URI = "licenseUri";
  private static final String KEY_REQUEST_HEADERS = "requestHeaders";

  @Override
  public MediaItem toMediaItem(MediaQueueItem mediaQueueItem) {
    @Nullable MediaInfo mediaInfo = mediaQueueItem.getMedia();
    checkNotNull(mediaInfo);
    androidx.media3.common.MediaMetadata.Builder metadataBuilder =
        new androidx.media3.common.MediaMetadata.Builder();
    @Nullable MediaMetadata metadata = mediaInfo.getMetadata();
    if (metadata != null) {
      if (metadata.containsKey(MediaMetadata.KEY_TITLE)) {
        metadataBuilder.setTitle(metadata.getString(MediaMetadata.KEY_TITLE));
      }
      if (metadata.containsKey(MediaMetadata.KEY_SUBTITLE)) {
        metadataBuilder.setSubtitle(metadata.getString(MediaMetadata.KEY_SUBTITLE));
      }
      if (metadata.containsKey(MediaMetadata.KEY_ARTIST)) {
        metadataBuilder.setArtist(metadata.getString(MediaMetadata.KEY_ARTIST));
      }
      if (metadata.containsKey(MediaMetadata.KEY_ALBUM_ARTIST)) {
        metadataBuilder.setAlbumArtist(metadata.getString(MediaMetadata.KEY_ALBUM_ARTIST));
      }
      if (metadata.containsKey(MediaMetadata.KEY_ALBUM_TITLE)) {
        metadataBuilder.setAlbumTitle(metadata.getString(MediaMetadata.KEY_ALBUM_TITLE));
      }
      if (!metadata.getImages().isEmpty()) {
        metadataBuilder.setArtworkUri(metadata.getImages().get(0).getUrl());
      }
      if (metadata.containsKey(MediaMetadata.KEY_COMPOSER)) {
        metadataBuilder.setComposer(metadata.getString(MediaMetadata.KEY_COMPOSER));
      }
      if (metadata.containsKey(MediaMetadata.KEY_DISC_NUMBER)) {
        metadataBuilder.setDiscNumber(metadata.getInt(MediaMetadata.KEY_DISC_NUMBER));
      }
      if (metadata.containsKey(MediaMetadata.KEY_TRACK_NUMBER)) {
        metadataBuilder.setTrackNumber(metadata.getInt(MediaMetadata.KEY_TRACK_NUMBER));
      }
    }
    // `mediaQueueItem` came from `toMediaQueueItem()` so the custom JSON data must be set.
    return getMediaItem(checkNotNull(mediaInfo.getCustomData()), metadataBuilder.build());
  }

  @Override
  public MediaQueueItem toMediaQueueItem(MediaItem mediaItem) {
    checkNotNull(mediaItem.localConfiguration);
    MediaMetadata metadata = new MediaMetadata(getMediaType(mediaItem));
    if (mediaItem.mediaMetadata.title != null) {
      metadata.putString(MediaMetadata.KEY_TITLE, mediaItem.mediaMetadata.title.toString());
    }
    if (mediaItem.mediaMetadata.subtitle != null) {
      metadata.putString(MediaMetadata.KEY_SUBTITLE, mediaItem.mediaMetadata.subtitle.toString());
    }
    if (mediaItem.mediaMetadata.artist != null) {
      metadata.putString(MediaMetadata.KEY_ARTIST, mediaItem.mediaMetadata.artist.toString());
    }
    if (mediaItem.mediaMetadata.albumArtist != null) {
      metadata.putString(
          MediaMetadata.KEY_ALBUM_ARTIST, mediaItem.mediaMetadata.albumArtist.toString());
    }
    if (mediaItem.mediaMetadata.albumTitle != null) {
      metadata.putString(
          MediaMetadata.KEY_ALBUM_TITLE, mediaItem.mediaMetadata.albumTitle.toString());
    }
    if (mediaItem.mediaMetadata.artworkUri != null) {
      metadata.addImage(new WebImage(mediaItem.mediaMetadata.artworkUri));
    }
    if (mediaItem.mediaMetadata.composer != null) {
      metadata.putString(MediaMetadata.KEY_COMPOSER, mediaItem.mediaMetadata.composer.toString());
    }
    if (mediaItem.mediaMetadata.discNumber != null) {
      metadata.putInt(MediaMetadata.KEY_DISC_NUMBER, mediaItem.mediaMetadata.discNumber);
    }
    if (mediaItem.mediaMetadata.trackNumber != null) {
      metadata.putInt(MediaMetadata.KEY_TRACK_NUMBER, mediaItem.mediaMetadata.trackNumber);
    }
    String contentUrl = mediaItem.localConfiguration.uri.toString();
    String contentId =
        mediaItem.mediaId.equals(MediaItem.DEFAULT_MEDIA_ID) ? contentUrl : mediaItem.mediaId;
    MediaInfo mediaInfo =
        new MediaInfo.Builder(contentId)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mediaItem.localConfiguration.mimeType)
            .setContentUrl(contentUrl)
            .setMetadata(metadata)
            .setCustomData(getCustomData(mediaItem))
            .build();
    return new MediaQueueItem.Builder(mediaInfo).build();
  }

  private static int getMediaType(MediaItem mediaItem) {
    Integer media3MediaType = mediaItem.mediaMetadata.mediaType;
    if (media3MediaType != null) {
      switch (media3MediaType) {
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_MOVIE:
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_TRAILER:
          return MediaMetadata.MEDIA_TYPE_MOVIE;
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_TV_SHOW:
          return MediaMetadata.MEDIA_TYPE_TV_SHOW;
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC:
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_RADIO_STATION:
          return MediaMetadata.MEDIA_TYPE_MUSIC_TRACK;
        case androidx.media3.common.MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER:
          return MediaMetadata.MEDIA_TYPE_AUDIOBOOK_CHAPTER;
        default:
          // Fall through to use MIME type.
          break;
      }
    }

    String mimeType =
        mediaItem.localConfiguration != null ? mediaItem.localConfiguration.mimeType : null;
    if (mimeType == null) {
      // TODO: b/432214377 - Revisit the media type once this ticket is addressed.
      Log.w(
          TAG,
          "Converting MediaItem with null MIME type and no media type. Assuming "
              + "MEDIA_TYPE_MOVIE. Song metadata may not be rendered correctly by the default"
              + " receiver.");
    }
    // We default to MEDIA_TYPE_MOVIE because that ensures the default receiver will render video,
    // if available. The disadvantage of guessing wrong is that song-related metadata may not be
    // rendered.
    return MimeTypes.isAudio(mimeType)
        ? MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        : MediaMetadata.MEDIA_TYPE_MOVIE;
  }

  // Deserialization.

  private static MediaItem getMediaItem(
      JSONObject customData, androidx.media3.common.MediaMetadata mediaMetadata) {
    try {
      JSONObject mediaItemJson = customData.getJSONObject(KEY_MEDIA_ITEM);
      MediaItem.Builder builder =
          new MediaItem.Builder()
              .setUri(Uri.parse(mediaItemJson.getString(KEY_URI)))
              .setMediaId(mediaItemJson.getString(KEY_MEDIA_ID))
              .setMediaMetadata(mediaMetadata);
      if (mediaItemJson.has(KEY_MIME_TYPE)) {
        builder.setMimeType(mediaItemJson.getString(KEY_MIME_TYPE));
      }
      if (mediaItemJson.has(KEY_DRM_CONFIGURATION)) {
        populateDrmConfiguration(mediaItemJson.getJSONObject(KEY_DRM_CONFIGURATION), builder);
      }
      return builder.build();
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  private static void populateDrmConfiguration(JSONObject json, MediaItem.Builder mediaItem)
      throws JSONException {
    MediaItem.DrmConfiguration.Builder drmConfiguration =
        new MediaItem.DrmConfiguration.Builder(UUID.fromString(json.getString(KEY_UUID)))
            .setLicenseUri(json.getString(KEY_LICENSE_URI));
    JSONObject requestHeadersJson = json.getJSONObject(KEY_REQUEST_HEADERS);
    HashMap<String, String> requestHeaders = new HashMap<>();
    for (Iterator<String> iterator = requestHeadersJson.keys(); iterator.hasNext(); ) {
      String key = iterator.next();
      requestHeaders.put(key, requestHeadersJson.getString(key));
    }
    drmConfiguration.setLicenseRequestHeaders(requestHeaders);
    mediaItem.setDrmConfiguration(drmConfiguration.build());
  }

  // Serialization.

  private static JSONObject getCustomData(MediaItem mediaItem) {
    JSONObject json = new JSONObject();
    try {
      json.put(KEY_MEDIA_ITEM, getMediaItemJson(mediaItem));
      @Nullable JSONObject playerConfigJson = getPlayerConfigJson(mediaItem);
      if (playerConfigJson != null) {
        json.put(KEY_PLAYER_CONFIG, playerConfigJson);
      }
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
    return json;
  }

  private static JSONObject getMediaItemJson(MediaItem mediaItem) throws JSONException {
    checkNotNull(mediaItem.localConfiguration);
    JSONObject json = new JSONObject();
    json.put(KEY_MEDIA_ID, mediaItem.mediaId);
    json.put(KEY_TITLE, mediaItem.mediaMetadata.title);
    json.put(KEY_URI, mediaItem.localConfiguration.uri.toString());
    json.put(KEY_MIME_TYPE, mediaItem.localConfiguration.mimeType);
    if (mediaItem.localConfiguration.drmConfiguration != null) {
      json.put(
          KEY_DRM_CONFIGURATION,
          getDrmConfigurationJson(mediaItem.localConfiguration.drmConfiguration));
    }
    return json;
  }

  private static JSONObject getDrmConfigurationJson(MediaItem.DrmConfiguration drmConfiguration)
      throws JSONException {
    JSONObject json = new JSONObject();
    json.put(KEY_UUID, drmConfiguration.scheme);
    json.put(KEY_LICENSE_URI, drmConfiguration.licenseUri);
    json.put(KEY_REQUEST_HEADERS, new JSONObject(drmConfiguration.licenseRequestHeaders));
    return json;
  }

  @Nullable
  private static JSONObject getPlayerConfigJson(MediaItem mediaItem) throws JSONException {
    if (mediaItem.localConfiguration == null
        || mediaItem.localConfiguration.drmConfiguration == null) {
      return null;
    }
    MediaItem.DrmConfiguration drmConfiguration = mediaItem.localConfiguration.drmConfiguration;

    String drmScheme;
    if (C.WIDEVINE_UUID.equals(drmConfiguration.scheme)) {
      drmScheme = "widevine";
    } else if (C.PLAYREADY_UUID.equals(drmConfiguration.scheme)) {
      drmScheme = "playready";
    } else {
      return null;
    }

    JSONObject playerConfigJson = new JSONObject();
    playerConfigJson.put("withCredentials", false);
    playerConfigJson.put("protectionSystem", drmScheme);
    if (drmConfiguration.licenseUri != null) {
      playerConfigJson.put("licenseUrl", drmConfiguration.licenseUri);
    }
    if (!drmConfiguration.licenseRequestHeaders.isEmpty()) {
      playerConfigJson.put("headers", new JSONObject(drmConfiguration.licenseRequestHeaders));
    }

    return playerConfigJson;
  }
}
