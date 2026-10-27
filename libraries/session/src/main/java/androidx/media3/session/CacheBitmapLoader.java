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

import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.ListenableFuture;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

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

  private @Nullable BitmapLoadRequest lastBitmapLoadRequest;

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
    byte[] dataHash = computeSha256(data);
    if (lastBitmapLoadRequest != null && lastBitmapLoadRequest.matches(dataHash)) {
      return lastBitmapLoadRequest.getFuture();
    }
    ListenableFuture<Bitmap> future = bitmapLoader.decodeBitmap(data);
    lastBitmapLoadRequest = new BitmapLoadRequest(dataHash, future);
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

  @Override
  public @Nullable ListenableFuture<Bitmap> loadBitmapFromMetadata(MediaMetadata metadata) {
    byte @Nullable [] dataHash =
        metadata.artworkData != null ? computeSha256(metadata.artworkData) : null;
    if (lastBitmapLoadRequest != null
        && lastBitmapLoadRequest.matches(dataHash, metadata.artworkUri)) {
      return lastBitmapLoadRequest.getFuture();
    }
    ListenableFuture<Bitmap> future = bitmapLoader.loadBitmapFromMetadata(metadata);
    if (future == null) {
      lastBitmapLoadRequest = null;
      return null;
    }
    if (dataHash != null) {
      lastBitmapLoadRequest = new BitmapLoadRequest(dataHash, future);
    } else if (metadata.artworkUri != null) {
      lastBitmapLoadRequest = new BitmapLoadRequest(metadata.artworkUri, future);
    } else {
      lastBitmapLoadRequest = null;
    }
    return future;
  }

  /**
   * Computes a SHA-256 digest on each request to avoid hash collisions (which would serve the wrong
   * cached artwork) without retaining a reference to {@code data}.
   */
  private static byte[] computeSha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Stores the result of a bitmap load request. Requests are identified either by a hash of a byte
   * array, if the bitmap is loaded from compressed data, or a URI, if the bitmap was loaded from a
   * URI.
   */
  private static class BitmapLoadRequest {
    private final byte @Nullable [] dataHash;
    private final @Nullable Uri uri;
    private final ListenableFuture<Bitmap> future;

    /** Creates load request for byte array hash. */
    private BitmapLoadRequest(byte[] dataHash, ListenableFuture<Bitmap> future) {
      this.dataHash = dataHash;
      this.uri = null;
      this.future = future;
    }

    /** Creates load request for URI. */
    private BitmapLoadRequest(Uri uri, ListenableFuture<Bitmap> future) {
      this.dataHash = null;
      this.uri = uri;
      this.future = future;
    }

    /** Whether the bitmap load request was performed for {@code dataHash}. */
    private boolean matches(byte[] dataHash) {
      return this.dataHash != null && Arrays.equals(this.dataHash, dataHash);
    }

    /** Whether the bitmap load request was performed for {@code uri}. */
    private boolean matches(@Nullable Uri uri) {
      return this.uri != null && this.uri.equals(uri);
    }

    /**
     * Whether the bitmap load request matches {@code dataHash} if non-null, or {@code uri}
     * otherwise.
     */
    private boolean matches(byte @Nullable [] dataHash, @Nullable Uri uri) {
      return dataHash != null ? matches(dataHash) : matches(uri);
    }

    /** Returns the future that set for the bitmap load request. */
    private ListenableFuture<Bitmap> getFuture() {
      return future;
    }
  }
}
