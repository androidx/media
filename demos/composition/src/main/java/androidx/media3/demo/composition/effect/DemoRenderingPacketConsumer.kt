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
package androidx.media3.demo.composition.effect

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.util.Consumer
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.GlTextureFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// TODO: b/449957627 - Remove once pipeline has been migrated to PacketConsumer interface.
/**
 * A simple placeholder [PacketConsumer] that renders frames from up to 4 simultaneous sequences in
 * a 2x2 grid on an output [Surface].
 */
@ExperimentalApi
@UnstableApi
internal class DemoRenderingPacketConsumer
private constructor(
  glExecutorService: ExecutorService,
  private val errorListener: Consumer<Exception>,
  private val outputSurface: Surface?,
) : PacketConsumer<List<GlTextureFrame>> {

  /** [PacketConsumer.Factory] for creating [DemoRenderingPacketConsumer] instances. */
  class Factory(
    private val glExecutorService: ExecutorService,
    private val errorListener: Consumer<Exception>,
  ) : PacketConsumer.Factory<List<GlTextureFrame>> {
    private var outputSurface: Surface? = null

    override fun create(): PacketConsumer<List<GlTextureFrame>> {
      return DemoRenderingPacketConsumer(glExecutorService, errorListener, outputSurface)
    }

    fun setOutputSurface(outputSurface: Surface?) {
      this.outputSurface = outputSurface
    }
  }

  private val bitmapPaint: Paint = Paint()
  private var bitmap: Bitmap? = null
  private var byteBuffer: ByteBuffer? = null
  private val inputChannel = Channel<Packet<List<GlTextureFrame>>>(capacity = 1)
  private val scope = CoroutineScope(glExecutorService.asCoroutineDispatcher())

  init {
    scope.launch { workerLoop() }
  }

  override fun tryQueuePacket(packet: Packet<List<GlTextureFrame>>): Boolean {
    return inputChannel.trySend(packet).isSuccess
  }

  override suspend fun queuePacket(packet: Packet<List<GlTextureFrame>>) {
    inputChannel.send(packet)
  }

  override suspend fun release() {
    inputChannel.close()
    scope.coroutineContext[Job]?.cancelAndJoin()
  }

  private suspend fun workerLoop() {
    try {
      for (packet in inputChannel) {
        try {
          render(packet.payload)
        } catch (e: Exception) {
          inputChannel.close(e)
          errorListener.accept(e)
        } finally {
          releaseFrames(packet.payload)
        }
      }
    } finally {
      var packet = inputChannel.tryReceive().getOrNull()
      while (packet != null) {
        releaseFrames(packet.payload)
        packet = inputChannel.tryReceive().getOrNull()
      }
    }
  }

  private fun releaseFrames(frames: List<GlTextureFrame>) {
    for (frame in frames) {
      frame.release()
    }
  }

  // Must be called on the GL thread.
  private fun render(frames: List<GlTextureFrame>) {
    val surface = outputSurface
    if (surface?.isValid != true) {
      return
    }
    val canvas = surface.lockCanvas(/* inOutDirty= */ null)
    if (canvas == null) {
      return
    }
    val w = canvas.width / 2
    val h = canvas.height / 2
    val destRects =
      arrayOf(
        Rect(0, 0, w, h),
        Rect(0, h, w, h * 2),
        Rect(w, 0, w * 2, h),
        Rect(w, h, w * 2, h * 2),
      )
    val maxNumSequences = min(4, frames.size)
    // Reset the background to black so transparent frames do not show the previous frame.
    canvas.drawColor(Color.BLACK)
    for (i in 0..<maxNumSequences) {
      val outputTexture: GlTextureInfo = frames[i].glTextureInfo
      val currentByteBuffer = getOrCreateByteBuffer(outputTexture)
      val currentBitmap = getOrCreateBitmap(outputTexture)
      GlUtil.focusFramebufferUsingCurrentContext(
        outputTexture.fboId,
        outputTexture.width,
        outputTexture.height,
      )
      GlUtil.checkGlError()
      GLES20.glReadPixels(
        /* x= */ 0,
        /* y= */ 0,
        outputTexture.width,
        outputTexture.height,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        byteBuffer,
      )
      GlUtil.checkGlError()
      currentByteBuffer.rewind()
      currentBitmap.copyPixelsFromBuffer(currentByteBuffer)
      val destRect = destRects[i]
      canvas.withScale(1f, -1f, destRect.centerX().toFloat(), destRect.centerY().toFloat()) {
        drawBitmap(currentBitmap, /* src= */ null, destRect, bitmapPaint)
      }
    }
    surface.unlockCanvasAndPost(canvas)
  }

  private fun getOrCreateByteBuffer(outputTexture: GlTextureInfo): ByteBuffer {
    var currentByteBuffer = this.byteBuffer
    val pixelBufferSize = outputTexture.width * outputTexture.height * 4
    if (currentByteBuffer == null || currentByteBuffer.capacity() < pixelBufferSize) {
      currentByteBuffer = ByteBuffer.allocateDirect(pixelBufferSize)
      this.byteBuffer = currentByteBuffer
    }
    currentByteBuffer.clear()
    return currentByteBuffer
  }

  private fun getOrCreateBitmap(outputTexture: GlTextureInfo): Bitmap {
    var currentBitmap = this.bitmap
    if (
      currentBitmap == null ||
        currentBitmap.height != outputTexture.height ||
        currentBitmap.width != outputTexture.width
    ) {
      currentBitmap = createBitmap(outputTexture.width, outputTexture.height)
      this.bitmap = currentBitmap
    }
    return currentBitmap
  }

  /**
   * A [GlObjectsProvider] that reuses a single [EGLContext] across [ ][.createEglContext] calls.
   */
  /* package */ class SingleContextGlObjectsProvider : GlObjectsProvider {
    private val glObjectsProvider: GlObjectsProvider = DefaultGlObjectsProvider()
    private var singleEglContext: EGLContext? = null

    @Throws(GlException::class)
    override fun createEglContext(
      eglDisplay: EGLDisplay,
      openGlVersion: Int,
      configAttributes: IntArray,
    ): EGLContext {
      if (singleEglContext == null) {
        singleEglContext =
          glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes)
      }
      return singleEglContext!!
    }

    @Throws(GlException::class)
    override fun createEglSurface(
      eglDisplay: EGLDisplay,
      surface: Any,
      colorTransfer: @ColorTransfer Int,
      isEncoderInputSurface: Boolean,
    ): EGLSurface {
      return glObjectsProvider.createEglSurface(
        eglDisplay,
        surface,
        colorTransfer,
        isEncoderInputSurface,
      )
    }

    @Throws(GlException::class)
    override fun createFocusedPlaceholderEglSurface(
      eglContext: EGLContext,
      eglDisplay: EGLDisplay,
    ): EGLSurface {
      return glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
    }

    @Throws(GlException::class)
    override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo {
      return glObjectsProvider.createBuffersForTexture(texId, width, height)
    }

    @Throws(GlException::class)
    override fun release(eglDisplay: EGLDisplay) {
      if (singleEglContext != null) {
        glObjectsProvider.release(eglDisplay)
      }
      singleEglContext = null
    }
  }
}
