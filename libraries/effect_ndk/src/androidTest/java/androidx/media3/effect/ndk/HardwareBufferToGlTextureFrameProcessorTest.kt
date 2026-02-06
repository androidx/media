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
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.isRunningOnEmulator
import androidx.media3.effect.GlTextureFrame
import androidx.media3.effect.GlTextureFrameRenderer
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.SingleContextGlObjectsProvider
import androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.listeningDecorator
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Tests for [HardwareBufferToGlTextureFrameProcessor]. */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class HardwareBufferToGlTextureFrameProcessorTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val glExecutorService = Executors.newSingleThreadExecutor()
  private val glDispatcher = glExecutorService.asCoroutineDispatcher()
  private val glObjectsProvider: GlObjectsProvider = SingleContextGlObjectsProvider()
  private val errorConsumer = Consumer<Exception> { t -> throw t }
  private lateinit var hardwareBufferToGlTextureFrameProcessor:
    HardwareBufferToGlTextureFrameProcessor
  private var renderedBitmapDeferred: CompletableDeferred<Bitmap>? = null
  private var toBitmapConsumer: PacketConsumer<GlTextureFrame>? = null
  private var glTextureFrameRenderer: GlTextureFrameRenderer? = null
  private var outputImageReader: ImageReader? = null

  @get:Rule val testName = TestName()

  @Before
  fun setUp() {
    hardwareBufferToGlTextureFrameProcessor =
      HardwareBufferToGlTextureFrameProcessor(
        context,
        glExecutorService,
        glObjectsProvider,
        errorConsumer,
      )
  }

  @After
  fun tearDown() {
    runBlocking {
      withContext(glDispatcher) {
        toBitmapConsumer?.release()
        hardwareBufferToGlTextureFrameProcessor?.release()
        glTextureFrameRenderer?.release()
        glObjectsProvider.release(GlUtil.getDefaultEglDisplay())
      }
    }
    outputImageReader?.close()
    glExecutorService.shutdown()
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  fun queuePacket_withARGB8888HardwareBuffer_outputsCorrectGlTexture() = runBlocking {
    renderedBitmapDeferred = CompletableDeferred()
    toBitmapConsumer = ConvertToBitmapConsumer(glDispatcher, renderedBitmapDeferred!!)
    hardwareBufferToGlTextureFrameProcessor.setOutput(toBitmapConsumer!!)

    val argb8888Bitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    val hardwareBitmap = argb8888Bitmap.copy(Bitmap.Config.HARDWARE, false)
    val argb8888HardwareBuffer = hardwareBitmap.hardwareBuffer

    val argb8888HardwareBufferFrame =
      HardwareBufferFrame.Builder(
          argb8888HardwareBuffer,
          glExecutorService,
          { argb8888HardwareBuffer.close() },
        )
        .setFormat(
          Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_RAW)
            .setWidth(argb8888Bitmap.width)
            .setHeight(argb8888Bitmap.height)
            .build()
        )
        .build()

    hardwareBufferToGlTextureFrameProcessor.queuePacket(Packet.of(argb8888HardwareBufferFrame))
    // The output bitmap is also ARGB8888

    val renderedBitmap =
      withTimeout(timeMillis = TEST_TIMEOUT_MS) { renderedBitmapDeferred!!.await() }

    assertThat(
        BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
          argb8888Bitmap,
          renderedBitmap,
          testName.methodName,
        )
      )
      .isLessThan(MAX_AVG_PIXEL_DIFFERENCE)

    argb8888HardwareBufferFrame.release(/* releaseFence= */ null)
  }

  @SdkSuppress(minSdkVersion = 28)
  @Test
  fun queuePacket_withFirstFrameAsYuv420HardwareBuffer_outputsCorrectGlTexture() = runBlocking {
    glTextureFrameRenderer =
      GlTextureFrameRenderer.create(
        context,
        listeningDecorator(glExecutorService),
        glObjectsProvider,
        { e -> errorConsumer.accept(e) },
        GlTextureFrameRenderer.Listener.NO_OP,
      )
    outputImageReader =
      ImageReader.newInstance(TEST_VIDEO_WIDTH, TEST_VIDEO_HEIGHT, PixelFormat.RGBA_8888, 1)
    glTextureFrameRenderer?.setRenderOutput(
      SurfaceInfo(
        outputImageReader!!.surface,
        outputImageReader!!.width,
        outputImageReader!!.height,
      )
    )
    val composition =
      Composition.Builder(
          EditedMediaItemSequence.withVideoFrom(
            listOf(
              EditedMediaItem.Builder(
                  MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                      // One frame
                      MediaItem.ClippingConfiguration.Builder().setEndPositionMs(500).build()
                    )
                    .build()
                )
                .setDurationUs(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoDurationUs)
                .build()
            )
          )
        )
        .build()
    val imageAcquiredDeferred = CompletableDeferred<Image>()
    outputImageReader?.setOnImageAvailableListener(
      { reader ->
        if (!imageAcquiredDeferred.isCompleted) {
          imageAcquiredDeferred.complete(checkNotNull(reader).acquireNextImage())
        }
      },
      Util.createHandlerForCurrentOrMainLooper(),
    )
    hardwareBufferToGlTextureFrameProcessor.setOutput(checkNotNull(glTextureFrameRenderer))
    val compositionPlayer =
      CompositionPlayer.Builder(context)
        .setPacketConsumerFactory {
          PassthroughPacketConsumer(hardwareBufferToGlTextureFrameProcessor)
        }
        .setGlObjectsProvider(glObjectsProvider)
        .build()
    withContext(Dispatchers.Main) {
      compositionPlayer.setComposition(composition)
      compositionPlayer.addListener(
        object : Player.Listener {
          override fun onRenderedFirstFrame() {
            compositionPlayer.stop()
          }

          override fun onPlayerError(exception: PlaybackException) {
            throw exception
          }
        }
      )
      compositionPlayer.prepare()
      compositionPlayer.play()
    }

    val renderedImage = withTimeout(timeMillis = TEST_TIMEOUT_MS) { imageAcquiredDeferred.await() }
    val renderedBitmap = BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888Image(renderedImage)
    renderedImage.close()

    val expectedBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    assertThat(
        BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
          expectedBitmap,
          renderedBitmap,
          testName.methodName,
        )
      )
      .isLessThan(MAX_AVG_PIXEL_DIFFERENCE)

    withContext(Dispatchers.Main) { compositionPlayer.release() }
  }

  private class ConvertToBitmapConsumer(
    private val glDispatcher: CoroutineDispatcher,
    private val renderedBitmapDeferred: CompletableDeferred<Bitmap>,
  ) : PacketConsumer<GlTextureFrame> {
    override suspend fun queuePacket(packet: Packet<GlTextureFrame>) =
      withContext(glDispatcher) {
        when (packet) {
          Packet.EndOfStream -> return@withContext
          is Packet.Payload<GlTextureFrame> -> {
            val inputTextureInfo = packet.payload.glTextureInfo
            val textureWidth = inputTextureInfo.width
            val textureHeight = inputTextureInfo.height
            val fboId = GlUtil.createFboForTexture(inputTextureInfo.texId)
            GlUtil.focusFramebufferUsingCurrentContext(fboId, textureWidth, textureHeight)
            val outputBitmap =
              BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer(
                textureWidth,
                textureHeight,
              )
            renderedBitmapDeferred.complete(outputBitmap)
          }
        }
      }

    override suspend fun release() {}
  }

  private class PassthroughPacketConsumer(
    private val hardwareBufferToGlTextureFrameProcessor: HardwareBufferToGlTextureFrameProcessor
  ) : PacketConsumer<List<HardwareBufferFrame>> {
    override suspend fun queuePacket(packet: Packet<List<HardwareBufferFrame>>) {
      when (packet) {
        is Packet.EndOfStream -> hardwareBufferToGlTextureFrameProcessor.queuePacket(packet)
        is Packet.Payload<List<HardwareBufferFrame>> ->
          hardwareBufferToGlTextureFrameProcessor.queuePacket(Packet.of(packet.payload[0]))
      }
    }

    override suspend fun release() {}
  }

  companion object {
    val TEST_TIMEOUT_MS = if (isRunningOnEmulator()) 20_000L else 10_000L
    val TEST_VIDEO_WIDTH = MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat!!.width
    val TEST_VIDEO_HEIGHT = MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat!!.height
    // TODO: b/474075198 - Calculate the transformation matrix from MediaFormat. The current
    //  transformation matrix is hardcoded, and causing the output bitmap to be a bit misaligned.
    const val MAX_AVG_PIXEL_DIFFERENCE = 10F
  }
}
