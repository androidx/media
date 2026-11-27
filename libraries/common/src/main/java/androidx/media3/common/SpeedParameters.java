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
package androidx.media3.common;

import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.ExperimentalApi;

/** Class that holds information about how to apply a speed-changing effect. */
// TODO: b/489655531 - Remove experimental annotation.
@ExperimentalApi
public final class SpeedParameters {

  public static final SpeedParameters DEFAULT =
      new SpeedParameters(SpeedProvider.DEFAULT, /* shouldMaintainPitch= */ false);

  /** The {@link SpeedProvider} to apply on a stream. */
  public final SpeedProvider speedProvider;

  /** Returns whether pitch should be maintained when time-stretching an audio stream. */
  public final boolean shouldMaintainPitch;

  public SpeedParameters(SpeedProvider speedProvider, boolean shouldMaintainPitch) {
    this.speedProvider = speedProvider;
    this.shouldMaintainPitch = shouldMaintainPitch;
  }
}
