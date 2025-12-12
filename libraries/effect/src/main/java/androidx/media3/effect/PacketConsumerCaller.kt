/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect

import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.util.concurrent.ListenableFuture
import java.lang.Exception
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * A utility that connects a producer of [Packet] of type [T] to a [PacketConsumer] via a [Channel].
 *
 * This class is experimental and will be renamed or removed in a future release.
 *
 * The owner of this class is responsible for invoking the [run] method, which will start sending
 * the queued [Packet] to the wrapped [PacketConsumer]. The owner is also responsible for calling
 * [release] to close the underlying [Channel] once this consumer is no longer needed.
 */
@ExperimentalApi
class PacketConsumerCaller<T>
private constructor(
  private val packetConsumer: PacketConsumer<T>,
  private val scope: CoroutineScope,
  private val errorConsumer: Consumer<Exception>,
) {
  private val packetChannel: Channel<Packet<T>> = Channel(RENDEZVOUS)
  private var consumerJob: Job? = null

  /** Start sending queued [Packet] the wrapped [PacketConsumer] */
  fun run() {
    if (consumerJob != null) {
      return
    }
    consumerJob =
      scope.launch {
        for (packet in packetChannel) {
          try {
            packetConsumer.queuePacket(packet)
          } catch (e: Exception) {
            errorConsumer.accept(e)
          }
        }
      }
  }

  /**
   * Attempts to queue a [Packet] for processing without suspending.
   *
   * If this method returns `true`, the ownership of the [packet] is transferred to the wrapped
   * [PacketConsumer] and the caller must not modify the [packet]. If returns `false`, it means the
   * consumer is at capacity and cannot accept the packet at the moment.
   *
   * @throws ClosedSendChannelException If the channel to send [packet] to the [PacketConsumer] is
   *   closed.
   */
  fun tryQueuePacket(packet: Packet<T>): Boolean =
    packetChannel
      .trySend(packet)
      .onClosed { t -> throw t ?: ClosedSendChannelException("Channel is closed") }
      .isSuccess

  /**
   * Queues a [Packet] for processing.
   *
   * The ownership of the [packet] is transferred to the wrapped [PacketConsumer] and the caller
   * must not modify the [packet].
   */
  fun queuePacket(packet: Packet<T>): ListenableFuture<Void?> =
    scope.future {
      packetChannel.send(packet)
      null
    }

  /**
   * Releases the internal [Channel] and cancels coroutine to queue [Packet] to the wrapped
   * [PacketConsumer].
   */
  fun release() {
    packetChannel.close()
    consumerJob?.cancel()
    consumerJob = null
    scope.cancel()
  }

  companion object {
    /**
     * Creates a [PacketConsumerCaller] instance.
     *
     * @param packetConsumer The [PacketConsumer] to wrap.
     * @param executorService The [ExecutorService] on which the [PacketConsumer.queuePacket] is
     *   invoked.
     * @param errorConsumer A [Consumer] for thrown [Exception].
     */
    @JvmStatic
    fun <T> create(
      packetConsumer: PacketConsumer<T>,
      executorService: ExecutorService,
      errorConsumer: Consumer<Exception>,
    ): PacketConsumerCaller<T> =
      PacketConsumerCaller(
        packetConsumer,
        CoroutineScope(executorService.asCoroutineDispatcher()),
        errorConsumer,
      )
  }
}
