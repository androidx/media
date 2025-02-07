/*
 * Copyright 2024 The Android Open Source Project
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

import android.R.layout
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.demo.composition.MatrixTransformationFactory.createDizzyCropEffect
import androidx.media3.demo.composition.databinding.CompositionPreviewActivityBinding
import androidx.media3.demo.composition.databinding.ExportSettingsBinding
import androidx.media3.effect.DebugTraceUtil
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
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
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An [Activity] that previews compositions, using [ ].
 */
class CompositionPreviewActivity : AppCompatActivity() {
    private var sequenceAssetTitles = mutableListOf<String>()
    private var selectedMediaItems = mutableListOf<Boolean>()
    private var presetDescriptions = emptyList<String>()
    private lateinit var assetItemAdapter: AssetItemAdapter
    private var compositionPlayer: CompositionPlayer? = null
    private var transformer: Transformer? = null
    private var outputFile: File? = null
    private lateinit var playerView: PlayerView
    private lateinit var exportButton: AppCompatButton
    private lateinit var exportInformationTextView: AppCompatTextView
    private lateinit var exportStopwatch: Stopwatch
    private var includeBackgroundAudioTrack = false
    private var appliesVideoEffects = false

    private lateinit var binding: CompositionPreviewActivityBinding
    private lateinit var exportSettingsBinding: ExportSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Util.SDK_INT >= 26) {
            window.setColorMode(ActivityInfo.COLOR_MODE_HDR)
        }
        binding = CompositionPreviewActivityBinding.inflate(layoutInflater)
        val rootView = binding.root
        setContentView(rootView)
        playerView = binding.compositionPlayerView

        binding.previewButton.setOnClickListener { previewComposition() }
        binding.editSequenceButton.setOnClickListener { selectPreset() }
        val presetList = binding.compositionPresetList
        presetList.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        val layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL,  /* reverseLayout= */false)
        presetList.setLayoutManager(layoutManager)

        exportInformationTextView = binding.exportInformationText
        exportButton = binding.compositionExportButton.apply {
            setOnClickListener { showExportSettings() }
        }

        val backgroundAudioCheckBox = binding.backgroundAudioCheckbox
        backgroundAudioCheckBox.setOnCheckedChangeListener { _, checked ->
            includeBackgroundAudioTrack = checked
        }

        val resolutionHeightAdapter =
            ArrayAdapter<String>(/* context= */ this, R.layout.spinner_item
            )
        resolutionHeightAdapter.setDropDownViewResource(layout.simple_spinner_dropdown_item)
        val resolutionHeightSpinner = binding.resolutionHeightSpinner
        resolutionHeightSpinner.setAdapter(resolutionHeightAdapter)
        resolutionHeightAdapter.addAll(RESOLUTION_HEIGHTS)

        val hdrModeAdapter = ArrayAdapter<String>(this, R.layout.spinner_item)
        hdrModeAdapter.setDropDownViewResource(layout.simple_spinner_dropdown_item)
        val hdrModeSpinner = binding.hdrModeSpinner
        hdrModeSpinner.setAdapter(hdrModeAdapter)
        hdrModeAdapter.addAll(HDR_MODE_DESCRIPTIONS.keys)

        val applyVideoEffectsCheckBox = binding.applyVideoEffectsCheckbox
        applyVideoEffectsCheckBox.setOnCheckedChangeListener { compoundButton: CompoundButton?, checked: Boolean ->
            appliesVideoEffects = checked
        }

        presetDescriptions = resources.getStringArray(R.array.preset_descriptions).asList()
        // Select two media items by default.
        selectedMediaItems = BooleanArray(presetDescriptions.size).toMutableList()
        selectedMediaItems[0] = true
        selectedMediaItems[2] = true
        sequenceAssetTitles = mutableListOf()
        for (i in selectedMediaItems.indices) {
            if (selectedMediaItems[i]) {
                sequenceAssetTitles.add(presetDescriptions[i])
            }
        }
        assetItemAdapter = AssetItemAdapter(sequenceAssetTitles)
        presetList.setAdapter(assetItemAdapter)

        exportStopwatch = Stopwatch.createUnstarted(object : Ticker() {
            override fun read(): Long {
                return SystemClock.elapsedRealtimeNanos()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                compositionPlayer?.apply {
                    pause()
                }
                if (exportStopwatch.isRunning) {
                    cancelExport()
                    exportStopwatch.reset()
                }
                finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        playerView.onResume()
    }

    override fun onStop() {
        super.onStop()
        playerView.onPause()
        releasePlayer()
        cancelExport()
        exportStopwatch.reset()
    }

    private fun prepareComposition(): Composition {
        val presetUris = resources.getStringArray(/* id= */R.array.preset_uris)
        val presetDurationsUs = resources.getIntArray(/* id= */R.array.preset_durations)
        val mediaItems = mutableListOf<EditedMediaItem>()
        val videoEffects = mutableListOf<Effect>()
        if (appliesVideoEffects) {
            videoEffects.add(createDizzyCropEffect())
            videoEffects.add(RgbFilter.createGrayscaleFilter())
        }
        val resolutionHeightSpinner = binding.resolutionHeightSpinner
        val selectedResolutionHeight = resolutionHeightSpinner.getSelectedItem().toString()
        if (SAME_AS_INPUT_OPTION != selectedResolutionHeight) {
            val resolutionHeight = selectedResolutionHeight.toInt()
            videoEffects.add(LanczosResample.scaleToFit(10000, resolutionHeight))
            videoEffects.add(Presentation.createForHeight(resolutionHeight))
        }
        // Preview requires all sequences to be the same duration, so calculate main sequence duration
        // and limit background sequence duration to match.
        var videoSequenceDurationUs = 0L
        for (i in selectedMediaItems.indices) {
            if (selectedMediaItems[i]) {
                val pitchChanger = SonicAudioProcessor()
                pitchChanger.setPitch(if (mediaItems.size % 2 == 0) 2f else 0.2f)
                val mediaItem = MediaItem.Builder().setUri(presetUris[i])
                    .setImageDurationMs(Util.usToMs(presetDurationsUs[i].toLong())) // Ignored for audio/video
                    .build()
                val itemBuilder = EditedMediaItem.Builder(mediaItem).setEffects(
                        Effects( /* audioProcessors= */
                            listOf(pitchChanger),  /* videoEffects= */
                            videoEffects
                        )
                    ).setDurationUs(presetDurationsUs[i].toLong())
                videoSequenceDurationUs += presetDurationsUs[i].toLong()
                mediaItems.add(itemBuilder.build())
            }
        }
        val videoSequence = EditedMediaItemSequence.Builder(mediaItems).build()
        val compositionSequences = mutableListOf<EditedMediaItemSequence>(videoSequence)
        if (includeBackgroundAudioTrack) {
            compositionSequences.add(getAudioBackgroundSequence(Util.usToMs(videoSequenceDurationUs)))
        }
        val sampleRateChanger = SonicAudioProcessor()
        sampleRateChanger.setOutputSampleRateHz(8000)
        val hdrModeSpinner = binding.hdrModeSpinner
        val selectedHdrMode: Int = HDR_MODE_DESCRIPTIONS.get(
            hdrModeSpinner.getSelectedItem().toString()
        ) ?: Composition.HDR_MODE_KEEP_HDR
        return Composition.Builder(compositionSequences).setEffects(
                Effects( /* audioProcessors= */
                    listOf(sampleRateChanger),  /* videoEffects= */
                    emptyList()
                )
            ).setHdrMode(selectedHdrMode).build()
    }

    private fun getAudioBackgroundSequence(durationMs: Long): EditedMediaItemSequence {
        val audioMediaItem: MediaItem =
            MediaItem.Builder().setUri(AUDIO_URI).setClippingConfiguration(
                    ClippingConfiguration.Builder().setStartPositionMs(0)
                        .setEndPositionMs(durationMs).build()
                ).build()
        val audioItem = EditedMediaItem.Builder(audioMediaItem).setDurationUs(59000000).build()
        return EditedMediaItemSequence.Builder(audioItem).build()
    }

    private fun previewComposition() {
        releasePlayer()
        val composition = prepareComposition()
        playerView.player = null

        val player = CompositionPlayer.Builder(applicationContext).build()
        compositionPlayer = player
        playerView.player = compositionPlayer
        playerView.controllerAutoShow = false
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    applicationContext, "Preview error: $error", Toast.LENGTH_LONG
                ).show()
                Log.e(TAG, "Preview error", error)
            }
        })
        player.setComposition(composition)
        player.prepare()
        player.play()
    }

    private fun selectPreset() {
        AlertDialog.Builder( /* context= */this).setTitle(R.string.select_preset_title)
            .setMultiChoiceItems(
                R.array.preset_descriptions,
                selectedMediaItems.toBooleanArray(),
                OnMultiChoiceClickListener { _, which, isChecked ->
                    this.selectPresetInDialog(
                        which, isChecked
                    )
                }).setPositiveButton(R.string.ok,  /* listener= */null).setCancelable(false)
            .create().show()
    }

    private fun selectPresetInDialog(which: Int, isChecked: Boolean) {
        selectedMediaItems[which] = isChecked
        // The items will be added to a the sequence in the order they were selected.
        if (isChecked) {
            sequenceAssetTitles.add(presetDescriptions[which])
            assetItemAdapter.notifyItemInserted(sequenceAssetTitles.size - 1)
        } else {
            val index = sequenceAssetTitles.indexOf(presetDescriptions[which])
            sequenceAssetTitles.remove(presetDescriptions[which])
            assetItemAdapter.notifyItemRemoved(index)
        }
    }

    private fun showExportSettings() {
        val alertDialogBuilder = AlertDialog.Builder(this)
        exportSettingsBinding = ExportSettingsBinding.inflate(layoutInflater)

        alertDialogBuilder.setView(exportSettingsBinding.root).setTitle(R.string.export_settings)
            .setPositiveButton(
                R.string.export, DialogInterface.OnClickListener { _, _ ->
                    exportComposition()
                }).setNegativeButton(
                R.string.cancel, DialogInterface.OnClickListener { dialog, _ -> dialog.dismiss() })

        val audioMimeAdapter = ArrayAdapter<String>( /* context= */this, R.layout.spinner_item
        )
        audioMimeAdapter.setDropDownViewResource(layout.simple_spinner_dropdown_item)
        val audioMimeSpinner = exportSettingsBinding.audioMimeSpinner
        audioMimeSpinner.setAdapter(audioMimeAdapter)
        audioMimeAdapter.addAll(
            SAME_AS_INPUT_OPTION,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_AMR_NB,
            MimeTypes.AUDIO_AMR_WB
        )

        val videoMimeAdapter = ArrayAdapter<String>( /* context= */this, R.layout.spinner_item
        )
        videoMimeAdapter.setDropDownViewResource(layout.simple_spinner_dropdown_item)
        val videoMimeSpinner = exportSettingsBinding.videoMimeSpinner
        videoMimeSpinner.setAdapter(videoMimeAdapter)
        videoMimeAdapter.addAll(
            SAME_AS_INPUT_OPTION,
            MimeTypes.VIDEO_H263,
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_MP4V,
            MimeTypes.VIDEO_AV1
        )

        val enableDebugTracingCheckBox = exportSettingsBinding.enableDebugTracingCheckbox
        enableDebugTracingCheckBox.setOnCheckedChangeListener(
            CompoundButton.OnCheckedChangeListener { buttonView: CompoundButton?, isChecked: Boolean ->
                DebugTraceUtil.enableTracing = isChecked
            })

        val useMedia3Mp4MuxerCheckBox = exportSettingsBinding.useMedia3Mp4MuxerCheckbox
        val useMedia3FragmentedMp4MuxerCheckBox =
            exportSettingsBinding.useMedia3FragmentedMp4MuxerCheckbox
        useMedia3Mp4MuxerCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                useMedia3FragmentedMp4MuxerCheckBox.setChecked(false)
            }
        }
        useMedia3FragmentedMp4MuxerCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                useMedia3Mp4MuxerCheckBox.setChecked(false)
            }
        }

        val dialog = alertDialogBuilder.create()
        dialog.show()
    }

    private fun exportComposition() {
        // Cancel and clean up files from any ongoing export.
        cancelExport()

        val composition = prepareComposition()

        try {
            outputFile = createExternalCacheFile(
                "composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4"
            )
        } catch (e: IOException) {
            Toast.makeText(
                applicationContext,
                "Aborting export! Unable to create output file: $e",
                Toast.LENGTH_LONG
            ).show()
            Log.e(TAG, "Aborting export! Unable to create output file: ", e)
            return
        }
        val filePath = outputFile!!.absolutePath

        val transformerBuilder = Transformer.Builder( /* context= */this)

        // Note: exportComposition is only called from showExportSettings(), which is where
        // exportSettingsBinding is initialized
        val audioMimeTypeSpinner = exportSettingsBinding.audioMimeSpinner
        val selectedAudioMimeType = audioMimeTypeSpinner.getSelectedItem().toString()
        if (SAME_AS_INPUT_OPTION != selectedAudioMimeType) {
            transformerBuilder.setAudioMimeType(selectedAudioMimeType)
        }

        val videoMimeTypeSpinner = exportSettingsBinding.videoMimeSpinner
        val selectedVideoMimeType = videoMimeTypeSpinner.getSelectedItem().toString()
        if (SAME_AS_INPUT_OPTION != selectedVideoMimeType) {
            transformerBuilder.setVideoMimeType(selectedVideoMimeType)
        }

        val useMedia3Mp4MuxerCheckBox = exportSettingsBinding.useMedia3Mp4MuxerCheckbox
        val useMedia3FragmentedMp4MuxerCheckBox =
            exportSettingsBinding.useMedia3FragmentedMp4MuxerCheckbox
        if (useMedia3Mp4MuxerCheckBox.isChecked) {
            transformerBuilder.setMuxerFactory(InAppMp4Muxer.Factory())
        }
        if (useMedia3FragmentedMp4MuxerCheckBox.isChecked) {
            transformerBuilder.setMuxerFactory(InAppFragmentedMp4Muxer.Factory())
        }

        transformer = transformerBuilder.addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition, exportResult: ExportResult
                ) {
                    exportStopwatch.stop()
                    val elapsedTimeMs = exportStopwatch.elapsed(TimeUnit.MILLISECONDS)
                    val details = getString(
                        R.string.export_completed, elapsedTimeMs / 1000f, filePath
                    )
                    Log.d(TAG, DebugTraceUtil.generateTraceSummary())
                    Log.i(TAG, details)
                    exportInformationTextView.text = details

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
                    Toast.makeText(
                        applicationContext, "Export error: $exportException", Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Export error", exportException)
                    Log.d(TAG, DebugTraceUtil.generateTraceSummary())
                    exportInformationTextView.setText(R.string.export_error)
                }
            }).build()

        exportInformationTextView.setText(R.string.export_started)
        exportStopwatch.reset()
        exportStopwatch.start()
        transformer!!.start(composition, filePath)
        Log.i(TAG, "Export started")
    }

    private fun releasePlayer() {
        compositionPlayer?.apply {
            release()
        }
        compositionPlayer = null
    }

    /** Cancels any ongoing export operation, and deletes output file contents.  */
    private fun cancelExport() {
        transformer?.apply {
            cancel()
        }
        transformer = null
        outputFile?.apply {
            delete()
        }
        outputFile = null
        exportInformationTextView.text = ""
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
        val file = File(externalCacheDir, fileName)
        if (file.exists() && !file.delete()) {
            throw IOException("Could not delete file: " + file.absolutePath)
        }
        if (!file.createNewFile()) {
            throw IOException("Could not create file: " + file.absolutePath)
        }
        return file
    }

    companion object {
        private const val TAG = "CompPreviewActivity"
        private const val AUDIO_URI =
            "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
        private const val SAME_AS_INPUT_OPTION = "same as input"
        private val HDR_MODE_DESCRIPTIONS = mapOf(
            Pair("Keep HDR", Composition.HDR_MODE_KEEP_HDR),
            Pair("MediaCodec tone-map HDR to SDR", Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC),
            Pair("OpenGL tone-map HDR to SDR", Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL),
            Pair("Force Interpret HDR as SDR", Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR)
        )
        private val RESOLUTION_HEIGHTS = listOf(
            SAME_AS_INPUT_OPTION, "144", "240", "360", "480", "720", "1080", "1440", "2160"
        )
    }
}
