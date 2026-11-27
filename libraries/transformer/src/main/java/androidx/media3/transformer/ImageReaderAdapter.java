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

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;

/** An interface that wraps {@link android.media.ImageReader}. */
/* package */ interface ImageReaderAdapter extends AutoCloseable {

  /** A factory for {@link ImageReaderAdapter} instances. */
  interface Factory {
    /** Creates a new instance. */
    ImageReaderAdapter create(int width, int height, int format, int maxImages, long usage);
  }

  /**
   * Acquires the next {@link ImageAdapter} from the reader's queue, or {@code null} if none is
   * available.
   */
  @Nullable
  ImageAdapter acquireNextImage();

  /** Returns the {@link Surface} that can be used to produce images into the reader. */
  Surface getSurface();

  /**
   * Sets the listener for available images.
   *
   * @param listener The {@link Consumer} to be invoked when a new image becomes available. The
   *     listener is provided with the {@link ImageReaderAdapter} instance that has the available
   *     image.
   * @param handler The {@link Handler} on which the listener should be invoked.
   */
  void setOnImageAvailableListener(Consumer<ImageReaderAdapter> listener, Handler handler);

  /**
   * Signals that a frame with the given {@code presentationTimeUs} is being queued to the reader's
   * {@linkplain #getSurface() surface}.
   */
  void notifyFrameQueued(long presentationTimeUs);

  /** Closes the reader and all acquired images. */
  @Override
  void close();
}
