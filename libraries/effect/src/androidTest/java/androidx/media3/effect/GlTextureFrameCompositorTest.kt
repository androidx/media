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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlTextureFrameCompositorTest {

  lateinit var glDispatcher: CoroutineDispatcher
  lateinit var glObjectsProvider: GlObjectsProvider

  @Before
  fun setup() {
    glDispatcher = Util.newSingleThreadExecutor("GlThread").asCoroutineDispatcher()
    runBlocking(glDispatcher) {
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

    compositor.queuePacket(packet)
    withTimeout(TEST_TIMEOUT_MS) { allReleased.await() }

    val frames = getPacketPayloadOrException(packet)
    assertThat(releasedTextures)
      .containsExactly(
        frames[0].glTextureInfo,
        frames[1].glTextureInfo,
        frames[2].glTextureInfo,
        frames[3].glTextureInfo,
      )
      .inOrder()

    compositor.release()
  }

  @Test
  fun queuePacket_withDownstreamConsumer_forwardsCompositedFrames() = doBlocking {
    val compositor = buildCompositor(createVideoCompositorSettings())
    val releasedTextures = mutableListOf<GlTextureInfo>()
    val expectedTexturesReleased = CompletableDeferred<Unit>()
    val numTexturesReleased = AtomicInteger()
    val onRelease = { textureInfo: GlTextureInfo ->
      releasedTextures.add(textureInfo)
      // Expect 4 textures from each of the 3 inputs to be released.
      if (numTexturesReleased.incrementAndGet() == 12) {
        expectedTexturesReleased.complete(Unit)
      }
    }
    val inputPacket1 = createTestPacket(onRelease)
    val inputPacket2 = createTestPacket(onRelease)
    val inputPacket3 = createTestPacket(onRelease)
    val expectedBitmap = createExpectedBitmap()
    val outputFrameDeferred1 = CompletableDeferred<GlTextureFrame>()
    val outputFrameDeferred2 = CompletableDeferred<GlTextureFrame>()
    val outputFrameDeferred3 = CompletableDeferred<GlTextureFrame>()
    val outputConsumer =
      createConsumer(outputFrameDeferred1, outputFrameDeferred2, outputFrameDeferred3)
    compositor.setOutput(outputConsumer)

    compositor.queuePacket(inputPacket1)
    compositor.queuePacket(inputPacket2)
    compositor.queuePacket(inputPacket3)

    withTimeout(TEST_TIMEOUT_MS) { expectedTexturesReleased.await() }
    val outputFrame1 = withTimeout(TEST_TIMEOUT_MS) { outputFrameDeferred1.await() }
    val outputFrame2 = withTimeout(TEST_TIMEOUT_MS) { outputFrameDeferred2.await() }
    val outputFrame3 = withTimeout(TEST_TIMEOUT_MS) { outputFrameDeferred3.await() }
    val averagePixelAbsoluteDifference1 =
      BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
        expectedBitmap,
        /* actual= */ createBitmap(outputFrame1.glTextureInfo),
        /* testId= */ null,
      )
    val averagePixelAbsoluteDifference2 =
      BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
        expectedBitmap,
        /* actual= */ createBitmap(outputFrame2.glTextureInfo),
        /* testId= */ null,
      )
    val averagePixelAbsoluteDifference3 =
      BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
        expectedBitmap,
        /* actual= */ createBitmap(outputFrame3.glTextureInfo),
        /* testId= */ null,
      )
    assertThat(averagePixelAbsoluteDifference1).isAtMost(ALLOWED_PIXEL_DIFFERENCE)
    assertThat(averagePixelAbsoluteDifference2).isAtMost(ALLOWED_PIXEL_DIFFERENCE)
    assertThat(averagePixelAbsoluteDifference3).isAtMost(ALLOWED_PIXEL_DIFFERENCE)
    val frames1 = getPacketPayloadOrException(inputPacket1)
    val frames2 = getPacketPayloadOrException(inputPacket2)
    val frames3 = getPacketPayloadOrException(inputPacket3)
    assertThat(releasedTextures)
      .containsExactly(
        frames1[0].glTextureInfo,
        frames1[1].glTextureInfo,
        frames1[2].glTextureInfo,
        frames1[3].glTextureInfo,
        frames2[0].glTextureInfo,
        frames2[1].glTextureInfo,
        frames2[2].glTextureInfo,
        frames2[3].glTextureInfo,
        frames3[0].glTextureInfo,
        frames3[1].glTextureInfo,
        frames3[2].glTextureInfo,
        frames3[3].glTextureInfo,
      )
      .inOrder()

    compositor.release()
  }

  @Test
  fun queuePacket_compositedFrameNotReleased_hangs() = doBlocking {
    val compositor = buildCompositor(createVideoCompositorSettings())
    val outputFrameDeferred = CompletableDeferred<GlTextureFrame>()
    val outputConsumer =
      createConsumer(outputFrameDeferred, CompletableDeferred(), releaseInputFrames = false)
    val packet = createTestPacket(onRelease = {})
    compositor.setOutput(outputConsumer)

    assertThrows(TimeoutCancellationException::class.java) {
      runBlocking { withTimeout(timeMillis = TEST_TIMEOUT_MS) { compositor.queuePacket(packet) } }
    }

    compositor.release()
  }

  @Test
  fun queueDownstreamThrows_propagatesExceptionAndReleasesPacket() = doBlocking {
    val exception = Exception("Expected exception")
    val thrownExceptionDeferred = CompletableDeferred<Exception>()
    val compositor = buildCompositor(createVideoCompositorSettings())
    val outputConsumer = createThrowingConsumer(exception)
    compositor.setOutput(outputConsumer)
    val releasedTextures = mutableListOf<GlTextureInfo>()
    val packet = createTestPacket { t -> releasedTextures.add(t) }

    try {
      compositor.queuePacket(packet)
    } catch (e: Exception) {
      thrownExceptionDeferred.complete(e)
    }

    val thrownException = withTimeout(TEST_TIMEOUT_MS) { thrownExceptionDeferred.await() }

    // TODO: b/459374133 - Remove dependency on GlUtil in TexturePool and add a test that the
    //  output texture is also released.
    assertThat(thrownException).isEqualTo(exception)
    val frames = getPacketPayloadOrException(packet)
    assertThat(releasedTextures)
      .containsExactly(
        frames[0].glTextureInfo,
        frames[1].glTextureInfo,
        frames[2].glTextureInfo,
        frames[3].glTextureInfo,
      )
      .inOrder()
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
    val blockLength = INPUT_SIDE_LENGTH / 2
    val width = INPUT_SIDE_LENGTH
    val height = INPUT_SIDE_LENGTH
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
    return runBlocking(glDispatcher) {
      val textureId =
        GlUtil.createTexture(
          INPUT_SIDE_LENGTH,
          INPUT_SIDE_LENGTH,
          /* useHighPrecisionColorComponents= */ false,
        )
      val fboId = GlUtil.createFboForTexture(textureId)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
      GLES20.glViewport(0, 0, INPUT_SIDE_LENGTH, INPUT_SIDE_LENGTH)
      GLES20.glClearColor(
        Color.red(color) / 255f,
        Color.green(color) / 255f,
        Color.blue(color) / 255f,
        Color.alpha(color) / 255f,
      )
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

      val textureInfo = GlTextureInfo(textureId, fboId, -1, INPUT_SIDE_LENGTH, INPUT_SIDE_LENGTH)
      GlTextureFrame.Builder(textureInfo, MoreExecutors.directExecutor(), onRelease).build()
    }
  }

  /** Creates a [Bitmap] matching the given [GlTextureFrame]. */
  private fun createBitmap(glTextureInfo: GlTextureInfo): Bitmap {
    return runBlocking(glDispatcher) {
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
      glDispatcher,
      DefaultGlObjectsProvider(),
      settings,
    )
  }

  private fun createConsumer(
    vararg deferredFrames: CompletableDeferred<GlTextureFrame>,
    releaseInputFrames: Boolean = true,
  ): PacketConsumer<GlTextureFrame> {
    val deferredIterator = deferredFrames.iterator()

    return object : PacketConsumer<GlTextureFrame> {

      override suspend fun queuePacket(packet: Packet<GlTextureFrame>) {
        val frame = getPacketPayloadOrException(packet)
        if (releaseInputFrames) {
          frame.release()
        }
        deferredIterator.next().complete(frame)
      }

      override suspend fun release() {}
    }
  }

  private fun createThrowingConsumer(exception: Exception): PacketConsumer<GlTextureFrame> {
    return object : PacketConsumer<GlTextureFrame> {

      override suspend fun queuePacket(packet: Packet<GlTextureFrame>) {
        throw exception
      }

      override suspend fun release() {}
    }
  }

  private fun <T> getPacketPayloadOrException(packet: Packet<T>): T {
    require(packet is Packet.Payload) { "Not data packet." }
    return packet.payload
  }

  private companion object {
    const val TEST_TIMEOUT_MS = 1000L
    const val INPUT_SIDE_LENGTH = 10
    const val ALLOWED_PIXEL_DIFFERENCE = 0f
  }
}
