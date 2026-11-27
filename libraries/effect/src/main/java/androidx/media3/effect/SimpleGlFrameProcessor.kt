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
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build.VERSION.SDK_INT
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import androidx.media3.common.video.AsyncFrame
import androidx.media3.common.video.Frame
import androidx.media3.common.video.FrameProcessor
import androidx.media3.common.video.FrameWriter
import androidx.media3.common.video.HardwareBufferFrame
import androidx.media3.common.video.SyncFenceWrapper
import androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Composites the [HardwareBufferFrame]s from the input frames onto the output [HardwareBufferFrame]
 * using OpenGL.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class SimpleGlFrameProcessor
private constructor(
  private val context: Context,
  private val hardwareBufferJniWrapper: HardwareBufferJniWrapper,
  private val overlaySettingsProvider: (HardwareBufferFrame) -> OverlaySettings,
  private val output: FrameWriter,
  private val listenerExecutor: Executor,
  private val listener: FrameProcessor.Listener,
) : FrameProcessor {

  class Factory
  @JvmOverloads
  constructor(
    private val context: Context,
    private val hardwareBufferJniWrapper: HardwareBufferJniWrapper,
    private val overlaySettingsProvider: (HardwareBufferFrame) -> OverlaySettings = { _ ->
      StaticOverlaySettings.Builder().build()
    },
  ) : FrameProcessor.Factory {
    override fun create(
      output: FrameWriter,
      listenerExecutor: Executor,
      listener: FrameProcessor.Listener,
    ): FrameProcessor {
      return SimpleGlFrameProcessor(
        context,
        hardwareBufferJniWrapper,
        overlaySettingsProvider,
        output,
        listenerExecutor,
        listener,
      )
    }
  }

  /** Executor used for all blocking [SyncFenceWrapper.await] calls and GL operations. */
  private val glExecutorService =
    MoreExecutors.listeningDecorator(
      Util.newSingleThreadExecutor("SimpleGlFrameProcessor::InternalThread")
    )
  private val internalExecutor = InternalExecutor()
  private val isReleased = AtomicBoolean(false)
  private val glObjectsProvider: GlObjectsProvider = DefaultGlObjectsProvider()

  private var eglDisplay: EGLDisplay? = null
  private var eglContext: EGLContext? = null
  private var eglSurface: EGLSurface? = null
  private var compositorGlProgram: DefaultCompositorGlProgram? = null
  private var externalCopyGlProgram: GlProgram? = null

  private val overlayMatrixProvider = OverlayMatrixProvider()
  private var targetFormat: Format? = null
  private var presentationEffect: Presentation? = null

  private val lock = Any()
  @GuardedBy("lock") private var needsWakeup = false
  @GuardedBy("lock") private var hasPendingTask = false
  private var endOfStreamSignaled = false

  override fun queue(frames: List<AsyncFrame>): Boolean {
    check(!isReleased.get())

    synchronized(lock) {
      if (hasPendingTask) {
        needsWakeup = true
        return false
      }
      hasPendingTask = true
    }

    internalExecutor.execute { queueInternal(frames) }
    return true
  }

  override fun signalEndOfStream() {
    if (isReleased.get()) {
      return
    }
    internalExecutor.execute {
      synchronized(lock) {
        // If hasPendingTask is true, queueInternal is waiting for capacity.
        // We must defer the EOS until that frame completes.
        if (!hasPendingTask) {
          output.signalEndOfStream()
        } else {
          endOfStreamSignaled = true
        }
      }
    }
  }

  override fun close() {
    if (!isReleased.getAndSet(true)) {
      internalExecutor.execute {
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
      glExecutorService.shutdown()
    }
  }

  /**
   * Processes the input frames and queues them downstream.
   *
   * Called on the internal thread.
   */
  private fun queueInternal(inputFrames: List<AsyncFrame>) {
    val firstInputFrame = inputFrames[0].frame
    // Step 1: Check format support and configure the output.
    if (targetFormat == null) {
      // Mark the frames as complete and notify the listener of an exception when the format is
      // unsupported.
      if (!output.info.isSupported(firstInputFrame.format, USAGE)) {
        listenerExecutor.execute {
          for (i in inputFrames.indices) {
            listener.onFrameProcessed(inputFrames[i].frame, /* onCompleteFence= */ null)
          }
        }
        listenerExecutor.execute {
          listener.onError(
            VideoFrameProcessingException("Format ${firstInputFrame.format} is unsupported.")
          )
        }
        return
      }
      targetFormat = firstInputFrame.format
      presentationEffect =
        Presentation.createForWidthAndHeight(
          targetFormat!!.width,
          targetFormat!!.height,
          Presentation.LAYOUT_SCALE_TO_FIT,
        )
      output.configure(
        targetFormat!!,
        Frame.USAGE_GPU_COLOR_OUTPUT or Frame.USAGE_GPU_SAMPLED_IMAGE,
      )
    }

    // Step 2: Attempt to dequeue output frame.
    val outputFrame =
      output.dequeueInputFrame(
        /* wakeupExecutor= */ internalExecutor,
        /* wakeupListener= */ { queueInternal(inputFrames) },
      )
    if (outputFrame == null) {
      return
    }

    var inputReleaseFences: List<SyncFenceWrapper?> = emptyList()
    try {

      // Step 3: Composite the input frames onto the output frame.
      val result = processFrames(inputFrames, outputFrame)
      val drawingCompletionFence = result.drawCompleteFence
      inputReleaseFences = result.readCompleteFences

      // Step 4: Update the output frame with the new content time and metadata.
      val updatedOutputFrame =
        (outputFrame.frame as HardwareBufferFrame)
          .buildUpon()
          .setContentTimeUs(firstInputFrame.contentTimeUs)
          .setMetadata(firstInputFrame.metadata)
          .build()

      // Step 5: Send the output frame downstream.
      output.queueInputFrame(updatedOutputFrame, drawingCompletionFence)

      // Step 6: Send any pending EOS signal downstream if needed.
      if (endOfStreamSignaled) {
        output.signalEndOfStream()
        endOfStreamSignaled = false
      }
    } finally {
      // Step 7: Always mark the input frames as complete.
      listenerExecutor.execute {
        for (i in inputFrames.indices) {
          val asyncFrame = inputFrames[i]
          val releaseFence = if (i < inputReleaseFences.size) inputReleaseFences[i] else null
          listener.onFrameProcessed(asyncFrame.frame, releaseFence)
        }
      }

      // Step 8: Allow the next frame to be queued.
      synchronized(lock) { hasPendingTask = false }

      // Step 9: Notify the wakeup listener if it is set.
      maybeTriggerWakeup()
    }
  }

  /** Called on the internal thread. */
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

  /**
   * Composites the input frames onto the output frame.
   *
   * Called on the internal thread.
   */
  private fun processFrames(
    inputAsyncFrames: List<AsyncFrame>,
    outputAsyncFrame: AsyncFrame,
  ): ProcessedFrameResult {
    val inputFrameInfos = mutableListOf<GlCompositionFrame>()
    val inputEglImages = mutableListOf<Long>()
    val inputTextures = mutableListOf<Int>()
    val intermediateFbos = mutableListOf<Int>()
    var outputEglImage = 0L
    var outputTexId = 0
    var outputFboId = 0

    var drawCompleteFence: SyncFenceWrapper? = null
    val readCompleteFences = mutableListOf<SyncFenceWrapper?>()

    try {
      // Ensure the OpenGL context is configured.
      maybeSetupGl()

      val outputFrame = outputAsyncFrame.frame as HardwareBufferFrame
      val outputHardwareBuffer = outputFrame.hardwareBuffer
      val outputWidth = outputHardwareBuffer.width
      val outputHeight = outputHardwareBuffer.height
      if (outputWidth <= 0 || outputHeight <= 0) {
        throw VideoFrameProcessingException(
          "Invalid output dimensions: ${outputWidth}x${outputHeight}. " +
            "Format: ${outputFrame.format.width}x${outputFrame.format.height}. " +
            "HardwareBuffer: ${outputHardwareBuffer.width}x${outputHardwareBuffer.height}."
        )
      }

      val targetWidth = targetFormat!!.width
      val targetHeight = targetFormat!!.height
      overlayMatrixProvider.configure(Size(targetWidth, targetHeight))

      for (i in inputAsyncFrames.indices) {
        val inputAsyncFrame = inputAsyncFrames[i]
        val inputFrame = inputAsyncFrame.frame as HardwareBufferFrame
        val hardwareBuffer = inputFrame.hardwareBuffer

        // TODO: b/479415385 - Replace this with a GPU wait.
        // Wait on the input fences, to ensure the input frames are ready to be read from.
        waitAndClose(inputAsyncFrame.acquireFence)

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

        // Configure presentation effect with current input size
        presentationEffect!!.configure(hardwareBuffer.width, hardwareBuffer.height)
        val presentationMatrix = presentationEffect!!.getGlMatrixArray(inputFrame.contentTimeUs)

        // Calculate overlay matrix assuming the input is logically target size
        val overlayMatrix =
          overlayMatrixProvider.getTransformationMatrix(
            Size(targetWidth, targetHeight),
            overlaySettings,
          )

        // Combine them: combined = overlayMatrix * presentationMatrix
        val combinedMatrix = FloatArray(16)
        Matrix.multiplyMM(
          combinedMatrix,
          /* resultOffset= */ 0,
          overlayMatrix,
          /* lhsOffset= */ 0,
          presentationMatrix,
          /* rhsOffset= */ 0,
        )

        inputFrameInfos.add(GlCompositionFrame(textureInfo, overlaySettings, combinedMatrix))
      }

      // Wait on the output fence, to ensure it is ready to be written to.
      waitAndClose(outputAsyncFrame.acquireFence)

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

      val eglDisplay = checkNotNull(eglDisplay)
      val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
      if (SDK_INT >= 33 && extensions.contains("EGL_ANDROID_native_fence_sync")) {
        val sync =
          EGL15.eglCreateSync(
            eglDisplay,
            /* type= */ EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID,
            /* attrib_list= */ longArrayOf(EGL14.EGL_NONE.toLong()),
            /* offset= */ 0,
          )
        GlUtil.checkEglException("eglCreateSync failed")
        if (sync != EGL15.EGL_NO_SYNC) {
          try {
            var nativeFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, sync)
            GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed")
            if (!nativeFence.isValid) {
              // Calling eglDupNativeFenceAndroid may produce an invalid fence the first time it
              // is called. See b/18052459.
              GLES20.glFlush()
              nativeFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, sync)
              GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed after glFlush")
            }
            if (nativeFence.isValid) {
              drawCompleteFence = SyncFenceWrapper.of(/* syncFence= */ nativeFence)
              for (i in inputAsyncFrames.indices) {
                val inputNativeFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, sync)
                GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed for input frame")
                check(inputNativeFence.isValid)
                readCompleteFences.add(SyncFenceWrapper.of(/* syncFence= */ inputNativeFence))
              }
            }
          } finally {
            EGL15.eglDestroySync(eglDisplay, sync)
            GlUtil.checkEglException("eglDestroySync failed")
          }
        }
      }

      if (drawCompleteFence == null) {
        GLES20.glFinish()
      }

      return ProcessedFrameResult(drawCompleteFence, readCompleteFences)
    } catch (e: Exception) {
      drawCompleteFence?.close()
      readCompleteFences.forEach { it?.close() }
      throw e
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

  // TODO: b/479415385 - Replace this with a GPU wait.
  private fun waitAndClose(fence: SyncFenceWrapper?) {
    if (fence == null) {
      return
    }
    try {
      check(fence.await(FENCE_TIMEOUT)) { "Fence timeout" }
    } finally {
      fence.close()
    }
  }

  private fun maybeTriggerWakeup() {
    var needsWakeup = false
    synchronized(lock) {
      needsWakeup = this.needsWakeup
      this.needsWakeup = false
    }
    if (needsWakeup) {
      listenerExecutor.execute { listener.onWakeup() }
    }
  }

  /**
   * Result of [processFrames].
   *
   * @property drawCompleteFence The [SyncFenceWrapper] that signals when the output frame is ready.
   * @property readCompleteFences The [SyncFenceWrapper] instances that signal when the input frames
   *   have been read by the GPU.
   */
  private data class ProcessedFrameResult(
    val drawCompleteFence: SyncFenceWrapper?,
    val readCompleteFences: List<SyncFenceWrapper?>,
  )

  /** Helper class to submit work to [glExecutorService] and handle exceptions. */
  private inner class InternalExecutor : Executor {
    override fun execute(runnable: Runnable) {
      try {
        Futures.addCallback(
          glExecutorService.submit(runnable),
          object : FutureCallback<Any?> {
            override fun onSuccess(result: Any?) {}

            override fun onFailure(t: Throwable) {
              listener.onError(VideoFrameProcessingException(t))
            }
          },
          listenerExecutor,
        )
      } catch (e: RejectedExecutionException) {
        listenerExecutor.execute { listener.onError(VideoFrameProcessingException(e)) }
      }
    }
  }

  companion object {
    private val FENCE_TIMEOUT = Duration.ofMillis(500)
    private val USAGE = Frame.USAGE_GPU_COLOR_OUTPUT or Frame.USAGE_GPU_SAMPLED_IMAGE
  }
}
