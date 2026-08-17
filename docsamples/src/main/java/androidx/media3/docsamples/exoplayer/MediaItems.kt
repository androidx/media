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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** Code snippets for media items. */
object MediaItemsKt {

  fun createMediaItemFromUri(videoUri: Uri) {
    // [START create_media_item_from_uri]
    val mediaItem = MediaItem.fromUri(videoUri)
    // [END create_media_item_from_uri]
  }

  fun createMediaItemFromUriWithTag(videoUri: Uri, mediaId: String, myAppData: Any) {
    // [START create_media_item_from_uri_with_tag]
    val mediaItem =
      MediaItem.Builder().setMediaId(mediaId).setTag(myAppData).setUri(videoUri).build()
    // [END create_media_item_from_uri_with_tag]
  }

  @OptIn(UnstableApi::class)
  fun imageDuration(imageUri: Uri) {
    // [START image_duration]
    val mediaItem = MediaItem.Builder().setUri(imageUri).setImageDurationMs(3000).build()
    // [END image_duration]
  }

  fun createMediaItemSetMimeType(hlsUri: Uri) {
    // [START create_media_item_set_mime_type]
    val mediaItem =
      MediaItem.Builder().setUri(hlsUri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
    // [END create_media_item_set_mime_type]
  }

  fun createMediaItemSetDrmProperties(
    videoUri: Uri,
    licenseUri: Uri,
    httpRequestHeaders: Map<String, String>,
  ) {
    // [START create_media_item_set_drm_properties]
    val mediaItem =
      MediaItem.Builder()
        .setUri(videoUri)
        .setDrmConfiguration(
          MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(licenseUri)
            .setMultiSession(true)
            .setLicenseRequestHeaders(httpRequestHeaders)
            .build()
        )
        .build()
    // [END create_media_item_set_drm_properties]
  }

  fun createMediaItemSideloadTextTracks(
    videoUri: Uri,
    subtitleUri: Uri,
    mimeType: String,
    language: String,
    selectionFlags: Int,
  ) {
    // [START create_media_item_sideload_text_tracks]
    val subtitle =
      MediaItem.SubtitleConfiguration.Builder(subtitleUri)
        .setMimeType(mimeType) // The correct MIME type (required).
        .setLanguage(language) // The subtitle language (optional).
        .setSelectionFlags(selectionFlags) // Selection flags for the track (optional).
        .build()
    val mediaItem =
      MediaItem.Builder().setUri(videoUri).setSubtitleConfigurations(listOf(subtitle)).build()
    // [END create_media_item_sideload_text_tracks]
  }

  fun createMediaItemSetClippingProperties(
    videoUri: Uri,
    startPositionMs: Long,
    endPositionMs: Long,
  ) {
    // [START create_media_item_set_clipping_properties]
    val mediaItem =
      MediaItem.Builder()
        .setUri(videoUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startPositionMs)
            .setEndPositionMs(endPositionMs)
            .build()
        )
        .build()
    // [END create_media_item_set_clipping_properties]
  }

  fun createMediaItemsSetAdTag(videoUri: Uri, adTagUri: Uri) {
    // [START create_media_items_set_ad_tag]
    val mediaItem =
      MediaItem.Builder()
        .setUri(videoUri)
        .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(adTagUri).build())
        .build()
    // [END create_media_items_set_ad_tag]
  }
}
