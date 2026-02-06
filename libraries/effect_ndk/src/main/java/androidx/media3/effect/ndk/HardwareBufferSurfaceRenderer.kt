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
package androidx.media3.effect.ndk

import android.content.Context
import androidx.annotation.RequiresApi
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Util
import androidx.media3.effect.GlTextureFrameRenderer
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.RenderingPacketConsumer
import androidx.media3.effect.SingleContextGlObjectsProvider
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors

/**
 * A [androidx.media3.effect.PacketConsumer] that renders a [Packet] of [HardwareBufferFrame]s to an
 * output [android.view.Surface] by first converting to a GL texture, using a
 * [HardwareBufferToGlTextureFrameProcessor] and [GlTextureFrameRenderer].
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class HardwareBufferSurfaceRenderer
private constructor(
  private val converter: HardwareBufferToGlTextureFrameProcessor,
  private val renderer: GlTextureFrameRenderer,
  private var errorConsumer: Consumer<Exception>,
) : RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> {

  override fun setRenderOutput(output: SurfaceInfo?) {
    renderer.setRenderOutput(output)
  }

  override fun setErrorConsumer(errorConsumer: Consumer<Exception>) {
    converter.setErrorConsumer(errorConsumer)
    renderer.setErrorConsumer(errorConsumer)
    this.errorConsumer = errorConsumer
  }

  override suspend fun queuePacket(packet: Packet<HardwareBufferFrame>) {
    converter.queuePacket(packet)
  }

  override suspend fun release() {
    converter.release()
    renderer.release()
  }

  companion object {
    /**
     * Creates a [HardwareBufferSurfaceRenderer] instance, that uses OpenGL to render
     * [HardwareBufferFrame] to an output [android.view.Surface].
     *
     * Internally creates a single threaded [java.util.concurrent.ExecutorService] to run GL
     * commands, and a [SingleContextGlObjectsProvider].
     */
    @JvmStatic
    fun create(
      context: Context,
      listener: GlTextureFrameRenderer.Listener,
      errorConsumer: Consumer<Exception>,
    ): HardwareBufferSurfaceRenderer {
      val glExecutorService =
        MoreExecutors.listeningDecorator(
          Util.newSingleThreadExecutor("DefaultHardwareBufferSurfaceRenderer::GlThread")
        )
      val glObjectsProvider = SingleContextGlObjectsProvider()
      return create(context, glExecutorService, glObjectsProvider, listener, errorConsumer)
    }

    /**
     * Creates a [HardwareBufferSurfaceRenderer] instance, that uses OpenGL to render
     * [HardwareBufferFrame] to an output [android.view.Surface], on the given
     * [glThread][glExecutorService].
     */
    @JvmStatic
    fun create(
      context: Context,
      glExecutorService: ListeningExecutorService,
      glObjectsProvider: GlObjectsProvider,
      listener: GlTextureFrameRenderer.Listener,
      errorConsumer: Consumer<Exception>,
    ): HardwareBufferSurfaceRenderer {
      val converter =
        HardwareBufferToGlTextureFrameProcessor(
          context,
          glExecutorService,
          glObjectsProvider,
          errorConsumer,
        )
      val renderer =
        GlTextureFrameRenderer.create(
          context,
          glExecutorService,
          glObjectsProvider,
          errorConsumer::accept,
          listener,
        )
      converter.setOutput(renderer)
      return HardwareBufferSurfaceRenderer(converter, renderer, errorConsumer)
    }
  }
}
