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

package androidx.media3.docsamples.exoplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

// Code snippets for the Shrinking guide.

object ShrinkingKt {

  @OptIn(UnstableApi::class)
  fun customRenderersFactory(context: Context) {
    // [START custom_renderers_factory]
    val audioOnlyRenderersFactory =
      RenderersFactory {
        handler: Handler,
        videoListener: VideoRendererEventListener,
        audioListener: AudioRendererEventListener,
        textOutput: TextOutput,
        metadataOutput: MetadataOutput ->
        arrayOf<Renderer>(
          MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener)
        )
      }
    val player = ExoPlayer.Builder(context, audioOnlyRenderersFactory).build()
    // [END custom_renderers_factory]
  }

  @OptIn(UnstableApi::class)
  fun customExtractorsFactory(context: Context) {
    // [START custom_extractors_factory]
    val mp4ExtractorFactory = ExtractorsFactory {
      arrayOf<Extractor>(Mp4Extractor(DefaultSubtitleParserFactory()))
    }
    val player =
      ExoPlayer.Builder(context, DefaultMediaSourceFactory(context, mp4ExtractorFactory)).build()
    // [END custom_extractors_factory]
  }

  @OptIn(UnstableApi::class)
  fun noExtractorsFactory(context: Context) {
    // [START no_extractors_factory]
    val player =
      ExoPlayer.Builder(context, DefaultMediaSourceFactory(context, ExtractorsFactory.EMPTY))
        .build()
    // [END no_extractors_factory]
  }

  @OptIn(UnstableApi::class)
  fun customMediaSourceFactory(context: Context, customMediaSourceFactory: MediaSource.Factory) {
    // [START custom_media_source_factory]
    val player = ExoPlayer.Builder(context, customMediaSourceFactory).build()
    // [END custom_media_source_factory]
  }

  @OptIn(UnstableApi::class)
  fun directMediaSourceInstantiation(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    customExtractorsFactory: ExtractorsFactory,
    uri: Uri,
  ) {
    // [START direct_media_source_instantiation]
    val player = ExoPlayer.Builder(context, MediaSource.Factory.UNSUPPORTED).build()
    val mediaSource =
      ProgressiveMediaSource.Factory(dataSourceFactory, customExtractorsFactory)
        .createMediaSource(MediaItem.fromUri(uri))
    // [END direct_media_source_instantiation]
  }
}
