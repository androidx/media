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

import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.ReleaseCallback;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * Converter from {@link Bitmap} to {@link Frame}.
 *
 * <p>Caches a reusable pixel representation across repeated frames while creating a new {@link
 * Frame} with per-frame metadata and release callbacks on each {@link #convert} call. The cached
 * value is reused until the {@link Bitmap} instance or its {@link Bitmap#getGenerationId()
 * generation ID} changes, or the converter is {@linkplain #flush() flushed} or {@linkplain #close()
 * closed}.
 *
 * @param <T> The reusable representation cached for the current {@link Bitmap} (for example, a
 *     {@link ByteBuffer} or reference-counted {@link DefaultHardwareBufferFrame}).
 */
/* package */ abstract class BitmapToFrameConverter<T> implements AutoCloseable {

  private @Nullable Bitmap currentBitmap;
  private int currentBitmapGenerationId;
  private @Nullable T cachedValue;
  private boolean isClosed;

  /* package */ BitmapToFrameConverter() {
    currentBitmapGenerationId = C.INDEX_UNSET;
  }

  /** Converts {@code bitmap} into a {@link Frame}, reusing the cached value when unchanged. */
  public final Frame convert(
      Bitmap bitmap,
      long contentTimeUs,
      Format format,
      Map<String, Object> metadata,
      Executor releaseExecutor,
      ReleaseCallback onFrameReleased) {
    checkState(!isClosed);
    @Nullable T value = cachedValue;
    if (value != null
        && (currentBitmap != bitmap || currentBitmapGenerationId != bitmap.getGenerationId())) {
      releaseCachedValue(value);
      value = null;
      cachedValue = null;
      currentBitmap = null;
      currentBitmapGenerationId = C.INDEX_UNSET;
    }
    if (value == null) {
      value = createCachedValue(bitmap);
      cachedValue = value;
      currentBitmap = bitmap;
      // Save the generationId after creating the cached value, as AndroidBitmap_unlockPixels can
      // cause the generationId to increment.
      currentBitmapGenerationId = bitmap.getGenerationId();
    }
    return createFrame(
        value, bitmap, contentTimeUs, format, metadata, releaseExecutor, onFrameReleased);
  }

  /** Releases any cached value and resets state. */
  public final void flush() {
    if (cachedValue != null) {
      releaseCachedValue(cachedValue);
      cachedValue = null;
      currentBitmap = null;
      currentBitmapGenerationId = C.INDEX_UNSET;
    }
  }

  @Override
  public final void close() {
    if (isClosed) {
      return;
    }
    isClosed = true;
    flush();
    onClose();
  }

  /** Creates the cached representation for {@code bitmap}. */
  protected abstract T createCachedValue(Bitmap bitmap);

  /**
   * Creates an output {@link Frame} from {@code cachedValue} that calls {@code onFrameReleased} on
   * {@code releaseExecutor} when released.
   */
  protected abstract Frame createFrame(
      T cachedValue,
      Bitmap bitmap,
      long contentTimeUs,
      Format format,
      Map<String, Object> metadata,
      Executor releaseExecutor,
      ReleaseCallback onFrameReleased);

  /**
   * Releases the converter's cached reference to {@code cachedValue}; previously created frames may
   * still be in use.
   */
  protected void releaseCachedValue(T cachedValue) {}

  /** Releases subclass resources after {@link #close()} flushes any cached state. */
  protected void onClose() {}
}
