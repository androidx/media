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
package androidx.media3.effect;

import androidx.annotation.Nullable;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OverlaySettings;

/** Holds required information to composite an input texture. */
/* package */ final class GlCompositionFrame {
  public final GlTextureInfo glTextureInfo;
  public final OverlaySettings overlaySettings;
  @Nullable public final float[] transformationMatrix;

  /** Creates a new instance. */
  public GlCompositionFrame(GlTextureInfo glTextureInfo, OverlaySettings overlaySettings) {
    this(glTextureInfo, overlaySettings, /* transformationMatrix= */ null);
  }

  /** Creates a new instance with an optional {@code transformationMatrix}. */
  public GlCompositionFrame(
      GlTextureInfo glTextureInfo,
      OverlaySettings overlaySettings,
      @Nullable float[] transformationMatrix) {
    this.glTextureInfo = glTextureInfo;
    this.overlaySettings = overlaySettings;
    this.transformationMatrix = transformationMatrix;
  }
}
