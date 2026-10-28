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
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.common.images.WebImage;
import com.google.common.collect.ImmutableSet;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link MediaItemConverter} implementation.
 *
 * <p>Predefined Cast {@link com.google.android.gms.cast.MediaMetadata} fields without a dedicated
 * property on {@link androidx.media3.common.MediaMetadata MediaItem.mediaMetadata} are mapped to
 * and from {@link androidx.media3.common.MediaMetadata#extras MediaItem.mediaMetadata.extras} under
 * their Cast keys (with date fields represented as {@code yyyyMMdd} or non-midnight {@code
 * yyyyMMdd'T'HHmmssXX} strings, matching {@link
 * com.google.android.gms.cast.MediaMetadata#getDateAsString(String)}). Other {@code extras} entries
 * of supported types ({@link Boolean}, {@link Integer}, {@link Long}, {@link Float}, {@link
 * Double}, {@link String}, and nested {@link Bundle} instances) are stored as JSON in {@link
 * MediaInfo#getCustomData()}, with {@link Float} values unpacked as {@link Double}.
 */
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
  private static final String KEY_LIVE_CONFIGURATION = "m3-liveConfiguration";
  private static final String KEY_TARGET_OFFSET_MS = "m3-targetOffsetMs";
  private static final String KEY_MIN_OFFSET_MS = "m3-minOffsetMs";
  private static final String KEY_MAX_OFFSET_MS = "m3-maxOffsetMs";
  private static final String KEY_MIN_PLAYBACK_SPEED = "m3-minPlaybackSpeed";
  private static final String KEY_MAX_PLAYBACK_SPEED = "m3-maxPlaybackSpeed";
  private static final String KEY_MEDIA3_EXTRAS = "m3-extras";
  private static final String ISO_8601_BASIC_DATE_PATTERN = "yyyyMMdd";
  private static final String ISO_8601_BASIC_DATE_TIME_PATTERN = "yyyyMMdd'T'HHmmssXX";

  // These fields have a 1:1 mapping between MediaMetadata and MediaItem.mediaMetadata.
  private static final ImmutableSet<String> FIRST_CLASS_METADATA_KEYS =
      ImmutableSet.of(
          MediaMetadata.KEY_TITLE,
          MediaMetadata.KEY_SUBTITLE,
          MediaMetadata.KEY_ARTIST,
          MediaMetadata.KEY_ALBUM_ARTIST,
          MediaMetadata.KEY_ALBUM_TITLE,
          MediaMetadata.KEY_COMPOSER,
          MediaMetadata.KEY_DISC_NUMBER,
          MediaMetadata.KEY_TRACK_NUMBER,
          MediaMetadata.KEY_RELEASE_DATE);

  @Override
  public MediaItem toMediaItem(MediaQueueItem mediaQueueItem) {
    @Nullable MediaInfo mediaInfo = mediaQueueItem.getMedia();
    if (mediaInfo == null) {
      return MediaItem.EMPTY;
    }
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
      if (metadata.containsKey(MediaMetadata.KEY_RELEASE_DATE)) {
        @Nullable Calendar releaseDate = metadata.getDate(MediaMetadata.KEY_RELEASE_DATE);
        if (releaseDate != null) {
          metadataBuilder.setReleaseYear(releaseDate.get(Calendar.YEAR));
          // Month is 0-indexed in Calendar, but 1-indexed in MediaMetadata.
          metadataBuilder.setReleaseMonth(releaseDate.get(Calendar.MONTH) + 1);
          metadataBuilder.setReleaseDay(releaseDate.get(Calendar.DAY_OF_MONTH));
        }
      }
    }

    // Deserialization of MediaItem.mediaMetadata.extras:
    // 1. Predefined Cast SDK fields are extracted directly from MediaMetadata using
    //    MediaMetadata.getTypeForKey to query each key's expected type.
    Bundle extras = new Bundle();
    if (metadata != null) {
      for (String key : metadata.keySet()) {
        if (key == null || key.isEmpty() || FIRST_CLASS_METADATA_KEYS.contains(key)) {
          continue;
        }
        @MediaMetadata.FieldType int type = MediaMetadata.getTypeForKey(key);
        switch (type) {
          case MediaMetadata.FIELD_TYPE_STRING:
            extras.putString(key, metadata.getString(key));
            break;
          case MediaMetadata.FIELD_TYPE_INT:
            extras.putInt(key, metadata.getInt(key));
            break;
          case MediaMetadata.FIELD_TYPE_DOUBLE:
            extras.putDouble(key, metadata.getDouble(key));
            break;
          case MediaMetadata.FIELD_TYPE_TIME_MILLIS:
            extras.putLong(key, metadata.getTimeMillis(key));
            break;
          case MediaMetadata.FIELD_TYPE_DATE:
            String dateStr = metadata.getDateAsString(key);
            if (dateStr != null) {
              extras.putString(key, dateStr);
            }
            break;
          default:
            break;
        }
      }
    }

    // 2. Unpack custom application extras stored under KEY_MEDIA3_EXTRAS in customData.
    @Nullable JSONObject customData = mediaInfo.getCustomData();
    if (customData != null && customData.has(KEY_MEDIA3_EXTRAS)) {
      try {
        JSONObject customExtrasJson = customData.getJSONObject(KEY_MEDIA3_EXTRAS);
        unpackCustomDataExtras(customExtrasJson, extras);
      } catch (JSONException e) {
        Log.w(TAG, "Failed to parse " + KEY_MEDIA3_EXTRAS + " from customData", e);
      }
    }

    if (!extras.isEmpty()) {
      metadataBuilder.setExtras(extras);
    }

    androidx.media3.common.MediaMetadata mediaMetadata = metadataBuilder.build();
    // TODO: b/526548538 - Get rid of custom keys in media3 when equivalent CastSDK fields are
    // present.
    if (customData != null && customData.has(KEY_MEDIA_ITEM)) {
      @Nullable MediaItem mediaItem = getMediaItem(customData, mediaMetadata);
      if (mediaItem != null) {
        return mediaItem;
      }
    }
    return toMediaItemFallback(mediaInfo, mediaMetadata);
  }

  private static MediaItem toMediaItemFallback(
      MediaInfo mediaInfo, androidx.media3.common.MediaMetadata mediaMetadata) {
    MediaItem.Builder builder = new MediaItem.Builder();
    String contentId = mediaInfo.getContentId();
    if (contentId != null) {
      builder.setMediaId(contentId);
    }
    @Nullable String contentUrl = mediaInfo.getContentUrl();
    @Nullable Uri uri = null;
    if (contentUrl != null && !contentUrl.isEmpty()) {
      uri = Uri.parse(contentUrl);
    } else if (contentId != null) {
      // The web sender SDK indicates the content url is optional and, if absent, the media id will
      // be used as media URL.
      // See
      // https://developers.google.com/cast/docs/reference/web_sender/chrome.cast.media.MediaInfo#contentUrl
      Uri parsedContentId = Uri.parse(contentId);
      if (parsedContentId.getScheme() != null) {
        uri = parsedContentId;
      }
    }
    if (uri != null) {
      builder.setUri(uri);
      if (mediaInfo.getContentType() != null) {
        builder.setMimeType(mediaInfo.getContentType());
      }
    }
    return builder.setMediaMetadata(mediaMetadata).build();
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
    if (mediaItem.mediaMetadata.releaseYear != null) {
      Calendar calendar = Calendar.getInstance();
      calendar.clear();
      calendar.set(
          mediaItem.mediaMetadata.releaseYear,
          mediaItem.mediaMetadata.releaseMonth != null
              // Month is 0-indexed in Calendar.
              ? mediaItem.mediaMetadata.releaseMonth - 1
              : 0,
          mediaItem.mediaMetadata.releaseDay != null ? mediaItem.mediaMetadata.releaseDay : 1);
      metadata.putDate(MediaMetadata.KEY_RELEASE_DATE, calendar);
    }
    // Serialization of MediaItem.mediaMetadata.extras:
    // In Cast SDK, predefined metadata fields belong in MediaMetadata, so we maintain that paradigm
    // by writing recognized keys (via MediaMetadata.getTypeForKey) directly to MediaMetadata.
    // Custom application extras (FIELD_TYPE_NONE) cannot be stored in MediaMetadata without prior
    // schema knowledge, so they are serialized as JSON under KEY_MEDIA3_EXTRAS in customData.
    @Nullable Bundle extras = mediaItem.mediaMetadata.extras;
    @Nullable JSONObject customDataExtras = null;
    if (extras != null) {
      for (String key : extras.keySet()) {
        if (key == null || key.isEmpty()) {
          continue;
        }
        if (!tryPuttingPredefinedField(metadata, key, extras)) {
          if (customDataExtras == null) {
            customDataExtras = new JSONObject();
          }
          putCustomField(customDataExtras, key, extras.get(key));
        }
      }
    }
    String contentUrl = mediaItem.localConfiguration.uri.toString();
    String contentId =
        mediaItem.mediaId.equals(MediaItem.DEFAULT_MEDIA_ID) ? contentUrl : mediaItem.mediaId;
    // If the MediaItem has a LiveConfiguration, we set the stream type to STREAM_TYPE_LIVE.
    // Otherwise, we leave it unset letting the receiver decide whether the item is live.
    boolean isExplicitlyLive =
        !mediaItem.liveConfiguration.equals(MediaItem.LiveConfiguration.UNSET);
    MediaInfo.Builder mediaInfoBuilder =
        new MediaInfo.Builder(contentId)
            .setContentType(mediaItem.localConfiguration.mimeType)
            .setContentUrl(contentUrl)
            .setMetadata(metadata)
            .setCustomData(getCustomData(mediaItem, customDataExtras));
    if (isExplicitlyLive) {
      mediaInfoBuilder.setStreamType(MediaInfo.STREAM_TYPE_LIVE);
    }
    MediaInfo mediaInfo = mediaInfoBuilder.build();
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

  private static @Nullable MediaItem getMediaItem(
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
        try {
          populateDrmConfiguration(mediaItemJson.getJSONObject(KEY_DRM_CONFIGURATION), builder);
        } catch (JSONException | IllegalArgumentException e) {
          Log.w(TAG, "Failed to parse drmConfiguration in customData", e);
        }
      }
      if (mediaItemJson.has(KEY_LIVE_CONFIGURATION)) {
        try {
          builder.setLiveConfiguration(
              getLiveConfiguration(mediaItemJson.getJSONObject(KEY_LIVE_CONFIGURATION)));
        } catch (JSONException e) {
          Log.w(TAG, "Failed to parse liveConfiguration in customData", e);
        }
      }
      return builder.build();
    } catch (JSONException e) {
      Log.w(TAG, "Failed to parse customData", e);
      return null;
    }
  }

  private static MediaItem.LiveConfiguration getLiveConfiguration(JSONObject json)
      throws JSONException {
    MediaItem.LiveConfiguration.Builder builder = new MediaItem.LiveConfiguration.Builder();
    if (json.has(KEY_TARGET_OFFSET_MS)) {
      builder.setTargetOffsetMs(json.getLong(KEY_TARGET_OFFSET_MS));
    }
    if (json.has(KEY_MIN_OFFSET_MS)) {
      builder.setMinOffsetMs(json.getLong(KEY_MIN_OFFSET_MS));
    }
    if (json.has(KEY_MAX_OFFSET_MS)) {
      builder.setMaxOffsetMs(json.getLong(KEY_MAX_OFFSET_MS));
    }
    if (json.has(KEY_MIN_PLAYBACK_SPEED)) {
      builder.setMinPlaybackSpeed((float) json.getDouble(KEY_MIN_PLAYBACK_SPEED));
    }
    if (json.has(KEY_MAX_PLAYBACK_SPEED)) {
      builder.setMaxPlaybackSpeed((float) json.getDouble(KEY_MAX_PLAYBACK_SPEED));
    }
    return builder.build();
  }

  private static void populateDrmConfiguration(JSONObject json, MediaItem.Builder mediaItem)
      throws JSONException {
    MediaItem.DrmConfiguration.Builder drmConfiguration =
        new MediaItem.DrmConfiguration.Builder(UUID.fromString(json.getString(KEY_UUID)));
    if (json.has(KEY_LICENSE_URI)) {
      drmConfiguration.setLicenseUri(json.getString(KEY_LICENSE_URI));
    }
    if (json.has(KEY_REQUEST_HEADERS)) {
      JSONObject requestHeadersJson = json.getJSONObject(KEY_REQUEST_HEADERS);
      HashMap<String, String> requestHeaders = new HashMap<>();
      for (Iterator<String> iterator = requestHeadersJson.keys(); iterator.hasNext(); ) {
        String key = iterator.next();
        requestHeaders.put(key, requestHeadersJson.getString(key));
      }
      drmConfiguration.setLicenseRequestHeaders(requestHeaders);
    }
    mediaItem.setDrmConfiguration(drmConfiguration.build());
  }

  // Serialization.

  private static JSONObject getCustomData(
      MediaItem mediaItem, @Nullable JSONObject customDataExtras) {
    JSONObject json = new JSONObject();
    try {
      json.put(KEY_MEDIA_ITEM, getMediaItemJson(mediaItem));
      @Nullable JSONObject playerConfigJson = getPlayerConfigJson(mediaItem);
      if (playerConfigJson != null) {
        json.put(KEY_PLAYER_CONFIG, playerConfigJson);
      }
      if (customDataExtras != null && customDataExtras.length() > 0) {
        json.put(KEY_MEDIA3_EXTRAS, customDataExtras);
      }
    } catch (JSONException e) {
      Log.w(TAG, "Failed to create customData", e);
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
    if (!mediaItem.liveConfiguration.equals(MediaItem.LiveConfiguration.UNSET)) {
      JSONObject liveConfigurationJson = getLiveConfigurationJson(mediaItem.liveConfiguration);
      json.put(KEY_LIVE_CONFIGURATION, liveConfigurationJson);
    }
    return json;
  }

  private static JSONObject getLiveConfigurationJson(MediaItem.LiveConfiguration liveConfiguration)
      throws JSONException {
    JSONObject json = new JSONObject();
    if (liveConfiguration.targetOffsetMs != C.TIME_UNSET) {
      json.put(KEY_TARGET_OFFSET_MS, liveConfiguration.targetOffsetMs);
    }
    if (liveConfiguration.minOffsetMs != C.TIME_UNSET) {
      json.put(KEY_MIN_OFFSET_MS, liveConfiguration.minOffsetMs);
    }
    if (liveConfiguration.maxOffsetMs != C.TIME_UNSET) {
      json.put(KEY_MAX_OFFSET_MS, liveConfiguration.maxOffsetMs);
    }
    if (liveConfiguration.minPlaybackSpeed != C.RATE_UNSET) {
      json.put(KEY_MIN_PLAYBACK_SPEED, liveConfiguration.minPlaybackSpeed);
    }
    if (liveConfiguration.maxPlaybackSpeed != C.RATE_UNSET) {
      json.put(KEY_MAX_PLAYBACK_SPEED, liveConfiguration.maxPlaybackSpeed);
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

  private static @Nullable JSONObject getPlayerConfigJson(MediaItem mediaItem)
      throws JSONException {
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

  private static boolean tryPuttingPredefinedField(
      MediaMetadata metadata, String key, Bundle extras) {
    if (FIRST_CLASS_METADATA_KEYS.contains(key)) {
      return false;
    }
    @MediaMetadata.FieldType int type = MediaMetadata.getTypeForKey(key);
    @Nullable Object value = extras.get(key);
    switch (type) {
      case MediaMetadata.FIELD_TYPE_STRING:
        if (value instanceof String) {
          metadata.putString(key, (String) value);
          return true;
        }
        return false;
      case MediaMetadata.FIELD_TYPE_INT:
        if (value instanceof Integer) {
          metadata.putInt(key, (Integer) value);
          return true;
        }
        return false;
      case MediaMetadata.FIELD_TYPE_DOUBLE:
        if (value instanceof Double) {
          metadata.putDouble(key, (Double) value);
          return true;
        }
        return false;
      case MediaMetadata.FIELD_TYPE_TIME_MILLIS:
        if (value instanceof Long) {
          metadata.putTimeMillis(key, (Long) value);
          return true;
        }
        return false;
      case MediaMetadata.FIELD_TYPE_DATE:
        return value instanceof String && tryPuttingDateField(metadata, key, (String) value);
      case MediaMetadata.FIELD_TYPE_NONE:
      default:
        return false;
    }
  }

  private static boolean tryPuttingDateField(MediaMetadata metadata, String key, String value) {
    @Nullable Calendar calendar = parseIso8601Date(value);
    if (calendar == null) {
      return false;
    }
    // Verify that the date can be put and retrieved from the MediaMetadata.
    MediaMetadata testMetadata = new MediaMetadata();
    testMetadata.putDate(key, calendar);
    if (!value.equals(testMetadata.getDateAsString(key))) {
      return false;
    }
    metadata.putDate(key, calendar);
    return true;
  }

  private static @Nullable Calendar parseIso8601Date(String dateString) {
    boolean hasTime = dateString.contains("T");
    @Nullable TimeZone timeZone = extractTimeZone(dateString, hasTime);
    if (timeZone == null) {
      return null;
    }
    String pattern = hasTime ? ISO_8601_BASIC_DATE_TIME_PATTERN : ISO_8601_BASIC_DATE_PATTERN;
    SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
    format.setLenient(false);
    format.setTimeZone(timeZone);
    ParsePosition position = new ParsePosition(0);
    @Nullable Date date = format.parse(dateString, position);
    if (date == null || position.getIndex() != dateString.length()) {
      return null;
    }
    Calendar calendar = Calendar.getInstance(timeZone, Locale.US);
    calendar.setTime(date);
    return calendar;
  }

  private static @Nullable TimeZone extractTimeZone(String dateString, boolean hasTime) {
    if (!hasTime || dateString.endsWith("Z")) {
      return TimeZone.getTimeZone("UTC");
    }
    int timeSeparatorIndex = dateString.indexOf('T');
    int offsetIndex = Math.max(dateString.lastIndexOf('+'), dateString.lastIndexOf('-'));
    if (offsetIndex > timeSeparatorIndex) {
      return TimeZone.getTimeZone("GMT" + dateString.substring(offsetIndex));
    }
    return null;
  }

  private static void putCustomField(JSONObject json, String key, @Nullable Object value) {
    if (value == null) {
      return;
    }
    try {
      if (value instanceof Bundle) {
        json.put(key, bundleToJson((Bundle) value));
      } else if (value instanceof Float) {
        Float floatValue = (Float) value;
        if (!floatValue.isNaN() && !floatValue.isInfinite()) {
          json.put(key, Double.parseDouble(floatValue.toString()));
        }
      } else if (value instanceof Boolean
          || value instanceof Integer
          || value instanceof Long
          || value instanceof Double
          || value instanceof String) {
        json.put(key, value);
      }
    } catch (JSONException e) {
      Log.w(TAG, "Failed to serialize custom extras key: " + key, e);
    }
  }

  private static JSONObject bundleToJson(Bundle bundle) {
    JSONObject json = new JSONObject();
    for (String key : bundle.keySet()) {
      if (key == null || key.isEmpty()) {
        continue;
      }
      putCustomField(json, key, bundle.get(key));
    }
    return json;
  }

  private static void unpackCustomDataExtras(JSONObject json, Bundle extras) {
    for (Iterator<String> it = json.keys(); it.hasNext(); ) {
      String key = it.next();
      Object val = json.opt(key);
      if (val instanceof JSONObject) {
        Bundle nestedBundle = new Bundle();
        unpackCustomDataExtras((JSONObject) val, nestedBundle);
        extras.putBundle(key, nestedBundle);
      } else if (val instanceof Boolean) {
        extras.putBoolean(key, (Boolean) val);
      } else if (val instanceof Integer) {
        extras.putInt(key, (Integer) val);
      } else if (val instanceof Long) {
        extras.putLong(key, (Long) val);
      } else if (val instanceof Float) {
        extras.putDouble(key, Double.parseDouble(val.toString()));
      } else if (val instanceof Double) {
        extras.putDouble(key, (Double) val);
      } else if (val instanceof String) {
        extras.putString(key, (String) val);
      }
    }
  }
}
