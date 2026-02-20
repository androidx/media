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
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder.ExternalImageRequest
import androidx.media3.exoplayer.image.ImageDecoder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ExternalLoader
import androidx.media3.exoplayer.source.ExternalLoader.LoadRequest
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.integration.concurrent.GlideFutures
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlin.math.max

@OptIn(UnstableApi::class)
object ImagesKt {

  fun imagesCreateMediaItem(context: Context, imageUri: Uri) {
    // [START images_create_media_item]
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media item to be played with the desired duration.
    player.setMediaItem(MediaItem.Builder().setUri(imageUri).setImageDurationMs(2000).build())
    // Prepare the player.
    player.prepare()
    // [END images_create_media_item]
  }

  fun imagesCreateMediaSource(imageUri: Uri, context: Context) {
    // [START images_create_media_source]
    // Create a data source factory.
    val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    // Create a media item with the image URI and the desired duration.
    val mediaItem = MediaItem.Builder().setUri(imageUri).setImageDurationMs(2000).build()
    // Create a progressive media source for this media item.
    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media source to be played.
    player.setMediaSource(mediaSource)
    // Prepare the player.
    player.prepare()
    // [END images_create_media_source]
  }

  fun imagesGlideMimeType(imageUri: Uri) {
    // [START images_glide_mime_type]
    val mediaItem =
      MediaItem.Builder()
        .setUri(imageUri)
        .setMimeType(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
        .build()
    // [END images_glide_mime_type]
  }

  fun imagesGlideDecoder(context: Context) {
    // [START images_glide_decoder]
    val glideImageDecoderFactory: ImageDecoder.Factory =
      ExternallyLoadedImageDecoder.Factory { request: ExternalImageRequest ->
        val displaySize = Util.getCurrentDisplayModeSize(context)
        GlideFutures.submit(
          Glide.with(context)
            .asBitmap()
            .load(request.uri)
            .override(max(displaySize.x, displaySize.y))
        )
      }
    val player: Player =
      ExoPlayer.Builder(context)
        .setRenderersFactory(
          object : DefaultRenderersFactory(context) {
            override fun getImageDecoderFactory(context: Context): ImageDecoder.Factory {
              return glideImageDecoderFactory
            }
          }
        )
        .build()
    // [END images_glide_decoder]
  }

  fun imagesGlidePreload(context: Context) {
    // [START images_glide_preload]
    val glidePreloader = ExternalLoader { request: LoadRequest ->
      GlideFutures.submit(
        Glide.with(context)
          .asFile()
          .apply(
            RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.DATA)
              .priority(Priority.HIGH)
              .skipMemoryCache(true)
          )
          .load(request.uri)
      )
    }
    val player: Player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setExternalImageLoader(glidePreloader)
        )
        .build()
    // [END images_glide_preload]
  }
}
