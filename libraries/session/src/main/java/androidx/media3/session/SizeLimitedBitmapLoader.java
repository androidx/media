/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.datasource.BitmapUtil.makeShared;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.Math.min;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A {@link BitmapLoader} that limits the size of bitmaps loaded by {@link #decodeBitmap(byte[])} or
 * {@link #loadBitmap(Uri)} request. Bitmaps with width or height larger than maxBitmapSize will be
 * scaled down so that the larger dimension will be equal or less than maxBitmapSize.
 */
@UnstableApi
public final class SizeLimitedBitmapLoader implements BitmapLoader {

  private final BitmapLoader bitmapLoader;
  private final int maxBitmapSize;
  private final boolean makeShared;

  /**
   * Creates an instance that size limits the bitmap loaded by the {@link BitmapLoader}.
   *
   * @param bitmapLoader The {@link BitmapLoader}.
   * @param maxBitmapSize The maximum size to limit the loaded {@link Bitmap} instances to.
   * @param makeShared Whether the {@link Bitmap} should be converted to an immutable, sharable
   *     instance that is most efficient for repeated transfer over binder interfaces.
   */
  public SizeLimitedBitmapLoader(BitmapLoader bitmapLoader, int maxBitmapSize, boolean makeShared) {
    this.bitmapLoader = bitmapLoader;
    this.maxBitmapSize = maxBitmapSize;
    this.makeShared = makeShared;
  }

  @Override
  public boolean supportsMimeType(String mimeType) {
    return bitmapLoader.supportsMimeType(mimeType);
  }

  @Override
  public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
    ListenableFuture<Bitmap> future = bitmapLoader.decodeBitmap(data);
    return Futures.transform(future, this::scaleIfNecessary, directExecutor());
  }

  @Override
  public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
    ListenableFuture<Bitmap> future = bitmapLoader.loadBitmap(uri);
    return Futures.transform(future, this::scaleIfNecessary, directExecutor());
  }

  @Nullable
  @Override
  public ListenableFuture<Bitmap> loadBitmapFromMetadata(MediaMetadata metadata) {
    ListenableFuture<Bitmap> future = bitmapLoader.loadBitmapFromMetadata(metadata);
    return future == null
        ? null
        : Futures.transform(future, this::scaleIfNecessary, directExecutor());
  }

  private Bitmap scaleIfNecessary(Bitmap bitmap) {
    if (bitmap.getWidth() > maxBitmapSize || bitmap.getHeight() > maxBitmapSize) {
      int width = bitmap.getWidth();
      int height = bitmap.getHeight();
      float scale = min((float) maxBitmapSize / width, (float) maxBitmapSize / height);
      int scaledWidth = (int) (width * scale);
      int scaledHeight = (int) (height * scale);
      bitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, /* filter= */ true);
    }
    if (makeShared) {
      bitmap = makeShared(bitmap);
    }
    return bitmap;
  }
}
