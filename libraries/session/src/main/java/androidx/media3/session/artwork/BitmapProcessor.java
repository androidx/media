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
package androidx.media3.session.artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Utility class for processing bitmaps, such as downsizing and re-compression. */
@UnstableApi
/* package */ final class BitmapProcessor {

  private BitmapProcessor() {}

  /** Exception thrown when bitmap processing fails. */
  public static final class BitmapProcessingException extends Exception {
    /** Creates a new instance with an error message. */
    public BitmapProcessingException(String message) {
      super(message);
    }

    /** Creates a new instance with the given message and cause. */
    public BitmapProcessingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Scales down the bitmap if it exceeds the maximum allowed dimension.
   *
   * <p>If the bitmap dimensions are within bounds, the original bytes are returned to avoid
   * unnecessary decompression/compression overhead. If the bitmap is larger, it is downscaled
   * proportionally and compressed (PNG if it has transparency, JPEG 85% quality otherwise).
   *
   * @param context The context to retrieve the maximum allowed dimension.
   * @param data The raw compressed bitmap bytes.
   * @return The optimized raw bitmap bytes.
   * @throws BitmapProcessingException If the bitmap data is invalid or processing fails.
   */
  public static byte[] scaleDownIfExceedsLimit(Context context, byte[] data)
      throws BitmapProcessingException {
    return scaleDownIfExceedsLimit(data, MediaSession.getBitmapDimensionLimit(context));
  }

  /**
   * Scales down the bitmap if it exceeds the maximum allowed dimension.
   *
   * <p>If the bitmap dimensions are within bounds, the original bytes are returned to avoid
   * unnecessary decompression/compression overhead. If the bitmap is larger, it is downscaled
   * proportionally and compressed (PNG if it has transparency, JPEG 85% quality otherwise).
   *
   * @param data The raw compressed bitmap bytes.
   * @param maxDimension The maximum allowed dimension in pixels.
   * @return The optimized raw bitmap bytes.
   * @throws BitmapProcessingException If the bitmap data is invalid or processing fails.
   */
  public static byte[] scaleDownIfExceedsLimit(byte[] data, int maxDimension)
      throws BitmapProcessingException {
    try {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, options);

      if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw new BitmapProcessingException(
            "Failed to decode bitmap bounds. Invalid or unsupported bitmap data.");
      }

      if (options.outWidth <= maxDimension && options.outHeight <= maxDimension) {
        return data; // Already within bounds, avoid re-compression overhead
      }

      // Calculate sample size to downscale
      options.inJustDecodeBounds = false;
      options.inPreferredConfig =
          Objects.equals(options.outMimeType, "image/jpeg")
              ? Bitmap.Config.RGB_565
              : Bitmap.Config.ARGB_8888;
      options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension);
      @Nullable Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
      if (bitmap == null) {
        throw new BitmapProcessingException("Failed to decode bitmap.");
      }

      byte[] rescaledData = scaleAndCompress(bitmap, maxDimension);
      bitmap.recycle();
      return rescaledData;
    } catch (BitmapProcessingException e) {
      throw e;
    } catch (Throwable e) {
      throw new BitmapProcessingException("Bitmap processing failed", e);
    }
  }

  /**
   * Scales the given {@link Bitmap} down proportionally if it exceeds the maximum allowed dimension
   * and compresses it to a byte array.
   *
   * <p>The input {@code bitmap} is NOT recycled.
   *
   * @param bitmap The bitmap to process.
   * @param maxDimension The maximum allowed dimension in pixels.
   * @return The compressed bitmap bytes.
   * @throws BitmapProcessingException If compression fails.
   */
  public static byte[] scaleAndCompress(Bitmap bitmap, int maxDimension)
      throws BitmapProcessingException {
    Bitmap bitmapToCompress = bitmap;
    if (bitmap.getWidth() > maxDimension || bitmap.getHeight() > maxDimension) {
      float ratio =
          Math.min(
              (float) maxDimension / bitmap.getWidth(), (float) maxDimension / bitmap.getHeight());
      Bitmap scaled =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.round(bitmap.getWidth() * ratio),
              Math.round(bitmap.getHeight() * ratio),
              /* filter= */ true);
      bitmapToCompress = scaled;
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.JPEG;
    int quality = 85;
    if (bitmapToCompress.hasAlpha()) {
      // Use PNG if the bitmap has transparency.
      compressFormat = Bitmap.CompressFormat.PNG;
      quality = 100;
    }
    if (!bitmapToCompress.compress(compressFormat, quality, outputStream)) {
      throw new BitmapProcessingException("Failed to compress bitmap.");
    }
    byte[] compressedData = outputStream.toByteArray();

    if (bitmapToCompress != bitmap) {
      bitmapToCompress.recycle();
    }
    return compressedData;
  }

  @VisibleForTesting
  /* package */ static int calculateInSampleSize(
      BitmapFactory.Options options, int reqWidth, int reqHeight) {
    final int height = options.outHeight;
    final int width = options.outWidth;
    int inSampleSize = 1;

    if (height > reqHeight || width > reqWidth) {
      final int halfHeight = height / 2;
      final int halfWidth = width / 2;
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2;
      }
    }
    return inSampleSize;
  }
}
