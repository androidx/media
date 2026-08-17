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

package androidx.media3.docsamples.transformer

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Transformer.ProgressState

/** Snippets for getting-started.md. */
object GettingStartedKt {

  @OptIn(UnstableApi::class)
  fun createTransformer(
    context: Context,
    transformerListener: Transformer.Listener,
    outputPath: String,
  ) {
    // [START create_transformer]
    val inputMediaItem = MediaItem.fromUri("path_to_input_file")
    val editedMediaItem = EditedMediaItem.Builder(inputMediaItem).setRemoveAudio(true).build()
    val transformer =
      Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H265)
        .addListener(transformerListener)
        .build()
    transformer.start(editedMediaItem, outputPath)
    // [END create_transformer]
  }

  private fun playOutput() {}

  @OptIn(UnstableApi::class) fun displayError(exportException: ExportException) {}

  @OptIn(UnstableApi::class)
  fun listenToTransformer() {
    // [START listen_to_transformer]
    val transformerListener: Transformer.Listener =
      object : Transformer.Listener {
        override fun onCompleted(composition: Composition, result: ExportResult) {
          playOutput()
        }

        override fun onError(
          composition: Composition,
          result: ExportResult,
          exception: ExportException,
        ) {
          displayError(exception)
        }
      }
    // [END listen_to_transformer]
  }

  @OptIn(UnstableApi::class)
  private fun updateProgressInUi(
    progressState: @ProgressState Int,
    progressHolder: ProgressHolder,
  ) {}

  @OptIn(UnstableApi::class)
  fun getProgressUpdate(
    transformer: Transformer,
    inputMediaItem: MediaItem,
    outputPath: String,
    mainHandler: Handler,
  ) {
    // [START get_progress_update]
    transformer.start(inputMediaItem, outputPath)
    val progressHolder = ProgressHolder()
    mainHandler.post(
      object : Runnable {
        override fun run() {
          val progressState: @ProgressState Int = transformer.getProgress(progressHolder)
          updateProgressInUi(progressState, progressHolder)
          if (progressState != Transformer.PROGRESS_STATE_NOT_STARTED) {
            mainHandler.postDelayed(/* r= */ this, /* delayMillis= */ 500)
          }
        }
      }
    )
    // [END get_progress_update]
  }
}
