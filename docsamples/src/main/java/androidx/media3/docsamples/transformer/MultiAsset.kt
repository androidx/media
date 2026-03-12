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

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Transformer

/** Snippets for multi-asset.md. */
object MultiAssetKt {

  @OptIn(UnstableApi::class)
  fun transformerComposition(
    transformer: Transformer,
    video1Uri: Uri,
    video2Uri: Uri,
    audioUri: Uri,
    filePath: String,
  ) {
    // [START transformer_composition]
    val video1 = EditedMediaItem.Builder(MediaItem.fromUri(video1Uri)).build()

    val video2 = EditedMediaItem.Builder(MediaItem.fromUri(video2Uri)).build()

    val videoSequence =
      EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
        .addItems(video1, video2)
        .build()

    val backgroundAudio = EditedMediaItem.Builder(MediaItem.fromUri(audioUri)).build()

    val backgroundAudioSequence =
      EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        .addItem(backgroundAudio)
        .setIsLooping(true) // Loop audio track through duration of videoSequence
        .build()

    val composition = Composition.Builder(videoSequence, backgroundAudioSequence).build()

    transformer.start(composition, filePath)
    // [END transformer_composition]
  }
}
