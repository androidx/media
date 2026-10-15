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
package androidx.media3.effect.playservices.mh

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.playservices.StudioSoundAudioProcessor
import androidx.media3.effect.playservices.StudioSoundAudioProcessor.AUDIO_TYPE_MUSIC
import androidx.media3.effect.playservices.StudioSoundAudioProcessor.AUDIO_TYPE_NOISE
import androidx.media3.effect.playservices.StudioSoundAudioProcessor.AUDIO_TYPE_SPEECH
import androidx.media3.effect.playservices.StudioSoundAudioProcessor.SeparationResult
import androidx.media3.exoplayer.audio.ToFloatPcmAudioProcessor
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WavMuxer
import androidx.media3.test.utils.AssetInfo.WAV_TOS_MONO_16BIT
import androidx.media3.test.utils.AssetInfo.WAV_TOS_STEREO_16BIT
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.android.gms.media.effect.studiosound.StudioSound
import com.google.common.base.Optional
import com.google.common.collect.ImmutableList
import com.google.common.io.ByteSource
import com.google.common.io.Files
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Instrumentation tests for [StudioSoundAudioProcessor]. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class StudioSoundAudioProcessorAndroidTest {

  companion object {
    private const val GOLDEN_FOLDER = "test-generated-goldens/StudioSoundAudioProcessorTest/"
  }

  @get:Rule val outputDir: TemporaryFolder = TemporaryFolder()
  @get:Rule val globalTimeout: Timeout = Timeout.seconds(25)
  private val context: Context = getApplicationContext()

  @Test
  fun process_with16BitPcmStereoInput_returns16ChannelOutputWithMatchingLabels() =
    runBlocking(Dispatchers.Main) {
      assumeDeviceSupported()
      val outputFile = outputDir.newFile()

      val (exportResult, separationResults) =
        runSourceSeparation(MediaItem.fromUri(WAV_TOS_STEREO_16BIT.uri), outputFile.path)

      assertThat(exportResult.channelCount).isEqualTo(16)
      assertThat(separationResults).hasSize(4)
      assertThat(separationResults[0].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[0].channelIndices).isEqualTo(intArrayOf(0, 1))
      assertThat(separationResults[1].label).isEqualTo(AUDIO_TYPE_NOISE)
      assertThat(separationResults[1].channelIndices).isEqualTo(intArrayOf(2, 3))
      assertThat(separationResults[2].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[2].channelIndices).isEqualTo(intArrayOf(4, 5))
      assertThat(separationResults[3].label).isEqualTo(AUDIO_TYPE_MUSIC)
      assertThat(separationResults[3].channelIndices).isEqualTo(intArrayOf(6, 7))

      // Use a ByteSource to avoid loading the entire files into memory and OOMing if Truth tries to
      // diff the bytes.
      val expectedByteSource =
        context.resources.assets.asByteSource(GOLDEN_FOLDER + "tos-stereo-golden.wav")
      val actualByteSource = Files.asByteSource(outputFile)
      assertThat(actualByteSource.contentEquals(expectedByteSource)).isTrue()
    }

  @Test
  fun process_withFloatPcmStereoInput_returns16ChannelOutputWithMatchingLabels() =
    runBlocking(Dispatchers.Main) {
      assumeDeviceSupported()
      val outputFile = outputDir.newFile()

      val (exportResult, separationResults) =
        runSourceSeparation(
          MediaItem.fromUri(WAV_TOS_STEREO_16BIT.uri),
          outputFile.path,
          ToFloatPcmAudioProcessor(),
        )

      assertThat(exportResult.channelCount).isEqualTo(16)
      assertThat(separationResults).hasSize(4)
      assertThat(separationResults[0].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[0].channelIndices).isEqualTo(intArrayOf(0, 1))
      assertThat(separationResults[1].label).isEqualTo(AUDIO_TYPE_NOISE)
      assertThat(separationResults[1].channelIndices).isEqualTo(intArrayOf(2, 3))
      assertThat(separationResults[2].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[2].channelIndices).isEqualTo(intArrayOf(4, 5))
      assertThat(separationResults[3].label).isEqualTo(AUDIO_TYPE_MUSIC)
      assertThat(separationResults[3].channelIndices).isEqualTo(intArrayOf(6, 7))

      // Use a ByteSource to avoid loading the entire files into memory and OOMing if Truth tries to
      // diff the bytes.
      val expectedByteSource =
        context.resources.assets.asByteSource(GOLDEN_FOLDER + "tos-stereo-golden.wav")
      val actualByteSource = Files.asByteSource(outputFile)
      assertThat(actualByteSource.contentEquals(expectedByteSource)).isTrue()
    }

  @Test
  fun process_with16BitPcmMonoInput_returns8ChannelOutputWithMatchingLabels() =
    runBlocking(Dispatchers.Main) {
      assumeDeviceSupported()
      val outputFile = outputDir.newFile()

      val (exportResult, separationResults) =
        runSourceSeparation(MediaItem.fromUri(WAV_TOS_MONO_16BIT.uri), outputFile.path)

      assertThat(exportResult.channelCount).isEqualTo(8)
      assertThat(separationResults).hasSize(4)
      assertThat(separationResults[0].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[0].channelIndices).isEqualTo(intArrayOf(0))
      assertThat(separationResults[1].label).isEqualTo(AUDIO_TYPE_NOISE)
      assertThat(separationResults[1].channelIndices).isEqualTo(intArrayOf(1))
      assertThat(separationResults[2].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[2].channelIndices).isEqualTo(intArrayOf(2))
      assertThat(separationResults[3].label).isEqualTo(AUDIO_TYPE_MUSIC)
      assertThat(separationResults[3].channelIndices).isEqualTo(intArrayOf(3))

      // Use a ByteSource to avoid loading the entire files into memory and OOMing if Truth tries to
      // diff the bytes.
      val expectedByteSource =
        context.resources.assets.asByteSource(GOLDEN_FOLDER + "tos-mono-golden.wav")
      val actualByteSource = Files.asByteSource(outputFile)
      assertThat(actualByteSource.contentEquals(expectedByteSource)).isTrue()
    }

  @Test
  fun process_withFloatPcmMonoInput_returns8ChannelOutputWithMatchingLabels() =
    runBlocking(Dispatchers.Main) {
      assumeDeviceSupported()
      val outputFile = outputDir.newFile()

      val (exportResult, separationResults) =
        runSourceSeparation(
          MediaItem.fromUri(WAV_TOS_MONO_16BIT.uri),
          outputFile.path,
          ToFloatPcmAudioProcessor(),
        )

      assertThat(exportResult.channelCount).isEqualTo(8)
      assertThat(separationResults).hasSize(4)
      assertThat(separationResults[0].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[0].channelIndices).isEqualTo(intArrayOf(0))
      assertThat(separationResults[1].label).isEqualTo(AUDIO_TYPE_NOISE)
      assertThat(separationResults[1].channelIndices).isEqualTo(intArrayOf(1))
      assertThat(separationResults[2].label).isEqualTo(AUDIO_TYPE_SPEECH)
      assertThat(separationResults[2].channelIndices).isEqualTo(intArrayOf(2))
      assertThat(separationResults[3].label).isEqualTo(AUDIO_TYPE_MUSIC)
      assertThat(separationResults[3].channelIndices).isEqualTo(intArrayOf(3))

      // Use a ByteSource to avoid loading the entire files into memory and OOMing if Truth tries to
      // diff the bytes.
      val expectedByteSource =
        context.resources.assets.asByteSource(GOLDEN_FOLDER + "tos-mono-golden.wav")
      val actualByteSource = Files.asByteSource(outputFile)
      assertThat(actualByteSource.contentEquals(expectedByteSource)).isTrue()
    }

  data class TransformationResult(
    val exportResult: ExportResult,
    val separationResults: ImmutableList<SeparationResult>,
  )

  suspend fun runSourceSeparation(
    mediaItem: MediaItem,
    outputPath: String,
    extraAudioProcessor: AudioProcessor? = null,
  ): TransformationResult = coroutineScope {
    val context = getApplicationContext<Context>()
    val transformationResult = CompletableDeferred<TransformationResult>()

    val processor = StudioSoundAudioProcessor.create(context).await()

    val item =
      EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(listOfNotNull(extraAudioProcessor, processor), emptyList()))
        .build()

    val transformer =
      Transformer.Builder(context)
        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
        .setAudioMimeType(MimeTypes.AUDIO_RAW)
        .setMuxerFactory(WavMuxerFactory())
        .addListener(
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
              transformationResult.complete(TransformationResult(result, processor.results!!))
            }

            override fun onError(
              composition: Composition,
              result: ExportResult,
              error: ExportException,
            ) {
              transformationResult.completeExceptionally(error)
            }
          }
        )
        .build()

    withContext(Dispatchers.Main) { transformer.start(item, outputPath) }

    transformationResult.await()
  }

  suspend fun assumeDeviceSupported() {
    assumeTrue(
      "Device not supported by Google Play services StudioSound module",
      StudioSound.getClient(context).isDeviceSupported.await(),
    )
  }

  fun AssetManager.asByteSource(assetPath: String): ByteSource =
    object : ByteSource() {
      override fun openStream(): InputStream = open(assetPath, AssetManager.ACCESS_STREAMING)

      override fun sizeIfKnown(): Optional<Long> {
        try {
          openFd(assetPath).use { afd ->
            val len = afd.length
            if (len != AssetFileDescriptor.UNKNOWN_LENGTH) {
              return Optional.of(len)
            }
          }
        } catch (_: IOException) {
          // Compressed assets in the APK throw on openFd()
        }
        return Optional.absent()
      }

      override fun toString(): String = "AssetByteSource($assetPath)"
    }

  private class WavMuxerFactory : Muxer.Factory {
    @Throws(MuxerException::class)
    override fun create(path: String): Muxer {
      try {
        return WavMuxer(SeekableMuxerOutput.of(path))
      } catch (ex: Exception) {
        throw MuxerException("Failed to create WavMuxer.", ex)
      }
    }

    override fun getSupportedSampleMimeTypes(trackType: @C.TrackType Int): ImmutableList<String> {
      return ImmutableList.of(MimeTypes.AUDIO_RAW)
    }
  }
}
