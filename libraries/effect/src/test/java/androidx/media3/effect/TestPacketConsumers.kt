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

import androidx.media3.effect.PacketConsumer.Packet
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await

/** A fake [PacketConsumer] implementation in Kotlin for testing from Java. */
class FakePacketConsumer<T> : PacketConsumer<T> {

  @JvmField val packet1Received: CompletableFuture<Packet<T>> = CompletableFuture()
  @JvmField val packet2Received: CompletableFuture<Packet<T>> = CompletableFuture()
  private var continueProcessing: CompletableFuture<Void> = CompletableFuture()
  private var receivedPacketCount: Int = 0

  /**
   * This implementation adds the received [packet], signals its arrival via the respective
   * [CompletableFuture] ([packet1Received] or [packet2Received]), and then **suspends**.
   *
   * Execution resumes only after calling [continueProcessing].
   */
  override suspend fun queuePacket(packet: Packet<T>) {
    receivedPacketCount++
    if (receivedPacketCount == 1) {
      packet1Received.complete(packet)
    } else if (receivedPacketCount == 2) {
      packet2Received.complete(packet)
    }
    continueProcessing.await()
  }

  fun continueProcessing() {
    continueProcessing.complete(null)
    continueProcessing = CompletableFuture()
  }

  override suspend fun release() {}
}

class ThrowingPacketConsumer<T> : PacketConsumer<T> {
  @JvmField val packetReceived: CompletableFuture<Packet<T>> = CompletableFuture()

  override suspend fun queuePacket(packet: Packet<T>) {
    packetReceived.complete(packet)
    throw IllegalArgumentException()
  }

  override suspend fun release() {}
}
