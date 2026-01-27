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
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.guava.future

@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
object PacketConsumerUtil {

  /**
   * Releases the [PacketConsumer] from Java by wrapping the suspend call in a [ListenableFuture].
   *
   * @param consumer The [PacketConsumer] to release.
   * @param executor The [ExecutorService] to launch the coroutine on.
   * @return A [ListenableFuture] that completes when the consumer has been released.
   */
  @JvmStatic
  fun <T> release(consumer: PacketConsumer<T>, executor: ExecutorService): ListenableFuture<Void?> =
    CoroutineScope(executor.asCoroutineDispatcher()).future {
      consumer.release()
      null
    }

  /**
   * A functional interface representing a function that accepts one argument and produces a result,
   * potentially throwing an exception during execution.
   */
  fun interface ThrowingFunction<I, O> {
    @Throws(Exception::class) fun apply(input: I): O
  }

  /**
   * Creates a [PacketProcessor] that applies a transformation function to incoming payloads and
   * executes a callback when the stream ends.
   *
   * When a [Packet.Payload] is received, the [onPayload] function is applied to the data, and the
   * result is wrapped in a new packet and sent to the output.
   *
   * When a [Packet.EndOfStream] is received, the [onEndOfStream] runnable is executed immediately
   * before the EOS packet is forwarded to the output.
   */
  @JvmStatic
  fun <I, O> createPacketProcessor(
    onPayload: ThrowingFunction<I, O>,
    onEndOfStream: Runnable,
  ): PacketProcessor<I, O> {
    return ListeningPacketProcessor(
      onPayload = { i -> onPayload.apply(i) },
      onEndOfStream = { onEndOfStream.run() },
    )
  }

  /** Helper class to wrap a function in a [PacketProcessor]. */
  private class ListeningPacketProcessor<I, O>(
    private val onPayload: (I) -> O,
    private val onEndOfStream: () -> Unit,
  ) : PacketProcessor<I, O> {

    private var output: PacketConsumer<O>? = null

    override fun setOutput(output: PacketConsumer<O>) {
      this.output = output
    }

    override suspend fun queuePacket(packet: Packet<I>) {
      when (packet) {
        is Packet.EndOfStream -> {
          onEndOfStream()
          output?.queuePacket(packet)
        }
        is Packet.Payload<I> -> {
          val outputPacket = onPayload(packet.payload)
          output?.queuePacket(Packet.of(outputPacket))
        }
      }
    }

    override suspend fun release() {}
  }
}
