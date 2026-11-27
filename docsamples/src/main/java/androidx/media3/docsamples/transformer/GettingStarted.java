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
package androidx.media3.docsamples.transformer;

import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

/** Snippets for getting-started.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class GettingStarted {

  private GettingStarted() {}

  @OptIn(markerClass = UnstableApi.class)
  public static void createTransformer(
      Context context, Transformer.Listener transformerListener, String outputPath) {
    // [START create_transformer]
    MediaItem inputMediaItem = MediaItem.fromUri("path_to_input_file");
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(inputMediaItem).setRemoveAudio(true).build();
    Transformer transformer =
        new Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .addListener(transformerListener)
            .build();
    transformer.start(editedMediaItem, outputPath);
    // [END create_transformer]
  }

  private static void playOutput() {}

  @OptIn(markerClass = UnstableApi.class)
  private static void displayError(ExportException exportException) {}

  @OptIn(markerClass = UnstableApi.class)
  public static void listenToTransformer() {
    // [START listen_to_transformer]
    Transformer.Listener transformerListener =
        new Transformer.Listener() {
          @Override
          public void onCompleted(Composition composition, ExportResult result) {
            playOutput();
          }

          @Override
          public void onError(
              Composition composition, ExportResult result, ExportException exception) {
            displayError(exception);
          }
        };
    // [END listen_to_transformer]
  }

  @OptIn(markerClass = UnstableApi.class)
  private static void updateProgressInUi(
      @Transformer.ProgressState int progressState, ProgressHolder progressHolder) {}

  @OptIn(markerClass = UnstableApi.class)
  public static void getProgressUpdate(
      Transformer transformer, MediaItem inputMediaItem, String outputPath, Handler mainHandler) {
    // [START get_progress_update]
    transformer.start(inputMediaItem, outputPath);
    ProgressHolder progressHolder = new ProgressHolder();
    mainHandler.post(
        new Runnable() {
          @Override
          public void run() {
            @Transformer.ProgressState int progressState = transformer.getProgress(progressHolder);
            updateProgressInUi(progressState, progressHolder);
            if (progressState != PROGRESS_STATE_NOT_STARTED) {
              mainHandler.postDelayed(/* r= */ this, /* delayMillis= */ 500);
            }
          }
        });
    // [END get_progress_update]
  }
}
