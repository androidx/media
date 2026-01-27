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

import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.PacketConsumer.Packet

// TODO: b/449957627 - Update this to apply effects to incoming frames.
/** A [PacketProcessor] the forwards the first frame from each input [Packet] downstream. */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class DefaultHardwareBufferEffectsPipeline :
  PacketProcessor<List<HardwareBufferFrame>, HardwareBufferFrame> {
  private var output: PacketConsumer<HardwareBufferFrame>? = null

  override fun setOutput(output: PacketConsumer<HardwareBufferFrame>) {
    this.output = output
  }

  override suspend fun queuePacket(packet: Packet<List<HardwareBufferFrame>>) {
    when (packet) {
      is Packet.EndOfStream -> output?.queuePacket(packet)
      is Packet.Payload -> {
        for (i in 1..packet.payload.lastIndex) {
          packet.payload[i].release()
        }
        output?.queuePacket(Packet.of(packet.payload[0]))
      }
    }
  }

  override suspend fun release() {}
}
