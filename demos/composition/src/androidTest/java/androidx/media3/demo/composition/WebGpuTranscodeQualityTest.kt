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
package androidx.media3.demo.composition

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.test.utils.AssetInfo
import androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

import org.junit.Ignore

/** Checks transcoding quality using WebGPU HardwareBuffer rendering. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class WebGpuTranscodeQualityTest {

  @get:Rule val tempFolder = TemporaryFolder()

  // In Gradle application builds, main assets are packaged in the target app APK, whereas
  // androidTest assets (e.g. test_data media files) are packaged in the test APK
  // (InstrumentationRegistry.getInstrumentation().context). Because the test runs in the target
  // app's process, ExoPlayer/Transformer must use the application Context for process operations
  // (e.g. registerReceiver), while delegating getAssets() to the test APK's Context to load
  // androidTest media assets.
  private val context: Context =
    object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
      override fun getApplicationContext(): Context = this

      override fun getAssets(): AssetManager =
        InstrumentationRegistry.getInstrumentation().context.assets
    }

  @Ignore("Flaky: b/577142229")
  @Test
  fun export_withWebGpu_psnrIsGreaterThanThreshold() = runBlocking {
    // Don't skip based on format support as input and output formats should be within CDD
    // requirements on all supported API versions, except for wearable devices.
    assumeFalse(Util.isWear(context))

    val outputFile = tempFolder.newFile("output.mp4")
    val transformer =
      Transformer.Builder(context)
        .setFrameProcessorFactory(WebGPUHardwareBufferEffectsPipeline.Factory())
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setEncoderFactory(ForceEncodeEncoderFactory(context))
        .build()
    val mediaItem =
      MediaItem.Builder()
        .setUri(AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder().setEndPositionMs(150).build()
        )
        .build()
    val editedMediaItem = EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build()

    withTimeout(120.seconds) {
      withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
          transformer.addListener(
            object : Transformer.Listener {
              override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                continuation.resume(exportResult)
              }

              override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
              ) {
                continuation.resumeWithException(exportException)
              }
            }
          )
          transformer.start(editedMediaItem, outputFile.absolutePath)
        }
      }
    }

    FrameExtractor.Builder(context, MediaItem.fromUri(outputFile.absolutePath)).build().use {
      actualFrameExtractor ->
      val actualFirstFrame = actualFrameExtractor.getFrame(/* positionMs= */ 0).await().bitmap
      val actualFrameAt100Ms = actualFrameExtractor.getFrame(/* positionMs= */ 100).await().bitmap

      FrameExtractor.Builder(context, mediaItem).build().use { expectedFrameExtractor ->
        val expectedFirstFrame = expectedFrameExtractor.getFrame(/* positionMs= */ 0).await().bitmap
        val expectedFrameAt100Ms =
          expectedFrameExtractor.getFrame(/* positionMs= */ 100).await().bitmap

        assertBitmapsAreSimilar(expectedFirstFrame, actualFirstFrame, /* psnrThresholdDb= */ 20.0)
        assertBitmapsAreSimilar(
          expectedFrameAt100Ms,
          actualFrameAt100Ms,
          /* psnrThresholdDb= */ 20.0,
        )
      }
    }
  }

  private class ForceEncodeEncoderFactory(context: Context) :
    Codec.EncoderFactory by DefaultEncoderFactory.Builder(context).build() {
    override fun videoNeedsEncoding(): Boolean = true
  }
}
