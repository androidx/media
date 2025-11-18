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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import androidx.annotation.ColorInt
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.media3.test.utils.doBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlTextureFrameCompositorTest {

  private val testTimeoutMs = 1000L
  private val inputLength = 10
  lateinit var dispatcher: CoroutineDispatcher
  lateinit var glObjectsProvider: GlObjectsProvider

  @Before
  fun setup() {
    dispatcher = Util.newSingleThreadExecutor("GlThread").asCoroutineDispatcher()
    runBlocking(dispatcher) {
      glObjectsProvider = DefaultGlObjectsProvider()
      val eglDisplay = GlUtil.getDefaultEglDisplay()
      val eglContext =
        glObjectsProvider.createEglContext(eglDisplay, 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
      glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
    }
  }

  @Test
  fun queuePacket_noDownstreamConsumer_dropsInput() = doBlocking {
    val compositor = buildCompositor(createVideoCompositorSettings())
    val releasedTextures = mutableListOf<GlTextureInfo>()
    val releaseCount = AtomicInteger()
    val allReleased = CompletableDeferred<Unit>()
    val packet = createTestPacket { t ->
      releasedTextures.add(t)
      if (releaseCount.incrementAndGet() == 4) {
        allReleased.complete(Unit)
      }
    }
    launch { compositor.run() }

    compositor.queuePacket(packet)
    withTimeout(testTimeoutMs) { allReleased.await() }

    assertThat(releasedTextures)
      .containsExactly(
        packet.payload[0].glTextureInfo,
        packet.payload[1].glTextureInfo,
        packet.payload[2].glTextureInfo,
        packet.payload[3].glTextureInfo,
      )
      .inOrder()

    compositor.release()
  }

  @Test
  fun queueDownstreamThrows_propagatesExceptionAndReleasesPacket() = doBlocking {
    val exception = Exception("Expected exception")
    val thrownExceptionDeferred = CompletableDeferred<Exception>()
    val compositor = buildCompositor(createVideoCompositorSettings())
    val outputConsumer = createThrowingConsumer(exception)
    compositor.setOutput(outputConsumer)
    launch {
      try {
        compositor.run()
      } catch (e: Exception) {
        thrownExceptionDeferred.complete(e)
      }
    }

    val releasedTextures = mutableListOf<GlTextureInfo>()
    val packet = createTestPacket { t -> releasedTextures.add(t) }

    compositor.queuePacket(packet)
    val thrownException = withTimeout(testTimeoutMs) { thrownExceptionDeferred.await() }

    // TODO: b/459374133 - Remove dependency on GlUtil in TexturePool and add a test that the
    //  output texture is also released.
    assertThat(thrownException).isEqualTo(exception)
    assertThat(releasedTextures)
      .containsExactly(
        packet.payload[0].glTextureInfo,
        packet.payload[1].glTextureInfo,
        packet.payload[2].glTextureInfo,
        packet.payload[3].glTextureInfo,
      )
      .inOrder()
  }

  @Test
  fun compositeTwoInputs_stacked_matchesExpectedBitmap() = doBlocking {
    val compositor = buildCompositor(createVideoCompositorSettings())
    val outputFrameDeferred = CompletableDeferred<GlTextureFrame>()
    val releasedTextures = mutableListOf<GlTextureInfo>()
    val inputPacket = createTestPacket { t -> releasedTextures.add(t) }
    val expectedBitmap = createExpectedBitmap()
    val outputConsumer = createConsumer(outputFrameDeferred)
    compositor.setOutput(outputConsumer)
    launch { compositor.run() }

    compositor.queuePacket(inputPacket)

    val outputFrame = withTimeout(testTimeoutMs) { outputFrameDeferred.await() }
    val receivedBitmap = createBitmap(outputFrame.glTextureInfo)
    val averagePixelAbsoluteDifference =
      BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
        expectedBitmap,
        receivedBitmap,
        /* testId= */ null,
      )
    assertThat(averagePixelAbsoluteDifference).isLessThan(2.5f)
    assertThat(releasedTextures)
      .containsExactly(
        inputPacket.payload[0].glTextureInfo,
        inputPacket.payload[1].glTextureInfo,
        inputPacket.payload[2].glTextureInfo,
        inputPacket.payload[3].glTextureInfo,
      )
      .inOrder()

    compositor.release()
  }

  private fun createVideoCompositorSettings(): VideoCompositorSettings {
    return object : VideoCompositorSettings {
      override fun getOutputSize(inputSizes: List<Size>): Size {
        return inputSizes[0]
      }

      override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        return when (inputId) {
          0 -> {
            StaticOverlaySettings.Builder()
              .setScale(0.5f, 0.5f)
              .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
              .setBackgroundFrameAnchor(-0.5f, 0.5f) // Top-left section of background
              .build()
          }

          1 -> {
            StaticOverlaySettings.Builder()
              .setScale(0.5f, 0.5f)
              .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
              .setBackgroundFrameAnchor(0.5f, 0.5f) // Top-right section of background
              .build()
          }

          2 -> {
            StaticOverlaySettings.Builder()
              .setScale(0.5f, 0.5f)
              .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
              .setBackgroundFrameAnchor(-0.5f, -0.5f) // Bottom-left section of background
              .build()
          }

          3 -> {
            StaticOverlaySettings.Builder()
              .setScale(0.5f, 0.5f)
              .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
              .setBackgroundFrameAnchor(0.5f, -0.5f) // Bottom-right section of background
              .build()
          }

          else -> {
            StaticOverlaySettings.Builder().build()
          }
        }
      }
    }
  }

  /** Returns a packet containing red, blue, green and yellow [GlTextureFrame]. */
  private fun createTestPacket(
    onRelease: (t: GlTextureInfo) -> Unit
  ): Packet<List<GlTextureFrame>> {
    val frame1 = createFrame(Color.RED, onRelease)
    val frame2 = createFrame(Color.BLUE, onRelease)
    val frame3 = createFrame(Color.GREEN, onRelease)
    val frame4 = createFrame(Color.YELLOW, onRelease)
    return Packet.of(listOf(frame1, frame2, frame3, frame4))
  }

  /**
   * Returns a bitmap matching the packet created in [createTestPacket], composited with
   * [createVideoCompositorSettings].
   */
  private fun createExpectedBitmap(): Bitmap {
    val blockLength = inputLength / 2
    val width = inputLength
    val height = inputLength
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()

    paint.color = Color.RED
    canvas.drawRect(0f, 0f, blockLength.toFloat(), blockLength.toFloat(), paint)
    paint.color = Color.BLUE
    canvas.drawRect(blockLength.toFloat(), 0f, width.toFloat(), blockLength.toFloat(), paint)
    paint.color = Color.GREEN
    canvas.drawRect(0f, blockLength.toFloat(), blockLength.toFloat(), height.toFloat(), paint)
    paint.color = Color.YELLOW
    canvas.drawRect(
      blockLength.toFloat(),
      blockLength.toFloat(),
      width.toFloat(),
      height.toFloat(),
      paint,
    )

    return bitmap
  }

  /** Creates a [GlTextureFrame] of the given [color]. */
  private fun createFrame(
    @ColorInt color: Int,
    onRelease: (t: GlTextureInfo) -> Unit = {},
  ): GlTextureFrame {
    return runBlocking(dispatcher) {
      val textureId =
        GlUtil.createTexture(inputLength, inputLength, /* useHighPrecisionColorComponents= */ false)
      val fboId = GlUtil.createFboForTexture(textureId)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
      GLES20.glViewport(0, 0, inputLength, inputLength)
      GLES20.glClearColor(
        Color.red(color) / 255f,
        Color.green(color) / 255f,
        Color.blue(color) / 255f,
        Color.alpha(color) / 255f,
      )
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      val textureInfo = GlTextureInfo(textureId, fboId, -1, inputLength, inputLength)
      GlTextureFrame.Builder(textureInfo, MoreExecutors.directExecutor(), onRelease).build()
    }
  }

  /** Creates a [Bitmap] matching the given [GlTextureFrame]. */
  private fun createBitmap(glTextureInfo: GlTextureInfo): Bitmap {
    return runBlocking(dispatcher) {
      val fboId = GlUtil.createFboForTexture(glTextureInfo.texId)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
      val bitmap =
        BitmapPixelTestUtil.createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
          glTextureInfo.width,
          glTextureInfo.height,
        )
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
      bitmap
    }
  }

  private fun buildCompositor(settings: VideoCompositorSettings): GlTextureFrameCompositor {
    return GlTextureFrameCompositor(
      getInstrumentation().context,
      dispatcher,
      DefaultGlObjectsProvider(),
      settings,
    )
  }

  private fun createConsumer(
    deferredFrame: CompletableDeferred<GlTextureFrame>
  ): PacketConsumer<GlTextureFrame> {
    return object : PacketConsumer<GlTextureFrame> {
      override fun tryQueuePacket(packet: Packet<GlTextureFrame>): Boolean {
        deferredFrame.complete(packet.payload)
        return true
      }

      override suspend fun queuePacket(packet: Packet<GlTextureFrame>) {
        deferredFrame.complete(packet.payload)
      }

      override suspend fun release() {}
    }
  }

  private fun createThrowingConsumer(exception: Exception): PacketConsumer<GlTextureFrame> {
    return object : PacketConsumer<GlTextureFrame> {
      override fun tryQueuePacket(packet: Packet<GlTextureFrame>): Boolean {
        throw exception
      }

      override suspend fun queuePacket(packet: Packet<GlTextureFrame>) {
        throw exception
      }

      override suspend fun release() {}
    }
  }
}
