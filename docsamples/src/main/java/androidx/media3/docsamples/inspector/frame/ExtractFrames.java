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

package androidx.media3.docsamples.inspector.frame;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.inspector.frame.FrameExtractor;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

@SuppressWarnings({"CheckReturnValue", "unused"})
@OptIn(markerClass = UnstableApi.class)
final class ExtractFrames {

  // [START extract_frames]
  public void extractFrames(Context context, MediaItem mediaItem) {
    // 1. Build the frame extractor.
    // `FrameExtractor` implements `AutoCloseable`, so use try-with-resources
    // so that the resources are automatically released.
    try (FrameExtractor frameExtractor = new FrameExtractor.Builder(context, mediaItem).build()) {
      // 2. Extract frames asynchronously.
      ListenableFuture<FrameExtractor.Frame> frameFuture = frameExtractor.getFrame(5000L);
      ListenableFuture<FrameExtractor.Frame> thumbnailFuture = frameExtractor.getThumbnail();

      ListenableFuture<List<Object>> allFutures = Futures.allAsList(frameFuture, thumbnailFuture);
      Futures.addCallback(
          allFutures,
          new FutureCallback<List<Object>>() {
            @Override
            public void onSuccess(List<Object> result) {
              handleFrame(Futures.getUnchecked(frameFuture), Futures.getUnchecked(thumbnailFuture));
            }

            @Override
            public void onFailure(Throwable t) {
              handleFailure(t);
            }
          },
          directExecutor());
    }
  }

  // [END extract_frames]

  private void handleFrame(FrameExtractor.Frame frame, FrameExtractor.Frame thumbnail) {}

  private void handleFailure(Throwable t) {}
}
