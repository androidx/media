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
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline
import androidx.media3.effect.GlTextureFrameRenderer.Listener.NO_OP
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.PacketConsumerHardwareBufferFrameQueue
import androidx.media3.effect.ndk.HardwareBufferSurfaceRenderer.Companion.create
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * A [androidx.media3.effect.PacketConsumer] that renders a [Packet] of
 * [androidx.media3.effect.HardwareBufferFrame]s to a [android.view.SurfaceHolder] by using a
 * [HardwareBufferToGlTextureFrameProcessor] and [androidx.media3.effect.GlTextureFrameRenderer].
 */
@RequiresApi(34)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class ProcessAndRenderToSurfaceConsumer
private constructor(
  private val effectsPipeline: DefaultHardwareBufferEffectsPipeline,
  private val frameQueue: PacketConsumerHardwareBufferFrameQueue,
  private val hardwareBufferRenderer: HardwareBufferSurfaceRenderer,
  private val rendererExecutor: Executor,
) : PacketConsumer<ImmutableList<HardwareBufferFrame>>, SurfaceHolder.Callback {

  /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
  class Factory(
    private val context: Context,
    private val glExecutorService: ExecutorService,
    private val glObjectsProvider: GlObjectsProvider,
    private val errorHandler: Consumer<Exception>,
  ) : PacketConsumer.Factory<ImmutableList<HardwareBufferFrame>> {
    private var surfaceHolder: SurfaceHolder? = null

    override fun create(): PacketConsumer<ImmutableList<HardwareBufferFrame>> {
      val hardwareBufferRenderer =
        create(
          context,
          MoreExecutors.listeningDecorator(glExecutorService),
          glObjectsProvider,
          NO_OP,
          errorHandler,
        )
      val frameQueue = PacketConsumerHardwareBufferFrameQueue(errorHandler, glExecutorService)
      val effectsPipeline = DefaultHardwareBufferEffectsPipeline()

      effectsPipeline.setRenderOutput(frameQueue)
      frameQueue.setOutput(hardwareBufferRenderer)

      val processAndRenderConsumer =
        ProcessAndRenderToSurfaceConsumer(
          effectsPipeline,
          frameQueue,
          hardwareBufferRenderer,
          glExecutorService,
        )
      surfaceHolder?.let { surfaceHolder ->
        surfaceHolder.addCallback(processAndRenderConsumer)
        hardwareBufferRenderer.setRenderOutput(
          SurfaceInfo(
            surfaceHolder.surface,
            surfaceHolder.surfaceFrame.width(),
            surfaceHolder.surfaceFrame.height(),
          )
        )
      }

      return processAndRenderConsumer
    }

    fun setOutput(output: SurfaceHolder?) {
      this.surfaceHolder = output
    }
  }

  override suspend fun queuePacket(packet: Packet<ImmutableList<HardwareBufferFrame>>) {
    effectsPipeline.queuePacket(packet)
  }

  override suspend fun release() {
    effectsPipeline.release()
    frameQueue.release()
    hardwareBufferRenderer.release()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
    rendererExecutor.execute {
      hardwareBufferRenderer.setRenderOutput(SurfaceInfo(holder.surface, width, height))
    }

  override fun surfaceCreated(holder: SurfaceHolder) {}

  override fun surfaceDestroyed(holder: SurfaceHolder) =
    rendererExecutor.execute { hardwareBufferRenderer.setRenderOutput(null) }
}
