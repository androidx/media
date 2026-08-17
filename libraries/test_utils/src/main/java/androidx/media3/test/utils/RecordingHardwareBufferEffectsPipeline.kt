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
package androidx.media3.test.utils

import android.content.Context
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.HardwareBufferFrameQueue
import androidx.media3.effect.HardwareBufferJniWrapper
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.RenderingPacketConsumer
import com.google.common.collect.ImmutableList

/**
 * Wraps a [DefaultHardwareBufferEffectsPipeline] and triggers a callback on every [queuePacket]
 * call.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class RecordingHardwareBufferEffectsPipeline
private constructor(
  private val internalPipeline:
    RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue>,
  private val onQueue: (ImmutableList<HardwareBufferFrame>) -> ImmutableList<HardwareBufferFrame>?,
) : RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue> {

  override fun setRenderOutput(output: HardwareBufferFrameQueue?) {
    internalPipeline.setRenderOutput(output)
  }

  override fun setErrorConsumer(errorConsumer: Consumer<Exception>) {
    internalPipeline.setErrorConsumer(errorConsumer)
  }

  override suspend fun queuePacket(packet: Packet<ImmutableList<HardwareBufferFrame>>) {
    when (packet) {
      is Packet.EndOfStream -> internalPipeline.queuePacket(Packet.EndOfStream)
      is Packet.Payload -> {
        onQueue(packet.payload)?.let { processedPayload ->
          internalPipeline.queuePacket(Packet.of(processedPayload))
        }
      }
    }
  }

  override suspend fun release() {
    internalPipeline.release()
  }

  companion object {

    /**
     * Create a [RecordingHardwareBufferEffectsPipeline] with a
     * [DefaultHardwareBufferEffectsPipeline].
     */
    @JvmStatic
    @RequiresApi(26)
    fun create(
      context: Context,
      hardwareBufferJniWrapper: HardwareBufferJniWrapper,
      onQueue: (ImmutableList<HardwareBufferFrame>) -> ImmutableList<HardwareBufferFrame>?,
    ): RecordingHardwareBufferEffectsPipeline {
      return RecordingHardwareBufferEffectsPipeline(
        DefaultHardwareBufferEffectsPipeline.create(context, hardwareBufferJniWrapper),
        onQueue,
      )
    }
  }
}
