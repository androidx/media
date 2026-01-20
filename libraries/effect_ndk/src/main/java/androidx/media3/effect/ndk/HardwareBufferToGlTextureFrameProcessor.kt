/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect.ndk

import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat.RGBA_8888
import android.hardware.HardwareBuffer
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.annotation.RequiresApi
import androidx.graphics.opengl.egl.EGLSpec
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultShaderProgram
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.ExternalShaderProgram
import androidx.media3.effect.FrameConsumer
import androidx.media3.effect.GlShaderProgramFrameProcessor
import androidx.media3.effect.GlTextureFrame
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.MatrixUtils
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.PacketConsumer.Packet.Payload
import androidx.media3.effect.PacketProcessor
import androidx.opengl.EGLExt
import androidx.opengl.EGLImageKHR
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.common.util.concurrent.MoreExecutors.listeningDecorator
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A [PacketProcessor] implementation that converts a [HardwareBufferFrame] to [GlTextureFrame]. */
@ExperimentalApi // TODO: b/474075198: Remove once FrameConsumer API is stable.
@RequiresApi(26)
class HardwareBufferToGlTextureFrameProcessor(
  private val context: Context,
  private val glExecutorService: ExecutorService,
  private val glObjectsProvider: GlObjectsProvider,
  private val errorConsumer: Consumer<Exception>,
) : PacketProcessor<HardwareBufferFrame, GlTextureFrame> {

  private val glDispatcher = glExecutorService.asCoroutineDispatcher()
  private val glScope = CoroutineScope(glDispatcher)

  private var outputConsumer: PacketConsumer<GlTextureFrame>? = null
  private var glShaderProgramFrameProcessor: GlShaderProgramFrameProcessor? = null
  private var eglContext: EGLContext? = null
  private var eglSurface: EGLSurface? = null

  override fun setOutput(output: PacketConsumer<GlTextureFrame>) {
    this.outputConsumer = output
  }

  override suspend fun queuePacket(packet: Packet<HardwareBufferFrame>) {
    when (packet) {
      is Payload<HardwareBufferFrame> -> {
        withContext(glDispatcher) {
          try {
            maybeSetupGlResources()
            val hardwareBufferFrame = packet.payload
            val hardwareBuffer = checkNotNull(hardwareBufferFrame.hardwareBuffer)

            if (hardwareBuffer.format == RGBA_8888) {
              // Map RGBA_8888 buffers directly to OpenGL RGBA_8888 textures.
              val (eglImage, texture) = sampleToGlTexture(hardwareBuffer, GLES20.GL_TEXTURE_2D)
              outputConsumer?.queuePacket(
                Packet.of(createGlTextureFrame(texture, hardwareBufferFrame, eglImage))
              )
            } else {
              sampleOpaqueHardwareBufferQueueDownstream(hardwareBufferFrame)
            }
          } catch (e: GlUtil.GlException) {
            errorConsumer.accept(e)
          }
        }
      }
      is Packet.EndOfStream -> {
        outputConsumer?.queuePacket(packet)
      }
    }
  }

  /**
   * Samples the input [HardwareBufferFrame.hardwareBuffer] to a texture of the specific [target].
   * Returns the sampled [EGLImageKHR] and the texture ID.
   */
  private fun sampleToGlTexture(
    hardwareBuffer: HardwareBuffer,
    target: Int,
  ): Pair<EGLImageKHR, Int> {
    // TODO: b/474075198 - Add JNI code to remove this dependency.
    val eglImage =
      EGLSpec.V14.eglCreateImageFromHardwareBuffer(hardwareBuffer)
        ?: throw GlUtil.GlException(
          "Unable to create EGLImageKHR, format:${hardwareBuffer.format}."
        )
    val texture = GlUtil.generateTexture()
    GLES20.glBindTexture(target, texture)
    GlUtil.checkGlError()
    EGLExt.glEGLImageTargetTexture2DOES(target, eglImage)
    GlUtil.checkGlError()
    GlUtil.checkEglException("Error creating GL texture from HardwareBuffer")
    return eglImage to texture
  }

  private suspend fun sampleOpaqueHardwareBufferQueueDownstream(
    hardwareBufferFrame: HardwareBufferFrame
  ) {
    // Opaque HardwareBuffers needs to be sampled by an external sampler
    val (eglImage, texture) =
      sampleToGlTexture(
        checkNotNull(hardwareBufferFrame.hardwareBuffer),
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      )

    val glShaderProgramFrameProcessor =
      this.glShaderProgramFrameProcessor ?: createGlShaderFrameProcessor(hardwareBufferFrame)

    glShaderProgramFrameProcessor.input.queueFrame(
      createGlTextureFrame(texture, hardwareBufferFrame, eglImage)
    )
  }

  private suspend fun createGlShaderFrameProcessor(
    hardwareBufferFrame: HardwareBufferFrame
  ): GlShaderProgramFrameProcessor {
    // TODO: b/474075198 - Add HDR support.
    // TODO: b/474075198 - Use the PacketConsumer version when ready.
    val glShaderProgram =
      DefaultShaderProgram.createWithExternalSampler(
        context,
        ColorInfo.SDR_BT709_LIMITED,
        ColorInfo.SDR_BT709_LIMITED,
        DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
        true,
      ) as ExternalShaderProgram
    glShaderProgram.setTextureTransformMatrix(constructTransformationMatrix(hardwareBufferFrame))
    val glShaderProgramFrameProcessor =
      GlShaderProgramFrameProcessor.create(
        listeningDecorator(glExecutorService),
        glShaderProgram,
        glObjectsProvider,
      )
    glShaderProgramFrameProcessor
      .setOutputAsync(
        object : FrameConsumer<GlTextureFrame> {
          override fun queueFrame(frame: GlTextureFrame): Boolean {
            glScope.launch { outputConsumer?.queuePacket(Packet.of(frame)) }
            return true
          }

          override fun setOnCapacityAvailableCallback(
            executor: Executor,
            onCapacityAvailableCallback: Runnable,
          ) {}

          override fun clearOnCapacityAvailableCallback() {}
        }
      )
      ?.await()
    this.glShaderProgramFrameProcessor = glShaderProgramFrameProcessor
    return glShaderProgramFrameProcessor
  }

  private fun createGlTextureFrame(
    texture: Int,
    hardwareBufferFrame: HardwareBufferFrame,
    eglImage: EGLImageKHR,
  ): GlTextureFrame {
    val format = hardwareBufferFrame.format
    val frameWidth =
      if (format.rotationDegrees != 90 && format.rotationDegrees != 270) format.width
      else format.height
    val frameHeight =
      if (format.rotationDegrees != 90 && format.rotationDegrees != 270) format.height
      else format.width

    return GlTextureFrame.Builder(
        GlTextureInfo(texture, C.INDEX_UNSET, C.INDEX_UNSET, frameWidth, frameHeight),
        directExecutor(),
        { glTextureInfo ->
          // TODO: b/474075198 - Use a more efficient sync method.
          GLES20.glFinish()
          GlUtil.deleteTexture(glTextureInfo.texId)
          if (!EGLExt.eglDestroyImageKHR(GlUtil.getDefaultEglDisplay(), eglImage)) {
            errorConsumer.accept(
              VideoFrameProcessingException(
                "eglDestroyImageKHR",
                hardwareBufferFrame.presentationTimeUs,
              )
            )
          }
          hardwareBufferFrame.release()
        },
      )
      .setPresentationTimeUs(hardwareBufferFrame.presentationTimeUs)
      .setReleaseTimeNs(hardwareBufferFrame.releaseTimeNs)
      .build()
  }

  private fun maybeSetupGlResources() {
    if (eglContext != null) {
      return
    }
    val eglDisplay = GlUtil.getDefaultEglDisplay()
    eglContext =
      glObjectsProvider.createEglContext(
        eglDisplay,
        /* openGlVersion= */ 3,
        // TODO: b/474075198 - Add HDR support.
        /* configAttributes= */ GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
      )
    eglSurface = glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext!!, eglDisplay)
  }

  override suspend fun release() {
    if (eglContext != null) {
      try {
        GlUtil.destroyEglSurface(GlUtil.getDefaultEglDisplay(), eglSurface)
        // eglContext is released by GlObjectsProvider
      } catch (e: GlUtil.GlException) {
        errorConsumer.accept(e)
      }
    }
  }

  companion object {
    fun constructTransformationMatrix(hardwareBufferFrame: HardwareBufferFrame): FloatArray {
      // TODO: b/327467890 - This should work on most devices, but it's better to get the matrix
      //  directly from MediaCodec.
      val hardwareBuffer = checkNotNull(hardwareBufferFrame.hardwareBuffer)
      val format = hardwareBufferFrame.format

      // y' = 1 - y
      val flipMatrix = Matrix()
      flipMatrix.setScale(1f, -1f)
      flipMatrix.postTranslate(0f, 1f)

      // Rotate back around the center.
      val rotateMatrix = Matrix()
      rotateMatrix.setRotate(-format.rotationDegrees.toFloat(), 0.5f, 0.5f)

      val cropMatrix = Matrix()
      val croppedWidth = format.width.toFloat()
      val croppedHeight = format.height.toFloat()
      val bufferWidth = hardwareBuffer.width.toFloat()
      val bufferHeight = hardwareBuffer.height.toFloat()
      cropMatrix.setScale(croppedWidth / bufferWidth, croppedHeight / bufferHeight)

      // Applies flipping, rotation and cropping, in order
      val transformMatrix = Matrix()
      transformMatrix.setConcat(rotateMatrix, flipMatrix)
      transformMatrix.setConcat(cropMatrix, transformMatrix)
      return MatrixUtils.getGlMatrixArray(transformMatrix)
    }
  }
}
