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
import static java.lang.Math.max;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import com.google.common.primitives.ImmutableIntArray;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A {@link BitmapLoader} that avoids producing bitmaps whose larger dimension equals the specific
 * values. See https://github.com/androidx/media/issues/3118.
 */
/* package */ final class SizeAvoidingBitmapLoader implements BitmapLoader {

  private final BitmapLoader bitmapLoader;
  private final ImmutableIntArray avoidSizes;

  /**
   * Creates an instance that loads bitmaps using {@link BitmapLoader} avoiding the specified sizes.
   *
   * @param bitmapLoader The {@link BitmapLoader}.
   * @param avoidSizes The sizes to avoid as the larger dimension.
   */
  public SizeAvoidingBitmapLoader(BitmapLoader bitmapLoader, ImmutableIntArray avoidSizes) {
    this.bitmapLoader = bitmapLoader;
    this.avoidSizes = avoidSizes;
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
    int height = bitmap.getHeight();
    int width = bitmap.getWidth();
    if (avoidSizes.contains(max(height, width))) {
      if (avoidSizes.contains(height)) {
        height--;
      }
      if (avoidSizes.contains(width)) {
        width--;
      }
      bitmap = Bitmap.createScaledBitmap(bitmap, width, height, /* filter= */ true);
      bitmap = makeShared(bitmap);
    }
    return bitmap;
  }
}
