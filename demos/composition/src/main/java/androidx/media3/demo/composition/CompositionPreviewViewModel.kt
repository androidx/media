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
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as geometrySize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.usToMs
import androidx.media3.demo.composition.MatrixTransformationFactory.createDizzyCropEffect
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.data.ExportState
import androidx.media3.demo.composition.data.Item
import androidx.media3.demo.composition.data.MediaState
import androidx.media3.demo.composition.data.OutputSettingsState
import androidx.media3.demo.composition.data.OverlayAsset
import androidx.media3.demo.composition.data.OverlayState
import androidx.media3.demo.composition.data.PlacedOverlay
import androidx.media3.demo.composition.data.PlacementState
import androidx.media3.demo.composition.effect.DemoRenderingPacketConsumer
import androidx.media3.demo.composition.effect.LottieEffectFactory
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.SingleContextGlObjectsProvider
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
import java.util.concurrent.ExecutorService
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

@OptIn(ExperimentalApi::class)
class CompositionPreviewViewModel(application: Application) : AndroidViewModel(application) {

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState: StateFlow<CompositionPreviewState> = _uiState.asStateFlow()

  var compositionPlayer by mutableStateOf(createCompositionPlayer())
  val EXPORT_ERROR_MESSAGE = application.resources.getString(R.string.export_error)
  val EXPORT_STARTED_MESSAGE = application.resources.getString(R.string.export_started)
  internal var frameConsumerEnabled: Boolean = false
  internal var outputSurface: Surface? = null
  internal val packetConsumerFactory: DemoRenderingPacketConsumer.Factory by lazy {
    DemoRenderingPacketConsumer.Factory(
      glExecutorService,
      errorListener = { e ->
        Log.e(TAG, "FrameConsumer error", e)
        _uiState.update { it.copy(snackbarMessage = "Preview error: $e") }
      },
    )
  }
  private val glExecutorService: ExecutorService by lazy {
    Util.newSingleThreadExecutor("CompositionDemo::GlThread")
  }
  private val glObjectsProvider: GlObjectsProvider by lazy { SingleContextGlObjectsProvider() }
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

  private fun createInitialState(): CompositionPreviewState {
    return CompositionPreviewState(
      availableLayouts = COMPOSITION_LAYOUT,
      compositionLayout = COMPOSITION_LAYOUT[0],
      mediaState = MediaState(),
      overlayState = OverlayState(),
      outputSettingsState =
        OutputSettingsState(
          resolutionHeight = SAME_AS_INPUT_OPTION,
          hdrMode = Composition.HDR_MODE_KEEP_HDR,
          audioMimeType = SAME_AS_INPUT_OPTION,
          videoMimeType = SAME_AS_INPUT_OPTION,
          muxerOption = MUXER_OPTIONS[0],
        ),
      exportState = ExportState(),
    )
  }

  init {
    // Load media items
    val initialMediaItems = mutableListOf<Item>()
    val titles = application.resources.getStringArray(/* id= */ R.array.preset_descriptions)
    val uris = application.resources.getStringArray(/* id= */ R.array.preset_uris)
    val durations = application.resources.getIntArray(/* id= */ R.array.preset_durations)
    for (i in titles.indices) {
      initialMediaItems.add(Item(titles[i], uris[i], durations[i].toLong(), emptySet()))
    }

    // Load drag and drop placeable overlay effects
    val initialPlaceableEffects = mutableListOf<OverlayAsset>()
    val placeableOverlayEffectsNames =
      application.resources.getStringArray(/* id= */ R.array.placeable_effects_names)
    val placeableEffectsUris =
      application.resources.getStringArray(/* id= */ R.array.placeable_effects_uris)

    for (i in placeableOverlayEffectsNames.indices) {
      initialPlaceableEffects.add(
        OverlayAsset(placeableOverlayEffectsNames[i], placeableEffectsUris[i])
      )
    }

    _uiState.update { currentState ->
      currentState.copy(
        mediaState =
          currentState.mediaState.copy(
            availableItems = initialMediaItems,
            selectedItems = List(4) { initialMediaItems[0].copy() },
            availableEffects = effectOptions.keys.toList(),
          ),
        overlayState = currentState.overlayState.copy(availableOverlays = initialPlaceableEffects),
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    releaseAndRecreatePlayer()
    cancelExport()
    exportStopwatch.reset()
  }

  fun onSnackbarMessageShown() {
    _uiState.update { currentState -> currentState.copy(snackbarMessage = null) }
  }

  fun onCompositionLayoutChanged(newLayout: String) {
    _uiState.update { currentState -> currentState.copy(compositionLayout = newLayout) }
    previewComposition()
  }

  fun enableDebugTracing(enable: Boolean) {
    _uiState.update { it.copy(isDebugTracingEnabled = enable) }
    DebugTraceUtil.enableTracing = enable
  }

  fun onFrameConsumerEnabledChanged(isEnabled: Boolean) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(frameConsumerEnabled = isEnabled))
    }
    previewComposition()
  }

  fun onIncludeBackgroundAudioChanged(isEnabled: Boolean) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(includeBackgroundAudio = isEnabled))
    }
  }

  fun onOutputResolutionChanged(resolution: String) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(resolutionHeight = resolution))
    }
  }

  fun onHdrModeChanged(hdrMode: Int) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(hdrMode = hdrMode))
    }
  }

  fun onAudioMimeTypeChanged(mimeType: String) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(audioMimeType = mimeType))
    }
  }

  fun onVideoMimeTypeChanged(mimeType: String) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(videoMimeType = mimeType))
    }
  }

  fun onMuxerOptionChanged(option: String) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(muxerOption = option))
    }
  }

  fun onRenderSizeChanged(newSize: geometrySize) {
    _uiState.update {
      it.copy(outputSettingsState = it.outputSettingsState.copy(renderSize = newSize))
    }
  }

  fun onPlaceNewOverlayClicked(asset: OverlayAsset) {
    if (_uiState.value.overlayState.placementState is PlacementState.Placing) return
    compositionPlayer.pause()
    placeNewOverlay(asset)
  }

  fun placeNewOverlay(asset: OverlayAsset) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val inputStream = getApplication<Application>().assets.open(asset.assetPath)
        val previewBitmap = BitmapFactory.decodeStream(inputStream)
        withContext(Dispatchers.Main) {
          val newOverlay = PlacedOverlay(assetName = asset.name, bitmap = previewBitmap)
          _uiState.update {
            it.copy(
              overlayState =
                it.overlayState.copy(
                  placementState = PlacementState.Placing(newOverlay, Offset.Zero)
                )
            )
          }
        }
      } catch (e: IOException) {
        Log.e(TAG, "Error loading overlay bitmap from assets", e)
        withContext(Dispatchers.Main) {
          _uiState.update { currentState ->
            currentState.copy(snackbarMessage = "Could not load overlay image.")
          }
        }
      }
    }
  }

  fun onPlaceExistingOverlayClicked(overlayId: UUID) {
    if (_uiState.value.overlayState.placementState is PlacementState.Placing) return
    compositionPlayer.pause()

    placeExistingOverlay(overlayId)

    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()
  }

  fun placeExistingOverlay(overlayId: UUID) {
    _uiState.update { currentState ->
      val overlayToEdit = currentState.overlayState.committedOverlays.find { it.id == overlayId }

      if (overlayToEdit == null) {
        return@update currentState
      }

      val newCommittedOverlays =
        currentState.overlayState.committedOverlays.filter { it.id != overlayId }

      val newPlacementState = PlacementState.Placing(overlayToEdit, overlayToEdit.uiTransformOffset)

      currentState.copy(
        overlayState =
          currentState.overlayState.copy(
            committedOverlays = newCommittedOverlays,
            placementState = newPlacementState,
          )
      )
    }
  }

  fun onOverlayDrag(dragAmount: Offset) {
    _uiState.update { currentState ->
      val currentPlacement =
        currentState.overlayState.placementState as? PlacementState.Placing
          ?: return@update currentState

      val overlayBitmap = currentPlacement.overlay.bitmap
      val newOffset = currentPlacement.currentUiTransformOffset + dragAmount
      val clampedX =
        newOffset.x.coerceIn(
          0f,
          currentState.outputSettingsState.renderSize.width - overlayBitmap.width,
        )
      val clampedY =
        newOffset.y.coerceIn(
          0f,
          currentState.outputSettingsState.renderSize.height - overlayBitmap.height,
        )
      val finalOffset = Offset(clampedX, clampedY)

      currentState.copy(
        overlayState =
          currentState.overlayState.copy(
            placementState =
              PlacementState.Placing(
                overlay = currentPlacement.overlay,
                currentUiTransformOffset = finalOffset,
              )
          )
      )
    }
  }

  fun onEndPlacementClicked() {
    endPlacementMode()

    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()

    compositionPlayer.play()
  }

  fun endPlacementMode() {
    _uiState.update { currentState ->
      val currentPlacement =
        currentState.overlayState.placementState as? PlacementState.Placing
          ?: return@update currentState
      val finalOverlay =
        currentPlacement.overlay.copy(
          overlay =
            createBitmapOverlay(
              currentPlacement.overlay.bitmap,
              currentPlacement.currentUiTransformOffset,
              currentState.outputSettingsState.renderSize,
            ),
          uiTransformOffset = currentPlacement.currentUiTransformOffset,
        )
      val newCommittedOverlays = currentState.overlayState.committedOverlays + finalOverlay
      currentState.copy(
        overlayState =
          currentState.overlayState.copy(
            committedOverlays = newCommittedOverlays,
            placementState = PlacementState.Inactive,
          )
      )
    }
  }

  private fun createBitmapOverlay(
    bitmap: Bitmap,
    transformOffset: Offset,
    renderSize: geometrySize,
  ): BitmapOverlay {
    if (renderSize == geometrySize.Zero) {
      return BitmapOverlay.createStaticBitmapOverlay(bitmap)
    }

    // Converts the bitmap's center from UI pixel coordinates (origin at top-left) to the normalized
    // [-1, 1] coordinate space anchors (origin at the center) that the overlay requires.
    val boxCenterXpx = transformOffset.x + (bitmap.width / 2f)
    val boxCenterYpx = transformOffset.y + (bitmap.height / 2f)

    val anchorX = -1f + (boxCenterXpx / renderSize.width) * 2f
    val anchorY = 1f - (boxCenterYpx / renderSize.height) * 2f

    val overlaySettings =
      StaticOverlaySettings.Builder().setBackgroundFrameAnchor(anchorX, anchorY).build()
    return BitmapOverlay.createStaticBitmapOverlay(bitmap, overlaySettings)
  }

  fun removeOverlay(overlayId: UUID) {
    _uiState.update { currentState ->
      val newCommittedOverlays =
        currentState.overlayState.committedOverlays.filter { it.id != overlayId }
      currentState.copy(
        overlayState = currentState.overlayState.copy(committedOverlays = newCommittedOverlays)
      )
    }
    compositionPlayer.setComposition(prepareComposition(), compositionPlayer.currentPosition)
    compositionPlayer.prepare()
  }

  fun addItem(index: Int, showSnackbarMessage: Boolean = true) {
    _uiState.update { currentState ->
      val itemToAdd = currentState.mediaState.availableItems[index].copy()
      val newSelectedItems = currentState.mediaState.selectedItems + itemToAdd
      currentState.copy(
        mediaState = currentState.mediaState.copy(selectedItems = newSelectedItems),
        snackbarMessage =
          if (showSnackbarMessage) "Added item: ${itemToAdd.title}"
          else currentState.snackbarMessage,
      )
    }
  }

  fun removeItem(index: Int) {
    _uiState.update { currentState ->
      val currentItems = currentState.mediaState.selectedItems
      val newSelectedItems = currentItems.filterIndexed { i, _ -> i != index }
      currentState.copy(mediaState = currentState.mediaState.copy(selectedItems = newSelectedItems))
    }
  }

  fun updateEffectsForItem(index: Int, newEffects: Set<String>) {
    _uiState.update { currentState ->
      val currentItems = currentState.mediaState.selectedItems

      val newSelectedItems =
        currentItems.mapIndexed { i, item ->
          if (i == index) {
            item.copy(selectedEffects = newEffects)
          } else {
            item
          }
        }
      currentState.copy(mediaState = currentState.mediaState.copy(selectedItems = newSelectedItems))
    }
  }

  fun previewComposition() {
    releaseAndRecreatePlayer()
    compositionPlayer.setComposition(prepareComposition())
    compositionPlayer.prepare()
    compositionPlayer.play()
  }

  fun exportComposition() {
    // Cancel and clean up files from any ongoing export.
    cancelExport()

    val composition = prepareComposition()
    val settings = uiState.value.outputSettingsState

    try {
      outputFile =
        createExternalCacheFile("composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4")
    } catch (e: IOException) {
      _uiState.update { currentState ->
        currentState.copy(snackbarMessage = "Aborting export! Unable to create output file: $e")
      }
      Log.e(TAG, "Aborting export! Unable to create output file: ", e)
      return
    }
    val filePath = outputFile!!.absolutePath

    val transformerBuilder = Transformer.Builder(/* context= */ getApplication())

    if (SAME_AS_INPUT_OPTION != settings.audioMimeType) {
      transformerBuilder.setAudioMimeType(settings.audioMimeType)
    }

    if (SAME_AS_INPUT_OPTION != settings.videoMimeType) {
      transformerBuilder.setVideoMimeType(settings.videoMimeType)
    }

    when (settings.muxerOption) {
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

              _uiState.update {
                it.copy(
                  exportState = it.exportState.copy(isExporting = false, exportResultInfo = details)
                )
              }

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
              _uiState.update {
                it.copy(
                  snackbarMessage = "Export error: $exportException",
                  exportState =
                    it.exportState.copy(
                      isExporting = false,
                      exportResultInfo = EXPORT_ERROR_MESSAGE,
                    ),
                )
              }
              Log.e(TAG, "Export error", exportException)
              Log.d(TAG, DebugTraceUtil.generateTraceSummary())
            }
          }
        )
        .build()

    _uiState.update {
      it.copy(
        exportState =
          it.exportState.copy(isExporting = true, exportResultInfo = EXPORT_STARTED_MESSAGE)
      )
    }
    exportStopwatch.reset()
    exportStopwatch.start()
    transformer!!.start(composition, filePath)
    Log.i(TAG, "Export started")
  }

  private fun prepareComposition(): Composition {
    val editedMediaItems = mutableListOf<EditedMediaItem>()
    val settings = uiState.value.outputSettingsState

    val globalVideoEffects = mutableListOf<Effect>()
    if (settings.resolutionHeight != SAME_AS_INPUT_OPTION) {
      val resolutionHeight = settings.resolutionHeight.toInt()
      globalVideoEffects.add(
        LanczosResample.scaleToFitWithFlexibleOrientation(10000, resolutionHeight)
      )
      globalVideoEffects.add(Presentation.createForShortSide(resolutionHeight))
    }
    for (item in uiState.value.mediaState.selectedItems) {
      val mediaItem =
        MediaItem.Builder()
          .setUri(item.uri)
          .setImageDurationMs(usToMs(item.durationUs)) // Ignored for audio/video
          .build()
      val effectsForItem = mutableListOf<Effect>()
      for (effectName in item.selectedEffects) {
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
      when (uiState.value.compositionLayout) {
        COMPOSITION_LAYOUT[1] -> 4 // 2x2 Grid
        COMPOSITION_LAYOUT[2] -> 2 // PiP Overlay
        else -> 1 // Sequence
      }
    // TODO(b/417365294): Improve how sequences are built
    val videoSequenceBuilders =
      MutableList(numSequences) {
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
      }
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
    if (settings.includeBackgroundAudio) {
      videoSequences.add(getAudioBackgroundSequence())
    }

    val allOverlays = _uiState.value.overlayState.committedOverlays.map { it.overlay!! }

    val finalVideoEffects = globalVideoEffects.toMutableList()

    if (settings.renderSize != geometrySize.Zero) {
      val presentation =
        Presentation.createForWidthAndHeight(
          settings.renderSize.width.roundToInt(),
          settings.renderSize.height.roundToInt(),
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
      .setHdrMode(settings.hdrMode)
      .build()
  }

  // TODO(b/417362922): Improve accuracy of VideoCompositorSettings implementation
  private fun getVideoCompositorSettings(): VideoCompositorSettings {
    return when (uiState.value.compositionLayout) {
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
    frameConsumerEnabled = uiState.value.outputSettingsState.frameConsumerEnabled
    if (uiState.value.outputSettingsState.frameConsumerEnabled) {
      packetConsumerFactory.setOutputSurface(outputSurface)
      playerBuilder.setPacketConsumerFactory(packetConsumerFactory)
      playerBuilder.setGlThreadExecutorService(glExecutorService)
      playerBuilder.setGlObjectsProvider(glObjectsProvider)
    } else if (uiState.value.compositionLayout != COMPOSITION_LAYOUT[0]) {
      playerBuilder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
    }
    playerBuilder.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
    val player = playerBuilder.build()
    player.addListener(
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          _uiState.update { currentState ->
            currentState.copy(snackbarMessage = "Preview error: $error")
          }
          Log.e(TAG, "Preview error", error)
        }
      }
    )
    return player
  }

  private fun releaseAndRecreatePlayer() {
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
    _uiState.update { it.copy(exportState = ExportState()) }
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
      return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        .addItem(audioItem)
        .setIsLooping(true)
        .build()
    }
  }
}

class CompositionPreviewViewModelFactory(private val application: Application) :
  ViewModelProvider.Factory {

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return CompositionPreviewViewModel(application) as T
  }

  companion object {
    private const val TAG = "CPVMF"
  }
}
