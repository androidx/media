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
package androidx.media3.effect;

import android.content.Context;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;

/** Applies a speed change by updating the frame timestamps. */
@UnstableApi
/* package */ final class SpeedChangeShaderProgram extends FrameCacheGlShaderProgram {

  private final float speed;

  public SpeedChangeShaderProgram(Context context, float speed, boolean useHdr)
      throws VideoFrameProcessingException {
    super(context, /* capacity= */ 1, useHdr);
    this.speed = speed;
  }

  @Override
  public void queueInputFrame(
      GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
    super.queueInputFrame(glObjectsProvider, inputTexture, (long) (presentationTimeUs / speed));
  }
}
