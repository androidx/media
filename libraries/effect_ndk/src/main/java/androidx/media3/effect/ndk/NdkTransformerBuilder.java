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
package androidx.media3.effect.ndk;

import android.content.Context;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.GlTextureFrameRenderer.Listener.NO_OP;
import androidx.media3.effect.RenderingPacketConsumer;
import androidx.media3.transformer.Transformer;

/**
 * Helper class to create a {@link Transformer.Builder} that has {@link androidx.media3.effect.ndk}
 * dependencies injected.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/474075198: Remove once FrameConsumer API is stable.
public class NdkTransformerBuilder {

  /**
   * Utility method for creating a {@link Transformer.Builder} that has {@link
   * androidx.media3.effect.ndk} dependencies injected.
   *
   * <p>Callers must set {@link
   * androidx.media3.transformer.Transformer.Builder#setHardwareBufferEffectsPipeline(RenderingPacketConsumer)}
   * on the returned builder before using it, in order to inject the effects processing pipeline.
   *
   * @param context The {@link Context}.
   * @return A {@link androidx.media3.transformer.Transformer.Builder} that can be built upon.
   */
  public static Transformer.Builder create(Context context) {
    HardwareBufferSurfaceRenderer hardwareBufferRenderer =
        HardwareBufferSurfaceRenderer.create(context, NO_OP.INSTANCE, /* errorConsumer= */ e -> {});
    return new Transformer.Builder(context).setHardwareBufferRenderer(hardwareBufferRenderer);
  }

  private NdkTransformerBuilder() {}
}
