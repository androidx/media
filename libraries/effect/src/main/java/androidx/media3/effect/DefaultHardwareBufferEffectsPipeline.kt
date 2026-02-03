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

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import androidx.annotation.RequiresApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.effect.PacketConsumer.Packet
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// TODO: b/479415308 - Replace HardwareBufferRenderer with another method of copying data to support
// APIs below 34.
/**
 * A [PacketProcessor] that renders the input [HardwareBufferFrame] into a new output buffer using
 * [HardwareBufferRenderer].
 */
@RequiresApi(34)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class DefaultHardwareBufferEffectsPipeline :
  RenderingPacketConsumer<List<HardwareBufferFrame>, HardwareBufferFrameQueue> {

  /** Executor used for all blocking [SyncFence.await] calls. */
  private val internalExecutor = Executors.newSingleThreadExecutor()
  private val internalDispatcher = internalExecutor.asCoroutineDispatcher()
  private val isReleased = AtomicBoolean(false)
  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private var outputBufferQueue: HardwareBufferFrameQueue? = null

  override fun setRenderOutput(output: HardwareBufferFrameQueue?) {
    this.outputBufferQueue = output
  }

  override suspend fun queuePacket(packet: Packet<List<HardwareBufferFrame>>) {
    check(!isReleased.get())
    when (packet) {
      is Packet.EndOfStream -> outputBufferQueue!!.signalEndOfStream()
      is Packet.Payload -> {
        for (i in 1..packet.payload.lastIndex) {
          packet.payload[i].release()
        }
        if (packet.payload.isNotEmpty()) {
          processFrame(packet.payload[0])
        }
      }
    }
  }

  override suspend fun release() {
    if (!isReleased.getAndSet(true)) {
      internalExecutor.shutdown()
    }
  }

  private suspend fun processFrame(inputFrame: HardwareBufferFrame) {
    try {
      if (inputFrame.hardwareBuffer == null) {
        throw IllegalArgumentException("Input frame missing HardwareBuffer")
      }
      // Get the output buffer that will be sent downstream.
      val outputFrame = getOutputFrame(inputFrame)
      check(outputFrame.hardwareBuffer != null)

      // Draw the input buffer contents into the output buffer.
      val acquireFence =
        renderToOutputBuffer(
          inputFrame.hardwareBuffer,
          inputFrame.acquireFence,
          inputFrame.format.width,
          inputFrame.format.height,
          outputFrame.hardwareBuffer,
          outputFrame.acquireFence,
        )

      // Send the output buffer downstream.
      val outputFrameWithMetadata =
        outputFrame
          .buildUpon()
          .setPresentationTimeUs(inputFrame.presentationTimeUs)
          .setReleaseTimeNs(inputFrame.releaseTimeNs)
          .setFormat(inputFrame.format)
          .setMetadata(inputFrame.metadata)
          .setAcquireFence(acquireFence)
          .build()
      outputBufferQueue!!.queue(outputFrameWithMetadata)
    } finally {
      inputFrame.release()
    }
  }

  private suspend fun getOutputFrame(inputFrame: HardwareBufferFrame): HardwareBufferFrame {
    val width = inputFrame.format.width
    val height = inputFrame.format.height
    val bufferFormat =
      HardwareBufferFrameQueue.FrameFormat(
        width,
        height,
        HardwareBuffer.RGBA_8888,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )

    // Try and get an output buffer from the queue. If not immediately available, suspend until
    // notified and retry.
    val capacityAvailable = CompletableDeferred<Unit>()
    var outputFrame =
      outputBufferQueue!!.dequeue(bufferFormat)
      /* wakeupListener= */ {
        capacityAvailable.complete(Unit)
      }
    if (outputFrame == null) {
      withTimeout(TIMEOUT_MS) { capacityAvailable.await() }
      outputFrame = outputBufferQueue!!.dequeue(bufferFormat) /* wakeupListener= */ {}
      // Throw the second time there is no buffer available.
      check(outputFrame != null)
    }
    return outputFrame
  }

  private suspend fun renderToOutputBuffer(
    inputBuffer: HardwareBuffer,
    inputFence: SyncFence?,
    inputWidth: Int,
    inputHeight: Int,
    outputBuffer: HardwareBuffer,
    outputFence: SyncFence?,
  ): SyncFence {
    // TODO: b/479415308 - Replace HardwareBufferRenderer with another method of copying data to
    // support APIs below 34.
    return HardwareBufferRenderer(outputBuffer).use { renderer ->
      // Ensure the input buffer has been fully written to, and is ready to be read.
      waitOn(inputFence)
      check(!inputBuffer.isClosed)
      val inputBitmap =
        Bitmap.wrapHardwareBuffer(inputBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
          ?: throw IllegalStateException("Failed to wrap input HardwareBuffer in Bitmap")

      val renderNode = RenderNode("PlaceholderEffect")
      renderNode.setPosition(0, 0, inputWidth, inputHeight)

      // Ensure the output buffer has been fully read from and is ready for reuse.
      waitOn(outputFence)
      check(!outputBuffer.isClosed)

      val canvas = renderNode.beginRecording(inputWidth, inputHeight)
      canvas.drawBitmap(inputBitmap, 0f, 0f, null)
      renderNode.endRecording()

      renderer.setContentRoot(renderNode)

      suspendCancellableCoroutine { continuation ->
        renderer.obtainRenderRequest().draw(internalExecutor) { result ->
          val fence = result.fence
          // Tries to resume; if it fails (because already cancelled), closes the fence.
          runCatching { continuation.resume(fence) { _, _, _ -> fence.close() } }
            .onFailure { fence.close() }
        }
      }
    }
  }

  /** Helper function to suspend, switch to an internal thread and wait on the given [SyncFence]. */
  private suspend fun waitOn(fence: SyncFence?) {
    fence?.let {
      // Switch to the internal dispatcher for the blocking call.
      val signaled = withContext(internalDispatcher) { fence.await(Duration.ofMillis(500)) }
      if (!signaled) {
        Log.w(TAG, "Timed out waiting for fence.")
      }
    }
  }

  companion object {
    private const val TAG = "DefaultHBEffects"
    // It can sometimes take ~1 second for the encoder to be configured and the first frame to be
    // encoded.
    private const val TIMEOUT_MS = 1_500L
  }
}
