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

import android.hardware.HardwareBuffer;
import android.media.Image;
import androidx.annotation.Nullable;

/** A default implementation of {@link ImageAdapter} that wraps an {@link Image}. */
/* package */ final class DefaultImageAdapter implements ImageAdapter {

  private final Image image;

  public DefaultImageAdapter(Image image) {
    this.image = image;
  }

  @Override
  public long getTimestampNs() {
    return image.getTimestamp();
  }

  @Override
  @Nullable
  public HardwareBuffer getHardwareBuffer() {
    if (SDK_INT >= 28) {
      return image.getHardwareBuffer();
    }
    return null;
  }

  @Override
  @Nullable
  public Image getInternalImage() {
    return image;
  }

  @Override
  public void close() {
    image.close();
  }
}
