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
package androidx.media3.transformer;

import android.content.Context;
import androidx.annotation.RequiresApi;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.HardwareBufferJni;
import org.junit.rules.ExternalResource;

/**
 * A JUnit rule that creates components configured with {@link DefaultGlFrameProcessor.Factory} for
 * testing.
 *
 * <p>Each created {@link DefaultGlFrameProcessor} creates and releases its own GL resources.
 */
public final class GlFrameProcessorTestRule extends ExternalResource {

  /** Returns a {@link DefaultGlFrameProcessor.Factory}. */
  @RequiresApi(26)
  public DefaultGlFrameProcessor.Factory createDefaultGlFrameProcessorFactory(Context context) {
    return new DefaultGlFrameProcessor.Factory.Builder(context, HardwareBufferJni.INSTANCE).build();
  }

  /**
   * Returns a {@link Transformer.Builder} configured with {@link DefaultGlFrameProcessor.Factory}.
   */
  @RequiresApi(AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK)
  public Transformer.Builder createTransformerBuilder(Context context) {
    return new Transformer.Builder(context)
        .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
        .setFrameProcessorFactory(createDefaultGlFrameProcessorFactory(context));
  }

  /**
   * Returns a {@link CompositionPlayer.Builder} configured with {@link
   * DefaultGlFrameProcessor.Factory}.
   */
  @RequiresApi(AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK)
  public CompositionPlayer.Builder createCompositionPlayerBuilder(Context context) {
    return new CompositionPlayer.Builder(context)
        .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
        .setFrameProcessorFactory(createDefaultGlFrameProcessorFactory(context));
  }
}
