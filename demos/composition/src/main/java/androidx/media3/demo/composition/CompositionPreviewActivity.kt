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

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldScope
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.COMPOSITION_LAYOUT
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.HDR_MODE_DESCRIPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.LAYOUT_EXTRA
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MUXER_OPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.RESOLUTION_HEIGHTS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.SAME_AS_INPUT_OPTION
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

/**
 * An [Activity] that previews compositions, using [ ].
 */
class CompositionPreviewActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Util.SDK_INT >= 26) {
            window.setColorMode(ActivityInfo.COLOR_MODE_HDR)
        }


        val compositionLayout = intent.getStringExtra(LAYOUT_EXTRA) ?: COMPOSITION_LAYOUT[0]
        Log.d("CPVMF", "Received layout of $compositionLayout")
        val viewModel: CompositionPreviewViewModel by viewModels { CompositionPreviewViewModelFactory(application, compositionLayout) }

        // TODO(nevmital): Update to follow https://developer.android.com/topic/architecture/ui-layer/events#consuming-trigger-updates
        viewModel.toastMessage.observe(this) { newMessage ->
            newMessage?.let {
                viewModel.toastMessage.value = null
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            CompositionDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val navigator = rememberSupportingPaneScaffoldNavigator()

                    BackHandler(navigator.canNavigateBack()) {
                        navigator.navigateBack()
                    }

                    SupportingPaneScaffold(
                        directive = navigator.scaffoldDirective,
                        value = navigator.scaffoldValue,
                        mainPane = {
                            CompositionPreviewPane(
                                shouldShowSupportingPaneButton = navigator.scaffoldValue.secondary == PaneAdaptedValue.Hidden,
                                onNavigateToSupportingPane = {
                                    navigator.navigateTo(
                                        ThreePaneScaffoldRole.Secondary
                                    )
                                },
                                viewModel
                            )
                        },
                        supportingPane = {
                            ExportOptionsPane(
                                viewModel,
                                shouldShowBackButton = navigator.scaffoldValue.primary == PaneAdaptedValue.Hidden,
                                onBack = { navigator.navigateBack() }
                            )
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    fun ThreePaneScaffoldScope.CompositionPreviewPane(
        shouldShowSupportingPaneButton: Boolean,
        onNavigateToSupportingPane: () -> Unit,
        viewModel: CompositionPreviewViewModel,
        modifier: Modifier = Modifier,
    ) {
        AnimatedPane(modifier = modifier.safeContentPadding()) {
            // Main pane content
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "${viewModel.compositionLayout} ${stringResource(R.string.preview_composition)}")
                AndroidView(
                    factory = { context -> PlayerView(context) },
                    update = { playerView ->
                        playerView.player = viewModel.compositionPlayer
                        playerView.useController = false
                    },
                    modifier = Modifier.weight(1f)
                )
//                PlayerSurface(viewModel.compositionPlayer, SURFACE_TYPE_SURFACE_VIEW)
                HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(0.dp, 4.dp))
                VideoSequenceList(viewModel)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.add_effects))
                    Switch(viewModel.applyVideoEffects, { checked -> viewModel.applyVideoEffects = checked })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.add_background_audio))
                    Switch(viewModel.includeBackgroundAudioTrack, { checked -> viewModel.includeBackgroundAudioTrack = checked })
                }
                OutputSettings(viewModel)
                HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(0.dp, 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp, 0.dp)
                ) {
                    Button(onClick = { viewModel.previewComposition() }) {
                        Text(text = stringResource(R.string.preview))
                    }
                    Spacer(Modifier.weight(1f))
                    if (shouldShowSupportingPaneButton) {
                        Button(onClick = onNavigateToSupportingPane) {
                            Text(text = stringResource(R.string.export_settings))
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    fun ThreePaneScaffoldScope.ExportOptionsPane(
        viewModel: CompositionPreviewViewModel,
        shouldShowBackButton: Boolean,
        onBack: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var isAudioTypeExpanded by remember { mutableStateOf(false) }
        var isVideoTypeExpanded by remember { mutableStateOf(false) }

        AnimatedPane(modifier = modifier.safeContentPadding()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.output_audio_mime_type))
                    DropDownSpinner(
                        isAudioTypeExpanded,
                        viewModel.outputAudioMimeType,
                        listOf(
                            SAME_AS_INPUT_OPTION,
                            MimeTypes.AUDIO_AAC,
                            MimeTypes.AUDIO_AMR_NB,
                            MimeTypes.AUDIO_AMR_WB
                        ),
                        { expanded -> isAudioTypeExpanded = expanded },
                        { selection -> viewModel.outputAudioMimeType = selection }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.output_video_mime_type))
                    DropDownSpinner(
                        isVideoTypeExpanded,
                        viewModel.outputVideoMimeType,
                        listOf(
                            SAME_AS_INPUT_OPTION,
                            MimeTypes.VIDEO_H263,
                            MimeTypes.VIDEO_H264,
                            MimeTypes.VIDEO_H265,
                            MimeTypes.VIDEO_MP4V,
                            MimeTypes.VIDEO_AV1
                        ),
                        { expanded -> isVideoTypeExpanded = expanded },
                        { selection -> viewModel.outputVideoMimeType = selection }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.enable_debug_tracing))
                    val debugTracingEnabled by viewModel.enableDebugTracing.collectAsState()
                    Switch(debugTracingEnabled, { checked -> viewModel.enableDebugTracing(checked) })
                }
                Column(modifier.selectableGroup()) {
                    MUXER_OPTIONS.forEach { text ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.selectable(
                                selected = text == viewModel.muxerOption,
                                onClick = { viewModel.muxerOption = text },
                                role = Role.RadioButton
                            )
                        ) {
                            RadioButton(
                                selected = (text == viewModel.muxerOption),
                                onClick = null
                            )
                            Text(text = text)
                        }
                    }
                }
                HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(0.dp, 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp, 0.dp)
                ) {
                    if(shouldShowBackButton) {
                        OutlinedButton({ onBack() }) {
                            Text(text = stringResource(R.string.cancel))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button({ viewModel.exportComposition() }) {
                        Text(text = stringResource(R.string.export))
                    }
                }
                viewModel.exportResultInformation?.let {
                    HorizontalDivider(thickness = 2.dp, modifier = Modifier.padding(0.dp, 4.dp))
                    Text(text = it)
                }
            }
        }
    }

    @Composable
    fun VideoSequenceList(viewModel: CompositionPreviewViewModel) {
        var showDialog by remember { mutableStateOf(false) }

        if(showDialog) {
            VideoSequenceDialog(
                { showDialog = false },
                viewModel.mediaItemTitles,
                viewModel.selectedMediaItems,
                { updatedSelections -> viewModel.updateSelectedItems(updatedSelections) }
            )
        }

        Box(modifier = Modifier
            .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(8.dp)
            ) {
                Text(text = stringResource(R.string.video_sequence_items))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.height(80.dp).fillMaxWidth()
                ) {
                    viewModel.selectedMediaTitles.forEachIndexed { index, title ->
                        item {
                            Text(text = "${index + 1}. $title")
                            //HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                ElevatedButton(
                    { showDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(text = stringResource(R.string.edit))
                }
            }
        }
    }

    @Composable
    fun VideoSequenceDialog(onDismissRequest: () -> Unit, videoTitles: List<String>, selectedVideos: MutableList<Boolean>, updateSelectedVideos: (List<Boolean>) -> Unit) {
        Dialog(
            onDismissRequest
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.select_preset_title),
                        modifier = Modifier.padding(0.dp, 8.dp)
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f).padding(8.dp, 0.dp)
                    ) {
                        itemsIndexed(videoTitles) { index, title ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = selectedVideos[index],
                                    onClick = { selectedVideos[index] = !selectedVideos[index] },
                                    role = Role.Checkbox
                                )
                            ) {
                                Checkbox(
                                    selectedVideos[index],
                                    { isChecked -> selectedVideos[index] = isChecked })
                                Text(text = title)
                            }
                        }
                    }
                    Button(
                        {
                            updateSelectedVideos(selectedVideos)
                            onDismissRequest()
                        },
                        modifier = Modifier.padding(0.dp, 4.dp)
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                }
            }
        }
    }

    @Composable
    fun OutputSettings(viewModel: CompositionPreviewViewModel) {
        var resolutionExpanded by remember { mutableStateOf(false) }
        var hdrExpanded by remember { mutableStateOf(false) }
        var selectedHdrMode by remember { mutableStateOf(HDR_MODE_DESCRIPTIONS.keys.first()) }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.output_video_resolution))
                DropDownSpinner(
                    resolutionExpanded,
                    viewModel.outputResolution,
                    RESOLUTION_HEIGHTS,
                    { newExpanded -> resolutionExpanded = newExpanded },
                    { newSelection -> viewModel.outputResolution = newSelection }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.hdr_mode))
                DropDownSpinner(
                    hdrExpanded,
                    selectedHdrMode,
                    HDR_MODE_DESCRIPTIONS.keys.toList(),
                    { newExpanded -> hdrExpanded = newExpanded },
                    { newSelection ->
                        selectedHdrMode = newSelection
                        viewModel.outputHdrMode = HDR_MODE_DESCRIPTIONS[newSelection] ?: Composition.HDR_MODE_KEEP_HDR
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "CompPreviewActivity"
    }
}
