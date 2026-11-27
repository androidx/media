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

package androidx.media3.docsamples.inspector;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.inspector.MetadataRetriever;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

@SuppressWarnings("unused")
@OptIn(markerClass = UnstableApi.class)
final class RetrieveMetadata {

  // [START retrieve_metadata]
  public void retrieveMetadata(Context context, MediaItem mediaItem) {
    // 1. Build the retriever.
    // `MetadataRetriever` implements `AutoCloseable`, so use try-with-resources
    // so that the resources are automatically released.
    try (MetadataRetriever retriever = new MetadataRetriever.Builder(context, mediaItem).build()) {
      // 2. Retrieve metadata asynchronously.
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationUsFuture = retriever.retrieveDurationUs();

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
          directExecutor());
    }
  }

  // [END retrieve_metadata]

  private void handleMetadata(TrackGroupArray trackGroups, Timeline timeline, Long durationUs) {}

  private void handleFailure(Throwable t) {}
}
