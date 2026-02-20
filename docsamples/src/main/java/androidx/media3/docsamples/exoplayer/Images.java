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

import static com.bumptech.glide.request.RequestOptions.diskCacheStrategyOf;
import static java.lang.Math.max;

import android.content.Context;
import android.graphics.Point;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ExternalLoader;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.integration.concurrent.GlideFutures;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

/** Snippets for Images. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Images {

  @OptIn(markerClass = UnstableApi.class)
  public static void imagesCreateMediaItem(Context context, Uri imageUri) {
    // [START images_create_media_item]
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media item to be played with the desired duration.
    player.setMediaItem(new MediaItem.Builder().setUri(imageUri).setImageDurationMs(2000).build());
    // Prepare the player.
    player.prepare();
    // [END images_create_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void imagesCreateMediaSource(Uri imageUri, Context context) {
    // [START images_create_media_source]
    // Create a data source factory.
    DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
    // Create a media item with the image URI and the desired duration.
    MediaItem mediaItem = new MediaItem.Builder().setUri(imageUri).setImageDurationMs(2000).build();
    // Create a progressive media source for this media item.
    MediaSource mediaSource =
        new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media source to be played.
    player.setMediaSource(mediaSource);
    // Prepare the player.
    player.prepare();
    // [END images_create_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void imagesGlideMimeType(Uri imageUri) {
    // [START images_glide_mime_type]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(imageUri)
            .setMimeType(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
            .build();
    // [END images_glide_mime_type]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void imagesGlideDecoder(Context context) {
    // [START images_glide_decoder]
    ImageDecoder.Factory glideImageDecoderFactory =
        new ExternallyLoadedImageDecoder.Factory(
            request -> {
              Point displaySize = Util.getCurrentDisplayModeSize(context);
              return GlideFutures.submit(
                  Glide.with(context)
                      .asBitmap()
                      .load(request.uri)
                      .override(max(displaySize.x, displaySize.y)));
            });
    Player player =
        new ExoPlayer.Builder(context)
            .setRenderersFactory(
                new DefaultRenderersFactory(context) {
                  @Override
                  protected ImageDecoder.Factory getImageDecoderFactory(Context context) {
                    return glideImageDecoderFactory;
                  }
                })
            .build();
    // [END images_glide_decoder]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void imagesGlidePreload(Context context) {
    // [START images_glide_preload]
    ExternalLoader glidePreloader =
        request ->
            GlideFutures.submit(
                Glide.with(context)
                    .asFile()
                    .apply(
                        diskCacheStrategyOf(DiskCacheStrategy.DATA)
                            .priority(Priority.HIGH)
                            .skipMemoryCache(true))
                    .load(request.uri));
    Player player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setExternalImageLoader(glidePreloader))
            .build();
    // [END images_glide_preload]
  }
}
