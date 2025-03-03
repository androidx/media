package androidx.media3.demo.composition

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.OverlaySettings
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import androidx.media3.demo.composition.MatrixTransformationFactory.createDizzyCropEffect
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.Presentation
import androidx.media3.effect.PreviewingMultipleInputVideoGraph
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class CompositionPreviewViewModel(application: Application, val compositionLayout: String) : AndroidViewModel(application) {
    var compositionPlayer by mutableStateOf<CompositionPlayer>(createCompositionPlayer())
    var transformer: Transformer? = null
    var outputFile: File? = null
    var exportStopwatch: Stopwatch = Stopwatch.createUnstarted(object : Ticker() {
        override fun read(): Long {
            return SystemClock.elapsedRealtimeNanos()
        }
    })
    var mediaItemTitles = mutableStateListOf<String>()
    var selectedMediaItems = mutableStateListOf<Boolean>() // TODO(nevmital): Update this to a list of indices, which can support reordering and duplicates
    var selectedMediaTitles = mutableStateListOf<String>()
    var selectedMediaEffects = mutableStateListOf<Boolean>()
    //var applyVideoEffects by mutableStateOf<Boolean>(false)
    var includeBackgroundAudioTrack by mutableStateOf<Boolean>(false)
    var outputResolution by mutableStateOf<String>(RESOLUTION_HEIGHTS[0])
    var outputHdrMode by mutableIntStateOf(Composition.HDR_MODE_KEEP_HDR)
    var outputAudioMimeType by mutableStateOf<String>(SAME_AS_INPUT_OPTION)
    var outputVideoMimeType by mutableStateOf<String>(SAME_AS_INPUT_OPTION)
    var muxerOption by mutableStateOf<String>(MUXER_OPTIONS[0])
    var exportResultInformation by mutableStateOf<String?>(null)

    private val _enableDebugTracing = MutableStateFlow(false)
    val enableDebugTracing: StateFlow<Boolean> = _enableDebugTracing.asStateFlow()

    var toastMessage = MutableLiveData<String?>(null)

    private val individualVideoEffects = listOf<Effect>(createDizzyCropEffect(), RgbFilter.createGrayscaleFilter())

    val mediaItemUris = application.resources.getStringArray(/* id= */R.array.preset_uris)
    val mediaItemDurationsUs = application.resources.getIntArray(/* id= */R.array.preset_durations)
    val EXPORT_ERROR_MESSAGE = application.resources.getString(R.string.export_error)
    val EXPORT_STARTED_MESSAGE = application.resources.getString(R.string.export_started)

    init {
        // Load media item titles
        mediaItemTitles.addAll(application.resources.getStringArray(R.array.preset_descriptions))
        // Load initial media item selections
        selectedMediaItems.addAll(BooleanArray(mediaItemTitles.size).toList())
        selectedMediaItems[0] = true
        selectedMediaItems[2] = true
        // Load initial selected media item titles
        updateSelectedItems(selectedMediaItems)
    }

    fun enableDebugTracing(enable: Boolean) {
        _enableDebugTracing.update { _ -> enable }
        DebugTraceUtil.enableTracing = enable
    }

    fun updateSelectedItems(selectedItems: List<Boolean>) {
        selectedMediaTitles.clear()
        selectedMediaEffects.clear()
        selectedItems.forEachIndexed { index, isSelected ->
            selectedMediaItems[index] = isSelected
            if(isSelected) {
                selectedMediaTitles.add(mediaItemTitles[index])
                selectedMediaEffects.add(false)
            }
        }
    }

    fun updateEffects(index: Int, checked: Boolean) {
        selectedMediaEffects[index] = checked
    }

    fun prepareComposition(): Composition {
        val mediaItems = mutableListOf<EditedMediaItem>()

        val universalVideoEffects = mutableListOf<Effect>()
        if (SAME_AS_INPUT_OPTION != outputResolution) {
            val resolutionHeight = outputResolution.toInt()
            universalVideoEffects.add(LanczosResample.scaleToFit(10000, resolutionHeight))
            universalVideoEffects.add(Presentation.createForHeight(resolutionHeight))
        }
        // Preview requires all sequences to be the same duration, so calculate main sequence duration
        // and limit background sequence duration to match.
        var videoSequenceDurationUs = 0L
        for (i in selectedMediaItems.indices) {
            if (selectedMediaItems[i]) {
                val mediaItem = MediaItem.Builder().setUri(mediaItemUris[i])
                    .setImageDurationMs(Util.usToMs(mediaItemDurationsUs[i].toLong())) // Ignored for audio/video
                    .build()
                val applyEffects = selectedMediaEffects[selectedMediaTitles.indexOf(mediaItemTitles[i])] // TODO(nevmital): Remove later, only needed temporarily
                val allVideoEffects = universalVideoEffects + if (applyEffects) individualVideoEffects else emptyList()
                val itemBuilder = EditedMediaItem.Builder(mediaItem).setEffects(
                    Effects(
                        /* audioProcessors= */emptyList(),
                        /* videoEffects= */allVideoEffects
                    )
                ).setDurationUs(mediaItemDurationsUs[i].toLong())
                videoSequenceDurationUs += mediaItemDurationsUs[i].toLong()
                mediaItems.add(itemBuilder.build())
            }
        }
        val numSequences = when (compositionLayout) {
            COMPOSITION_LAYOUT[1] -> 4 // 2x2 Grid
            COMPOSITION_LAYOUT[2] -> 2 // PiP Overlay
            else -> 1 // Sequence
        }
        val videoSequenceBuilders = MutableList<EditedMediaItemSequence.Builder>(numSequences) { _ -> EditedMediaItemSequence.Builder() }
        val videoSequences = mutableListOf<EditedMediaItemSequence>()
        for(sequence in 0..<numSequences) {
            var hasItem = false
            for(item in sequence..<mediaItems.size step numSequences) {
                hasItem = true
                Log.d(TAG, "Adding item $item to sequence $sequence")
                videoSequenceBuilders[sequence].addItem(mediaItems[item])
            }
            if(hasItem) {
                videoSequences.add(videoSequenceBuilders[sequence].build())
                Log.d(TAG, "Sequence #$sequence has ${videoSequences.last().editedMediaItems.size} item(s)")
            }
        }
        if (includeBackgroundAudioTrack) {
            videoSequences.add(getAudioBackgroundSequence(Util.usToMs(videoSequenceDurationUs)))
        }
        // TODO(nevmital): Do we want a checkbox for this AudioProcessor?
        val sampleRateChanger = SonicAudioProcessor()
        sampleRateChanger.setOutputSampleRateHz(8000)
        return Composition.Builder(videoSequences)
            .setEffects(
                    Effects( /* audioProcessors= */
                        listOf(sampleRateChanger),  /* videoEffects= */
                        emptyList()
                    )
                )
            .setVideoCompositorSettings(getVideoCompositorSettings())
            .setHdrMode(outputHdrMode)
            .build()
    }

    fun getAudioBackgroundSequence(durationMs: Long): EditedMediaItemSequence {
        val audioMediaItem: MediaItem =
            MediaItem.Builder().setUri(AUDIO_URI).setClippingConfiguration(
                ClippingConfiguration.Builder().setStartPositionMs(0)
                    .setEndPositionMs(durationMs).build()
            ).build()
        val audioItem = EditedMediaItem.Builder(audioMediaItem).setDurationUs(durationMs).build()
        return EditedMediaItemSequence.Builder(audioItem).build()
    }

    fun getVideoCompositorSettings() : VideoCompositorSettings {
        return when(compositionLayout) {
            COMPOSITION_LAYOUT[1] -> {
                // 2x2 Grid
                object : VideoCompositorSettings {
                    override fun getOutputSize(inputSizes: List<Size>): Size {
                        return inputSizes[0]
                    }

                    override fun getOverlaySettings(
                        inputId: Int,
                        presentationTimeUs: Long
                    ): OverlaySettings {
                        return when(inputId) {
                            0 -> {
                                StaticOverlaySettings.Builder()
                                    .setScale(0.25f, 0.25f)
                                    .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
                                    .setBackgroundFrameAnchor(-0.5f, 0.5f) // Top-left section of background
                                    .build()
                            }
                            1 -> {
                                StaticOverlaySettings.Builder()
                                    .setScale(0.25f, 0.25f)
                                    .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
                                    .setBackgroundFrameAnchor(0.5f, 0.5f) // Top-right section of background
                                    .build()
                            }
                            2 -> {
                                StaticOverlaySettings.Builder()
                                    .setScale(0.25f, 0.25f)
                                    .setOverlayFrameAnchor(0f, 0f) // Middle of overlay
                                    .setBackgroundFrameAnchor(-0.5f, -0.5f) // Bottom-left section of background
                                    .build()
                            }
                            3 -> {
                                StaticOverlaySettings.Builder()
                                    .setScale(0.25f, 0.25f)
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

                    override fun getOverlaySettings(
                        inputId: Int,
                        presentationTimeUs: Long
                    ): OverlaySettings {
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

    fun previewComposition() {
        releasePlayer()
        val composition = prepareComposition()

        compositionPlayer.setComposition(composition)
        compositionPlayer.prepare()
        compositionPlayer.play()
    }

    fun exportComposition() {
        // Cancel and clean up files from any ongoing export.
        cancelExport()

        val composition = prepareComposition()

        try {
            outputFile = createExternalCacheFile(
                "composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4"
            )
        } catch (e: IOException) {
            toastMessage.value = "Aborting export! Unable to create output file: $e"
            Log.e(TAG, "Aborting export! Unable to create output file: ", e)
            return
        }
        val filePath = outputFile!!.absolutePath

        val transformerBuilder = Transformer.Builder( /* context= */getApplication())

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

        transformer = transformerBuilder.addListener(object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition, exportResult: ExportResult
            ) {
                exportStopwatch.stop()
                val elapsedTimeMs = exportStopwatch.elapsed(TimeUnit.MILLISECONDS)
                val details = getApplication<Application>().resources.getString(
                    R.string.export_completed, elapsedTimeMs / 1000f, filePath
                )
                Log.d(TAG, DebugTraceUtil.generateTraceSummary())
                Log.i(TAG, details)
                exportResultInformation = details

                try {
                    val resultJson = JsonUtil.exportResultAsJsonObject(exportResult)
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
                exportException: ExportException
            ) {
                exportStopwatch.stop()
                toastMessage.value = "Export error: $exportException"
                Log.e(TAG, "Export error", exportException)
                Log.d(TAG, DebugTraceUtil.generateTraceSummary())
                exportResultInformation = EXPORT_ERROR_MESSAGE
            }
        }).build()

        exportResultInformation = EXPORT_STARTED_MESSAGE
        exportStopwatch.reset()
        exportStopwatch.start()
        transformer!!.start(composition, filePath)
        Log.i(TAG, "Export started")
    }

    fun createCompositionPlayer(): CompositionPlayer {
        val playerBuilder = CompositionPlayer.Builder(getApplication())
        if(compositionLayout != COMPOSITION_LAYOUT[0]) {
            playerBuilder
                .setPreviewingVideoGraphFactory(PreviewingMultipleInputVideoGraph.Factory())
        }
        val player = playerBuilder.build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toastMessage.value = "Preview error: $error"
                Log.e(TAG, "Preview error", error)
            }
        })
        //player.repeatMode = Player.REPEAT_MODE_ALL
        return player
    }

    fun releasePlayer() {
        compositionPlayer.stop()
        compositionPlayer.release()
        compositionPlayer = createCompositionPlayer()
    }

    /** Cancels any ongoing export operation, and deletes output file contents.  */
    fun cancelExport() {
        transformer?.apply {
            cancel()
        }
        transformer = null
        outputFile?.apply {
            delete()
        }
        outputFile = null
        exportResultInformation = null
    }

    /**
     * Creates a [File] of the `fileName` in the application cache directory.
     *
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

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        cancelExport()
        exportStopwatch.reset()
    }

    companion object {
        private const val TAG = "CompPreviewVM"
        private const val AUDIO_URI =
            "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
        const val SAME_AS_INPUT_OPTION = "same as input"
        val HDR_MODE_DESCRIPTIONS = mapOf(
            Pair("Keep HDR", Composition.HDR_MODE_KEEP_HDR),
            Pair("MediaCodec tone-map HDR to SDR", Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC),
            Pair("OpenGL tone-map HDR to SDR", Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL),
            Pair("Force Interpret HDR as SDR", Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
        )
        val RESOLUTION_HEIGHTS = listOf(
            SAME_AS_INPUT_OPTION, "144", "240", "360", "480", "720", "1080", "1440", "2160"
        )
        val MUXER_OPTIONS = listOf(
            "Use Platform MediaMuxer",
            "Use Media3 Mp4Muxer",
            "Use Media3 FragmentedMp4Muxer"
        )
        const val LAYOUT_EXTRA = "composition_layout"
        val COMPOSITION_LAYOUT = listOf(
            "Sequential",
            "2x2 grid",
            "PiP overlay"
        )
    }
}

class CompositionPreviewViewModelFactory(val application: Application, val compositionLayout: String): ViewModelProvider.Factory {
    init {
        Log.d("CPVMF", "Creating ViewModel with $compositionLayout")
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CompositionPreviewViewModel(application, compositionLayout) as T
    }
}