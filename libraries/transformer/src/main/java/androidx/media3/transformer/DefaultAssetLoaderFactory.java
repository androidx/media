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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.transformer.TransformerUtil.isImage;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.media.metrics.EditingSession;
import android.media.metrics.LogSessionId;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** The default {@link AssetLoader.Factory} implementation. */
@UnstableApi
public final class DefaultAssetLoaderFactory implements AssetLoader.Factory {

  /** A builder for {@link DefaultAssetLoaderFactory} instances. */
  public static final class Builder {
    private final Context context;

    private Codec.DecoderFactory decoderFactory;
    private Clock clock;

    @Nullable private MediaSource.Factory mediaSourceFactory;
    @Nullable private BitmapLoader bitmapLoader;
    @Nullable private TrackSelector.Factory trackSelectorFactory;
    @Nullable private LogSessionId logSessionId;

    /**
     * Creates a builder.
     *
     * @param context The {@link Context}.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      this.decoderFactory = new DefaultDecoderFactory.Builder(context).build();
      this.clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link Codec.DecoderFactory} to use to decode the samples (if necessary).
     *
     * <p>The default is a {@link DefaultDecoderFactory}.
     *
     * @param decoderFactory The {@link Codec.DecoderFactory}.
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setDecoderFactory(Codec.DecoderFactory decoderFactory) {
      this.decoderFactory = decoderFactory;
      return this;
    }

    /**
     * Sets the {@link Clock} to use.
     *
     * <p>The default is {@link Clock#DEFAULT}. This should only be changed for testing.
     *
     * @param clock The {@link Clock}.
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Sets the {@link MediaSource.Factory} to use to retrieve samples to transform when an {@link
     * ExoPlayerAssetLoader} is used.
     *
     * <p>If not set, a {@link DefaultMediaSourceFactory} is used by default.
     *
     * <p>This factory is used to configure the underlying {@link
     * ExoPlayer.Builder#setMediaSourceFactory(MediaSource.Factory)}.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory}.
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(@Nullable MediaSource.Factory mediaSourceFactory) {
      this.mediaSourceFactory = mediaSourceFactory;
      return this;
    }

    /**
     * Sets the {@link BitmapLoader} to use to load and decode images.
     *
     * <p>If not set, a {@link DataSourceBitmapLoader} is used by default. When possible based on
     * SDK version the {@link BitmapFactory.Options#inPreferredColorSpace} will be set to {@link
     * ColorSpace.Named#SRGB}.
     *
     * @param bitmapLoader The {@link BitmapLoader}.
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setBitmapLoader(BitmapLoader bitmapLoader) {
      this.bitmapLoader = bitmapLoader;
      return this;
    }

    /**
     * Sets the {@link TrackSelector.Factory} to use when selecting the track to transform.
     *
     * <p>If not set, a factory that creates a {@link DefaultTrackSelector} which forces the highest
     * supported bitrate is used.
     *
     * <p>This factory is used to create the {@link TrackSelector} that configures the underlying
     * {@link ExoPlayer.Builder#setTrackSelector(TrackSelector)}.
     *
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setTrackSelectorFactory(@Nullable TrackSelector.Factory trackSelectorFactory) {
      this.trackSelectorFactory = trackSelectorFactory;
      return this;
    }

    /**
     * Sets the optional {@link LogSessionId} of the {@link EditingSession}.
     *
     * <p>The default is {@code null}.
     *
     * @param logSessionId The {@link LogSessionId}.
     * @return The builder.
     */
    @CanIgnoreReturnValue
    public Builder setLogSessionId(@Nullable LogSessionId logSessionId) {
      this.logSessionId = logSessionId;
      return this;
    }

    /** Constructs a {@link DefaultAssetLoaderFactory} instance. */
    public DefaultAssetLoaderFactory build() {
      return new DefaultAssetLoaderFactory(this);
    }
  }

  private static final String TAG = "DefaultAssetLoaderFact";

  private final Context context;
  private final Codec.DecoderFactory decoderFactory;
  private final Clock clock;
  @Nullable private final MediaSource.Factory mediaSourceFactory;
  private final BitmapLoader bitmapLoader;
  @Nullable private final TrackSelector.Factory trackSelectorFactory;
  @Nullable private final LogSessionId logSessionId;

  private AssetLoader.@MonotonicNonNull Factory imageAssetLoaderFactory;
  private AssetLoader.@MonotonicNonNull Factory exoPlayerAssetLoaderFactory;

  private DefaultAssetLoaderFactory(Builder builder) {
    this.context = builder.context;
    this.decoderFactory = builder.decoderFactory;
    this.clock = builder.clock;
    this.mediaSourceFactory = builder.mediaSourceFactory;
    this.trackSelectorFactory = builder.trackSelectorFactory;
    this.logSessionId = builder.logSessionId;

    if (builder.bitmapLoader == null) {
      @Nullable BitmapFactory.Options options = null;
      if (SDK_INT >= 26) {
        options = new BitmapFactory.Options();
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
      }
      this.bitmapLoader =
          new DataSourceBitmapLoader(
              MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
              new DefaultDataSource.Factory(context),
              options,
              GlUtil.MAX_BITMAP_DECODING_SIZE);
    } else {
      this.bitmapLoader = builder.bitmapLoader;
    }
  }

  /**
   * Creates an instance.
   *
   * <p>Uses {@link DataSourceBitmapLoader} to load images, setting the {@link
   * BitmapFactory.Options#inPreferredColorSpace} to {@link ColorSpace.Named#SRGB} when possible.
   *
   * @param context The {@link Context}.
   * @param decoderFactory The {@link Codec.DecoderFactory} to use to decode the samples (if
   *     necessary).
   * @param clock The {@link Clock} to use. It should always be {@link Clock#DEFAULT}, except for
   *     testing.
   * @param logSessionId The optional {@link LogSessionId} of the {@link EditingSession}.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultAssetLoaderFactory(
      Context context,
      Codec.DecoderFactory decoderFactory,
      Clock clock,
      @Nullable LogSessionId logSessionId) {
    this(
        new Builder(context)
            .setDecoderFactory(decoderFactory)
            .setClock(clock)
            .setLogSessionId(logSessionId));
  }

  /**
   * Creates an instance with the default {@link Clock} and {@link Codec.DecoderFactory}.
   *
   * <p>For multi-picture formats (e.g. gifs), a single image frame from the container is loaded.
   * The frame loaded is determined by the {@link BitmapLoader} implementation.
   *
   * @param context The {@link Context}.
   * @param bitmapLoader The {@link BitmapLoader} to use to load and decode images.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultAssetLoaderFactory(Context context, BitmapLoader bitmapLoader) {
    this(new Builder(context).setBitmapLoader(bitmapLoader));
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
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultAssetLoaderFactory(
      Context context,
      Codec.DecoderFactory decoderFactory,
      Clock clock,
      @Nullable MediaSource.Factory mediaSourceFactory,
      BitmapLoader bitmapLoader) {
    this(
        new Builder(context)
            .setDecoderFactory(decoderFactory)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBitmapLoader(bitmapLoader));
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
   * @param trackSelectorFactory The {@link TrackSelector.Factory} to use when selecting the track
   *     to transform.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultAssetLoaderFactory(
      Context context,
      Codec.DecoderFactory decoderFactory,
      Clock clock,
      @Nullable MediaSource.Factory mediaSourceFactory,
      BitmapLoader bitmapLoader,
      TrackSelector.Factory trackSelectorFactory) {
    this(
        new Builder(context)
            .setDecoderFactory(decoderFactory)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBitmapLoader(bitmapLoader)
            .setTrackSelectorFactory(trackSelectorFactory));
  }

  @Override
  public AssetLoader createAssetLoader(
      EditedMediaItem editedMediaItem,
      Looper looper,
      AssetLoader.Listener listener,
      CompositionSettings compositionSettings) {
    MediaItem mediaItem = editedMediaItem.mediaItem;
    if (isImage(context, mediaItem)
        && checkNotNull(mediaItem.localConfiguration).imageDurationMs != C.TIME_UNSET) {
      // Checks that mediaItem.localConfiguration.imageDurationMs is explicitly set.
      // This is particularly important for motion photos, where setting imageDurationMs ensures
      // that the motion photo is handled as an image.
      if (imageAssetLoaderFactory == null) {
        imageAssetLoaderFactory = new ImageAssetLoader.Factory(context, bitmapLoader);
      }
      return imageAssetLoaderFactory.createAssetLoader(
          editedMediaItem, looper, listener, compositionSettings);
    }
    if (exoPlayerAssetLoaderFactory == null) {
      exoPlayerAssetLoaderFactory =
          new ExoPlayerAssetLoader.Factory.Builder(context, decoderFactory)
              .setClock(clock)
              .setMediaSourceFactory(mediaSourceFactory)
              .setTrackSelectorFactory(trackSelectorFactory)
              .setLogSessionId(logSessionId)
              .build();
    }
    return exoPlayerAssetLoaderFactory.createAssetLoader(
        editedMediaItem, looper, listener, compositionSettings);
  }
}
