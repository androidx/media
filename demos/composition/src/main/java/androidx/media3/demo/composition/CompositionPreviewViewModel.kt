/*
 * Copyright 2025 The Android Open Source Project
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

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as geometrySize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.usToMs
import androidx.media3.demo.composition.MatrixTransformationFactory.createDizzyCropEffect
import androidx.media3.demo.composition.effect.LottieEffectFactory
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.JsonUtil
import androidx.media3.transformer.Transformer
import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

class CompositionPreviewViewModel(application: Application, val compositionLayout: String) :
  AndroidViewModel(application) {
  data class Item(
    val title: String,
    val uri: String,
    val durationUs: Long,
    var selectedEffects: MutableState<Set<String>>,
  )

  val committedOverlays = mutableStateListOf<PlacedOverlay>()

  var overlayPlacementState by mutableStateOf<PlacementState>(PlacementState.Inactive)
    private set

  var snackbarMessage by mutableStateOf<String?>(null)

  var compositionPlayer by mutableStateOf(createCompositionPlayer())

  var mediaItemOptions = mutableStateListOf<Item>()
  var selectedMediaItems = mutableStateListOf<Item>()

  var includeBackgroundAudioTrack by mutableStateOf(false)
  var outputResolution by mutableStateOf(RESOLUTION_HEIGHTS[0])
  var outputHdrMode by mutableIntStateOf(Composition.HDR_MODE_KEEP_HDR)
  var outputAudioMimeType by mutableStateOf(SAME_AS_INPUT_OPTION)
  var outputVideoMimeType by mutableStateOf(SAME_AS_INPUT_OPTION)
  var muxerOption by mutableStateOf(MUXER_OPTIONS[0])
  var exportResultInformation by mutableStateOf<String?>(null)

  val EXPORT_ERROR_MESSAGE = application.resources.getString(R.string.export_error)
  val EXPORT_STARTED_MESSAGE = application.resources.getString(R.string.export_started)

  private val _enableDebugTracing = MutableStateFlow(false)

  var renderSize by mutableStateOf(geometrySize.Zero)
    private set

  val enableDebugTracing: StateFlow<Boolean> = _enableDebugTracing.asStateFlow()

  private var transformer: Transformer? = null
  private var outputFile: File? = null
  private var exportStopwatch: Stopwatch =
    Stopwatch.createUnstarted(
      object : Ticker() {
        override fun read(): Long {
          return SystemClock.elapsedRealtimeNanos()
        }
      }
    )

  var placeableEffectsOptions = mutableListOf<OverlayAsset>()

  private val effectOptions: Map<String, Effect> by lazy {
    buildMap {
      put(
        application.resources.getString(R.string.effect_name_grayscale),
        RgbFilter.createGrayscaleFilter(),
      )
      put(application.resources.getString(R.string.effect_name_dizzy_crop), createDizzyCropEffect())

      LottieEffectFactory.buildAvailableEffects(getApplication()).forEach { (name, effect) ->
        put(name, effect)
      }
    }
  }

  val availableEffectNames: List<String> = effectOptions.keys.toList()

  init {
    // Load media items
    val titles = application.resources.getStringArray(/* id= */ R.array.preset_descriptions)
    val uris = application.resources.getStringArray(/* id= */ R.array.preset_uris)
    val durations = application.resources.getIntArray(/* id= */ R.array.preset_durations)
    for (i in titles.indices) {
      mediaItemOptions.add(
        Item(titles[i], uris[i], durations[i].toLong(), mutableStateOf(emptySet()))
      )
    }

    // Load drag and drop placeable overlay effects
    val placeableOverlayEffectsNames =
      application.resources.getStringArray(/* id= */ R.array.placeable_effects_names)
    val placeableEffectsUris =
      application.resources.getStringArray(/* id= */ R.array.placeable_effects_uris)

    for (i in placeableOverlayEffectsNames.indices) {
      placeableEffectsOptions.add(
        OverlayAsset(placeableOverlayEffectsNames[i], placeableEffectsUris[i])
      )
    }

    // Load initial media item selections. No need to show the Snackbar message at this point
    addItem(0, showSnackbarMessage = false)
    addItem(0, showSnackbarMessage = false)
    addItem(0, showSnackbarMessage = false)
    addItem(0, showSnackbarMessage = false)
  }

  override fun onCleared() {
    super.onCleared()
    releasePlayer()
    cancelExport()
    exportStopwatch.reset()
  }

  fun enableDebugTracing(enable: Boolean) {
    _enableDebugTracing.update { _ -> enable }
    DebugTraceUtil.enableTracing = enable
  }

  fun onRenderSizeChanged(newSize: geometrySize) {
    renderSize = newSize
  }

  fun onPlaceNewOverlayClicked(asset: OverlayAsset) {
    if (overlayPlacementState !is PlacementState.Inactive) return
    compositionPlayer.pause()
    placeNewOverlay(asset)
  }

  fun placeNewOverlay(asset: OverlayAsset) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val inputStream = getApplication<Application>().assets.open(asset.assetPath)
        val previewBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        withContext(Dispatchers.Main) {
          val newOverlay = PlacedOverlay(assetName = asset.name, bitmap = previewBitmap)
          overlayPlacementState = PlacementState.Placing(newOverlay, UiTransform())
        }
      } catch (e: IOException) {
        Log.e(TAG, "Error loading overlay bitmap from assets", e)
        withContext(Dispatchers.Main) { snackbarMessage = "Could not load overlay image." }
      }
    }
  }

  fun onPlaceExistingOverlayClicked(overlayId: UUID) {
    if (overlayPlacementState !is PlacementState.Inactive) return
    compositionPlayer.pause()

    placeExistingOverlay(overlayId)

    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()
  }

  fun placeExistingOverlay(overlayId: UUID) {
    val overlayToEdit = committedOverlays.find { it.id == overlayId } ?: return
    committedOverlays.remove(overlayToEdit)
    overlayPlacementState = PlacementState.Placing(overlayToEdit, overlayToEdit.uiTransform)
  }

  fun onOverlayDrag(dragAmount: Offset) {
    val currentState = overlayPlacementState as? PlacementState.Placing ?: return
    val newOffset = currentState.currentUiTransform.offset + dragAmount
    val overlayBitmap = currentState.overlay.bitmap
    val clampedX = newOffset.x.coerceIn(0f, renderSize.width - overlayBitmap.width)
    val clampedY = newOffset.y.coerceIn(0f, renderSize.height - overlayBitmap.height)
    val newTransform = currentState.currentUiTransform.copy(offset = Offset(clampedX, clampedY))
    overlayPlacementState = currentState.copy(currentUiTransform = newTransform)
  }

  fun onEndPlacementClicked() {
    endPlacementMode()

    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()

    compositionPlayer.play()
  }

  fun endPlacementMode() {
    val currentState = overlayPlacementState as? PlacementState.Placing ?: return
    val finalTransform = currentState.currentUiTransform
    val finalPlacedObject = currentState.overlay.copy(uiTransform = finalTransform)
    finalPlacedObject.overlay = createBitmapOverlay(finalPlacedObject.bitmap, finalTransform)
    committedOverlays.add(finalPlacedObject)
    overlayPlacementState = PlacementState.Inactive
  }

  private fun createBitmapOverlay(bitmap: Bitmap, transform: UiTransform): BitmapOverlay {
    if (renderSize == geometrySize.Zero) {
      return BitmapOverlay.createStaticBitmapOverlay(bitmap)
    }

    // Converts the bitmap's center from UI pixel coordinates (origin at top-left) to the normalized
    // [-1, 1] coordinate space anchors (origin at the center) that the overlay requires.
    val boxCenterXpx = transform.offset.x + (bitmap.width / 2f)
    val boxCenterYpx = transform.offset.y + (bitmap.height / 2f)

    val anchorX = -1f + (boxCenterXpx / renderSize.width) * 2f
    val anchorY = 1f - (boxCenterYpx / renderSize.height) * 2f

    val overlaySettings =
      StaticOverlaySettings.Builder().setBackgroundFrameAnchor(anchorX, anchorY).build()
    return BitmapOverlay.createStaticBitmapOverlay(bitmap, overlaySettings)
  }

  fun removeOverlay(overlayId: UUID) {
    committedOverlays.removeAll { it.id == overlayId }
    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()
  }

  fun addItem(index: Int, showSnackbarMessage: Boolean = true) {
    selectedMediaItems.add(
      mediaItemOptions[index].copy(selectedEffects = mutableStateOf(emptySet()))
    )
    if (showSnackbarMessage) {
      snackbarMessage = "Added item: ${mediaItemOptions[index].title}"
    }
  }

  fun removeItem(index: Int) {
    selectedMediaItems.removeAt(index)
  }

  fun updateEffectsForItem(index: Int, newEffects: Set<String>) {
    selectedMediaItems[index].selectedEffects.value = newEffects
  }

  fun previewComposition() {
    releasePlayer()
    compositionPlayer.setComposition(prepareComposition())
    compositionPlayer.prepare()
    compositionPlayer.play()
  }

  fun exportComposition() {
    // Cancel and clean up files from any ongoing export.
    cancelExport()

    val composition = prepareComposition()

    try {
      outputFile =
        createExternalCacheFile("composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4")
    } catch (e: IOException) {
      snackbarMessage = "Aborting export! Unable to create output file: $e"
      Log.e(TAG, "Aborting export! Unable to create output file: ", e)
      return
    }
    val filePath = outputFile!!.absolutePath

    val transformerBuilder = Transformer.Builder(/* context= */ getApplication())

    if (SAME_AS_INPUT_OPTION != outputAudioMimeType) {
      transformerBuilder.setAudioMimeType(outputAudioMimeType)
    }

    if (SAME_AS_INPUT_OPTION != outputVideoMimeType) {
      transformerBuilder.setVideoMimeType(outputVideoMimeType)
    }

    when (muxerOption) {
      MUXER_OPTIONS[0] -> {}
      MUXER_OPTIONS[1] -> {
        transformerBuilder.setMuxerFactory(InAppMp4Muxer.Factory())
      }

      MUXER_OPTIONS[2] -> {
        transformerBuilder.setMuxerFactory(InAppFragmentedMp4Muxer.Factory())
      }
    }

    transformer =
      transformerBuilder
        .addListener(
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              exportStopwatch.stop()
              val elapsedTimeMs = exportStopwatch.elapsed(TimeUnit.MILLISECONDS)
              val details =
                getApplication<Application>()
                  .resources
                  .getString(R.string.export_completed, elapsedTimeMs / 1000f, filePath)
              Log.d(TAG, DebugTraceUtil.generateTraceSummary())
              Log.i(TAG, details)
              exportResultInformation = details

              try {
                val resultJson =
                  JsonUtil.exportResultAsJsonObject(exportResult)
                    .put("elapsedTimeMs", elapsedTimeMs)
                    .put("device", JsonUtil.getDeviceDetailsAsJsonObject())
                for (line in Util.split(resultJson.toString(2), "\n")) {
                  Log.i(TAG, line)
                }
              } catch (e: JSONException) {
                Log.w(TAG, "Unable to convert exportResult to JSON", e)
              }
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              exportStopwatch.stop()
              snackbarMessage = "Export error: $exportException"
              Log.e(TAG, "Export error", exportException)
              Log.d(TAG, DebugTraceUtil.generateTraceSummary())
              exportResultInformation = EXPORT_ERROR_MESSAGE
            }
          }
        )
        .build()

    exportResultInformation = EXPORT_STARTED_MESSAGE
    exportStopwatch.reset()
    exportStopwatch.start()
    transformer!!.start(composition, filePath)
    Log.i(TAG, "Export started")
  }

  private fun prepareComposition(): Composition {
    val editedMediaItems = mutableListOf<EditedMediaItem>()

    val globalVideoEffects = mutableListOf<Effect>()
    if (outputResolution != SAME_AS_INPUT_OPTION) {
      val resolutionHeight = outputResolution.toInt()
      globalVideoEffects.add(
        LanczosResample.scaleToFitWithFlexibleOrientation(10000, resolutionHeight)
      )
      globalVideoEffects.add(Presentation.createForShortSide(resolutionHeight))
    }
    for (item in selectedMediaItems) {
      val mediaItem =
        MediaItem.Builder()
          .setUri(item.uri)
          .setImageDurationMs(usToMs(item.durationUs)) // Ignored for audio/video
          .build()
      val effectsForItem = mutableListOf<Effect>()
      for (effectName in item.selectedEffects.value) {
        // TODO(b/433484977): Order of applied effects should be more clear in the UI
        effectOptions[effectName]?.let { effect -> effectsForItem.add(effect) }
      }
      val finalVideoEffects = globalVideoEffects + effectsForItem
      val itemBuilder =
        EditedMediaItem.Builder(mediaItem)
          .setEffects(
            Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ finalVideoEffects)
          )
          // For image inputs. Automatically ignored if input is audio/video.
          .setFrameRate(DEFAULT_FRAME_RATE_FPS)
          // Setting duration explicitly is only required for preview with CompositionPlayer, and
          // is not needed for export with Transformer.
          .setDurationUs(item.durationUs)
      editedMediaItems.add(itemBuilder.build())
    }
    val numSequences =
      when (compositionLayout) {
        COMPOSITION_LAYOUT[1] -> 4 // 2x2 Grid
        COMPOSITION_LAYOUT[2] -> 2 // PiP Overlay
        else -> 1 // Sequence
      }
    // TODO(b/417365294): Improve how sequences are built
    val videoSequenceBuilders = MutableList(numSequences) { EditedMediaItemSequence.Builder() }
    val videoSequences = mutableListOf<EditedMediaItemSequence>()
    for (sequenceIndex in 0 until numSequences) {
      var hasItem = false
      for (item in sequenceIndex until editedMediaItems.size step numSequences) {
        hasItem = true
        Log.d(TAG, "Adding item $item to sequence $sequenceIndex")
        videoSequenceBuilders[sequenceIndex].addItem(editedMediaItems[item])
      }
      if (hasItem) {
        videoSequences.add(videoSequenceBuilders[sequenceIndex].build())
        Log.d(
          TAG,
          "Sequence #$sequenceIndex has ${videoSequences.last().editedMediaItems.size} item(s)",
        )
      }
    }
    if (includeBackgroundAudioTrack) {
      videoSequences.add(getAudioBackgroundSequence())
    }

    val allOverlays = committedOverlays.map { it.overlay!! }

    val finalVideoEffects = globalVideoEffects.toMutableList()

    if (renderSize != geometrySize.Zero) {
      val presentation =
        Presentation.createForWidthAndHeight(
          renderSize.width.roundToInt(),
          renderSize.height.roundToInt(),
          Presentation.LAYOUT_SCALE_TO_FIT,
        )
      finalVideoEffects.add(presentation)
    }

    val overlayEffectList = mutableListOf<Effect>()
    if (allOverlays.isNotEmpty()) {
      val effect = OverlayEffect(allOverlays)
      overlayEffectList.add(effect)
    }

    finalVideoEffects.addAll(overlayEffectList)

    return Composition.Builder(videoSequences)
      .setEffects(
        Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ finalVideoEffects)
      )
      .setVideoCompositorSettings(getVideoCompositorSettings())
      .setHdrMode(outputHdrMode)
      .build()
  }

  // TODO(b/417362922): Improve accuracy of VideoCompositorSettings implementation
  private fun getVideoCompositorSettings(): VideoCompositorSettings {
    return when (compositionLayout) {
      COMPOSITION_LAYOUT[1] -> {
        // 2x2 Grid
        object : VideoCompositorSettings {
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

      COMPOSITION_LAYOUT[2] -> {
        // PiP Overlay
        val cycleTimeUs = 3_000_000f // Time for overlay to complete a cycle in Us

        object : VideoCompositorSettings {
          override fun getOutputSize(inputSizes: List<Size>): Size {
            return inputSizes[0]
          }

          override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            return if (inputId == 0) {
              val cycleRadians = 2 * Math.PI * (presentationTimeUs / cycleTimeUs)
              StaticOverlaySettings.Builder()
                .setScale(0.35f, 0.35f)
                .setOverlayFrameAnchor(0f, 1f) // Top-middle of overlay
                .setBackgroundFrameAnchor(sin(cycleRadians).toFloat() * 0.5f, -0.2f)
                .setRotationDegrees(cos(cycleRadians).toFloat() * -10f)
                .build()
            } else {
              StaticOverlaySettings.Builder().build()
            }
          }
        }
      }

      else -> {
        VideoCompositorSettings.DEFAULT
      }
    }
  }

  private fun createCompositionPlayer(): CompositionPlayer {
    val playerBuilder = CompositionPlayer.Builder(getApplication())
    if (compositionLayout != COMPOSITION_LAYOUT[0]) {
      playerBuilder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
    }
    playerBuilder.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
    val player = playerBuilder.build()
    player.addListener(
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          snackbarMessage = "Preview error: $error"
          Log.e(TAG, "Preview error", error)
        }
      }
    )
    return player
  }

  private fun releasePlayer() {
    compositionPlayer.stop()
    compositionPlayer.release()
    compositionPlayer = createCompositionPlayer()
  }

  /** Cancels any ongoing export operation, and deletes output file contents. */
  private fun cancelExport() {
    transformer?.apply { cancel() }
    transformer = null
    outputFile?.apply { delete() }
    outputFile = null
    exportResultInformation = null
  }

  /**
   * Creates a [File] of the `fileName` in the application cache directory.
   *
   * If a file of that name already exists, it is overwritten.
   */
  // TODO: b/320636291 - Refactor duplicate createExternalCacheFile functions.
  @Throws(IOException::class)
  private fun createExternalCacheFile(fileName: String): File {
    val file = File(getApplication<Application>().externalCacheDir, fileName)
    if (file.exists() && !file.delete()) {
      throw IOException("Could not delete file: " + file.absolutePath)
    }
    if (!file.createNewFile()) {
      throw IOException("Could not create file: " + file.absolutePath)
    }
    return file
  }

  companion object {
    const val SAME_AS_INPUT_OPTION = "same as input"
    const val LAYOUT_EXTRA = "composition_layout"
    private const val TAG = "CompPreviewVM"
    private const val AUDIO_URI = "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
    private const val DEFAULT_FRAME_RATE_FPS = 30
    val HDR_MODE_DESCRIPTIONS =
      mapOf(
        Pair("Keep HDR", Composition.HDR_MODE_KEEP_HDR),
        Pair(
          "MediaCodec tone-map HDR to SDR",
          Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
        ),
        Pair("OpenGL tone-map HDR to SDR", Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL),
        Pair(
          "Force Interpret HDR as SDR",
          Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
        ),
      )
    val RESOLUTION_HEIGHTS =
      listOf(SAME_AS_INPUT_OPTION, "144", "240", "360", "480", "720", "1080", "1440", "2160")
    val MUXER_OPTIONS =
      listOf("Use Platform MediaMuxer", "Use Media3 Mp4Muxer", "Use Media3 FragmentedMp4Muxer")
    val COMPOSITION_LAYOUT = listOf("Sequential", "2x2 grid", "PiP overlay")

    fun getAudioBackgroundSequence(): EditedMediaItemSequence {
      val audioMediaItem: MediaItem = MediaItem.Builder().setUri(AUDIO_URI).build()
      val audioItem = EditedMediaItem.Builder(audioMediaItem).setDurationUs(59_000_000).build()
      return EditedMediaItemSequence.Builder(audioItem).setIsLooping(true).build()
    }
  }
}

class CompositionPreviewViewModelFactory(
  private val application: Application,
  private val compositionLayout: String,
) : ViewModelProvider.Factory {
  init {
    Log.d(TAG, "Creating ViewModel with $compositionLayout")
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return CompositionPreviewViewModel(application, compositionLayout) as T
  }

  companion object {
    private const val TAG = "CPVMF"
  }
}
