/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.docsamples;

import android.net.Uri;
import androidx.media3.common.MediaItem;

/** Snippets for the ad insertion developer guide. */
@SuppressWarnings({"unused"})
public final class AdInsertion {

  private AdInsertion() {}

  public static void createMediaItem(Uri videoUri, Uri adTagUri) {
    // [START create_media_item]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setAdsConfiguration(new MediaItem.AdsConfiguration.Builder(adTagUri).build())
            .build();
    // [END create_media_item]
  }
}
