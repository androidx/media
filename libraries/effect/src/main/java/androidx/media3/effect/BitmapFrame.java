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
package androidx.media3.effect;

import android.graphics.Bitmap;
import androidx.media3.common.Format;

/** A {@link Frame} implementation that wraps a {@link Bitmap}. */
/* package */ final class BitmapFrame implements Frame {

  /** Metadata associated with a {@link BitmapFrame}. */
  public static final class Metadata implements Frame.Metadata {
    private final long presentationTimeUs;
    private final Format format;

    public Metadata(long presentationTimeUs, Format format) {
      this.presentationTimeUs = presentationTimeUs;
      this.format = format;
    }

    public long getPresentationTimeUs() {
      return presentationTimeUs;
    }

    public Format getFormat() {
      return format;
    }
  }

  private final Bitmap bitmap;
  private final Metadata metadata;

  public BitmapFrame(Bitmap bitmap, BitmapFrame.Metadata metadata) {
    this.bitmap = bitmap;
    this.metadata = metadata;
  }

  @Override
  public Metadata getMetadata() {
    return metadata;
  }

  @Override
  public void release() {
    bitmap.recycle();
  }

  public Bitmap getBitmap() {
    return bitmap;
  }
}
