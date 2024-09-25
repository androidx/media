/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.media3.transformer;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** The default {@link AssetLoader.Factory} implementation. */
@UnstableApi
public final class DefaultAssetLoaderFactory implements AssetLoader.Factory {

  // Limit decoded images to 4096x4096 - should be large enough for most image to video
  // transcode operations, and smaller than GL_MAX_TEXTURE_SIZE for most devices.
  // TODO: b/356072337 - consider reading this from GL_MAX_TEXTURE_SIZE. This requires an
  //   active OpenGL context.
  private static final int MAXIMUM_BITMAP_OUTPUT_DIMENSION = 4096;

  private final Context context;
  private final Codec.DecoderFactory decoderFactory;
  private final Clock clock;
  @Nullable private final MediaSource.Factory mediaSourceFactory;
  private final BitmapLoader bitmapLoader;

  private AssetLoader.@MonotonicNonNull Factory imageAssetLoaderFactory;
  private AssetLoader.@MonotonicNonNull Factory exoPlayerAssetLoaderFactory;

  /**
   * Creates an instance.
   *
   * <p>Uses {@link DataSourceBitmapLoader} to load images, setting the {@link
   * android.graphics.BitmapFactory.Options#inPreferredColorSpace} to {@link
   * android.graphics.ColorSpace.Named#SRGB} when possible.
   *
   * @param context The {@link Context}.
   * @param decoderFactory The {@link Codec.DecoderFactory} to use to decode the samples (if
   *     necessary).
   * @param clock The {@link Clock} to use. It should always be {@link Clock#DEFAULT}, except for
   *     testing.
   */
  public DefaultAssetLoaderFactory(
      Context context, Codec.DecoderFactory decoderFactory, Clock clock) {
    this.context = context.getApplicationContext();
    this.decoderFactory = decoderFactory;
    this.clock = clock;
    this.mediaSourceFactory = null;
    @Nullable BitmapFactory.Options options = null;
    if (Util.SDK_INT >= 26) {
      options = new BitmapFactory.Options();
      options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
    }
    this.bitmapLoader =
        new DataSourceBitmapLoader(
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            new DefaultDataSource.Factory(context),
            options,
            MAXIMUM_BITMAP_OUTPUT_DIMENSION);
  }

  /**
   * Creates an instance with the default {@link Clock} and {@link Codec.DecoderFactory}.
   *
   * <p>For multi-picture formats (e.g. gifs), a single image frame from the container is loaded.
   * The frame loaded is determined by the {@link BitmapLoader} implementation.
   *
   * @param context The {@link Context}.
   * @param bitmapLoader The {@link BitmapLoader} to use to load and decode images.
   */
  public DefaultAssetLoaderFactory(Context context, BitmapLoader bitmapLoader) {
    this.context = context.getApplicationContext();
    this.decoderFactory = new DefaultDecoderFactory(context);
    this.clock = Clock.DEFAULT;
    this.mediaSourceFactory = null;
    this.bitmapLoader = bitmapLoader;
  }

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   * @param decoderFactory The {@link Codec.DecoderFactory} to use to decode the samples (if
   *     necessary).
   * @param clock The {@link Clock} to use. It should always be {@link Clock#DEFAULT}, except for
   *     testing.
   * @param mediaSourceFactory The {@link MediaSource.Factory} to use to retrieve the samples to
   *     transform when an {@link ExoPlayerAssetLoader} is used.
   * @param bitmapLoader The {@link BitmapLoader} to use to load and decode images.
   */
  public DefaultAssetLoaderFactory(
      Context context,
      Codec.DecoderFactory decoderFactory,
      Clock clock,
      MediaSource.Factory mediaSourceFactory,
      BitmapLoader bitmapLoader) {
    this.context = context.getApplicationContext();
    this.decoderFactory = decoderFactory;
    this.clock = clock;
    this.mediaSourceFactory = mediaSourceFactory;
    this.bitmapLoader = bitmapLoader;
  }

  @Override
  public AssetLoader createAssetLoader(
      EditedMediaItem editedMediaItem,
      Looper looper,
      AssetLoader.Listener listener,
      CompositionSettings compositionSettings) {
    MediaItem mediaItem = editedMediaItem.mediaItem;
    if (isImage(mediaItem)) {
      if (imageAssetLoaderFactory == null) {
        imageAssetLoaderFactory = new ImageAssetLoader.Factory(context, bitmapLoader);
      }
      return imageAssetLoaderFactory.createAssetLoader(
          editedMediaItem, looper, listener, compositionSettings);
    }
    if (exoPlayerAssetLoaderFactory == null) {
      exoPlayerAssetLoaderFactory =
          mediaSourceFactory != null
              ? new ExoPlayerAssetLoader.Factory(context, decoderFactory, clock, mediaSourceFactory)
              : new ExoPlayerAssetLoader.Factory(context, decoderFactory, clock);
    }
    return exoPlayerAssetLoaderFactory.createAssetLoader(
        editedMediaItem, looper, listener, compositionSettings);
  }

  private boolean isImage(MediaItem mediaItem) {
    @Nullable String mimeType = ImageAssetLoader.getImageMimeType(context, mediaItem);
    return mimeType != null && MimeTypes.isImage(mimeType);
  }
}
