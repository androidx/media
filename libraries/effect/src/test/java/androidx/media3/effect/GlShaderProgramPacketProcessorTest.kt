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

import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.effect.GlShaderProgram.InputListener
import androidx.media3.effect.GlShaderProgram.OutputListener
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.test.utils.RecordingPacketConsumer
import androidx.media3.test.utils.doBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for [GlShaderProgramPacketProcessor]. */
@RunWith(AndroidJUnit4::class)
class GlShaderProgramPacketProcessorTest {

  private val glThreadExecutor = Executors.newSingleThreadExecutor()
  private val glDispatcher = glThreadExecutor.asCoroutineDispatcher()

  private lateinit var processor: GlShaderProgramPacketProcessor
  private lateinit var mockGlObjectsProvider: GlObjectsProvider
  private lateinit var fakeGlShaderProgram: FakeGlShaderProgram
  private lateinit var recordingErrorConsumer: RecordingErrorConsumer
  private lateinit var recordingOutputConsumer: RecordingPacketConsumer<GlTextureFrame>

  @Before
  fun setUp() {
    mockGlObjectsProvider =
      object : GlObjectsProvider {
        override fun createEglContext(
          eglDisplay: EGLDisplay,
          openGlVersion: Int,
          configAttributes: IntArray,
        ): EGLContext = throw UnsupportedOperationException()

        override fun createEglSurface(
          eglDisplay: EGLDisplay,
          surface: Any,
          colorTransfer: @ColorTransfer Int,
          isEncoderInputSurface: Boolean,
        ): EGLSurface = throw UnsupportedOperationException()

        override fun createFocusedPlaceholderEglSurface(
          eglContext: EGLContext,
          eglDisplay: EGLDisplay,
        ): EGLSurface = throw UnsupportedOperationException()

        override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo =
          throw UnsupportedOperationException()

        override fun release(eglDisplay: EGLDisplay) = throw UnsupportedOperationException()
      }

    fakeGlShaderProgram = FakeGlShaderProgram()
    recordingOutputConsumer = RecordingPacketConsumer<GlTextureFrame>()
    recordingErrorConsumer = RecordingErrorConsumer()

    // Create must be called on the GL thread context
    doBlocking(glDispatcher) {
      processor =
        GlShaderProgramPacketProcessor.create(
          fakeGlShaderProgram,
          glDispatcher,
          mockGlObjectsProvider,
        )
      processor.setOutput(recordingOutputConsumer)
    }
  }

  @After
  fun tearDown() =
    doBlocking(glDispatcher) {
      if (::processor.isInitialized) {
        processor.release()
      }
      glThreadExecutor.shutdownNow()
      recordingErrorConsumer.assertNoUnexpectedErrors()
    }

  @Test
  fun createAsync_completesSuccessfully() {
    val processorFuture: ListenableFuture<GlShaderProgramPacketProcessor> =
      GlShaderProgramPacketProcessor.createAsync(
        fakeGlShaderProgram,
        glThreadExecutor,
        mockGlObjectsProvider,
      )

    val processor = processorFuture.get()

    assertThat(processor).isNotNull()
    runBlocking(glDispatcher) { processor.release() }
  }

  @Test
  fun queuePacket_payload_processesMultipleFramesSuccessfully() = doBlocking {
    val inputFrame1 = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture1 = createTexture(texId = 101)
    val outputPtsUs1 = 1001L
    val inputFrame2 = createTestFrame(id = 2, timestampUs = 2000L)
    val outputTexture2 = createTexture(texId = 102)
    val outputPtsUs2 = 2002L
    val inputFrame3 = createTestFrame(id = 3, timestampUs = 3000L)
    val outputTexture3 = createTexture(texId = 103)
    val outputPtsUs3 = 3003L

    fakeGlShaderProgram.onQueue = { frameInfo ->
      // Executed on the glDispatcher thread.
      when (frameInfo.presentationTimeUs) {
        1000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture1, outputPtsUs1)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
        2000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture2, outputPtsUs2)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
        3000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture3, outputPtsUs3)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
        else ->
          throw IllegalStateException("Unexpected frame timestamp: ${frameInfo.presentationTimeUs}")
      }
    }

    processor.queuePacket(Packet.of(inputFrame1))
    processor.queuePacket(Packet.of(inputFrame2))
    processor.queuePacket(Packet.of(inputFrame3))

    assertThat(recordingOutputConsumer.queuedPackets).hasSize(3)

    val out1 = (recordingOutputConsumer.queuedPackets[0] as Packet.Payload<GlTextureFrame>).payload
    assertThat(out1.glTextureInfo).isEqualTo(outputTexture1)
    assertThat(out1.presentationTimeUs).isEqualTo(outputPtsUs1)
    assertThat(out1.metadata).isEqualTo(inputFrame1.metadata)
    assertThat((inputFrame1.metadata as TestMetadata).released.get()).isTrue()

    val out2: GlTextureFrame =
      (recordingOutputConsumer.queuedPackets[1] as Packet.Payload<GlTextureFrame>).payload
    assertThat(out2.glTextureInfo).isEqualTo(outputTexture2)
    assertThat(out2.presentationTimeUs).isEqualTo(outputPtsUs2)
    assertThat(out2.metadata).isEqualTo(inputFrame2.metadata)
    assertThat((inputFrame2.metadata as TestMetadata).released.get()).isTrue()

    val out3 = (recordingOutputConsumer.queuedPackets[2] as Packet.Payload<GlTextureFrame>).payload
    assertThat(out3.glTextureInfo).isEqualTo(outputTexture3)
    assertThat(out3.presentationTimeUs).isEqualTo(outputPtsUs3)
    assertThat(out3.metadata).isEqualTo(inputFrame3.metadata)
    assertThat((inputFrame3.metadata as TestMetadata).released.get()).isTrue()

    assertThat(fakeGlShaderProgram.queuedFrames).hasSize(3)
    assertThat(fakeGlShaderProgram.queuedFrames[0]!!.presentationTimeUs).isEqualTo(1000L)
    assertThat(fakeGlShaderProgram.queuedFrames[1]!!.presentationTimeUs).isEqualTo(2000L)
    assertThat(fakeGlShaderProgram.queuedFrames[2]!!.presentationTimeUs).isEqualTo(3000L)

    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_suspendsUntilOutputAvailable() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture = createTexture(texId = 101)
    val outputPtsUs = 1001L
    val shaderQueued = CompletableDeferred<Unit>()
    val shaderCanOutput = CompletableDeferred<Unit>()
    fakeGlShaderProgram.onQueue = { _ ->
      // Signal that the frame is in the shader
      shaderQueued.complete(Unit)
      // Wait until the test allows output
      doBlocking { shaderCanOutput.await() }
      // Simulate output
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture, outputPtsUs)
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }

    shaderQueued.await()
    // queuePacket is suspended.
    assertThat(queueJob.isActive).isTrue()

    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()

    shaderCanOutput.complete(Unit)

    queueJob.join()
    assertThat(recordingOutputConsumer.queuedPackets).hasSize(1)
    val outFrame =
      (recordingOutputConsumer.queuedPackets[0] as Packet.Payload<GlTextureFrame>).payload
    assertThat(outFrame.glTextureInfo).isEqualTo(outputTexture)
    assertThat(outFrame.presentationTimeUs).isEqualTo(outputPtsUs)
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_suspendsUntilConsumerAccepts() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture = createTexture(texId = 101)
    val outputPtsUs = 1001L
    val blockConsumer = CompletableDeferred<Unit>()
    val consumerQueued = CompletableDeferred<Unit>()
    recordingOutputConsumer.onQueue = { frame ->
      consumerQueued.complete(Unit)
      doBlocking { blockConsumer.await() }
    }
    fakeGlShaderProgram.onQueue = { frameInfo ->
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture, outputPtsUs)
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }

    consumerQueued.await()
    assertThat(queueJob.isActive).isTrue()

    blockConsumer.complete(Unit)

    queueJob.join()
    assertThat(recordingOutputConsumer.queuedPackets).hasSize(1)
    val outFrame =
      (recordingOutputConsumer.queuedPackets[0] as Packet.Payload<GlTextureFrame>).payload
    assertThat(outFrame.glTextureInfo).isEqualTo(outputTexture)
    assertThat(outFrame.presentationTimeUs).isEqualTo(outputPtsUs)
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_shaderQueueInputFrameError_throwsException() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val queueException = RuntimeException("Failed to queue to shader program")
    fakeGlShaderProgram.onQueue = { _ -> throw queueException }

    val thrownException =
      assertThrows(java.lang.RuntimeException::class.java) {
        runBlocking { processor.queuePacket(Packet.of(inputFrame)) }
      }

    assertThat(thrownException).hasMessageThat().isEqualTo(queueException.message)
    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_downstreamConsumerError_throwsException() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val queueException = RuntimeException("Failed to queue to downstream consumer")
    fakeGlShaderProgram.onQueue = { frameInfo ->
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
        frameInfo.textureInfo!!,
        frameInfo.presentationTimeUs,
      )
    }
    recordingOutputConsumer.onQueue = { throw queueException }

    val thrownException =
      assertThrows(java.lang.RuntimeException::class.java) {
        runBlocking { processor.queuePacket(Packet.of(inputFrame)) }
      }

    assertThat(thrownException).hasMessageThat().isEqualTo(queueException.message)
    assertThat(recordingOutputConsumer.queuedPackets).hasSize(1)
    val outFrame =
      (recordingOutputConsumer.queuedPackets[0] as Packet.Payload<GlTextureFrame>).payload
    assertThat(outFrame.glTextureInfo).isEqualTo(inputFrame.glTextureInfo)
    assertThat(outFrame.presentationTimeUs).isEqualTo(inputFrame.presentationTimeUs)
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_endOfStream_propagatedInOrder() = doBlocking {
    val inputFrame1 = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture1 = createTexture(texId = 101)
    val outputPtsUs1 = 1001L
    val inputFrame2 = createTestFrame(id = 2, timestampUs = 2000L)
    val outputTexture2 = createTexture(texId = 102)
    val outputPtsUs2 = 2002L
    val inputFrame3 = createTestFrame(id = 3, timestampUs = 3000L)
    val outputTexture3 = createTexture(texId = 103)
    val outputPtsUs3 = 3003L
    fakeGlShaderProgram.onQueue = { frameInfo ->
      when (frameInfo.presentationTimeUs) {
        1000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture1, outputPtsUs1)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
        2000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture2, outputPtsUs2)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
        3000L -> {
          fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture3, outputPtsUs3)
          fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
        }
      }
    }

    processor.queuePacket(Packet.of(inputFrame1))
    processor.queuePacket(Packet.of(inputFrame2))
    processor.queuePacket(Packet.EndOfStream)
    processor.queuePacket(Packet.of(inputFrame3))

    val packets = recordingOutputConsumer.queuedPackets
    assertThat(packets).hasSize(4)

    assertThat(packets[0]).isInstanceOf(Packet.Payload::class.java)
    val outFrame1 = (packets[0] as Packet.Payload).payload
    assertThat(outFrame1.presentationTimeUs).isEqualTo(outputPtsUs1)
    assertThat((inputFrame1.metadata as TestMetadata).released.get()).isTrue()

    assertThat(packets[1]).isInstanceOf(Packet.Payload::class.java)
    val outFrame2 = (packets[1] as Packet.Payload).payload
    assertThat(outFrame2.presentationTimeUs).isEqualTo(outputPtsUs2)
    assertThat((inputFrame2.metadata as TestMetadata).released.get()).isTrue()

    assertThat(packets[2]).isSameInstanceAs(Packet.EndOfStream)

    assertThat(packets[3]).isInstanceOf(Packet.Payload::class.java)
    val outFrame3 = (packets[3] as Packet.Payload).payload
    assertThat(outFrame3.presentationTimeUs).isEqualTo(outputPtsUs3)
    assertThat((inputFrame3.metadata as TestMetadata).released.get()).isTrue()

    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun queuePacket_afterRelease_releasesPayloadAndDoesNotThrow() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    processor.release()

    processor.queuePacket(Packet.of(inputFrame))

    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
  }

  @Test
  fun queuePacket_multipleReadySignals_buffersCapacity() = doBlocking {
    val inputFrame1 = createTestFrame(id = 1, timestampUs = 1000L)
    val inputFrame2 = createTestFrame(id = 2, timestampUs = 2000L)

    // fakeGlShaderProgram increments capacity in setInputListener. Increment it again so the
    // capacity becomes 2.
    fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()

    fakeGlShaderProgram.onQueue = { frameInfo ->
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
        createTexture(texId = 101),
        frameInfo.presentationTimeUs + 1,
      )
    }
    processor.queuePacket(Packet.of(inputFrame1))

    fakeGlShaderProgram.onQueue = { frameInfo ->
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
        createTexture(texId = 102),
        frameInfo.presentationTimeUs + 1,
      )
    }
    processor.queuePacket(Packet.of(inputFrame2))

    assertThat(recordingOutputConsumer.queuedPackets).hasSize(2)
  }

  @Test
  fun queuePacket_shaderError_throwsException() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val shaderException = VideoFrameProcessingException("Shader error")
    fakeGlShaderProgram.onQueue = { _ ->
      fakeGlShaderProgram.errorListener?.onError(shaderException)
    }

    val thrownException =
      assertThrows(VideoFrameProcessingException::class.java) {
        runBlocking { processor.queuePacket(Packet.of(inputFrame)) }
      }

    assertThat(thrownException).isSameInstanceAs(shaderException)
  }

  @Test
  fun queuePacket_afterException_processesPacket() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture = createTexture(texId = 102)
    val shaderException = VideoFrameProcessingException("Shader error")
    // Trigger a failure.
    fakeGlShaderProgram.onQueue = { _ ->
      fakeGlShaderProgram.errorListener?.onError(shaderException)
    }
    assertThrows(VideoFrameProcessingException::class.java) {
      runBlocking { processor.queuePacket(Packet.of(inputFrame)) }
    }

    // Ensure there is capacity, and that the next packet won't throw.
    fakeGlShaderProgram.onQueue = { _ ->
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(
        outputTexture,
        inputFrame.presentationTimeUs + 1,
      )
    }
    fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()

    processor.queuePacket(Packet.of(inputFrame))

    assertThat(recordingOutputConsumer.queuedPackets).hasSize(1)
    val outFrame =
      (recordingOutputConsumer.queuedPackets[0] as Packet.Payload<GlTextureFrame>).payload
    assertThat(outFrame.glTextureInfo).isEqualTo(outputTexture)
  }

  @Test
  fun queuePacket_concurrentCalls_throwsException() = doBlocking {
    val inputFrame1 = createTestFrame(id = 1, timestampUs = 1000L)
    val inputFrame2 = createTestFrame(id = 2, timestampUs = 2000L)
    val shaderStarted = CompletableDeferred<Unit>()
    fakeGlShaderProgram.onQueue = { _ ->
      shaderStarted.complete(Unit)
      // Do not forward output to cause processing to hang.
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame1)) }
    shaderStarted.await()

    assertThrows(java.lang.IllegalStateException::class.java) {
      runBlocking(glDispatcher) { processor.queuePacket(Packet.of(inputFrame2)) }
    }
    queueJob.cancelAndJoin()
  }

  @Test
  fun release_whileQueuePacketSuspended_cancelsQueuePacket() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val shaderStarted = CompletableDeferred<Unit>()
    fakeGlShaderProgram.onQueue = { _ ->
      shaderStarted.complete(Unit)
      // Do not forward output to cause processing to hang.
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }
    shaderStarted.await()
    processor.release()
    queueJob.join()

    assertThat(queueJob.isCancelled).isTrue()
    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  @Test
  fun release_whileQueuePacketSuspended_releasesOutputFrame() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture1 = createTexture(texId = 101)
    val outputPtsUs1 = 1001L
    val shaderStarted = CompletableDeferred<Unit>()
    fakeGlShaderProgram.onQueue = { _ ->
      shaderStarted.complete(Unit)
      // Do not forward output to cause processing to hang.
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }
    shaderStarted.await()
    processor.release()
    queueJob.join()

    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()

    // Simulate the underlying shader program producing output after the processor was released.
    withContext(glDispatcher) {
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture1, outputPtsUs1)
      fakeGlShaderProgram.inputListener.onReadyToAcceptInputFrame()
    }

    // The output texture should be immediately released.
    assertThat(fakeGlShaderProgram.releasedFrames).containsExactly(outputTexture1)
  }

  @Test
  fun queuePacket_cancelledBeforeOutputProduced_releasesOutputFrame() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val outputTexture1 = createTexture(texId = 101)
    val outputPtsUs1 = 1001L
    val shaderStarted = CompletableDeferred<Unit>()
    fakeGlShaderProgram.onQueue = { _ ->
      shaderStarted.complete(Unit)
      // Do not forward output to cause processing to hang.
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }
    shaderStarted.await()
    queueJob.cancelAndJoin()

    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()

    // Simulate the underlying shader program producing output after the queuePacket coroutine was
    // cancelled.
    withContext(glDispatcher) {
      fakeGlShaderProgram.outputListener.onOutputFrameAvailable(outputTexture1, outputPtsUs1)
    }

    // The output texture should be immediately released.
    assertThat(fakeGlShaderProgram.releasedFrames).containsExactly(outputTexture1)
  }

  @Test
  fun queuePacket_jobCancelled_cancelsShaderWork() = doBlocking {
    val inputFrame = createTestFrame(id = 1, timestampUs = 1000L)
    val shaderStarted = CompletableDeferred<Unit>()
    val shaderContinuedAfterCancel = AtomicBoolean(false)
    fakeGlShaderProgram.onQueue = { _ ->
      shaderStarted.complete(Unit)
      // Do not forward output to cause processing to hang.
    }

    val queueJob = launch(glDispatcher) { processor.queuePacket(Packet.of(inputFrame)) }
    shaderStarted.await()
    queueJob.cancel()
    queueJob.join()

    assertThat(shaderContinuedAfterCancel.get()).isFalse()
    assertThat(recordingOutputConsumer.queuedPackets).isEmpty()
    assertThat((inputFrame.metadata as TestMetadata).released.get()).isTrue()
    assertThat(recordingErrorConsumer.exceptions).isEmpty()
  }

  companion object {
    private fun createTestFrame(id: Int, timestampUs: Long): GlTextureFrame {
      val metadata = TestMetadata()
      val format = Format.Builder().build() // Minimal format
      return GlTextureFrame.Builder(
          createTexture(texId = id),
          directExecutor(),
          { _ -> metadata.released.set(true) },
        )
        .setPresentationTimeUs(timestampUs)
        .setMetadata(metadata)
        .setFormat(format)
        .build()
    }

    private fun createTexture(texId: Int): GlTextureInfo {
      return GlTextureInfo(texId, -1, -1, 100, 100)
    }
  }

  private class TestMetadata : Frame.Metadata {
    val released = AtomicBoolean(false)
  }

  private class RecordingErrorConsumer : Consumer<VideoFrameProcessingException> {
    val exceptions = mutableListOf<VideoFrameProcessingException>()

    override fun accept(t: VideoFrameProcessingException) {
      exceptions.add(t)
    }

    fun assertNoUnexpectedErrors() {
      assertThat(exceptions).isEmpty()
    }
  }

  class FakeGlShaderProgram() : GlShaderProgram {
    class QueuedFrameInfo(val textureInfo: GlTextureInfo?, val presentationTimeUs: Long)

    val queuedFrames: MutableList<QueuedFrameInfo?> = ArrayList<QueuedFrameInfo?>()
    val releasedFrames: MutableList<GlTextureInfo> = ArrayList<GlTextureInfo>()
    var onQueue: (QueuedFrameInfo) -> Unit = {}
    var inputListener: InputListener = object : InputListener {}
      private set

    var outputListener: OutputListener = object : OutputListener {}
      private set

    var errorListener: GlShaderProgram.ErrorListener? =
      GlShaderProgram.ErrorListener { e: VideoFrameProcessingException? -> }
      private set

    override fun setInputListener(inputListener: InputListener) {
      this.inputListener = inputListener
      inputListener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: OutputListener) {
      this.outputListener = outputListener
    }

    override fun setErrorListener(
      executor: Executor,
      errorListener: GlShaderProgram.ErrorListener,
    ) {
      this.errorListener = errorListener
    }

    override fun queueInputFrame(
      glObjectsProvider: GlObjectsProvider,
      inputTexture: GlTextureInfo,
      presentationTimeUs: Long,
    ) {
      val queuedFrameInfo = QueuedFrameInfo(inputTexture, presentationTimeUs)
      queuedFrames.add(queuedFrameInfo)
      onQueue(queuedFrameInfo)
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
      releasedFrames.add(outputTexture)
    }

    override fun signalEndOfCurrentInputStream() {
      outputListener.onCurrentOutputStreamEnded()
    }

    override fun flush() {
      inputListener.onFlush()
    }

    override fun release() {}
  }
}
