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
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat.RGBA_8888
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.Util
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.ndk.HardwareBufferJni
import androidx.media3.test.utils.AssetInfo
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.media3.test.utils.DecodeOneFrameUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
@SdkSuppress(minSdkVersion = 29)
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

  @get:Rule val testName = TestName()

  @Before
  fun setUp() {
    hardwareBufferToGlTextureFrameProcessor =
      HardwareBufferToGlTextureFrameProcessor(
        context,
        glExecutorService,
        glObjectsProvider,
        HardwareBufferJni,
        errorConsumer,
      )
  }

  @After
  fun tearDown() {
    runBlocking {
      withContext(glDispatcher) {
        toBitmapConsumer?.release()
        hardwareBufferToGlTextureFrameProcessor?.release()
        glObjectsProvider.release(GlUtil.getDefaultEglDisplay())
      }
    }
    glExecutorService.shutdown()
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  fun queuePacket_withARGB8888HardwareBuffer_outputsCorrectGlTexture() = runBlocking {
    renderedBitmapDeferred = CompletableDeferred()
    toBitmapConsumer = ConvertToBitmapConsumer(glDispatcher, renderedBitmapDeferred!!)
    hardwareBufferToGlTextureFrameProcessor.setOutput(toBitmapConsumer!!)

    val argb8888Bitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png")
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

  @SdkSuppress(minSdkVersion = 29)
  @Test
  fun queuePacket_withFirstFrameAsYuv420HardwareBuffer_outputsCorrectGlTexture() = runBlocking {
    val inputImageReader =
      ImageReader.newInstance(
        TEST_VIDEO_WIDTH,
        TEST_VIDEO_HEIGHT,
        ImageFormat.YUV_420_888,
        /* maxImages= */ 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )
    val inputMediaFormatRef = AtomicReference<MediaFormat>()
    DecodeOneFrameUtil.decodeOneMediaItemFrame(
      MediaItem.fromUri(AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri),
      object : DecodeOneFrameUtil.Listener {
        override fun onContainerExtracted(mediaFormat: MediaFormat) {}

        override fun onFrameDecoded(mediaFormat: MediaFormat) {
          inputMediaFormatRef.set(mediaFormat)
        }
      },
      inputImageReader.surface,
    )
    renderedBitmapDeferred = CompletableDeferred()
    toBitmapConsumer = ConvertToBitmapConsumer(glDispatcher, renderedBitmapDeferred!!)
    hardwareBufferToGlTextureFrameProcessor.setOutput(toBitmapConsumer!!)
    val inputImage = inputImageReader.acquireLatestImage()
    val inputHardwareBuffer = inputImage.hardwareBuffer!!
    assertThat(inputHardwareBuffer.format).isNotEqualTo(RGBA_8888)
    // Override the input format to force it to be 1920x1080 rather than 1920x1088.
    val inputFormat =
      MediaFormatUtil.createFormatFromMediaFormat(inputMediaFormatRef.get())
        .buildUpon()
        .setWidth(TEST_VIDEO_WIDTH)
        .setHeight(TEST_VIDEO_HEIGHT)
        .build()
    val inputHardwareBufferFrame =
      HardwareBufferFrame.Builder(
          inputHardwareBuffer,
          glExecutorService,
          {
            inputHardwareBuffer.close()
            inputImage.close()
          },
        )
        .setFormat(inputFormat)
        .build()

    hardwareBufferToGlTextureFrameProcessor.queuePacket(Packet.of(inputHardwareBufferFrame))

    val renderedBitmap =
      withTimeout(timeMillis = TEST_TIMEOUT_MS) { renderedBitmapDeferred!!.await() }
    val expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png")
    assertThat(
        BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
          expectedBitmap,
          renderedBitmap,
          testName.methodName,
        )
      )
      .isLessThan(MAX_AVG_PIXEL_DIFFERENCE)

    inputImageReader.close()
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

  companion object {
    val TEST_TIMEOUT_MS = if (Util.isRunningOnEmulator()) 20_000L else 10_000L
    val TEST_VIDEO_WIDTH = AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat!!.width
    val TEST_VIDEO_HEIGHT = AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat!!.height
    // TODO: b/474075198 - Calculate the transformation matrix from MediaFormat. The current
    //  transformation matrix is hardcoded, and causing the output bitmap to be a bit misaligned.
    const val MAX_AVG_PIXEL_DIFFERENCE = 10F
  }
}
