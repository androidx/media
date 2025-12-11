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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Surface
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Util
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.media3.test.utils.BitmapPixelTestUtil.copyByteBufferFromRbga8888Image
import androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888ImageBuffer
import androidx.media3.test.utils.doBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlTextureFrameRendererTest {

  private lateinit var context: Context
  private lateinit var executorService: ListeningExecutorService
  private lateinit var glDispatcher: CoroutineDispatcher
  private lateinit var glObjectsProvider: GlObjectsProvider
  private lateinit var consumer: GlTextureFrameRenderer
  private lateinit var imageReader: ImageReader

  private val errorConsumer = Consumer<VideoFrameProcessingException> { e -> throw e }

  @Before
  fun setUp() {
    runBlocking {
      context = ApplicationProvider.getApplicationContext()
      executorService = MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("Test-GL"))
      glDispatcher = executorService.asCoroutineDispatcher()
      withContext(glDispatcher) {
        glObjectsProvider = SingleContextGlObjectsProvider()
        val eglDisplay = GlUtil.getDefaultEglDisplay()
        val eglContext =
          glObjectsProvider.createEglContext(
            GlUtil.getDefaultEglDisplay(),
            /* openGlVersion= */ 2,
            GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
          )
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
      }
    }
  }

  @After
  fun tearDown() = runBlocking {
    withContext(glDispatcher) { glObjectsProvider.release(GlUtil.getDefaultEglDisplay()) }
    if (::consumer.isInitialized) {
      consumer.release()
    }
    if (::imageReader.isInitialized) {
      imageReader.close()
    }
  }

  @Test
  fun queuePacket_multipleFrames_rendersToSurfaceAndReleasesFrame() = doBlocking {
    val releaseChannel =
      Channel<GlTextureInfo>(capacity = 3) { _ ->
        throw AssertionError("Unexpected texture release")
      }
    val outputChannel =
      Channel<Bitmap>(capacity = 3) { _ -> throw AssertionError("Unexpected output bitmap") }
    val surface = setupOutputSurface(outputChannel)
    val outputSurfaceInfo = SurfaceInfo(surface, WIDTH, HEIGHT)
    val inputs =
      listOf(Color.RED, Color.BLUE, Color.GREEN).map { color ->
        createFrameWithBitmap(color, WIDTH, HEIGHT, releaseChannel)
      }
    consumer =
      GlTextureFrameRenderer.create(context, executorService, glObjectsProvider, errorConsumer)
    consumer.setOutputSurfaceInfo(outputSurfaceInfo)

    // Verify each input after it is queued to avoid the flakiness from the Surface dropping frames.
    for (input in inputs) {
      consumer.queuePacket(Packet.of(input.frame))

      val capturedBitmap = withTimeout(TEST_TIMEOUT_MS) { outputChannel.receive() }
      val capturedRelease = withTimeout(TEST_TIMEOUT_MS) { releaseChannel.receive() }
      assertThat(
          BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
            capturedBitmap,
            input.bitmap,
            null,
          )
        )
        .isEqualTo(0f)
      assertThat(capturedRelease).isEqualTo(input.frame.glTextureInfo)
    }

    consumer.release()
  }

  @Test
  fun queuePacket_noOutputSurfaceSet_dropsFrames() = doBlocking {
    val releaseChannel =
      Channel<GlTextureInfo>(capacity = 3) { _ ->
        throw AssertionError("Unexpected texture release")
      }
    val inputs =
      listOf(Color.RED, Color.BLUE, Color.GREEN).map { color ->
        createFrameWithBitmap(color, WIDTH, HEIGHT, releaseChannel)
      }
    consumer =
      GlTextureFrameRenderer.create(context, executorService, glObjectsProvider, errorConsumer)

    for (input in inputs) {
      consumer.queuePacket(Packet.of(input.frame))
    }

    val expectedReleases = inputs.map { it.frame.glTextureInfo }
    val actualReleases =
      List(inputs.size) { withTimeout(TEST_TIMEOUT_MS) { releaseChannel.receive() } }
    assertThat(actualReleases).containsExactlyElementsIn(expectedReleases).inOrder()

    consumer.release()
  }

  private fun setupOutputSurface(outputChannel: SendChannel<Bitmap>): Surface {
    imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, /* maxImages= */ 1)
    imageReader.setOnImageAvailableListener(
      { reader ->
        val image = reader.acquireNextImage()
        if (image != null) {
          val bitmap =
            createArgb8888BitmapFromRgba8888ImageBuffer(copyByteBufferFromRbga8888Image(image))
          outputChannel.trySend(bitmap).onClosed {
            throw AssertionError("Renderer produced more frames than expected")
          }
          image.close()
        }
      },
      Util.createHandlerForCurrentOrMainLooper(),
    )
    return imageReader.surface
  }

  private suspend fun createFrameWithBitmap(
    color: Int,
    width: Int,
    height: Int,
    releaseChannel: SendChannel<GlTextureInfo>,
  ): GlTextureFrameWithBitmap =
    withContext(glDispatcher) {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(color)
      val textureId = GlUtil.createTexture(bitmap)
      val textureInfo = GlTextureInfo(textureId, /* fboId */ -1, /* rboId= */ -1, width, height)
      val frame =
        GlTextureFrame.Builder(textureInfo, MoreExecutors.directExecutor()) { texture ->
            releaseChannel.trySend(texture).onClosed {
              throw AssertionError("Renderer released more textures than expected")
            }
          }
          .build()
      GlTextureFrameWithBitmap(frame, bitmap)
    }

  private data class GlTextureFrameWithBitmap(val frame: GlTextureFrame, val bitmap: Bitmap)

  private companion object {
    const val TEST_TIMEOUT_MS = 5000L
    const val WIDTH = 20
    const val HEIGHT = 10
  }
}
