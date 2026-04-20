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
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size as geometrySize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
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
import androidx.media3.demo.composition.data.Preset
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.ndk.HardwareBufferJni
import androidx.media3.effect.ndk.NdkCompositionPlayerBuilder
import androidx.media3.effect.ndk.NdkTransformerBuilder
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionFrameMetadata
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
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

@OptIn(ExperimentalApi::class)
class CompositionPreviewViewModel(application: Application) : AndroidViewModel(application) {

  val compositionLayouts = listOf(Preset.SEQUENCE, Preset.GRID, Preset.PIP)

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState: StateFlow<CompositionPreviewState> = _uiState.asStateFlow()

  var compositionPlayer by mutableStateOf(createCompositionPlayer())
  val EXPORT_ERROR_MESSAGE = application.resources.getString(R.string.export_error)
  val EXPORT_STARTED_MESSAGE = application.resources.getString(R.string.export_started)
  val FAILED_LOAD_MEDIA_MESSAGE = application.resources.getString(R.string.failed_load_media)
  val FAILED_GET_DURATION_MESSAGE = application.resources.getString(R.string.failed_get_duration)
  val API_33_REQUIRED_MESSAGE =
    application.resources.getString(R.string.api_33_required_packet_consumer)
  internal var frameConsumerEnabled: Boolean = false
  internal var surfaceView: SurfaceView? = null
  private var transformer: Transformer? = null
  private var outputFile: File? = null
  private var preparedComposition: Composition? = null
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
    }
  }

  private fun createInitialState(): CompositionPreviewState {
    return CompositionPreviewState(
      sequenceTrackTypes = listOf(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)),
      mediaState = MediaState(selectedItemsBySequence = listOf(emptyList())),
      outputSettingsState =
        OutputSettingsState(
          resolutionHeight = SAME_AS_INPUT_OPTION,
          hdrMode = Composition.HDR_MODE_KEEP_HDR,
          audioMimeType = SAME_AS_INPUT_OPTION,
          videoMimeType = SAME_AS_INPUT_OPTION,
          muxerOption = MUXER_OPTIONS[0],
        ),
      exportState = ExportState(),
      selectedPreset = Preset.SEQUENCE,
      selectedSequenceIndex = 0,
    )
  }

  init {
    // Load media items
    val initialMediaItems = mutableListOf<Item>()
    val titles = application.resources.getStringArray(/* id= */ R.array.preset_descriptions)
    val uris = application.resources.getStringArray(/* id= */ R.array.preset_uris)
    val durations = application.resources.getIntArray(/* id= */ R.array.preset_durations)
    for (i in titles.indices) {
      initialMediaItems.add(
        Item(
          title = titles[i],
          uri = uris[i],
          durationUs = durations[i].toLong(),
          selectedEffects = emptySet(),
        )
      )
    }
    initialMediaItems.add(
      Item(
        title = application.resources.getString(R.string.background_audio_track),
        uri = AUDIO_URI,
        durationUs = 59_000_000L,
        selectedEffects = emptySet(),
      )
    )

    _uiState.update { currentState ->
      val numSequences = 1
      currentState.copy(
        mediaState =
          currentState.mediaState.copy(
            availableItems = initialMediaItems,
            selectedItemsBySequence =
              List(numSequences) { List(4) { initialMediaItems[0].copy() } },
            availableEffects = effectOptions.keys.toList(),
          )
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

  fun onPresetSelected(preset: Preset) {
    _uiState.update { currentState ->
      val numSequences =
        when (preset) {
          Preset.GRID -> 4 // 2x2 Grid
          Preset.PIP -> 2 // PiP Overlay
          else -> 1 // Sequence
        }
      val currentTrackTypes = currentState.sequenceTrackTypes
      val newTrackTypes =
        List(numSequences) { i ->
          if (i < currentTrackTypes.size) currentTrackTypes[i]
          else setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        }
      val firstItem = currentState.mediaState.availableItems.firstOrNull()?.copy()
      val newItemsBySequence: List<List<Item>> =
        when (preset) {
          Preset.GRID -> {
            List(4) { if (firstItem != null) listOf(firstItem.copy()) else emptyList<Item>() }
          }
          Preset.PIP -> {
            List(2) {
              if (firstItem != null) listOf(firstItem.copy(), firstItem.copy())
              else emptyList<Item>()
            }
          }
          Preset.SEQUENCE -> {
            List(1) { if (firstItem != null) List(4) { firstItem.copy() } else emptyList<Item>() }
          }
          Preset.CUSTOM -> {
            val currentItemsBySequence = currentState.mediaState.selectedItemsBySequence
            List(numSequences) { i -> currentItemsBySequence[i] }
          }
        }
      currentState.copy(
        sequenceTrackTypes = newTrackTypes,
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = newItemsBySequence),
        isCompositionSet = false,
        selectedPreset = preset,
        selectedSequenceIndex = 0,
      )
    }
    setComposition()
  }

  fun onSequenceTrackTypeChanged(sequenceIndex: Int, newTrackTypes: Set<Int>) {
    _uiState.update { currentState ->
      val updatedTrackTypes = currentState.sequenceTrackTypes.toMutableList()
      if (sequenceIndex < updatedTrackTypes.size) {
        updatedTrackTypes[sequenceIndex] = newTrackTypes
      }
      currentState.copy(
        sequenceTrackTypes = updatedTrackTypes,
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
      )
    }
  }

  fun addSequence() {
    _uiState.update { currentState ->
      val newTrackTypes =
        currentState.sequenceTrackTypes + listOf(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
      val firstItem = currentState.mediaState.availableItems.firstOrNull()?.copy()
      val newSequenceItems = if (firstItem != null) listOf(firstItem) else emptyList()
      val newItemsBySequence =
        currentState.mediaState.selectedItemsBySequence + listOf(newSequenceItems)
      currentState.copy(
        sequenceTrackTypes = newTrackTypes,
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = newItemsBySequence),
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
        selectedSequenceIndex = newTrackTypes.size - 1,
      )
    }
  }

  fun removeSequence(sequenceIndex: Int) {
    _uiState.update { currentState ->
      val currentTrackTypes = currentState.sequenceTrackTypes
      val currentItemsBySequence = currentState.mediaState.selectedItemsBySequence
      if (currentTrackTypes.size <= 1) return@update currentState // Keep at least one sequence

      val newTrackTypes = currentTrackTypes.filterIndexed { index, _ -> index != sequenceIndex }
      val newItemsBySequence = currentItemsBySequence.filterIndexed { index, _ ->
        index != sequenceIndex
      }

      val newSelectedSequenceIndex =
        if (currentState.selectedSequenceIndex >= newTrackTypes.size) {
          (newTrackTypes.size - 1).coerceAtLeast(0)
        } else {
          currentState.selectedSequenceIndex
        }

      currentState.copy(
        sequenceTrackTypes = newTrackTypes,
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = newItemsBySequence),
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
        selectedSequenceIndex = newSelectedSequenceIndex,
      )
    }
  }

  fun onSequenceSelected(index: Int) {
    _uiState.update { it.copy(selectedSequenceIndex = index) }
  }

  fun enableDebugTracing(enable: Boolean) {
    _uiState.update { it.copy(isDebugTracingEnabled = enable, isCompositionSet = false) }
    DebugTraceUtil.enableTracing = enable
  }

  fun onFrameConsumerEnabledChanged(isEnabled: Boolean) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(frameConsumerEnabled = isEnabled),
        isCompositionSet = false,
      )
    }
  }

  fun onIncludeBackgroundAudioChanged(isEnabled: Boolean) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(includeBackgroundAudio = isEnabled),
        isCompositionSet = false,
      )
    }
  }

  fun onOutputResolutionChanged(resolution: String) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(resolutionHeight = resolution),
        isCompositionSet = false,
      )
    }
  }

  fun onHdrModeChanged(hdrMode: Int) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(hdrMode = hdrMode),
        isCompositionSet = false,
      )
    }
  }

  fun onAudioMimeTypeChanged(mimeType: String) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(audioMimeType = mimeType),
        isCompositionSet = false,
      )
    }
  }

  fun onVideoMimeTypeChanged(mimeType: String) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(videoMimeType = mimeType),
        isCompositionSet = false,
      )
    }
  }

  fun onMuxerOptionChanged(option: String) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(muxerOption = option),
        isCompositionSet = false,
      )
    }
  }

  fun onRenderSizeChanged(newSize: geometrySize) {
    _uiState.update {
      it.copy(
        outputSettingsState = it.outputSettingsState.copy(renderSize = newSize),
        isCompositionSet = false,
      )
    }
  }

  fun addItem(sequenceIndex: Int, itemIndex: Int) {
    _uiState.update { currentState ->
      val itemToAdd = currentState.mediaState.availableItems[itemIndex].copy()
      val currentItemsBySequence = currentState.mediaState.selectedItemsBySequence.toMutableList()
      if (sequenceIndex < currentItemsBySequence.size) {
        val updatedItems = currentItemsBySequence[sequenceIndex].toMutableList()
        updatedItems.add(itemToAdd)
        currentItemsBySequence[sequenceIndex] = updatedItems
      }
      currentState.copy(
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = currentItemsBySequence),
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
      )
    }
  }

  /**
   * Adds a local item represented by [uri] to the specified sequence.
   *
   * This method extracts the [display name][OpenableColumns.DISPLAY_NAME] of the file, and then
   * uses [MetadataRetriever] to extract the file type and duration.
   */
  fun addLocalItem(sequenceIndex: Int, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      // Use last path segment as default value if content resolver can't get one.
      var displayName = uri.lastPathSegment!!

      // Try to get display name.
      try {
        context.contentResolver
          .query(
            uri,
            /* projection= */ arrayOf(OpenableColumns.DISPLAY_NAME),
            /* selection= */ null,
            /* selectionArgs= */ null,
            /* sortOrder= */ null,
          )
          ?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
              displayName = cursor.getString(nameIndex)
            }
          }
      } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Failed to get display name", e)
      }

      // Default duration for images.
      var durationUs = DEFAULT_IMAGE_DURATION_US
      val mediaItem = MediaItem.fromUri(uri)
      try {
        // Try to get file type and duration.
        MetadataRetriever.Builder(context, mediaItem).build().use {
          val trackGroups = it.retrieveTrackGroups().await()
          // Don't retrieve duration for images.
          if (trackGroups.length > 0 && trackGroups[0].type != C.TRACK_TYPE_IMAGE) {
            durationUs = it.retrieveDurationUs().await()
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to retrieve metadata", e)
        withContext(Dispatchers.Main) {
          _uiState.update { currentState ->
            currentState.copy(snackbarMessage = FAILED_LOAD_MEDIA_MESSAGE)
          }
        }
        return@launch
      }
      if (durationUs == C.TIME_UNSET) {
        _uiState.update { currentState ->
          currentState.copy(snackbarMessage = FAILED_GET_DURATION_MESSAGE)
        }
        return@launch
      }

      val newItem =
        Item(
          title = displayName,
          uri = uri.toString(),
          durationUs = durationUs,
          selectedEffects = emptySet(),
        )

      withContext(Dispatchers.Main) {
        _uiState.update { currentState ->
          val currentItemsBySequence =
            currentState.mediaState.selectedItemsBySequence.toMutableList()
          if (sequenceIndex < currentItemsBySequence.size) {
            val updatedItems = currentItemsBySequence[sequenceIndex].toMutableList()
            updatedItems.add(newItem)
            currentItemsBySequence[sequenceIndex] = updatedItems
          }
          currentState.copy(
            mediaState =
              currentState.mediaState.copy(selectedItemsBySequence = currentItemsBySequence),
            isCompositionSet = false,
            selectedPreset = Preset.CUSTOM,
          )
        }
      }
    }
  }

  fun removeItem(sequenceIndex: Int, itemIndex: Int) {
    _uiState.update { currentState ->
      val currentItemsBySequence = currentState.mediaState.selectedItemsBySequence.toMutableList()
      if (sequenceIndex < currentItemsBySequence.size) {
        val updatedItems = currentItemsBySequence[sequenceIndex].toMutableList()
        if (itemIndex < updatedItems.size) {
          updatedItems.removeAt(itemIndex)
          currentItemsBySequence[sequenceIndex] = updatedItems
        }
      }
      currentState.copy(
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = currentItemsBySequence),
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
      )
    }
  }

  fun updateEffectsForItem(sequenceIndex: Int, itemIndex: Int, newEffects: Set<String>) {
    _uiState.update { currentState ->
      val currentItemsBySequence = currentState.mediaState.selectedItemsBySequence.toMutableList()
      if (sequenceIndex < currentItemsBySequence.size) {
        val updatedItems = currentItemsBySequence[sequenceIndex].toMutableList()
        if (itemIndex < updatedItems.size) {
          updatedItems[itemIndex] = updatedItems[itemIndex].copy(selectedEffects = newEffects)
          currentItemsBySequence[sequenceIndex] = updatedItems
        }
      }
      currentState.copy(
        mediaState = currentState.mediaState.copy(selectedItemsBySequence = currentItemsBySequence),
        isCompositionSet = false,
        selectedPreset = Preset.CUSTOM,
      )
    }
  }

  fun setComposition() {
    releaseAndRecreatePlayer()
    val composition = prepareComposition()
    preparedComposition = composition
    compositionPlayer.setComposition(composition)
    compositionPlayer.prepare()
    _uiState.update { it.copy(isCompositionSet = true) }
  }

  fun play() {
    compositionPlayer.play()
  }

  fun exportComposition() {
    // Cancel and clean up files from any ongoing export.
    cancelExport()

    val composition =
      if (uiState.value.isCompositionSet) preparedComposition ?: prepareComposition()
      else prepareComposition()
    val settings = uiState.value.outputSettingsState

    try {
      outputFile =
        createExternalCacheFile("composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4")
    } catch (e: IOException) {
      _uiState.update { currentState ->
        currentState.copy(
          snackbarMessage =
            getApplication<Application>()
              .resources
              .getString(R.string.abort_export_output_file, e.toString())
        )
      }
      Log.e(TAG, "Aborting export! Unable to create output file: ", e)
      return
    }
    val filePath = outputFile!!.absolutePath

    val transformerBuilder =
      if (uiState.value.outputSettingsState.frameConsumerEnabled) {
        if (SDK_INT < 33) {
          _uiState.update {
            it.copy(snackbarMessage = API_33_REQUIRED_MESSAGE, isCompositionSet = false)
          }
          return
        }
        NdkTransformerBuilder.create(getApplication())
          .setHardwareBufferEffectsPipeline(
            DefaultHardwareBufferEffectsPipeline.create(
              getApplication(),
              hardwareBufferJniWrapper = HardwareBufferJni,
              overlaySettingsProvider = CompositionPreviewViewModel::getOverlaySettings,
            )
          )
      } else {
        Transformer.Builder(/* context= */ getApplication())
      }

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
                  snackbarMessage =
                    getApplication<Application>()
                      .resources
                      .getString(R.string.export_error_msg, exportException.toString()),
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
    val settings = uiState.value.outputSettingsState
    var compositionHasVideo = false

    val globalVideoEffects = mutableListOf<Effect>()
    if (settings.resolutionHeight != SAME_AS_INPUT_OPTION) {
      val resolutionHeight = settings.resolutionHeight.toInt()
      globalVideoEffects.add(
        LanczosResample.scaleToFitWithFlexibleOrientation(10000, resolutionHeight)
      )
      globalVideoEffects.add(Presentation.createForShortSide(resolutionHeight))
    }

    val explicitTrackTypes = uiState.value.sequenceTrackTypes
    val numSequences = explicitTrackTypes.size
    val itemsBySequence = uiState.value.mediaState.selectedItemsBySequence

    val sequences = mutableListOf<EditedMediaItemSequence>()
    for (sequenceIndex in 0 until numSequences) {
      val sequenceItems =
        if (sequenceIndex < itemsBySequence.size) itemsBySequence[sequenceIndex] else emptyList()
      val trackTypes = explicitTrackTypes[sequenceIndex]
      if (trackTypes.contains(C.TRACK_TYPE_VIDEO)) {
        compositionHasVideo = true
      }

      val editedMediaItems = mutableListOf<EditedMediaItem>()

      for (item in sequenceItems) {
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
            // Required for image inputs. For video inputs, it sets the target FPS.
            .setFrameRate(DEFAULT_FRAME_RATE_FPS)
            // Setting duration explicitly is only required for preview with CompositionPlayer, and
            // is not needed for export with Transformer.
            .setDurationUs(item.durationUs)
        editedMediaItems.add(itemBuilder.build())
      }

      val sequenceBuilder = EditedMediaItemSequence.Builder(trackTypes)
      for (editedItem in editedMediaItems) {
        sequenceBuilder.addItem(editedItem)
      }
      sequences.add(sequenceBuilder.build())
    }

    if (settings.includeBackgroundAudio) {
      sequences.add(getAudioBackgroundSequence())
    }

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

    val compositionBuilder = Composition.Builder(sequences).setHdrMode(settings.hdrMode)

    if (compositionHasVideo) {
      compositionBuilder
        .setEffects(
          Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ finalVideoEffects)
        )
        .setVideoCompositorSettings(getVideoCompositorSettings())
    }

    return compositionBuilder.build()
  }

  // TODO(b/417362922): Improve accuracy of VideoCompositorSettings implementation
  private fun getVideoCompositorSettings(): VideoCompositorSettings {
    val videoSequenceIndices =
      uiState.value.sequenceTrackTypes.mapIndexedNotNull { index, trackTypes ->
        if (trackTypes.contains(C.TRACK_TYPE_VIDEO)) index else null
      }
    val numVideoSequences = videoSequenceIndices.size

    val mapInputId = { inputId: Int -> videoSequenceIndices.indexOf(inputId) }

    if (numVideoSequences >= 3) {
      return object : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>): Size {
          return inputSizes[0]
        }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
          val videoInputIndex = mapInputId(inputId)
          if (videoInputIndex == -1) return StaticOverlaySettings.Builder().build()

          val cols = ceil(sqrt(numVideoSequences.toDouble())).toInt()
          val rows = ceil(numVideoSequences.toDouble() / cols).toInt()

          val colIndex = videoInputIndex % cols
          val rowIndex = videoInputIndex / cols

          val scaleX = 1.0f / cols
          val scaleY = 1.0f / rows

          // x center in [-1, 1]
          val anchorX = -1f + (colIndex + 0.5f) * (2.0f / cols)
          // y center in [-1, 1], with 1 at top
          val anchorY = 1f - (rowIndex + 0.5f) * (2.0f / rows)

          return StaticOverlaySettings.Builder()
            .setScale(scaleX, scaleY)
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(anchorX, anchorY)
            .build()
        }
      }
    } else if (numVideoSequences == 2) {
      // PiP Overlay
      val cycleTimeUs = 3_000_000f // Time for overlay to complete a cycle in Us

      return object : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>): Size {
          return inputSizes[0]
        }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
          val videoInputIndex = mapInputId(inputId)

          return if (videoInputIndex == 0) {
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

    return VideoCompositorSettings.DEFAULT
  }

  private fun createCompositionPlayer(): CompositionPlayer {
    val playerBuilder: CompositionPlayer.Builder
    frameConsumerEnabled = uiState.value.outputSettingsState.frameConsumerEnabled
    if (uiState.value.outputSettingsState.frameConsumerEnabled && SDK_INT >= 28) {
      playerBuilder = NdkCompositionPlayerBuilder.create(getApplication())
      playerBuilder.setHardwareBufferEffectsPipeline(
        DefaultHardwareBufferEffectsPipeline.create(
          getApplication(),
          hardwareBufferJniWrapper = HardwareBufferJni,
          overlaySettingsProvider = CompositionPreviewViewModel::getOverlaySettings,
        )
      )
    } else {
      playerBuilder = CompositionPlayer.Builder(getApplication())
      if (uiState.value.sequenceTrackTypes.size > 1) {
        playerBuilder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
      }
    }
    playerBuilder.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
    val player = playerBuilder.build()
    player.addListener(
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          _uiState.update { currentState ->
            currentState.copy(
              snackbarMessage =
                getApplication<Application>()
                  .resources
                  .getString(R.string.preview_error_msg, error.toString())
            )
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
    private const val DEFAULT_IMAGE_DURATION_US = 1_000_000L
    val MEDIA_TYPES = arrayOf("video/*", "image/*", "audio/*")
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

    fun getAudioBackgroundSequence(): EditedMediaItemSequence {
      val audioMediaItem: MediaItem = MediaItem.Builder().setUri(AUDIO_URI).build()
      val audioItem = EditedMediaItem.Builder(audioMediaItem).setDurationUs(59_000_000).build()
      return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        .addItem(audioItem)
        .setIsLooping(true)
        .build()
    }

    fun getOverlaySettings(frame: HardwareBufferFrame): OverlaySettings {
      val metadata =
        (frame.metadata as? CompositionFrameMetadata) ?: throw IllegalArgumentException()
      return metadata.composition.videoCompositorSettings.getOverlaySettings(
        metadata.sequenceIndex,
        frame.presentationTimeUs,
      )
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
