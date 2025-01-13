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
package androidx.media3.container;

import androidx.media3.common.util.UnstableApi;

/** Utility methods for MP4 containers. */
@UnstableApi
public final class Mp4Util {
  /** The original video track without any depth based effects applied. */
  public static final int EDITABLE_TRACK_TYPE_SHARP = 0;

  /**
   * A linear encoded depth video track.
   *
   * <p>See https://developer.android.com/static/media/camera/camera2/Dynamic-depth-v1.0.pdf for
   * linear depth encoding.
   */
  public static final int EDITABLE_TRACK_TYPE_DEPTH_LINEAR = 1;

  /**
   * An inverse encoded depth video track.
   *
   * <p>See https://developer.android.com/static/media/camera/camera2/Dynamic-depth-v1.0.pdf for
   * inverse depth encoding.
   */
  public static final int EDITABLE_TRACK_TYPE_DEPTH_INVERSE = 2;

  /** A timed metadata of depth video track. */
  public static final int EDITABLE_TRACK_TYPE_DEPTH_METADATA = 3;

  private Mp4Util() {}
}
