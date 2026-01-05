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
package androidx.media3.test.utils

import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet

/**
 * A [PacketConsumer] implementation that holds a reference to all queued packets, and optionally
 * releases the underlying frames.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class RecordingPacketConsumer(private val releaseIncomingFrames: Boolean) :
  PacketConsumer<MutableList<HardwareBufferFrame>> {
  val queuedPackets: List<MutableList<HardwareBufferFrame>>
    get() {
      return _queuedPackets.toList()
    }

  private val _queuedPackets: MutableList<MutableList<HardwareBufferFrame>> = ArrayList()

  override suspend fun queuePacket(packet: Packet<MutableList<HardwareBufferFrame>>) {
    if (packet is Packet.Payload) {
      queue(packet.payload)
    }
  }

  override suspend fun release() {}

  private fun queue(frames: MutableList<HardwareBufferFrame>) {
    if (releaseIncomingFrames) {
      for (frame in frames) {
        frame.release()
      }
    }
    _queuedPackets.add(frames)
  }
}
