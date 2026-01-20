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
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

// TODO: b/474075198 - Remove this class once HardwareBuffer to GlTexture conversion is implemented.
/**
 * Temporary class to help test HardwareBuffer integration, this will be removed once HardwareBuffer
 * to GlTexture conversion logic is implemented.
 */
@RestrictTo(Scope.LIBRARY_GROUP)
class PlaceholderHardwareBufferToGlTextureConverter(
  private val glExecutorService: ExecutorService,
  private val glObjectsProvider: GlObjectsProvider,
) : PacketProcessor<HardwareBufferFrame, GlTextureFrame> {

  private val glDispatcher = glExecutorService.asCoroutineDispatcher()
  private var outputConsumer: PacketConsumer<GlTextureFrame>? = null
  private var eglDisplay: EGLDisplay? = null
  private var eglContext: EGLContext? = null
  private var eglSurface: EGLSurface? = null

  override fun setOutput(output: PacketConsumer<GlTextureFrame>) {
    outputConsumer = output
  }

  override suspend fun queuePacket(packet: PacketConsumer.Packet<HardwareBufferFrame>) {
    when (packet) {
      is PacketConsumer.Packet.EndOfStream ->
        outputConsumer?.queuePacket(PacketConsumer.Packet.EndOfStream)
      is PacketConsumer.Packet.Payload -> {
        withContext(glDispatcher) {
          val inputFrame = packet.payload
          maybeInitOpenGl(inputFrame)
          val texId =
            GlUtil.createTexture(
              TEXTURE_LENGTH,
              TEXTURE_LENGTH,
              /* useHighPrecisionColorComponents= */ false,
            )
          val textureInfo =
            GlTextureInfo(
              texId,
              /* fboId= */ C.INDEX_UNSET,
              /* rboId= */ C.INDEX_UNSET,
              TEXTURE_LENGTH,
              TEXTURE_LENGTH,
            )
          val outputFrame =
            GlTextureFrame.Builder(
                textureInfo,
                glExecutorService,
                { glTextureInfo -> GlUtil.deleteTexture(glTextureInfo.texId) },
              )
              .setPresentationTimeUs(inputFrame.presentationTimeUs)
              .setReleaseTimeNs(inputFrame.releaseTimeNs)
              .setFormat(inputFrame.format)
              .setMetadata(inputFrame.metadata)
              .build()

          outputConsumer?.queuePacket(PacketConsumer.Packet.of(outputFrame))
          inputFrame.release()
        }
      }
    }
  }

  override suspend fun release() {}

  private fun maybeInitOpenGl(frame: HardwareBufferFrame) {
    if (eglDisplay != null) {
      return
    }
    val configAttributes =
      if (ColorInfo.isTransferHdr(frame.format.colorInfo)) {
        GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102
      } else {
        GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888
      }
    val eglDisplay = GlUtil.getDefaultEglDisplay()
    val eglContextAndSurface =
      createFocusedEglContextWithFallback(glObjectsProvider, eglDisplay, configAttributes)
    this.eglDisplay = eglDisplay
    this.eglContext = eglContextAndSurface.first
    this.eglSurface = eglContextAndSurface.second
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
    val eglContext = glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes)
    // Some OpenGL ES 3.0 contexts returned from createEglContext may throw EGL_BAD_MATCH when
    // being used to createFocusedPlaceHolderEglSurface, despite GL documentation suggesting the
    // contexts, if successfully created, are valid. Check early whether the context is really
    // valid.
    val eglSurface = glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
    return eglContext to eglSurface
  }

  companion object {
    private const val TEXTURE_LENGTH = 1
  }
}
