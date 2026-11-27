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

import androidx.media3.common.util.ExperimentalApi

/**
 * A consumer that accepts [Packet]s.
 *
 * This interface is experimental and will be renamed or removed in a future release.
 *
 * @param T The type of [Packet.payload] being consumed.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
interface PacketConsumer<T> {

  /**
   * Represents a packet.
   *
   * The [Packet] could carry either [Payload] or [EndOfStream].
   *
   * @param T The type of the [Payload.payload] contained within the packet.
   */
  sealed interface Packet<out T> {
    /** A [Packet] implementation to wrap a [payload] of type [T]. */
    data class Payload<T>(val payload: T) : Packet<T>

    /** A [Packet] implementation to represent an end of stream (EOS) signal. */
    data object EndOfStream : Packet<Nothing>

    companion object {
      /**
       * Creates an immutable [Payload].
       *
       * @param payload The data to be carried by the packet.
       * @return A new [Packet] instance wrapping the payload.
       */
      @JvmStatic fun <T> of(payload: T): Packet<T> = Payload(payload)
    }
  }

  /** A factory for [PacketConsumer] instances. */
  fun interface Factory<T> {

    /** Creates a new [PacketConsumer] instance. */
    fun create(): PacketConsumer<T>
  }

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
