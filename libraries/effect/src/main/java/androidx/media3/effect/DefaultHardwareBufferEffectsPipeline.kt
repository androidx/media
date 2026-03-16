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

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.ColorInfo.SDR_BT709_LIMITED
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultCompositorGlProgram.InputFrameInfo
import androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.collect.ImmutableList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A [PacketProcessor] that composites the [HardwareBufferFrame]s from the input packet onto the
 * output [HardwareBufferFrame] using OpenGL.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class DefaultHardwareBufferEffectsPipeline
private constructor(
  private val context: Context,
  private val hardwareBufferJniWrapper: HardwareBufferJniWrapper,
  private val overlaySettingsProvider: (HardwareBufferFrame) -> OverlaySettings,
) : RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue> {

  /** Executor used for all blocking [android.hardware.SyncFence.await] calls and GL operations. */
  private val internalExecutor = Executors.newSingleThreadExecutor()
  private val internalDispatcher = internalExecutor.asCoroutineDispatcher()
  private val isReleased = AtomicBoolean(false)
  private val glObjectsProvider: GlObjectsProvider = DefaultGlObjectsProvider()

  private var eglDisplay: EGLDisplay? = null
  private var eglContext: EGLContext? = null
  private var eglSurface: EGLSurface? = null
  private var compositorGlProgram: DefaultCompositorGlProgram? = null
  private var externalCopyGlProgram: GlProgram? = null

  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private var outputBufferQueue: HardwareBufferFrameQueue? = null

  override fun setRenderOutput(output: HardwareBufferFrameQueue?) {
    this.outputBufferQueue = output
  }

  override fun setErrorConsumer(errorConsumer: Consumer<Exception>) {}

  override suspend fun queuePacket(packet: Packet<ImmutableList<HardwareBufferFrame>>) {
    check(!isReleased.get())
    when (packet) {
      is Packet.EndOfStream -> outputBufferQueue!!.signalEndOfStream()
      is Packet.Payload -> {
        if (packet.payload.isNotEmpty()) {
          // Step 1: Dequeue an output frame, use the format of the first sequence to determine
          // the output format.
          val outputFrame = getOutputFrame(packet.payload[0].format)
          var drawingCompletionFence: SyncFenceCompat? = null
          try {
            // Step 2: Composite the input frames onto the output frame on the internal thread.
            val outputFrameWithMetadata = processFrames(packet.payload, outputFrame)
            drawingCompletionFence = outputFrameWithMetadata.acquireFence
            // Step 3: Forward output frame down stream.
            outputBufferQueue!!.queue(outputFrameWithMetadata)
          } catch (e: Exception) {
            // Ensure the dequeued output frame is released on failures.
            outputFrame.release(/* releaseFence= */ null)
            throw e
          } finally {
            // Always release the input frames, ensuring the release waits until after drawing
            // completes.
            for (inputFrame in packet.payload) {
              inputFrame.release(drawingCompletionFence?.let { SyncFenceCompat.duplicate(it) })
            }
          }
        }
      }
    }
  }

  override suspend fun release() {
    if (!isReleased.getAndSet(true)) {
      withContext(internalDispatcher) {
        compositorGlProgram?.release()
        compositorGlProgram = null
        externalCopyGlProgram?.delete()
        externalCopyGlProgram = null
        eglSurface?.let { GlUtil.destroyEglSurface(checkNotNull(eglDisplay), it) }
        eglSurface = null
        eglDisplay?.let { glObjectsProvider.release(it) }
        eglDisplay = null
        eglContext = null
      }
      internalExecutor.shutdown()
    }
  }

  private fun maybeSetupGl() {
    if (eglContext != null) {
      return
    }
    val eglDisplay = GlUtil.getDefaultEglDisplay()
    this.eglDisplay = eglDisplay
    val eglContext =
      glObjectsProvider.createEglContext(
        eglDisplay,
        /* openGlVersion= */ 3,
        /* configAttributes= */ GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
      )
    this.eglContext = eglContext
    eglSurface = glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
    compositorGlProgram = DefaultCompositorGlProgram(context)
    val externalCopyGlProgram =
      GlProgram(
        context,
        /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es2,
        /* fragmentShaderResId= */ R.raw.fragment_shader_transformation_sdr_external_es2,
      )
    externalCopyGlProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
    // TODO: b/475511306 - Ensure the frame is correctly rotated.
    externalCopyGlProgram.setFloatsUniform(
      "uTexTransformationMatrix",
      GlUtil.create4x4IdentityMatrix(),
    )
    externalCopyGlProgram.setFloatsUniform(
      "uTransformationMatrix",
      GlUtil.create4x4IdentityMatrix(),
    )
    externalCopyGlProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix())
    externalCopyGlProgram.setIntUniform("uSdrWorkingColorSpace", WORKING_COLOR_SPACE_ORIGINAL)
    externalCopyGlProgram.setIntUniform("uOutputColorTransfer", COLOR_TRANSFER_SDR_VIDEO)
    this.externalCopyGlProgram = externalCopyGlProgram
  }

  private suspend fun processFrames(
    inputFrames: ImmutableList<HardwareBufferFrame>,
    outputFrame: HardwareBufferFrame,
  ): HardwareBufferFrame =
    withContext(internalDispatcher) {
      val inputFrameInfos = mutableListOf<InputFrameInfo>()
      val inputEglImages = mutableListOf<Long>()
      val inputTextures = mutableListOf<Int>()
      val intermediateFbos = mutableListOf<Int>()
      var outputEglImage = 0L
      var outputTexId = 0
      var outputFboId = 0

      try {
        // Ensure the OpenGL context is configured.
        maybeSetupGl()

        val outputHardwareBuffer = checkNotNull(outputFrame.hardwareBuffer)
        val outputWidth = outputHardwareBuffer.width
        val outputHeight = outputHardwareBuffer.height
        if (outputWidth <= 0 || outputHeight <= 0) {
          throw VideoFrameProcessingException(
            "Invalid output dimensions: ${outputWidth}x${outputHeight}. " +
              "Format: ${outputFrame.format.width}x${outputFrame.format.height}. " +
              "HardwareBuffer: ${outputHardwareBuffer.width}x${outputHardwareBuffer.height}."
          )
        }

        for (i in inputFrames.indices) {
          val inputFrame = inputFrames[i]
          val hardwareBuffer = checkNotNull(inputFrame.hardwareBuffer)

          // TODO: b/479415385 - Replace this with a GPU wait.
          // Wait on the input fences, to ensure the input frames are ready to be read from.
          waitAndClose(inputFrame.acquireFence)

          // Create an EGL image and texture for each input frame.
          val eglImage =
            hardwareBufferJniWrapper.nativeCreateEglImageFromHardwareBuffer(
              checkNotNull(eglDisplay).nativeHandle,
              hardwareBuffer,
            )
          if (eglImage == 0L) throw GlUtil.GlException("Failed to create input EGLImage")
          inputEglImages.add(eglImage)

          val isRgba8888 = hardwareBuffer.format == HardwareBuffer.RGBA_8888
          val target = if (isRgba8888) GLES20.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES

          // TODO: b/459374133 - Reuse textures.
          val texId = GlUtil.generateTexture()
          GLES20.glBindTexture(target, texId)
          check(hardwareBufferJniWrapper.nativeBindEGLImage(target, eglImage))
          inputTextures.add(texId)

          var finalTexId = texId
          val isExternalInput = !isRgba8888

          // Draw into a GL_TEXTURE_2D if the input is an external texture, as the compositor can
          // only handle GL_TEXTURE_2D.
          // TODO: b/286211012 - Handle external textures in the compositor.
          if (isExternalInput) {
            // TODO: b/459374133 - Reuse textures.
            // TODO: b/449957627 - This will not correctly handle mixing inputs with different
            //  colors. Map Everything to a working color space before compositing.
            val internalTexId =
              GlUtil.createTexture(
                hardwareBuffer.width,
                hardwareBuffer.height,
                /* useHighPrecisionColorComponents= */ false,
              )
            inputTextures.add(internalTexId)
            val intermediateFboId = GlUtil.createFboForTexture(internalTexId)
            intermediateFbos.add(intermediateFboId)
            GlUtil.focusFramebufferUsingCurrentContext(
              intermediateFboId,
              hardwareBuffer.width,
              hardwareBuffer.height,
            )
            val copyProgram = checkNotNull(externalCopyGlProgram)
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()

            finalTexId = internalTexId
          }

          // Calculate the placement of each input texture on the output texture.
          val textureInfo =
            GlTextureInfo(
              finalTexId,
              /* fboId= */ C.INDEX_UNSET,
              /* rboId= */ C.INDEX_UNSET,
              hardwareBuffer.width,
              hardwareBuffer.height,
            )
          val overlaySettings = overlaySettingsProvider(inputFrame)
          inputFrameInfos.add(InputFrameInfo(textureInfo, overlaySettings))
        }

        // Wait on the output fence, to ensure it is ready to be written to.
        waitAndClose(outputFrame.acquireFence)

        // Create the output EGL image and texture.
        outputEglImage =
          hardwareBufferJniWrapper.nativeCreateEglImageFromHardwareBuffer(
            checkNotNull(eglDisplay).nativeHandle,
            outputHardwareBuffer,
          )
        if (outputEglImage == 0L) {
          throw GlUtil.GlException("Failed to create output EGLImage")
        }

        // TODO: b/459374133 - Reuse textures.
        outputTexId = GlUtil.generateTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexId)
        check(hardwareBufferJniWrapper.nativeBindEGLImage(GLES20.GL_TEXTURE_2D, outputEglImage))

        outputFboId = GlUtil.createFboForTexture(outputTexId)
        val outputTextureInfo =
          GlTextureInfo(
            outputTexId,
            outputFboId,
            /* rboId= */ C.INDEX_UNSET,
            outputWidth,
            outputHeight,
          )

        // Draw the input textures into the output texture.
        checkNotNull(compositorGlProgram).drawFrame(inputFrameInfos, outputTextureInfo)

        // TODO: b/479415385 - Create a SyncFenceCompat from a GL sync fence and set it as the
        //  acquireFence of the output frame, rather than calling glFinish.
        GLES20.glFinish()
        val drawingCompletionFence = null

        outputFrame
          .buildUpon()
          .setPresentationTimeUs(inputFrames[0].presentationTimeUs)
          .setReleaseTimeNs(inputFrames[0].releaseTimeNs)
          .setFormat(inputFrames[0].format)
          .setMetadata(inputFrames[0].metadata)
          .setAcquireFence(drawingCompletionFence)
          .build()
      } finally {
        // Clean up GL resources.
        for (fboId in intermediateFbos) {
          GlUtil.deleteFbo(fboId)
        }
        if (outputFboId != 0) {
          GlUtil.deleteFbo(outputFboId)
        }
        if (outputTexId != 0) {
          GlUtil.deleteTexture(outputTexId)
        }
        if (outputEglImage != 0L) {
          check(
            hardwareBufferJniWrapper.nativeDestroyEGLImage(
              checkNotNull(eglDisplay).nativeHandle,
              outputEglImage,
            )
          )
        }
        for (texId in inputTextures) {
          GlUtil.deleteTexture(texId)
        }
        for (eglImage in inputEglImages) {
          check(
            hardwareBufferJniWrapper.nativeDestroyEGLImage(
              checkNotNull(eglDisplay).nativeHandle,
              eglImage,
            )
          )
        }
      }
    }

  private suspend fun getOutputFrame(format: Format): HardwareBufferFrame {
    val width = format.width
    val height = format.height

    val bufferFormat =
      HardwareBufferFrameQueue.FrameFormat.Builder()
        .setWidth(width)
        .setHeight(height)
        .setPixelFormat(
          if (ColorInfo.isTransferHdr(format.colorInfo)) HardwareBuffer.RGBA_1010102
          else HardwareBuffer.RGBA_8888
        )
        .setUsageFlags(
          HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        .setColorInfo(format.colorInfo ?: SDR_BT709_LIMITED)
        .build()

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

  // TODO: b/479415385 - Replace this with a GPU wait.
  private fun waitAndClose(fence: SyncFenceCompat?) {
    fence?.let { fence ->
      check(fence.await(FENCE_TIMEOUT_MS))
      fence.close()
    }
  }

  companion object {
    // It can take multiple seconds for the encoder to be configured and the first frame to be
    // encoded.
    private const val TIMEOUT_MS = 10_000L
    private const val FENCE_TIMEOUT_MS = 500

    /**
     * Creates a new [DefaultHardwareBufferEffectsPipeline], that uses a default
     * [StaticOverlaySettings] to composite each sequence..
     */
    @JvmStatic
    fun create(
      context: Context,
      hardwareBufferJniWrapper: HardwareBufferJniWrapper,
    ): DefaultHardwareBufferEffectsPipeline {
      val overlaySettingsProvider: (HardwareBufferFrame) -> OverlaySettings = { _ ->
        StaticOverlaySettings.Builder().build()
      }
      return create(context, hardwareBufferJniWrapper, overlaySettingsProvider)
    }

    /**
     * Creates a new [DefaultHardwareBufferEffectsPipeline] that calls the given
     * [overlaySettingsProvider] for each frame when compositing.
     */
    @JvmStatic
    fun create(
      context: Context,
      hardwareBufferJniWrapper: HardwareBufferJniWrapper,
      overlaySettingsProvider: (HardwareBufferFrame) -> OverlaySettings,
    ): DefaultHardwareBufferEffectsPipeline {
      return DefaultHardwareBufferEffectsPipeline(
        context,
        hardwareBufferJniWrapper,
        overlaySettingsProvider,
      )
    }
  }
}
