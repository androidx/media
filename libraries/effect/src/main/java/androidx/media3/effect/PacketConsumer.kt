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
package androidx.media3.effect

/**
 * A consumer that accepts [Packet]s.
 *
 * This interface is experimental and will be renamed or removed in a future release.
 *
 * @param T The type of [Packet.payload] being consumed.
 */
internal interface PacketConsumer<T> {

  /**
   * Represents a data packet that wraps a [payload].
   *
   * @param T The type of the [payload] contained within the packet.
   */
  interface Packet<T> {
    /** The data carried by this packet. */
    val payload: T
  }

  /**
   * Attempts to queue a [Packet] for processing without blocking.
   *
   * If this method returns `true`, the ownership of the [packet] is transferred to this
   * [PacketConsumer] and the caller must not modify the [packet].
   *
   * @param packet The [Packet] to process.
   * @return `true` if the packet was accepted and queued for processing. Returns `false` if the
   *   consumer is at capacity and cannot accept the packet at this time.
   */
  fun tryQueuePacket(packet: Packet<T>): Boolean

  /**
   * Queues a [Packet] for processing, suspending the caller if the consumer is at capacity.
   *
   * Once this method returns, the ownership of the [packet] is transferred to this
   * [PacketConsumer], and the caller should not modify the [packet].
   *
   * @param packet The [Packet] to process.
   */
  suspend fun queuePacket(packet: Packet<T>)

  /** Releases all resources. */
  suspend fun release()
}
