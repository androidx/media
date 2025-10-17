/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.datasource;

import static androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType;
import static androidx.media3.datasource.BitmapUtil.decode;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.concurrent.Executors;

/**
 * A {@link BitmapLoader} implementation that uses a {@link DataSource} to support fetching images
 * from URIs and {@link BitmapFactory} to load them into {@link Bitmap}.
 *
 * <p>Loading tasks are delegated to a {@link ListeningExecutorService} defined during construction.
 * If no executor service is passed, all tasks are delegated to a single-thread executor service
 * that is shared between instances of this class.
 */
@UnstableApi
public final class DataSourceBitmapLoader implements BitmapLoader {

  public static final Supplier<ListeningExecutorService> DEFAULT_EXECUTOR_SERVICE =
      Suppliers.memoize(
          () -> MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()));

  /** A builder for {@link DataSourceBitmapLoader} instances. */
  public static final class Builder {

    private final Context context;

    @Nullable private ListeningExecutorService listeningExecutorService;
    @Nullable private DataSource.Factory dataSourceFactory;
    @Nullable private BitmapFactory.Options options;
    private int maximumOutputDimension;
    private boolean makeShared;

    /**
     * Creates a builder.
     *
     * @param context The context.
     */
    public Builder(Context context) {
      this.context = context;
      this.maximumOutputDimension = C.LENGTH_UNSET;
    }

    /**
     * Sets the {@link DataSource.Factory} to be used to create {@link DataSource} instances for
     * loading bitmaps.
     *
     * <p>If not set, a {@link DefaultDataSource.Factory} will be used.
     *
     * @param dataSourceFactory A {@link DataSource.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setDataSourceFactory(DataSource.Factory dataSourceFactory) {
      this.dataSourceFactory = dataSourceFactory;
      return this;
    }

    /**
     * Sets the {@link ListeningExecutorService} to be used for loading bitmaps.
     *
     * <p>If not set, {@link #DEFAULT_EXECUTOR_SERVICE} will be used.
     *
     * @param listeningExecutorService A {@link ListeningExecutorService}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setExecutorService(ListeningExecutorService listeningExecutorService) {
      this.listeningExecutorService = listeningExecutorService;
      return this;
    }

    /**
     * Sets the {@link BitmapFactory.Options} to be used for decoding bitmaps.
     *
     * @param options A {@link BitmapFactory.Options}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBitmapFactoryOptions(@Nullable BitmapFactory.Options options) {
      this.options = options;
      return this;
    }

    /**
     * Sets the maximum output dimension for decoded bitmaps.
     *
     * @param maximumOutputDimension The maximum output dimension in pixels.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMaximumOutputDimension(int maximumOutputDimension) {
      this.maximumOutputDimension = maximumOutputDimension;
      return this;
    }

    /**
     * Sets whether the {@link Bitmap} should be converted to an immutable, sharable instance that
     * is most efficient for repeated transfer over binder interfaces.
     *
     * @param makeShared Whether to make the {@link Bitmap} shared.
     * @return This builder.
     * @see BitmapUtil#makeShared(Bitmap)
     */
    @CanIgnoreReturnValue
    public Builder setMakeShared(boolean makeShared) {
      this.makeShared = makeShared;
      return this;
    }

    /** Builds a {@link DataSourceBitmapLoader}. */
    public DataSourceBitmapLoader build() {
      return new DataSourceBitmapLoader(this);
    }
  }

  private final ListeningExecutorService listeningExecutorService;
  private final DataSource.Factory dataSourceFactory;
  @Nullable private final BitmapFactory.Options options;
  private final int maximumOutputDimension;
  private final boolean makeShared;

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DataSourceBitmapLoader(Context context) {
    this(new Builder(context));
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DataSourceBitmapLoader(Context context, int maximumOutputDimension) {
    this(new Builder(context).setMaximumOutputDimension(maximumOutputDimension));
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @SuppressWarnings("deprecation") // Calling deprecated constructor.
  @Deprecated
  public DataSourceBitmapLoader(
      ListeningExecutorService listeningExecutorService, DataSource.Factory dataSourceFactory) {
    this(listeningExecutorService, dataSourceFactory, /* options= */ null);
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @SuppressWarnings("deprecation") // Calling deprecated constructor.
  @Deprecated
  public DataSourceBitmapLoader(
      ListeningExecutorService listeningExecutorService,
      DataSource.Factory dataSourceFactory,
      @Nullable BitmapFactory.Options options) {
    this(
        listeningExecutorService,
        dataSourceFactory,
        options,
        /* maximumOutputDimension= */ C.LENGTH_UNSET);
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DataSourceBitmapLoader(
      ListeningExecutorService listeningExecutorService,
      DataSource.Factory dataSourceFactory,
      @Nullable BitmapFactory.Options options,
      int maximumOutputDimension) {
    this.listeningExecutorService = listeningExecutorService;
    this.dataSourceFactory = dataSourceFactory;
    this.options = options;
    this.maximumOutputDimension = maximumOutputDimension;
    this.makeShared = false;
  }

  private DataSourceBitmapLoader(Builder builder) {
    this.dataSourceFactory =
        builder.dataSourceFactory != null
            ? builder.dataSourceFactory
            : new DefaultDataSource.Factory(builder.context);
    this.listeningExecutorService =
        builder.listeningExecutorService != null
            ? builder.listeningExecutorService
            : checkNotNull(DEFAULT_EXECUTOR_SERVICE.get());
    this.options = builder.options;
    this.maximumOutputDimension = builder.maximumOutputDimension;
    this.makeShared = builder.makeShared;
  }

  @Override
  public boolean supportsMimeType(String mimeType) {
    return isBitmapFactorySupportedMimeType(mimeType);
  }

  @Override
  public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
    return listeningExecutorService.submit(
        () ->
            maybeAsShared(makeShared, decode(data, data.length, options, maximumOutputDimension)));
  }

  @Override
  public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
    return listeningExecutorService.submit(
        () ->
            load(
                dataSourceFactory.createDataSource(),
                uri,
                options,
                maximumOutputDimension,
                makeShared));
  }

  private static Bitmap load(
      DataSource dataSource,
      Uri uri,
      @Nullable BitmapFactory.Options options,
      int maximumOutputDimension,
      boolean makeShared)
      throws IOException {
    try {
      DataSpec dataSpec = new DataSpec(uri);
      dataSource.open(dataSpec);
      byte[] readData = DataSourceUtil.readToEnd(dataSource);
      return maybeAsShared(
          makeShared, decode(readData, readData.length, options, maximumOutputDimension));
    } finally {
      dataSource.close();
    }
  }

  private static Bitmap maybeAsShared(boolean makeShared, Bitmap bitmap) {
    return makeShared ? BitmapUtil.makeShared(bitmap) : bitmap;
  }
}
