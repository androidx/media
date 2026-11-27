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
package androidx.media3.docsamples.exoplayer;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.Map;

/** Code snippets for media items. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass",
  "GoodTime-ApiWithNumericTimeUnit"
})
public final class MediaItems {

  public static void createMediaItemFromUri(Uri videoUri) {
    // [START create_media_item_from_uri]
    MediaItem mediaItem = MediaItem.fromUri(videoUri);
    // [END create_media_item_from_uri]
  }

  public static void createMediaItemFromUriWithTag(Uri videoUri, String mediaId, Object myAppData) {
    // [START create_media_item_from_uri_with_tag]
    MediaItem mediaItem =
        new MediaItem.Builder().setMediaId(mediaId).setTag(myAppData).setUri(videoUri).build();
    // [END create_media_item_from_uri_with_tag]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void imageDuration(Uri imageUri) {
    // [START image_duration]
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(imageUri).setImageDurationMs(3_000).build();
    // [END image_duration]
  }

  public static void createMediaItemSetMimeType(Uri hlsUri) {
    // [START create_media_item_set_mime_type]
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(hlsUri).setMimeType(MimeTypes.APPLICATION_M3U8).build();
    // [END create_media_item_set_mime_type]
  }

  public static void createMediaItemSetDrmProperties(
      Uri videoUri, Uri licenseUri, Map<String, String> httpRequestHeaders) {
    // [START create_media_item_set_drm_properties]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setDrmConfiguration(
                new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUri)
                    .setMultiSession(true)
                    .setLicenseRequestHeaders(httpRequestHeaders)
                    .build())
            .build();
    // [END create_media_item_set_drm_properties]
  }

  public static void createMediaItemSideloadTextTracks(
      Uri videoUri, Uri subtitleUri, String mimeType, String language, int selectionFlags) {
    // [START create_media_item_sideload_text_tracks]
    MediaItem.SubtitleConfiguration subtitle =
        new MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(mimeType) // The correct MIME type (required).
            .setLanguage(language) // The subtitle language (optional).
            .setSelectionFlags(selectionFlags) // Selection flags for the track (optional).
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(ImmutableList.of(subtitle))
            .build();
    // [END create_media_item_sideload_text_tracks]
  }

  public static void createMediaItemSetClippingProperties(
      Uri videoUri, long startPositionMs, long endPositionMs) {
    // [START create_media_item_set_clipping_properties]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(
                new ClippingConfiguration.Builder()
                    .setStartPositionMs(startPositionMs)
                    .setEndPositionMs(endPositionMs)
                    .build())
            .build();
    // [END create_media_item_set_clipping_properties]
  }

  public static void createMediaItemsSetAdTag(Uri videoUri, Uri adTagUri) {
    // [START create_media_items_set_ad_tag]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setAdsConfiguration(new MediaItem.AdsConfiguration.Builder(adTagUri).build())
            .build();
    // [END create_media_items_set_ad_tag]
  }
}
