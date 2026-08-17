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
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet

/**
 * A [PacketConsumer] implementation that holds a reference to all queued packets, and optionally
 * releases the underlying frames.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class RecordingPacketConsumer<T> : PacketConsumer<T> {
  var onQueue: (T) -> Unit = {}
  val queuedPackets: List<Packet<T>>
    get() {
      return _queuedPackets.toList()
    }

  val queuedPayloads: List<T>
    get() {
      return _queuedPackets.toList().filterIsInstance<Packet.Payload<T>>().map { it.payload }
    }

  private val _queuedPackets: MutableList<Packet<T>> = ArrayList()

  override suspend fun queuePacket(packet: Packet<T>) {
    _queuedPackets.add(packet)
    if (packet is Packet.Payload) {
      onQueue(packet.payload)
    }
  }

  override suspend fun release() {}
}
