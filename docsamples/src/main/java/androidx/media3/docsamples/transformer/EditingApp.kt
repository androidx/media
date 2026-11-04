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
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import kotlin.math.min

/** Snippets for editing-app.md. */
object EditingAppKt {

  @OptIn(UnstableApi::class)
  fun transcodeBetweenFormats(context: Context) {
    // [START transcode_between_formats]
    val transformer =
      Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H265)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .build()
    // [END transcode_between_formats]
  }

  @OptIn(UnstableApi::class)
  fun setHdrMode(editedMediaItem: EditedMediaItem) {
    // [START set_hdr_mode]
    val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
    val composition =
      Composition.Builder(videoSequence).setHdrMode(Composition.HDR_MODE_KEEP_HDR).build()
    // [END set_hdr_mode]
  }

  fun trimVideo(videoUri: Uri) {
    // [START trim_video]
    val clippingConfiguration =
      MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(10_000) // start at 10 seconds
        .setEndPositionMs(20_000) // end at 20 seconds
        .build()
    val mediaItem =
      MediaItem.Builder().setUri(videoUri).setClippingConfiguration(clippingConfiguration).build()
    // [END trim_video]
  }

  @OptIn(UnstableApi::class)
  fun useBuiltInEffects(mediaItem: MediaItem) {
    // [START use_built_in_effects]
    val channelMixingProcessor = ChannelMixingAudioProcessor()
    channelMixingProcessor.putChannelMixingMatrix(
      ChannelMixingMatrix.createForConstantGain(
        /* inputChannelCount= */ 2,
        /* outputChannelCount= */ 1,
      )
    )
    val rotateEffect = ScaleAndRotateTransformation.Builder().setRotationDegrees(60f).build()
    val cropEffect = Crop(-0.5f, 0.5f, -0.5f, 0.5f)
    val effects = Effects(listOf(channelMixingProcessor), listOf(rotateEffect, cropEffect))

    val editedMediaItem = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
    // [END use_built_in_effects]
  }

  @OptIn(UnstableApi::class)
  fun createCustomEffects(inputMediaItem: MediaItem) {
    // [START create_custom_effects]
    val zoomEffect = MatrixTransformation { presentationTimeUs ->
      val transformationMatrix = Matrix()
      // Set the scaling factor based on the playback position
      val scale = min(1f, presentationTimeUs / 1_000_000f)
      transformationMatrix.postScale(/* sx= */ scale, /* sy= */ scale)
      transformationMatrix
    }

    val editedMediaItem =
      EditedMediaItem.Builder(inputMediaItem)
        .setEffects(Effects(listOf(), listOf(zoomEffect)))
        .build()
    // [END create_custom_effects]
  }

  @OptIn(ExperimentalApi::class, UnstableApi::class)
  fun previewComposition(context: Context, composition: Composition) {
    // [START preview_composition]
    val compositionPlayer = CompositionPlayer.Builder(context).build()
    compositionPlayer.setComposition(composition)
    compositionPlayer.prepare()
    compositionPlayer.play()
    // [END preview_composition]
  }

  @OptIn(UnstableApi::class)
  fun previewVideoEffects(context: Context, inputMediaItem: MediaItem, zoomEffect: Effect) {
    // [START preview_video_effects]
    val player =
      ExoPlayer.Builder(context).build().also { exoPlayer ->
        exoPlayer.setMediaItem(inputMediaItem)
        exoPlayer.setVideoEffects(listOf(zoomEffect))
        exoPlayer.prepare()
      }
    // [END preview_video_effects]
  }

  @OptIn(UnstableApi::class)
  fun previewAudioEffects(context: Context, channelMixingProcessor: ChannelMixingAudioProcessor) {
    // [START preview_audio_effects]
    val player =
      ExoPlayer.Builder(
          context,
          object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
              context: Context,
              enableFloatOutput: Boolean,
              enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink {
              return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(channelMixingProcessor))
                .build()
            }
          },
        )
        .build()
    // [END preview_audio_effects]
  }

  @OptIn(UnstableApi::class)
  fun startTransformation(
    context: Context,
    listener: Transformer.Listener,
    editedMediaItem: EditedMediaItem,
    outputPath: String,
  ) {
    // [START start_transformation]
    val transformer = Transformer.Builder(context).addListener(listener).build()
    transformer.start(editedMediaItem, outputPath)
    // [END start_transformation]
  }
}
