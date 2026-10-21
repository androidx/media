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
package androidx.media3.exoplayer.video;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.view.Surface;
import androidx.media3.common.Flags;
import androidx.media3.common.util.UnstableApi;

/** A placeholder {@link Surface}. */
@UnstableApi
public interface PlaceholderSurfaceInterface {

  /**
   * Returns whether the device supports secure placeholder surfaces.
   *
   * @param context Any {@link Context}.
   * @return Whether the device supports secure placeholder surfaces.
   */
  static boolean isSecureSupported(Context context) {
    return PlaceholderSurface.isSecureSupportedInternal(context);
  }

  /**
   * Returns a newly created placeholder surface. The placeholder must be released by calling {@link
   * #release()} when it's no longer required.
   *
   * <p>When {@link Flags#FLAG_IMAGE_READER_PLACEHOLDER_SURFACE} is enabled on API 29+, non-secure
   * placeholder surfaces are backed by an {@link android.media.ImageReader} instead of an EGL
   * pbuffer surface. Secure placeholder surfaces always use the OpenGL-backed {@link
   * PlaceholderSurface} because {@link android.media.ImageReader} requires {@link
   * android.hardware.HardwareBuffer#USAGE_PROTECTED_CONTENT} in its consumer usage bits to mark the
   * surface as protected, which causes some OMX secure decoders to crash when pushing CPU-locked
   * blank buffers to the placeholder surface on surface switch or shutdown.
   *
   * @param context Any {@link Context}.
   * @param secure Whether a secure surface is required. Must only be requested if {@link
   *     #isSecureSupported(Context)} returns {@code true}.
   * @throws IllegalStateException If a secure surface is requested on a device for which {@link
   *     #isSecureSupported(Context)} returns {@code false}.
   */
  static PlaceholderSurfaceInterface newInstance(Context context, boolean secure) {
    checkState(!secure || isSecureSupported(context));
    if (!secure && SDK_INT >= 29 && Flags.isEnabled(Flags.FLAG_IMAGE_READER_PLACEHOLDER_SURFACE)) {
      return new ImageReaderPlaceholderSurface();
    }
    return PlaceholderSurface.newInstanceInternal(context, secure);
  }

  /** Returns the placeholder {@link Surface}. */
  Surface getSurface();

  /** Returns whether the placeholder surface is secure. */
  boolean isSecure();

  /** Releases the placeholder surface and its underlying resources. */
  void release();
}
