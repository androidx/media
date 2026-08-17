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
package androidx.media3.exoplayer.image;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

/** A listener for image metadata. */
@UnstableApi
public interface ImageMetadataListener {
  /**
   * Called on the playback thread when an image is about to be available.
   *
   * <p>This method is called prior to invoking {@link ImageOutput#onImageAvailable(long,
   * android.graphics.Bitmap)}.
   *
   * @param presentationTimeUs The presentation time of the image, in microseconds.
   * @param format The format associated with the image.
   */
  void onImageAboutToBeAvailable(long presentationTimeUs, Format format);
}
