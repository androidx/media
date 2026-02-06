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

import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

/**
 * A [PacketProcessor] implementation that operates on [GlTextureFrame]s and wraps a
 * [GlShaderProgram].
 *
 * This class is experimental and subject to change.
 *
 * This implementation currently enforces a strict one-to-one mapping between input and output
 * frames. It will throw a [VideoFrameProcessingException] if the wrapped [GlShaderProgram] fails to
 * produce a single output frame for a given input frame, and will suspend until output is produced.
 *
 * TODO: b/463340817 - Update this to handle arbitrary number of input and output frames from the
 *   wrapped GlShaderProgram.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
@RestrictTo(LIBRARY_GROUP)
class GlShaderProgramPacketProcessor
private constructor(
  private val glThreadDispatcher: CoroutineDispatcher,
  private val shaderProgram: GlShaderProgram,
  private val glObjectsProvider: GlObjectsProvider,
  private val shaderProgramErrorHandler: Consumer<VideoFrameProcessingException>,
) :
  PacketProcessor<GlTextureFrame, GlTextureFrame>,
  GlShaderProgram.InputListener,
  GlShaderProgram.OutputListener {

  private val isReleased = AtomicBoolean()

  @Volatile private var outputConsumer: PacketConsumer<GlTextureFrame>? = null

  // State accessed only on the dispatcher thread
  private var currentInputFrame: GlTextureFrame? = null
  private var currentInputMetadata: Frame.Metadata? = null
  private var outputFrameDeferred: CompletableDeferred<GlTextureFrame>? = null

  init {
    shaderProgram.setInputListener(this)
    shaderProgram.setOutputListener(this)
    // The error listener will run on the dispatcher thread.
    shaderProgram.setErrorListener(
      glThreadDispatcher.asExecutor(),
      shaderProgramErrorHandler::accept,
    )
  }

  /**
   * Queues a [Packet] for processing.
   *
   * Suspends for [Packet.Payload] until the [GlShaderProgram] produces an output frame, then queues
   * it to the [outputConsumer]. [Packet.EndOfStream] is propagated immediately.
   */
  override suspend fun queuePacket(packet: Packet<GlTextureFrame>) {
    withContext(glThreadDispatcher) {
      if (isReleased.get()) {
        if (packet is Packet.Payload) packet.payload.release(/* releaseFence= */ null)
        return@withContext
      }

      when (packet) {
        is Packet.Payload -> processFramePacket(packet.payload)
        is Packet.EndOfStream -> {
          outputConsumer?.queuePacket(Packet.EndOfStream)
        }
      }
    }
  }

  private suspend fun processFramePacket(inputFrame: GlTextureFrame) {
    check(outputFrameDeferred == null) { "Frame processing already in progress" }
    check(currentInputFrame == null) { "currentInputFrame not null" }

    currentInputFrame = inputFrame
    currentInputMetadata = inputFrame.metadata
    val deferred = CompletableDeferred<GlTextureFrame>()
    outputFrameDeferred = deferred
    var outputFrame: GlTextureFrame? = null

    try {
      shaderProgram.queueInputFrame(
        glObjectsProvider,
        inputFrame.glTextureInfo,
        inputFrame.presentationTimeUs,
      )
      outputFrame = deferred.await()
      outputConsumer?.queuePacket(Packet.of(outputFrame))
        ?: outputFrame.release(/* releaseFence= */ null)
    } catch (e: Exception) {
      outputFrame?.release(/* releaseFence= */ null)
      throw e
    } finally {
      inputFrame.release(/* releaseFence= */ null)
      currentInputFrame = null
      currentInputMetadata = null
      outputFrameDeferred = null
    }
  }

  override fun setOutput(output: PacketConsumer<GlTextureFrame>) {
    this.outputConsumer = output
  }

  override suspend fun release() {
    if (!isReleased.compareAndSet(false, true)) return
    withContext(glThreadDispatcher) {
      outputFrameDeferred?.cancel("Processor released")
      outputFrameDeferred = null
      currentInputFrame?.release(/* releaseFence= */ null)
      currentInputFrame = null
      currentInputMetadata = null
      shaderProgram.release()
    }
  }

  // GlShaderProgram.OutputListener - Called on GL thread

  override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
    if (isReleased.get()) {
      shaderProgram.releaseOutputFrame(outputTexture)
      return
    }

    val deferred = checkNotNull(outputFrameDeferred)
    val inputMetadata = currentInputMetadata
    val inputFrame = currentInputFrame
    if (inputFrame == null || inputMetadata == null) {
      deferred.completeExceptionally(
        VideoFrameProcessingException(
          "Missing input frame/metadata for output at $presentationTimeUs"
        )
      )
      return
    }

    val outputFrame =
      GlTextureFrame.Builder(
          outputTexture,
          glThreadDispatcher.asExecutor(),
          /* releaseTextureCallback= */ { texInfo -> shaderProgram.releaseOutputFrame(texInfo) },
        )
        .setPresentationTimeUs(presentationTimeUs)
        .setFormat(inputFrame.format)
        .setMetadata(inputMetadata)
        .setReleaseTimeNs(inputFrame.releaseTimeNs)
        .setFenceSync(inputFrame.fenceSync)
        .build()

    check(deferred.complete(outputFrame))
  }

  companion object {

    /**
     * Creates a new [GlShaderProgramPacketProcessor] instance on the [glThreadDispatcher].
     *
     * The [shaderProgram] to wrap must produce a single output frame per input frame.
     */
    suspend fun create(
      shaderProgram: GlShaderProgram,
      glThreadDispatcher: CoroutineDispatcher,
      glObjectsProvider: GlObjectsProvider,
      shaderProgramErrorHandler: Consumer<VideoFrameProcessingException>,
    ): GlShaderProgramPacketProcessor {
      return withContext(glThreadDispatcher) {
        GlShaderProgramPacketProcessor(
          glThreadDispatcher,
          shaderProgram,
          glObjectsProvider,
          shaderProgramErrorHandler,
        )
      }
    }

    /**
     * Asynchronously creates a [GlShaderProgramPacketProcessor] using a [ListenableFuture].
     *
     * The [shaderProgram] to wrap must produce a single output frame per input frame.
     */
    fun createAsync(
      shaderProgram: GlShaderProgram,
      glThreadExecutorService: ExecutorService,
      glObjectsProvider: GlObjectsProvider,
      shaderProgramErrorHandler: Consumer<VideoFrameProcessingException>,
    ): ListenableFuture<GlShaderProgramPacketProcessor> {
      val glDispatcher = glThreadExecutorService.asCoroutineDispatcher()
      return CoroutineScope(glDispatcher).future {
        create(shaderProgram, glDispatcher, glObjectsProvider, shaderProgramErrorHandler)
      }
    }
  }
}
