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

import android.hardware.HardwareBuffer;
import android.media.Image;
import androidx.annotation.Nullable;

/** An interface that wraps {@link android.media.Image}. */
/* package */ interface ImageAdapter extends AutoCloseable {

  /** Returns the presentation timestamp of the image, in nanoseconds. */
  long getTimestampNs();

  /**
   * Returns the {@link HardwareBuffer} associated with this image, or {@code null} if none exists.
   */
  @Nullable
  HardwareBuffer getHardwareBuffer();

  /** Returns the underlying platform image object if available, or {@code null}. */
  @Nullable
  Image getInternalImage();

  @Override
  void close();
}
