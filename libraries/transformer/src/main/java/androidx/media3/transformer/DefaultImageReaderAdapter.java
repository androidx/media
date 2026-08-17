/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;

/** A default implementation of {@link ImageReaderAdapter} that wraps a real {@link ImageReader}. */
/* package */ final class DefaultImageReaderAdapter implements ImageReaderAdapter {

  /** A factory for {@link DefaultImageReaderAdapter} instances. */
  /* package */ static final class Factory implements ImageReaderAdapter.Factory {
    @Override
    public ImageReaderAdapter create(int width, int height, int format, int maxImages, long usage) {
      ImageReader imageReader;
      if (SDK_INT >= 29) {
        imageReader = ImageReader.newInstance(width, height, format, maxImages, usage);
      } else {
        imageReader = ImageReader.newInstance(width, height, format, maxImages);
      }
      return new DefaultImageReaderAdapter(imageReader);
    }
  }

  private final ImageReader imageReader;

  private DefaultImageReaderAdapter(ImageReader imageReader) {
    this.imageReader = imageReader;
  }

  @Override
  @Nullable
  public ImageAdapter acquireNextImage() {
    Image image = imageReader.acquireNextImage();
    return image == null ? null : new DefaultImageAdapter(image);
  }

  @Override
  public Surface getSurface() {
    return imageReader.getSurface();
  }

  @Override
  public void setOnImageAvailableListener(Consumer<ImageReaderAdapter> listener, Handler handler) {
    imageReader.setOnImageAvailableListener(reader -> listener.accept(this), handler);
  }

  @Override
  public void notifyFrameQueued(long presentationTimeUs) {
    // Do nothing.
  }

  @Override
  public void close() {
    imageReader.close();
  }
}
