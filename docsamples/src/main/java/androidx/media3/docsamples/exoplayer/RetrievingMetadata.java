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

import androidx.annotation.OptIn;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;

/** Code snippets for the Retrieving metadata guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass",
  "PatternVariableCanBeUsed"
})
public final class RetrievingMetadata {

  private static void handleTitle(CharSequence title) {}

  public static void onMediaMetadataChanged() {
    new Player.Listener() {
      // [START on_media_metadata_changed]
      @Override
      public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
        if (mediaMetadata.title != null) {
          handleTitle(mediaMetadata.title);
        }
      }
      // [END on_media_metadata_changed]
    };
  }

  @OptIn(markerClass = UnstableApi.class)
  private static void handleMetadata(
      TrackGroupArray trackGroups, Timeline timeline, Long durationUs) {}

  private static void handleFailure(Throwable throwable) {}

  @OptIn(markerClass = UnstableApi.class)
  private static void handleMotionPhotoMetadata(MotionPhotoMetadata motionPhotoMetadata) {}

  @OptIn(markerClass = UnstableApi.class)
  public static void retrieveMotionPhotoMetadata(TrackGroupArray trackGroups) {
    // [START retrieve_motion_photo_metadata]
    for (int i = 0; i < trackGroups.length; i++) {
      TrackGroup trackGroup = trackGroups.get(i);
      Metadata metadata = trackGroup.getFormat(0).metadata;
      if (metadata != null && metadata.length() == 1) {
        Metadata.Entry metadataEntry = metadata.get(0);
        if (metadataEntry instanceof MotionPhotoMetadata) {
          MotionPhotoMetadata motionPhotoMetadata = (MotionPhotoMetadata) metadataEntry;
          handleMotionPhotoMetadata(motionPhotoMetadata);
        }
      }
    }
    // [END retrieve_motion_photo_metadata]
  }

  private RetrievingMetadata() {}
}
