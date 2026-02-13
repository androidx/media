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
package androidx.media3.effect

import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.media3.common.util.ExperimentalApi
import com.google.common.collect.ImmutableList
import java.util.concurrent.Executor

/**
 * A [PacketConsumer] that renders a [Packet] of [HardwareBufferFrame]s to a
 * [android.view.SurfaceHolder].
 */
@RequiresApi(34)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class ProcessAndRenderToSurfaceConsumer
private constructor(
  private val effectsPipeline: DefaultHardwareBufferEffectsPipeline,
  private val frameQueue: SurfaceHolderHardwareBufferFrameQueue,
) : PacketConsumer<ImmutableList<HardwareBufferFrame>> {

  /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
  class Factory : PacketConsumer.Factory<ImmutableList<HardwareBufferFrame>> {
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceHolderExecutor: Executor? = null
    private var listener: SurfaceHolderHardwareBufferFrameQueue.Listener? = null
    private var listenerExecutor: Executor? = null

    override fun create(): PacketConsumer<ImmutableList<HardwareBufferFrame>> {
      val frameQueue =
        SurfaceHolderHardwareBufferFrameQueue(
          surfaceHolder!!,
          surfaceHolderExecutor!!,
          listener!!,
          listenerExecutor!!,
        )
      val effectsPipeline = DefaultHardwareBufferEffectsPipeline()

      effectsPipeline.setRenderOutput(frameQueue)

      val processAndRenderConsumer = ProcessAndRenderToSurfaceConsumer(effectsPipeline, frameQueue)

      return processAndRenderConsumer
    }

    fun setOutput(output: SurfaceHolder?, executor: Executor?) {
      this.surfaceHolder = output
      this.surfaceHolderExecutor = executor
    }

    fun setListener(
      listener: SurfaceHolderHardwareBufferFrameQueue.Listener?,
      executor: Executor?,
    ) {
      this.listener = listener
      this.listenerExecutor = executor
    }
  }

  override suspend fun queuePacket(
    packet: PacketConsumer.Packet<ImmutableList<HardwareBufferFrame>>
  ) {
    effectsPipeline.queuePacket(packet)
  }

  override suspend fun release() {
    effectsPipeline.release()
    frameQueue.release()
  }
}
