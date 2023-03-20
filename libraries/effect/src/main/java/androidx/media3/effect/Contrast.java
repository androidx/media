/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkArgument;

import android.content.Context;
import androidx.annotation.FloatRange;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;

/** A {@link GlEffect} to control the contrast of video frames. */
@UnstableApi
public class Contrast implements GlEffect {

  /** Adjusts the contrast of video frames in the interval [-1, 1]. */
  public final float contrast;

  /**
   * Creates a new instance for the given contrast value.
   *
   * <p>Contrast values range from -1 (all gray pixels) to 1 (maximum difference of colors). 0 means
   * to add no contrast and leaves the frames unchanged.
   */
  public Contrast(@FloatRange(from = -1, to = 1) float contrast) {
    checkArgument(-1 <= contrast && contrast <= 1, "Contrast needs to be in the interval [-1, 1].");
    this.contrast = contrast;
  }

  @Override
  public SingleFrameGlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new ContrastShaderProgram(context, this, useHdr);
  }

  @Override
  public boolean isNoOp(int inputWidth, int inputHeight) {
    return contrast == 0f;
  }
}
