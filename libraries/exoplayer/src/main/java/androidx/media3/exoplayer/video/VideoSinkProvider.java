/*
 * Copyright 2023 The Android Open Source Project
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

import android.view.Surface;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;

/** A provider of {@link VideoSink VideoSinks}. */
@UnstableApi
public interface VideoSinkProvider {

  /** Returns a {@link VideoSink} to forward video frames for processing. */
  VideoSink getSink();

  /** Sets the output surface info. */
  void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution);

  /** Clears the set output surface info. */
  void clearOutputSurfaceInfo();

  /** Releases the sink provider. */
  void release();
}
