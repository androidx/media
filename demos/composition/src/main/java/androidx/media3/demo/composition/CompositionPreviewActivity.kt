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

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.LocaleList
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.HDR_MODE_DESCRIPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MEDIA_TYPES
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MUXER_OPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.RESOLUTION_HEIGHTS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.SAME_AS_INPUT_OPTION
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.data.ExportState
import androidx.media3.demo.composition.data.Item
import androidx.media3.demo.composition.data.OutputSettingsState
import androidx.media3.demo.composition.data.Preset
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.launch

/**
 * An Activity ([AppCompatActivity]) that previews compositions, using
 * [androidx.media3.transformer.CompositionPlayer].
 */
class CompositionPreviewActivity : AppCompatActivity() {
  // Experimental opt-in is needed for adaptive composables
  // https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout
  @OptIn(ExperimentalMaterial3AdaptiveApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (SDK_INT >= 26) {
      window.setColorMode(ActivityInfo.COLOR_MODE_HDR)
    }

    // Request permission in case the file is local. This is for manual testing only.
    val permission =
      if (SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
      } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
      }
    if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
        this,
        /* permissions= */ arrayOf(permission),
        /* requestCode= */ 1,
      )
    }

    val viewModel: CompositionPreviewViewModel by viewModels {
      CompositionPreviewViewModelFactory(application)
    }

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val snackbarHostState = remember { SnackbarHostState() }

      LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (message != null) {
          snackbarHostState.showSnackbar(message)
          viewModel.onSnackbarMessageShown()
        }
      }

      CompositionDemoTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { innerPadding ->
          val scope = rememberCoroutineScope()
          val navigator = rememberSupportingPaneScaffoldNavigator()

          BackHandler(navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

          SupportingPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            mainPane = {
              AnimatedPane {
                CompositionPreviewPane(
                  shouldShowSupportingPaneButton =
                    navigator.scaffoldValue.secondary == PaneAdaptedValue.Hidden,
                  onNavigateToSupportingPane = {
                    scope.launch { navigator.navigateTo(ThreePaneScaffoldRole.Secondary) }
                  },
                  viewModel,
                  uiState = uiState,
                )
              }
            },
            supportingPane = {
              AnimatedPane {
                ExportOptionsPane(
                  outputSettings = uiState.outputSettingsState,
                  exportState = uiState.exportState,
                  isDebugTracingEnabled = uiState.isDebugTracingEnabled,
                  onAudioMimeTypeChanged = viewModel::onAudioMimeTypeChanged,
                  onVideoMimeTypeChanged = viewModel::onVideoMimeTypeChanged,
                  onMuxerOptionChanged = viewModel::onMuxerOptionChanged,
                  onDebugTracingChanged = viewModel::enableDebugTracing,
                  onExport = viewModel::exportComposition,
                  shouldShowBackButton = navigator.scaffoldValue.primary == PaneAdaptedValue.Hidden,
                  onBack = { scope.launch { navigator.navigateBack() } },
                )
              }
            },
            modifier =
              Modifier.padding(innerPadding)
                .padding(MaterialTheme.spacing.standard, MaterialTheme.spacing.small),
          )
        }
      }
    }
  }

  @OptIn(ExperimentalMaterial3AdaptiveApi::class)
  @Composable
  fun CompositionPreviewPane(
    shouldShowSupportingPaneButton: Boolean,
    onNavigateToSupportingPane: () -> Unit,
    viewModel: CompositionPreviewViewModel,
    uiState: CompositionPreviewState,
    modifier: Modifier = Modifier,
  ) {
    val scrollState = rememberScrollState()
    var isLayoutDropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
      Text(
        text =
          stringResource(
            R.string.preview_composition_title,
            presetToString(uiState.selectedPreset),
          ),
        fontWeight = FontWeight.Bold,
      )

      @Suppress("UnusedBoxWithConstraintsScope")
      BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
        contentAlignment = Alignment.Center,
      ) {
        // Video Player
        AndroidView(
          factory = { context -> PlayerView(context) },
          update = { playerView ->
            playerView.player = viewModel.compositionPlayer
            playerView.setTimeBarScrubbingEnabled(true)
            playerView.setUseController(true)
            // TODO: b/449957627 - Remove once internal pipeline is migrated to FrameConsumer.
            viewModel.surfaceView = playerView.videoSurfaceView as SurfaceView
          },
          modifier = Modifier.fillMaxSize(),
        )
      }

      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )

      Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
        modifier = modifier.weight(1f).verticalScroll(scrollState),
      ) {
        if (uiState.sequenceTrackTypes.isNotEmpty()) {
          ScrollableTabRow(
            selectedTabIndex = uiState.selectedSequenceIndex,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
          ) {
            uiState.sequenceTrackTypes.forEachIndexed { index, _ ->
              Tab(
                selected = uiState.selectedSequenceIndex == index,
                onClick = { viewModel.onSequenceSelected(index) },
                text = { Text(text = stringResource(R.string.sequence_label, index + 1)) },
              )
            }
            // Add Sequence button
            Tab(
              selected = false,
              onClick = { viewModel.addSequence() },
              text = {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_sequence))
              },
            )
          }

          SequencePane(
            sequenceIndex = uiState.selectedSequenceIndex,
            trackTypes = uiState.sequenceTrackTypes[uiState.selectedSequenceIndex],
            selectedItems =
              uiState.mediaState.selectedItemsBySequence.getOrNull(uiState.selectedSequenceIndex)
                ?: emptyList(),
            availableItems = uiState.mediaState.availableItems,
            availableEffects = uiState.mediaState.availableEffects,
            isEnabled = true,
            onTrackTypeChanged = viewModel::onSequenceTrackTypeChanged,
            onAddItem = viewModel::addItem,
            onRemoveItem = viewModel::removeItem,
            onUpdateEffects = viewModel::updateEffectsForItem,
            onAddLocalItem = viewModel::addLocalItem,
            onRemoveSequence = viewModel::removeSequence,
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
          Text(text = stringResource(R.string.presets), modifier = Modifier.textPadding())
          DropDownSpinner(
            isDropDownOpen = isLayoutDropdownExpanded,
            selectedOption = uiState.selectedPreset,
            dropDownOptions = viewModel.compositionLayouts,
            changeDropDownOpen = { isLayoutDropdownExpanded = it },
            changeSelectedOption = { newSelection ->
              viewModel.onPresetSelected(newSelection)
              isLayoutDropdownExpanded = false
            },
            labelProvider = { preset -> presetToString(preset) },
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.frame_consumer_enabled),
            modifier = Modifier.textPadding(),
          )
          Switch(
            checked = uiState.outputSettingsState.frameConsumerEnabled,
            onCheckedChange = { isEnabled -> viewModel.onFrameConsumerEnabledChanged(isEnabled) },
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.add_background_audio),
            modifier = Modifier.textPadding(),
          )
          Switch(
            checked = uiState.outputSettingsState.includeBackgroundAudio,
            onCheckedChange = { isEnabled -> viewModel.onIncludeBackgroundAudioChanged(isEnabled) },
          )
        }

        OutputSettings(
          outputSettings = uiState.outputSettingsState,
          onResolutionChanged = viewModel::onOutputResolutionChanged,
          onHdrModeChanged = viewModel::onHdrModeChanged,
        )
      }

      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )

      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.small),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        Button(onClick = { viewModel.setComposition() }) {
          Text(text = stringResource(R.string.set_composition))
        }
        Button(onClick = { viewModel.play() }, enabled = uiState.isCompositionSet) {
          Text(text = stringResource(R.string.play))
        }
        if (shouldShowSupportingPaneButton) {
          Button(onClick = onNavigateToSupportingPane) {
            Text(text = stringResource(R.string.export_settings))
          }
        }
      }
    }
  }

  @OptIn(ExperimentalMaterial3AdaptiveApi::class)
  @Composable
  fun ExportOptionsPane(
    outputSettings: OutputSettingsState,
    exportState: ExportState,
    isDebugTracingEnabled: Boolean,
    onAudioMimeTypeChanged: (String) -> Unit,
    onVideoMimeTypeChanged: (String) -> Unit,
    onMuxerOptionChanged: (String) -> Unit,
    onDebugTracingChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
    shouldShowBackButton: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    var isAudioTypeExpanded by remember { mutableStateOf(false) }
    var isVideoTypeExpanded by remember { mutableStateOf(false) }
    Column(
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
      modifier = modifier,
    ) {
      Text(text = stringResource(R.string.export_settings), fontWeight = FontWeight.Bold)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.output_audio_mime_type),
          modifier = Modifier.textPadding(),
        )
        DropDownSpinner(
          isDropDownOpen = isAudioTypeExpanded,
          selectedOption = outputSettings.audioMimeType,
          dropDownOptions =
            listOf(
              SAME_AS_INPUT_OPTION,
              MimeTypes.AUDIO_AAC,
              MimeTypes.AUDIO_AMR_NB,
              MimeTypes.AUDIO_AMR_WB,
            ),
          changeDropDownOpen = { expanded -> isAudioTypeExpanded = expanded },
          changeSelectedOption = onAudioMimeTypeChanged,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.output_video_mime_type),
          modifier = Modifier.textPadding(),
        )
        DropDownSpinner(
          isDropDownOpen = isVideoTypeExpanded,
          selectedOption = outputSettings.videoMimeType,
          dropDownOptions =
            listOf(
              SAME_AS_INPUT_OPTION,
              MimeTypes.VIDEO_H263,
              MimeTypes.VIDEO_H264,
              MimeTypes.VIDEO_H265,
              MimeTypes.VIDEO_MP4V,
              MimeTypes.VIDEO_AV1,
              MimeTypes.VIDEO_APV,
              MimeTypes.VIDEO_DOLBY_VISION,
            ),
          changeDropDownOpen = { expanded -> isVideoTypeExpanded = expanded },
          changeSelectedOption = onVideoMimeTypeChanged,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.enable_debug_tracing),
          modifier = Modifier.textPadding(),
        )
        Switch(
          checked = isDebugTracingEnabled,
          onCheckedChange = { checked -> onDebugTracingChanged(checked) },
        )
      }
      Column(Modifier.selectableGroup()) {
        MUXER_OPTIONS.forEach { text ->
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
              Modifier.selectable(
                  selected = text == outputSettings.muxerOption,
                  onClick = { onMuxerOptionChanged(text) },
                  role = Role.RadioButton,
                )
                .fillMaxWidth(),
          ) {
            Text(text = text, modifier = Modifier.textPadding())
            RadioButton(selected = (text == outputSettings.muxerOption), onClick = null)
          }
        }
      }
      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )
      Row(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.small, 0.dp)) {
        if (shouldShowBackButton) {
          OutlinedButton({ onBack() }) { Text(text = stringResource(R.string.cancel)) }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onExport) { Text(text = stringResource(R.string.export)) }
      }
      exportState.exportResultInfo?.let {
        HorizontalDivider(
          thickness = 2.dp,
          modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
        )
        Text(text = it)
      }
    }
  }

  @Composable
  fun SequencePane(
    sequenceIndex: Int,
    trackTypes: Set<Int>,
    selectedItems: List<Item>,
    availableItems: List<Item>,
    availableEffects: List<String>,
    isEnabled: Boolean,
    onTrackTypeChanged: (Int, Set<Int>) -> Unit,
    onAddItem: (Int, Int) -> Unit,
    onRemoveItem: (Int, Int) -> Unit,
    onUpdateEffects: (sequenceIndex: Int, itemIndex: Int, effects: Set<String>) -> Unit,
    onAddLocalItem: (Int, Uri) -> Unit,
    onRemoveSequence: (Int) -> Unit,
  ) {
    var selectedMediaItemIndex by remember { mutableStateOf<Int?>(null) }
    var showEditMediaItemsDialog by remember { mutableStateOf(false) }

    val filePickerLauncher =
      rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
        uri: Uri? ->
        uri?.let { onAddLocalItem(sequenceIndex, it) }
      }

    if (showEditMediaItemsDialog) {
      VideoSequenceDialog(
        onDismissRequest = { showEditMediaItemsDialog = false },
        itemOptions = availableItems,
        addSelectedVideo = { itemIndex -> onAddItem(sequenceIndex, itemIndex) },
      )
    }

    selectedMediaItemIndex?.let { itemIndex ->
      val item = selectedItems[itemIndex]
      EffectSelectionDialog(
        onDismissRequest = { selectedMediaItemIndex = null },
        effectOptions = availableEffects,
        currentSelections = item.selectedEffects,
        onEffectsSelected = { newEffects -> onUpdateEffects(sequenceIndex, itemIndex, newEffects) },
      )
    }

    Column(
      modifier =
        Modifier.padding(vertical = 4.dp)
          .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
          .padding(MaterialTheme.spacing.small)
    ) {
      // Track Settings
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.sequence_label, sequenceIndex + 1),
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = trackTypes.contains(C.TRACK_TYPE_AUDIO),
            onCheckedChange = { checked ->
              val newTypes =
                if (checked) trackTypes + C.TRACK_TYPE_AUDIO else trackTypes - C.TRACK_TYPE_AUDIO
              onTrackTypeChanged(sequenceIndex, newTypes)
            },
            enabled =
              isEnabled && !(trackTypes.contains(C.TRACK_TYPE_AUDIO) && trackTypes.size == 1),
          )
          Text(text = stringResource(R.string.audio))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = trackTypes.contains(C.TRACK_TYPE_VIDEO),
            onCheckedChange = { checked ->
              val newTypes =
                if (checked) trackTypes + C.TRACK_TYPE_VIDEO else trackTypes - C.TRACK_TYPE_VIDEO
              onTrackTypeChanged(sequenceIndex, newTypes)
            },
            enabled =
              isEnabled && !(trackTypes.contains(C.TRACK_TYPE_VIDEO) && trackTypes.size == 1),
          )
          Text(text = stringResource(R.string.video))
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

      // Media Items List
      Text(
        text = stringResource(R.string.add_effects_hint),
        fontSize = 12.sp,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp),
      )

      LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        itemsIndexed(selectedItems) { index, item ->
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth().clickable(enabled = isEnabled) {
                selectedMediaItemIndex = index
              },
          ) {
            Column(modifier = Modifier.textPadding().weight(1f)) {
              Text(text = stringResource(R.string.item_index_label, index + 1, item.title))
              val effectsText =
                item.selectedEffects.joinToString().ifEmpty { stringResource(R.string.none) }
              Text(
                text = stringResource(R.string.effect_label, effectsText),
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
              )
            }
            IconButton({ onRemoveItem(sequenceIndex, index) }, enabled = isEnabled) {
              Icon(
                Icons.TwoTone.Delete,
                contentDescription = stringResource(R.string.remove_item, index + 1),
              )
            }
          }
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        ElevatedButton(onClick = { showEditMediaItemsDialog = true }, enabled = isEnabled) {
          Text(text = stringResource(R.string.edit))
        }
        ElevatedButton(onClick = { filePickerLauncher.launch(MEDIA_TYPES) }, enabled = isEnabled) {
          Text(text = stringResource(R.string.add_local_file))
        }
        ElevatedButton(onClick = { onRemoveSequence(sequenceIndex) }, enabled = isEnabled) {
          Text(text = stringResource(R.string.delete_sequence))
        }
      }
    }
  }

  @Composable
  fun VideoSequenceDialog(
    onDismissRequest: () -> Unit,
    itemOptions: List<Item>,
    addSelectedVideo: (Int) -> Unit,
  ) {
    Dialog(onDismissRequest) {
      Card(
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.mini),
        shape = RoundedCornerShape(16.dp),
      ) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = stringResource(R.string.select_items),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(0.dp, MaterialTheme.spacing.small),
          )
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f).padding(MaterialTheme.spacing.small, 0.dp),
          ) {
            itemsIndexed(itemOptions) { index, item ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                FilledIconButton(onClick = { addSelectedVideo(index) }) {
                  Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_item))
                }
                val duration = item.durationUs.toDuration(DurationUnit.MICROSECONDS)
                val durationString =
                  String.format(
                    getLocale(),
                    "%02d:%02d",
                    duration.inWholeMinutes,
                    duration.inWholeSeconds % 60,
                  )
                Text(text = stringResource(R.string.item_with_duration, item.title, durationString))
              }
            }
          }
          Button(
            { onDismissRequest() },
            modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
          ) {
            Text(text = stringResource(R.string.ok))
          }
        }
      }
    }
  }

  @Composable
  fun OutputSettings(
    outputSettings: OutputSettingsState,
    onResolutionChanged: (String) -> Unit,
    onHdrModeChanged: (Int) -> Unit,
  ) {
    var resolutionExpanded by remember { mutableStateOf(false) }
    var hdrExpanded by remember { mutableStateOf(false) }
    val selectedHdrKey =
      HDR_MODE_DESCRIPTIONS.entries.find { it.value == outputSettings.hdrMode }?.key

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.output_video_resolution),
          modifier = Modifier.textPadding(),
        )
        DropDownSpinner(
          isDropDownOpen = resolutionExpanded,
          selectedOption = outputSettings.resolutionHeight,
          dropDownOptions = RESOLUTION_HEIGHTS,
          changeDropDownOpen = { newExpanded -> resolutionExpanded = newExpanded },
          changeSelectedOption = { newSelection -> onResolutionChanged(newSelection) },
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(text = stringResource(R.string.hdr_mode), modifier = Modifier.textPadding())
        DropDownSpinner(
          isDropDownOpen = hdrExpanded,
          selectedOption = selectedHdrKey ?: HDR_MODE_DESCRIPTIONS.keys.first(),
          dropDownOptions = HDR_MODE_DESCRIPTIONS.keys.toList(),
          changeDropDownOpen = { newExpanded -> hdrExpanded = newExpanded },
          changeSelectedOption = { newSelection ->
            val newMode = HDR_MODE_DESCRIPTIONS[newSelection] ?: Composition.HDR_MODE_KEEP_HDR
            onHdrModeChanged(newMode)
          },
        )
      }
    }
  }

  @Composable
  fun EffectSelectionDialog(
    onDismissRequest: () -> Unit,
    effectOptions: List<String>,
    currentSelections: Set<String>,
    onEffectsSelected: (Set<String>) -> Unit,
  ) {
    var selectedOptions by remember { mutableStateOf(currentSelections) }

    Dialog(onDismissRequest = onDismissRequest) {
      Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.standard)) {
          Text(
            text = stringResource(R.string.select_effects),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
          )
          Column {
            effectOptions.forEach { effectName ->
              Row(
                Modifier.fillMaxWidth()
                  .clickable {
                    selectedOptions =
                      if (selectedOptions.contains(effectName)) {
                        selectedOptions - effectName
                      } else {
                        selectedOptions + effectName
                      }
                  }
                  .padding(vertical = MaterialTheme.spacing.mini),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(checked = selectedOptions.contains(effectName), onCheckedChange = null)
                Text(
                  text = effectName,
                  modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(MaterialTheme.spacing.standard))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
              onClick = onDismissRequest,
              modifier = Modifier.padding(end = MaterialTheme.spacing.small),
            ) {
              Text(stringResource(R.string.cancel))
            }
            Button(
              onClick = {
                onEffectsSelected(selectedOptions)
                onDismissRequest()
              }
            ) {
              Text(stringResource(R.string.ok))
            }
          }
        }
      }
    }
  }

  @Composable
  private fun presetToString(preset: Preset): String {
    return when (preset) {
      Preset.SEQUENCE -> stringResource(R.string.preset_sequence)
      Preset.GRID -> stringResource(R.string.preset_grid)
      Preset.PIP -> stringResource(R.string.preset_pip)
      Preset.CUSTOM -> stringResource(R.string.preset_custom)
    }
  }

  companion object {
    private const val TAG = "CompPreviewActivity"

    fun getLocale(): Locale {
      return if (SDK_INT >= 24) {
        LocaleList.getDefault().get(0) ?: Locale.getDefault()
      } else {
        Locale.getDefault()
      }
    }
  }
}
