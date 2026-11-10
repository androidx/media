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
import androidx.media3.common.Format;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ReleaseCallback;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A {@link BitmapToFrameConverter} that converts {@link Bitmap.Config#ARGB_8888} bitmaps into
 * {@link DefaultImagePlanesFrame} instances.
 */
/* package */ final class BitmapToImagePlanesConverter extends BitmapToFrameConverter<ByteBuffer> {

  private static final int RGBA_8888_BYTES_PER_PIXEL = 4;

  @Override
  protected ByteBuffer createCachedValue(Bitmap bitmap) {
    // TODO(b/582230137): Investigate whether non-ARGB_8888 Bitmap configs need to be supported.
    checkState(bitmap.getConfig() == Bitmap.Config.ARGB_8888);
    ByteBuffer buffer = ByteBuffer.allocateDirect(bitmap.getByteCount());
    bitmap.copyPixelsToBuffer(buffer);
    buffer.rewind();
    return buffer;
  }

  @Override
  protected Frame createFrame(
      ByteBuffer bitmapBuffer,
      Bitmap bitmap,
      long contentTimeUs,
      Format format,
      Map<String, Object> metadata,
      Executor releaseExecutor,
      ReleaseCallback onFrameReleased) {
    ImagePlanesFrame.Plane plane =
        new DefaultPlane(
            bitmapBuffer.duplicate(),
            /* rowStride= */ bitmap.getRowBytes(),
            /* pixelStride= */ RGBA_8888_BYTES_PER_PIXEL);
    return new DefaultImagePlanesFrame.Builder(
            ImmutableList.of(plane), releaseExecutor, onFrameReleased)
        .setContentTimeUs(contentTimeUs)
        .setFormat(format)
        .setMetadata(metadata)
        .setInternalImage(bitmap)
        .build();
  }
}
