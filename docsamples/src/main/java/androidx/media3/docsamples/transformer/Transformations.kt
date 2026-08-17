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
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer

/** Snippets for transformations.md. */
object TransformationsKt {

  @OptIn(UnstableApi::class)
  fun transcodeBetweenFormats(context: Context) {
    // [START transcode_between_formats]
    Transformer.Builder(context)
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .build()
    // [END transcode_between_formats]
  }

  @OptIn(UnstableApi::class)
  fun removeAudio(inputMediaItem: MediaItem) {
    // [START remove_audio]
    EditedMediaItem.Builder(inputMediaItem).setRemoveAudio(true).build()
    // [END remove_audio]
  }

  fun transformationsTrimAClip(uri: Uri) {
    // [START trim_a_clip]
    val inputMediaItem =
      MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(10_000)
            .setEndPositionMs(20_000)
            .build()
        )
        .build()
    // [END trim_a_clip]
  }

  @OptIn(UnstableApi::class)
  fun transformationsApplyVideoEffects(uri: Uri) {
    // [START apply_video_effects]
    EditedMediaItem.Builder(MediaItem.fromUri(uri))
      .setEffects(
        Effects(
          /* audioProcessors= */ listOf(),
          /* videoEffects= */ listOf(Presentation.createForHeight(480)),
        )
      )
      .build()
    // [END apply_video_effects]
  }

  @OptIn(UnstableApi::class)
  fun transformationsApplyVideoEffectsScaling(uri: Uri) {
    // [START apply_video_effects_scaling]
    val editedMediaItem =
      EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(
          Effects(
            /* audioProcessors= */ listOf(),
            /* videoEffects= */ listOf(
              ScaleAndRotateTransformation.Builder().setScale(.5f, .5f).build()
            ),
          )
        )
        .build()
    // [END apply_video_effects_scaling]
  }

  @OptIn(UnstableApi::class)
  fun transformationsApplyVideoEffectsRotation(uri: Uri) {
    // [START apply_video_effects_rotation]
    EditedMediaItem.Builder(MediaItem.fromUri(uri))
      .setEffects(
        Effects(
          /* audioProcessors= */ listOf(),
          /* videoEffects= */ listOf(
            ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()
          ),
        )
      )
      .build()
    // [END apply_video_effects_rotation]
  }

  @OptIn(UnstableApi::class)
  fun transformationsImageInput(imageUri: Uri) {
    // [START transformations_input_image]
    val imageMediaItem =
      MediaItem.Builder()
        .setUri(imageUri)
        .setImageDurationMs(5000) // 5 seconds
        .build()

    val editedImageItem =
      EditedMediaItem.Builder(imageMediaItem)
        .setFrameRate(30) // 30 frames per second
        .build()
    // [END transformations_input_image]
  }

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  fun transformationsMp4EditList(context: Context) {
    // [START mp4-edit-lists]
    Transformer.Builder(context).experimentalSetMp4EditListTrimEnabled(true).build()
    // [END mp4-edit-lists]
  }

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  fun transformationsTrimOptimization(context: Context) {
    // [START trim_optimization]
    Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build()
    // [END trim_optimization]
  }
}
