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

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;
import androidx.media3.inspector.MetadataRetriever;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.Executor;

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
  public static void retrieveMetadataWithoutPlayback(
      Context context, MediaItem mediaItem, Executor executor) {
    // [START retrieve_metadata_without_playback]
    try (MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, mediaItem).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = metadataRetriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = metadataRetriever.retrieveTimeline();
      ListenableFuture<Long> durationUsFuture = metadataRetriever.retrieveDurationUs();
      ListenableFuture<List<Object>> allFutures =
          Futures.allAsList(trackGroupsFuture, timelineFuture, durationUsFuture);
      Futures.addCallback(
          allFutures,
          new FutureCallback<List<Object>>() {
            @Override
            public void onSuccess(List<Object> result) {
              handleMetadata(
                  Futures.getUnchecked(trackGroupsFuture),
                  Futures.getUnchecked(timelineFuture),
                  Futures.getUnchecked(durationUsFuture));
            }

            @Override
            public void onFailure(Throwable t) {
              handleFailure(t);
            }
          },
          executor);
    }
    // [END retrieve_metadata_without_playback]
  }

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
