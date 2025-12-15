/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.demo.composition.effect

import android.content.Context
import android.view.SurfaceHolder
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlTextureFrame
import androidx.media3.effect.GlTextureFrameCompositor
import androidx.media3.effect.GlTextureFrameRenderer
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.transformer.CompositionFrameMetadata
import com.google.common.util.concurrent.MoreExecutors.listeningDecorator
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin

// TODO: b/449957627 - Remove once pipeline has been migrated to PacketConsumer interface.
/**
 * A [PacketConsumer] that:
 * 1. Composites multiple [GlTextureFrame]s into a single output [GlTextureFrame] using
 *    [GlTextureFrameCompositor].
 * 2. Renders the [GlTextureFrame] into [SurfaceHolder] using [GlTextureFrameRenderer].
 * 3. Uses [SurfaceHolder.Callback] callbacks to update the [GlTextureFrameRenderer].
 */
@ExperimentalApi
@UnstableApi
internal class ProcessAndRenderToSurfaceConsumer
private constructor(
  context: Context,
  glExecutorService: ExecutorService,
  glObjectsProvider: GlObjectsProvider,
  private val errorListener: Consumer<Exception>,
  output: SurfaceHolder?,
) : PacketConsumer<List<GlTextureFrame>>, SurfaceHolder.Callback {

  /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
  class Factory(
    private val context: Context,
    private val glExecutorService: ExecutorService,
    private val glObjectsProvider: GlObjectsProvider,
    private val errorListener: Consumer<Exception>,
  ) : PacketConsumer.Factory<List<GlTextureFrame>> {
    private var output: SurfaceHolder? = null

    override fun create(): PacketConsumer<List<GlTextureFrame>> {
      return ProcessAndRenderToSurfaceConsumer(
        context,
        glExecutorService,
        glObjectsProvider,
        errorListener,
        output,
      )
    }

    fun setOutput(output: SurfaceHolder?) {
      this.output = output
    }
  }

  /**
   * A [PacketConsumer] which renders [GlTextureFrame] packets to an output [android.view.Surface].
   */
  private val surfaceViewRenderer =
    GlTextureFrameRenderer.create(
      context,
      listeningDecorator(glExecutorService),
      glObjectsProvider,
      errorHandler = { t -> errorListener.accept(t) },
      GlTextureFrameRenderer.Listener.NO_OP,
    )

  /**
   * A packet processor which inputs a list of [GlTextureFrame]s and outputs a single
   * [GlTextureFrame]. Video contents are composited as instructed by
   * [GlTextureFrameCompositor.videoCompositorSettings].
   */
  private val compositor =
    GlTextureFrameCompositor(context, glExecutorService.asCoroutineDispatcher(), glObjectsProvider)

  /** A [CoroutineScope] which launches coroutines that run on the GL thread. */
  private val scope = CoroutineScope(glExecutorService.asCoroutineDispatcher())

  init {
    // Listen to SurfaceHolder callbacks.
    // TODO: b/292111083 - Consider moving the SurfaceHolder callback listener to the
    // GlTextureFrameRenderer.
    output?.addCallback(this)

    // Forward the compositor output to the renderer.
    compositor.setOutput(surfaceViewRenderer)
  }

  // PacketConsumer implementation which forwards frames to compositor.

  override suspend fun queuePacket(packet: Packet<List<GlTextureFrame>>) {
    // TODO: b/463336410 - Make a Composition-aware GlTextureFrameCompositor which updates
    // videoCompositorSettings from CompositionFrameMetadata.
    if (packet is Packet.Payload) {
      compositor.videoCompositorSettings =
        (packet.payload[0].metadata as CompositionFrameMetadata).composition.videoCompositorSettings
    }
    compositor.queuePacket(packet)
  }

  override suspend fun release() {
    surfaceViewRenderer.release()
    compositor.release()
    scope.coroutineContext[Job]?.cancelAndJoin()
  }

  // SurfaceHolder.Callback implementation

  override fun surfaceCreated(holder: SurfaceHolder) {}

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    surfaceViewRenderer.setOutputSurfaceInfo(SurfaceInfo(holder.surface, width, height))
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    surfaceViewRenderer.setOutputSurfaceInfo(null)
  }
}
