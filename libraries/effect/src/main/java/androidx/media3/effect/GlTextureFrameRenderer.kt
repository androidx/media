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
import androidx.annotation.RestrictTo
import androidx.media3.common.ColorInfo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.util.concurrent.ListeningExecutorService
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
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class GlTextureFrameRenderer
private constructor(
  private val context: Context,
  private val glExecutorService: ExecutorService,
  private val glObjectsProvider: GlObjectsProvider,
  private val videoFrameProcessingTaskExecutor: VideoFrameProcessingTaskExecutor,
) :
  RunnablePacketConsumer<GlTextureFrame>,
  GlShaderProgram.InputListener,
  FinalShaderProgramWrapper.Listener {

  private val glDispatcher = glExecutorService.asCoroutineDispatcher()
  private val inputConsumer = ChannelPacketConsumer(this::consume) { frame -> frame.release() }
  private val isReleased = AtomicBoolean(false)
  private var hasRenderedPendingFrame = CompletableDeferred<Unit>(Unit)
  private var finalShaderProgramWrapper: FinalShaderProgramWrapper? = null
  @Volatile private var outputSurfaceInfo: SurfaceInfo? = null

  override suspend fun queuePacket(packet: Packet<GlTextureFrame>) =
    inputConsumer.queuePacket(packet)

  /**
   * Releases all resources associated with this renderer.
   *
   * This method cancels any pending render operations, releases the inputConsumer, and destroys the
   * underlying GL shader program. It ensures that no further frames are processed after being
   * called.
   */
  override suspend fun release() {
    if (isReleased.compareAndSet(false, true)) {
      inputConsumer.release()
      hasRenderedPendingFrame.cancel()
      withContext(glDispatcher) { finalShaderProgramWrapper?.release() }
    }
  }

  override suspend fun run() {
    withContext(glDispatcher) { inputConsumer.run() }
  }

  /**
   * Updates the output surface information.
   *
   * @param surfaceInfo The new [SurfaceInfo], or null if the surface is no longer available.
   */
  fun setOutputSurfaceInfo(surfaceInfo: SurfaceInfo?) {
    outputSurfaceInfo = surfaceInfo
    finalShaderProgramWrapper?.setOutputSurfaceInfo(surfaceInfo)
  }

  override fun onReadyToAcceptInputFrame() {}

  override fun onInputFrameProcessed(inputTexture: GlTextureInfo) {
    hasRenderedPendingFrame.complete(Unit)
  }

  override fun onInputStreamProcessed() {}

  override fun onFrameRendered(presentationTimeUs: Long) {}

  /**
   * Consumes a single [GlTextureFrame], first configuring the renderer if required and queuing the
   * frame to the shader program.
   *
   * This method suspends until the frame is fully rendered. If the renderer is released while
   * waiting, it gracefully exits.
   */
  private suspend fun consume(frame: GlTextureFrame) {
    if (isReleased.get()) return
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
      // Suspend until the frame is rendered to stop the input consumer releasing this frame and
      // accepting another.
      hasRenderedPendingFrame.await()
    } catch (_: CancellationException) {
      // This happens when release() is called while we are waiting.
      // We can safely ignore it and return, allowing the run loop to exit.
      return
    }
  }

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
        glExecutorService,
        object : VideoFrameProcessor.Listener {},
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
     * @return A newly created [GlTextureFrameRenderer].
     */
    @JvmStatic
    fun create(
      context: Context,
      glExecutorService: ListeningExecutorService,
      glObjectsProvider: GlObjectsProvider,
      errorHandler: Consumer<VideoFrameProcessingException>,
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
