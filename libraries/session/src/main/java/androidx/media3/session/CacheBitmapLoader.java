/*
 * Copyright 2022 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link BitmapLoader} that caches the result of the last {@link #decodeBitmap(byte[])}, {@link
 * #loadBitmap(Uri)} or {@link #loadBitmapFromMetadata(MediaMetadata)} request.
 *
 * <p>Requests are fulfilled from the last bitmap load request when the last bitmap is requested
 * from the same {@code data}, the same {@code uri}, or the same {@link MediaMetadata#artworkUri} or
 * {@link MediaMetadata#artworkData}. If the request doesn't match the previous request, the request
 * is forwarded to the provided {@link BitmapLoader} and the result is cached.
 */
@UnstableApi
public final class CacheBitmapLoader implements BitmapLoader {

  private final BitmapLoader bitmapLoader;

  private @MonotonicNonNull BitmapLoadRequest lastBitmapLoadRequest;

  /**
   * Creates an instance that is able to cache the last bitmap load request to the given bitmap
   * loader.
   */
  public CacheBitmapLoader(BitmapLoader bitmapLoader) {
    this.bitmapLoader = bitmapLoader;
  }

  @Override
  public boolean supportsMimeType(String mimeType) {
    return bitmapLoader.supportsMimeType(mimeType);
  }

  @Override
  public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
    if (lastBitmapLoadRequest != null && lastBitmapLoadRequest.matches(data)) {
      return lastBitmapLoadRequest.getFuture();
    }
    ListenableFuture<Bitmap> future = bitmapLoader.decodeBitmap(data);
    lastBitmapLoadRequest = new BitmapLoadRequest(data, future);
    return future;
  }

  @Override
  public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
    if (lastBitmapLoadRequest != null && lastBitmapLoadRequest.matches(uri)) {
      return lastBitmapLoadRequest.getFuture();
    }
    ListenableFuture<Bitmap> future = bitmapLoader.loadBitmap(uri);
    lastBitmapLoadRequest = new BitmapLoadRequest(uri, future);
    return future;
  }

  @Nullable
  @Override
  public ListenableFuture<Bitmap> loadBitmapFromMetadata(MediaMetadata metadata) {
    if (lastBitmapLoadRequest != null && lastBitmapLoadRequest.matches(metadata)) {
      return lastBitmapLoadRequest.getFuture();
    }
    ListenableFuture<Bitmap> future = bitmapLoader.loadBitmapFromMetadata(metadata);
    if (future == null) {
      return null;
    }
    lastBitmapLoadRequest = new BitmapLoadRequest(metadata, future);
    return future;
  }

  /**
   * Stores the result of a bitmap load request. Requests are identified either by a byte array, if
   * the bitmap is loaded from compressed data, or a URI, if the bitmap was loaded from a URI.
   */
  private static class BitmapLoadRequest {
    @Nullable private final byte[] data;
    @Nullable private final Uri uri;
    @Nullable private final ListenableFuture<Bitmap> future;

    /** Creates load request for byte array. */
    private BitmapLoadRequest(byte[] data, ListenableFuture<Bitmap> future) {
      this.data = data;
      this.uri = null;
      this.future = future;
    }

    /** Creates load request for URI. */
    private BitmapLoadRequest(Uri uri, ListenableFuture<Bitmap> future) {
      this.data = null;
      this.uri = uri;
      this.future = future;
    }

    /** Creates load request for media metadata. */
    private BitmapLoadRequest(MediaMetadata metadata, ListenableFuture<Bitmap> future) {
      this.data = metadata.artworkData;
      this.uri = metadata.artworkUri;
      this.future = future;
    }

    /** Whether the bitmap load request was performed for {@code data}. */
    private boolean matches(byte[] data) {
      return this.data != null && Arrays.equals(this.data, data);
    }

    /** Whether the bitmap load request was performed for {@code uri}. */
    private boolean matches(Uri uri) {
      return this.uri != null && this.uri.equals(uri);
    }

    /**
     * Whether the bitmap load request was performed for either {@code metadata.artworkUri} or
     * {@code metadata.artworkData}.
     */
    private boolean matches(MediaMetadata metadata) {
      return (this.uri != null && this.uri.equals(metadata.artworkUri))
          || (this.data != null && Arrays.equals(this.data, metadata.artworkData));
    }

    /** Returns the future that set for the bitmap load request. */
    private ListenableFuture<Bitmap> getFuture() {
      return checkNotNull(future);
    }
  }
}
