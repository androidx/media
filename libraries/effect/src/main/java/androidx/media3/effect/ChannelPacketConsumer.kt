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
import androidx.media3.effect.PacketConsumer.Packet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.onClosed

/**
 * A [RunnablePacketConsumer] implementation that uses a [Channel] to receive and process packets of
 * type [T].
 *
 * This class is experimental and will be renamed or removed in a future release.
 *
 * The owner of this class is responsible for launching the [run] method, which will cause the
 * consumer to start calling [onConsume] on queued packets. The owner is also responsible for
 * calling [release] to close the underlying [Channel] once this consumer is no longer needed.
 *
 * It uses a [Channel.RENDEZVOUS] channel. Callers to [queuePacket] will suspend until the consumer
 * is ready to process the packet.
 *
 * The consumer guarantees that for every packet received by the [run] loop, the [onRelease]
 * parameter function will be called exactly once, even if [onConsume] throws an exception or the
 * coroutine is cancelled.
 *
 * @param onConsume A suspending function that processes the packet's payload.
 * @param onRelease A function that releases the packet's payload. This is called in a `finally`
 *   block after [onConsume] finishes or throws.
 */
@ExperimentalApi
class ChannelPacketConsumer<T>(
  private val onConsume: suspend (T) -> Unit,
  private val onRelease: (T) -> Unit,
) : RunnablePacketConsumer<T> {

  // Use Channel.RENDEZVOUS to ensure no packets are buffered in the channel, so callers are
  // suspended until the packet can be consumed.
  private val inputChannel = Channel<Packet<T>>(Channel.RENDEZVOUS)
  private val isReleased = AtomicBoolean(false)
  private val isRunning = AtomicBoolean(false)

  /**
   * Tries to queue a [packet] for consumption without suspending.
   *
   * @param packet The [Packet] to queue.
   * @return `true` if the packet was queued successfully, `false` otherwise. A `false` return value
   *   indicates that the consumer is not ready to accept a packet (i.e., it is busy in its
   *   [onConsume] block).
   * @throws ClosedSendChannelException If the channel is closed.
   */
  override fun tryQueuePacket(packet: Packet<T>): Boolean =
    inputChannel
      .trySend(packet)
      .onClosed { cause ->
        throw cause ?: ClosedSendChannelException("Consumer channel is closed.")
      }
      .isSuccess

  /**
   * Queues a [packet] for consumption, suspending until the packet is accepted.
   *
   * This function will suspend until the consumer's [run] loop is ready to receive the next item.
   *
   * @param packet The [Packet] to queue.
   * @throws ClosedSendChannelException If the channel is closed.
   */
  override suspend fun queuePacket(packet: Packet<T>) {
    inputChannel.send(packet)
  }

  /**
   * Releases the consumer.
   *
   * This closes the input channel and cancels the worker coroutine. Any packets remaining in the
   * channel will be released. Once released, the consumer can no longer accept packets. This method
   * is idempotent.
   */
  override suspend fun release() {
    if (isReleased.compareAndSet(false, true)) {
      inputChannel.close()
      drainChannel()
    }
  }

  /**
   * Runs the worker loop.
   *
   * This is a suspending, run-to-completion function. It will run until the channel is closed or an
   * error is thrown.
   *
   * The caller is responsible for launching this in a coroutine and handling any exceptions.
   *
   * @throws IllegalStateException If [run] is called while it is already running, or if it is
   *   called after [release].
   */
  override suspend fun run() {
    if (!isRunning.compareAndSet(false, true)) {
      throw IllegalStateException("Consumer is already running.")
    }
    if (isReleased.get()) {
      throw IllegalStateException("Consumer is released.")
    }
    try {
      for (packet in inputChannel) {
        try {
          onConsume(packet.payload)
        } finally {
          onRelease(packet.payload)
        }
      }
    } finally {
      drainChannel()
      isRunning.set(false)
    }
  }

  private fun drainChannel() {
    while (true) {
      val packet = inputChannel.tryReceive().getOrNull() ?: break
      onRelease(packet.payload)
    }
  }
}
