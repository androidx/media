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

import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.test.utils.doBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelPacketConsumerTest {
  private val testTimeout = 1000L
  private lateinit var exceptionThrown: CompletableDeferred<Exception>
  private lateinit var frameConsumed: CompletableDeferred<Unit>
  private lateinit var releaseCalled: CompletableDeferred<Unit>

  @Before
  fun setup() {
    exceptionThrown = CompletableDeferred()
    releaseCalled = CompletableDeferred()
    frameConsumed = CompletableDeferred()
  }

  @Test
  fun queuePacket_forwardsToCallbacks() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val inputFrame3 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val consumer =
      ChannelPacketConsumer(onConsume = consumedFrames::add, onRelease = releasedFrames::add)
    val runJob = launch { consumer.run() }

    consumer.queuePacket(Packet.of(inputFrame1))
    consumer.queuePacket(Packet.of(inputFrame2))
    consumer.queuePacket(Packet.of(inputFrame3))

    assertThat(consumedFrames).containsExactly(inputFrame1, inputFrame2, inputFrame3).inOrder()
    assertThat(releasedFrames).containsExactly(inputFrame1, inputFrame2, inputFrame3).inOrder()

    runJob.cancelAndJoin()
    consumer.release()
  }

  @Test
  fun tryQueuePacket_forwardsToCallbacks() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val inputFrame3 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame ->
          consumedFrames.add(frame)
          frameConsumed.complete(Unit)
        },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    launch { consumer.run() }

    yield()
    assertThat(consumer.tryQueuePacket(Packet.of(inputFrame1))).isTrue()
    withTimeout(testTimeout) { frameConsumed.await() }
    frameConsumed = CompletableDeferred()

    assertThat(consumer.tryQueuePacket(Packet.of(inputFrame2))).isTrue()
    withTimeout(testTimeout) { frameConsumed.await() }
    frameConsumed = CompletableDeferred()

    assertThat(consumer.tryQueuePacket(Packet.of(inputFrame3))).isTrue()
    withTimeout(testTimeout) { frameConsumed.await() }

    assertThat(consumedFrames).containsExactly(inputFrame1, inputFrame2, inputFrame3).inOrder()
    assertThat(releasedFrames).containsExactly(inputFrame1, inputFrame2, inputFrame3).inOrder()

    consumer.release()
  }

  @Test
  fun run_multipleCalls_throwsIllegalStateException() = doBlocking {
    val consumer =
      ChannelPacketConsumer<TestFrame>(onConsume = { frameConsumed.complete(Unit) }, onRelease = {})
    val runJob1 = launch { consumer.run() }
    consumer.queuePacket(Packet.of(TestFrame()))
    frameConsumed.await()

    assertThrows(IllegalStateException::class.java) { runBlocking { consumer.run() } }

    runJob1.cancelAndJoin()
  }

  @Test
  fun consumeThrows_propagatesExceptionToRun() = doBlocking {
    val inputFrame1 = TestFrame()
    val releasedFrames = mutableListOf<TestFrame>()
    val exception = RuntimeException("Test exception")
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { throw exception },
        onRelease = { frame ->
          releasedFrames.add(frame)
          releaseCalled.complete(Unit)
        },
      )
    val runJob = launch {
      try {
        consumer.run()
      } catch (e: Exception) {
        exceptionThrown.complete(e)
      }
    }

    consumer.queuePacket(Packet.of(inputFrame1))

    val thrownException = withTimeout(testTimeout) { exceptionThrown.await() }
    assertThat(thrownException).isEqualTo(exception)
    assertThat(releasedFrames).containsExactly(inputFrame1).inOrder()
    assertThat(runJob.isCompleted).isTrue()
  }

  @Test
  fun queuePacket_afterException_succeeds() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val deferredFrame = CompletableDeferred<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val exception = RuntimeException("Test exception")
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame ->
          if (frame == inputFrame1) {
            throw exception
          }
          deferredFrame.complete(frame)
        },
        onRelease = { frame ->
          releasedFrames.add(frame)
          releaseCalled.complete(Unit)
        },
      )
    val runJob1 = launch {
      try {
        consumer.run()
      } catch (e: Exception) {
        exceptionThrown.complete(e)
      }
    }
    consumer.queuePacket(Packet.of(inputFrame1))
    withTimeout(testTimeout) { runJob1.join() }
    launch { consumer.run() }

    consumer.queuePacket(Packet.of(inputFrame2))

    val outputFrame = withTimeout(testTimeout) { deferredFrame.await() }
    assertThat(outputFrame).isEqualTo(inputFrame2)

    consumer.release()
  }

  @Test
  fun release_endsRunJob() = doBlocking {
    val consumer = ChannelPacketConsumer<TestFrame>(onConsume = {}, onRelease = {})
    val runJob = launch { consumer.run() }

    launch { consumer.release() }.join()

    withTimeout(testTimeout) { runJob.join() }
  }

  @Test
  fun runJob_whenCancelled_doesNotReleaseConsumer() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame ->
          frameConsumed.complete(Unit)
          consumedFrames.add(frame)
        },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    val runJob1 = launch { consumer.run() }

    consumer.queuePacket(Packet.of(inputFrame1))
    frameConsumed.await()
    runJob1.cancelAndJoin()

    assertThat(consumedFrames).containsExactly(inputFrame1)

    frameConsumed = CompletableDeferred()
    launch { consumer.run() }
    consumer.queuePacket(Packet.of(inputFrame2))
    frameConsumed.await()

    assertThat(consumedFrames).containsExactly(inputFrame1, inputFrame2).inOrder()

    consumer.release()
  }

  @Test
  fun cancelQueuePacket_doesNotConsumeCancelledPacket() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val frameReceived = CompletableDeferred<Unit>()
    val continueProcessing = CompletableDeferred<Unit>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame ->
          consumedFrames.add(frame)
          frameReceived.complete(Unit)
          continueProcessing.await()
        },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    launch { consumer.run() }
    consumer.queuePacket(Packet.of(inputFrame1))
    val queuePacket2 = launch { consumer.queuePacket(Packet.of(inputFrame2)) }
    frameReceived.await()

    queuePacket2.cancelAndJoin()
    continueProcessing.complete(Unit)
    yield()

    assertThat(consumedFrames).containsExactly(inputFrame1)
    assertThat(releasedFrames).containsExactly(inputFrame1)

    consumer.release()
  }

  @Test
  fun release_withQueuedPacket_releasesQueuedPacket() = doBlocking {
    val inputFrame1 = TestFrame()
    val inputFrame2 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val frameReceived = CompletableDeferred<Unit>()
    val continueProcessing = CompletableDeferred<Unit>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame ->
          frameReceived.complete(Unit)
          consumedFrames.add(frame)
          continueProcessing.await()
        },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    val runJob = launch { consumer.run() }
    launch {
      consumer.queuePacket(Packet.of(inputFrame1))
      consumer.queuePacket(Packet.of(inputFrame2))
    }
    frameReceived.await()

    consumer.release()

    // Releasing the consumer causes all queued packets to be released.
    assertThat(releasedFrames).containsExactly(inputFrame2).inOrder()

    runJob.cancelAndJoin()

    // Cancelling the run job causes all in progress packets to be released.
    assertThat(releasedFrames).containsExactly(inputFrame2, inputFrame1).inOrder()
    assertThat(consumedFrames).containsExactly(inputFrame1)
  }

  @Test
  fun run_afterRelease_throwsIllegalStateException() = doBlocking {
    val consumer = ChannelPacketConsumer<TestFrame>(onConsume = {}, onRelease = {})

    consumer.release()

    assertThrows(IllegalStateException::class.java) { runBlocking { consumer.run() } }
  }

  @Test
  fun queuePacket_afterRelease_throwsClosedSendChannelException() = doBlocking {
    val inputFrame1 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame -> consumedFrames.add(frame) },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    launch { consumer.run() }
    launch { consumer.release() }.join()

    assertThrows(ClosedSendChannelException::class.java) {
      runBlocking { consumer.queuePacket(Packet.of(inputFrame1)) }
    }

    assertThat(releasedFrames).isEmpty()
    assertThat(consumedFrames).isEmpty()
  }

  @Test
  fun tryQueuePacket_afterRelease_throwsClosedSendChannelException() = doBlocking {
    val inputFrame1 = TestFrame()
    val consumedFrames = mutableListOf<TestFrame>()
    val releasedFrames = mutableListOf<TestFrame>()
    val consumer =
      ChannelPacketConsumer<TestFrame>(
        onConsume = { frame -> consumedFrames.add(frame) },
        onRelease = { frame -> releasedFrames.add(frame) },
      )
    launch { consumer.run() }
    launch { consumer.release() }.join()

    assertThrows(ClosedSendChannelException::class.java) {
      consumer.tryQueuePacket(Packet.of(inputFrame1))
    }
    assertThat(releasedFrames).isEmpty()
    assertThat(consumedFrames).isEmpty()
  }

  private class TestFrame()
}
