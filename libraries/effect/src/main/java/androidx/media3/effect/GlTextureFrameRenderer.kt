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

import android.content.Context
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.media3.common.ColorInfo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A [PacketConsumer] implementation that renders [GlTextureFrame]s to an output
 * [android.view.Surface].
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class GlTextureFrameRenderer
private constructor(
  private val context: Context,
  private val glExecutorService: ExecutorService,
  private val glObjectsProvider: GlObjectsProvider,
  private val videoFrameProcessingTaskExecutor: VideoFrameProcessingTaskExecutor,
  private var errorHandler: Consumer<VideoFrameProcessingException>,
  private var listener: Listener,
) :
  RenderingPacketConsumer<GlTextureFrame, SurfaceInfo>,
  GlShaderProgram.InputListener,
  FinalShaderProgramWrapper.Listener {

  interface Listener {
    /** Called when the output size changes. */
    fun onOutputSizeChanged(width: Int, height: Int) {}

    /**
     * Called when an output frame with the given {@code presentationTimeUs} becomes available for
     * rendering.
     */
    fun onOutputFrameAvailableForRendering(presentationTimeUs: Long) {}

    /** Called after the [GlTextureFrameRenderer] has rendered its final output frame. */
    fun onEnded() {}

    object NO_OP : Listener
  }

  override fun setErrorConsumer(errorConsumer: Consumer<Exception>) {
    errorHandler = Consumer<VideoFrameProcessingException> { t -> errorConsumer.accept(t) }
  }

  private val glDispatcher = glExecutorService.asCoroutineDispatcher()
  private val isReleased = AtomicBoolean(false)
  private var hasRenderedPendingFrame = CompletableDeferred<Unit>(Unit)
  private var finalShaderProgramWrapper: FinalShaderProgramWrapper? = null
  @Volatile private var outputSurfaceInfo: SurfaceInfo? = null

  /**
   * Consumes a single [GlTextureFrame], first configuring the renderer if required and queuing the
   * frame to the shader program.
   *
   * This method suspends until the frame is fully rendered. If the renderer is released while
   * waiting, it gracefully exits.
   */
  override suspend fun queuePacket(packet: Packet<GlTextureFrame>): Unit =
    withContext(glDispatcher) {
      when (packet) {
        is Packet.Payload -> {
          val frame = packet.payload
          if (isReleased.get()) return@withContext
          // TODO: b/449957627 - Investigate reconfiguring when the output ColorInfo changes.
          val finalShaderProgramWrapper =
            finalShaderProgramWrapper
              ?: initializeFinalShaderProgramWrapper(
                frame.format.colorInfo ?: ColorInfo.SDR_BT709_LIMITED
              )
          hasRenderedPendingFrame = CompletableDeferred()
          finalShaderProgramWrapper.queueInputFrame(
            glObjectsProvider,
            frame.glTextureInfo,
            frame.presentationTimeUs,
          )
          finalShaderProgramWrapper.renderOutputFrame(glObjectsProvider, frame.releaseTimeNs)
          try {
            // Suspend until the frame is rendered to stop the caller releasing this frame.
            hasRenderedPendingFrame.await()
          } catch (_: CancellationException) {
            // This happens when release() is called while we are waiting.
            // We can safely ignore it and return, allowing the queuePacket method to exit.
            return@withContext
          }
          frame.release()
        }

        is Packet.EndOfStream -> {
          finalShaderProgramWrapper?.signalEndOfCurrentInputStream()
        }
      }
    }

  /**
   * Releases all resources associated with this renderer.
   *
   * This method cancels any pending render operations, releases the inputConsumer, and destroys the
   * underlying GL shader program. It ensures that no further frames are processed after being
   * called.
   */
  override suspend fun release() {
    if (isReleased.compareAndSet(false, true)) {
      hasRenderedPendingFrame.cancel()
      withContext(glDispatcher) { finalShaderProgramWrapper?.release() }
    }
  }

  /**
   * Updates the output surface information with new [SurfaceInfo], or null if the surface is no
   * longer available.
   */
  override fun setRenderOutput(output: SurfaceInfo?) {
    outputSurfaceInfo = output
    finalShaderProgramWrapper?.setOutputSurfaceInfo(output)
  }

  override fun onReadyToAcceptInputFrame() {}

  override fun onInputFrameProcessed(inputTexture: GlTextureInfo) {
    hasRenderedPendingFrame.complete(Unit)
  }

  override fun onInputStreamProcessed() {
    listener.onEnded()
  }

  override fun onFrameRendered(presentationTimeUs: Long) {}

  /**
   * Configures the internal GL environment (EGL Context and Surface) based on the provided
   * [outputColorInfo].
   *
   * This creates a new [FinalShaderProgramWrapper] suited for either SDR or HDR rendering.
   */
  private fun initializeFinalShaderProgramWrapper(
    outputColorInfo: ColorInfo
  ): FinalShaderProgramWrapper {
    val configAttributes =
      if (ColorInfo.isTransferHdr(outputColorInfo)) {
        GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102
      } else {
        GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888
      }
    val eglDisplay = GlUtil.getDefaultEglDisplay()
    val eglContextAndSurface =
      createFocusedEglContextWithFallback(glObjectsProvider, eglDisplay, configAttributes)
    val finalShaderProgramWrapper =
      FinalShaderProgramWrapper(
        context,
        eglDisplay,
        eglContextAndSurface.first,
        eglContextAndSurface.second,
        outputColorInfo,
        videoFrameProcessingTaskExecutor,
        directExecutor(),
        object : VideoFrameProcessor.Listener {
          override fun onOutputSizeChanged(width: Int, height: Int) {
            listener.onOutputSizeChanged(width, height)
          }

          override fun onOutputFrameAvailableForRendering(
            presentationTimeUs: Long,
            isRedrawnFrame: Boolean,
          ) {
            listener.onOutputFrameAvailableForRendering(presentationTimeUs)
          }

          override fun onError(e: VideoFrameProcessingException) {
            errorHandler.accept(e)
          }
        },
        /* textureOutputListener= */ null,
        /* textureOutputCapacity= */ 0,
        DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
        /* renderFramesAutomatically= */ false,
      )
    finalShaderProgramWrapper.setInputListener(this)
    finalShaderProgramWrapper.setListener(this)
    finalShaderProgramWrapper.setOutputSurfaceInfo(outputSurfaceInfo)
    this.finalShaderProgramWrapper = finalShaderProgramWrapper
    return finalShaderProgramWrapper
  }

  companion object {
    /**
     * Creates a new instance of [GlTextureFrameRenderer].
     *
     * @param context The application context.
     * @param glExecutorService The executor service to be used for GL operations.
     * @param glObjectsProvider Provider for GL objects (textures, buffers, etc.).
     * @param errorHandler A consumer to handle any [VideoFrameProcessingException]s that occur.
     * @param listener A [Listener] to be notified of events from the renderer.
     * @return A newly created [GlTextureFrameRenderer].
     */
    @JvmStatic
    fun create(
      context: Context,
      glExecutorService: ListeningExecutorService,
      glObjectsProvider: GlObjectsProvider,
      errorHandler: Consumer<VideoFrameProcessingException>,
      listener: Listener,
    ): GlTextureFrameRenderer {
      val videoFrameProcessingTaskExecutor =
        VideoFrameProcessingTaskExecutor(
          glExecutorService,
          /* shouldShutdownExecutorService= */ false,
          /* errorListener= */ errorHandler::accept,
        )
      return GlTextureFrameRenderer(
        context,
        glExecutorService,
        glObjectsProvider,
        videoFrameProcessingTaskExecutor,
        errorHandler,
        listener,
      )
    }

    /**
     * Creates an OpenGL ES 3.0 context if possible, and an OpenGL ES 2.0 context otherwise.
     *
     * See [createFocusedEglContext].
     */
    private fun createFocusedEglContextWithFallback(
      glObjectsProvider: GlObjectsProvider,
      eglDisplay: EGLDisplay,
      configAttributes: IntArray,
    ): Pair<EGLContext, EGLSurface> {
      return try {
        createFocusedEglContext(glObjectsProvider, eglDisplay, openGlVersion = 3, configAttributes)
      } catch (e: GlException) {
        createFocusedEglContext(glObjectsProvider, eglDisplay, openGlVersion = 2, configAttributes)
      }
    }

    /**
     * Creates an [EGLContext], focuses it using a
     * [GlObjectsProvider.createFocusedPlaceholderEglSurface], and returns the [EGLContext] and
     * [EGLSurface] as a [Pair].
     */
    private fun createFocusedEglContext(
      glObjectsProvider: GlObjectsProvider,
      eglDisplay: EGLDisplay,
      openGlVersion: Int,
      configAttributes: IntArray,
    ): Pair<EGLContext, EGLSurface> {
      val eglContext =
        glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes)
      // Some OpenGL ES 3.0 contexts returned from createEglContext may throw EGL_BAD_MATCH when
      // being used to createFocusedPlaceHolderEglSurface, despite GL documentation suggesting the
      // contexts, if successfully created, are valid. Check early whether the context is really
      // valid.
      val eglSurface = glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
      return eglContext to eglSurface
    }
  }
}
