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
package androidx.media3.demo.composition.effect

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketProcessor

/**
 * A [PacketProcessor] that selects the first frame from a list of input frames and passes it to the
 * output consumer.
 */
@OptIn(ExperimentalApi::class) // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class PassthroughPacketProcessor : PacketProcessor<List<HardwareBufferFrame>, HardwareBufferFrame> {
  @Volatile private var outputConsumer: PacketConsumer<HardwareBufferFrame>? = null

  override fun setOutput(output: PacketConsumer<HardwareBufferFrame>) {
    outputConsumer = output
  }

  override suspend fun queuePacket(packet: PacketConsumer.Packet<List<HardwareBufferFrame>>) {
    when (packet) {
      is PacketConsumer.Packet.EndOfStream ->
        outputConsumer?.queuePacket(PacketConsumer.Packet.EndOfStream)
      is PacketConsumer.Packet.Payload -> {
        for (i in 1..<packet.payload.size) {
          packet.payload[i].release()
        }
        outputConsumer?.queuePacket(PacketConsumer.Packet.of(packet.payload[0]))
      }
    }
  }

  override suspend fun release() {}
}
