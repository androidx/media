/*
 * Copyright 2024 The Android Open Source Project
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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import com.google.common.base.Ascii;

/** Utility methods for images. */
@UnstableApi
public final class ImageUtil {

  private ImageUtil() {}

  /**
   * Returns the {@linkplain MimeTypes MIME type} corresponding to an image {@linkplain Uri URI}
   * path extension. {@code null} is returned if the extension is not an image extension.
   */
  @Nullable
  public static String getCommonImageMimeTypeFromExtension(Uri uri) {
    @Nullable String path = uri.getPath();
    if (path == null) {
      return null;
    }
    int extensionIndex = path.lastIndexOf('.');
    if (extensionIndex == -1 || extensionIndex == path.length() - 1) {
      return null;
    }
    String extension = Ascii.toLowerCase(path.substring(extensionIndex + 1));
    switch (extension) {
      case "bmp":
      case "dib":
        return MimeTypes.IMAGE_BMP;
      case "heif":
      case "heic":
        return MimeTypes.IMAGE_HEIF;
      case "jpg":
      case "jpeg":
      case "jpe":
      case "jif":
      case "jfif":
      case "jfi":
        return MimeTypes.IMAGE_JPEG;
      case "png":
        return MimeTypes.IMAGE_PNG;
      case "webp":
        return MimeTypes.IMAGE_WEBP;
      case "gif":
        return "image/gif";
      case "tiff":
      case "tif":
        return "image/tiff";
      case "raw":
      case "arw":
      case "cr2":
      case "k25":
        return "image/raw";
      case "svg":
      case "svgz":
        return "image/svg+xml";
      case "ico":
        return "image/x-icon";
      case "avif":
        return "image/avif";
      default:
        return null;
    }
  }
}
