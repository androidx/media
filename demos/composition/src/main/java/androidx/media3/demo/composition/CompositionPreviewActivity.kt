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
import android.graphics.Bitmap
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.LocaleList
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MimeTypes
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.HDR_MODE_DESCRIPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MUXER_OPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.RESOLUTION_HEIGHTS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.SAME_AS_INPUT_OPTION
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.data.ExportState
import androidx.media3.demo.composition.data.Item
import androidx.media3.demo.composition.data.MediaState
import androidx.media3.demo.composition.data.OutputSettingsState
import androidx.media3.demo.composition.data.OverlayAsset
import androidx.media3.demo.composition.data.OverlayState
import androidx.media3.demo.composition.data.PlacementState
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
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
      if (SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
      else Manifest.permission.READ_EXTERNAL_STORAGE
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

    val placementState = uiState.overlayState.placementState
    val isOverlayPlacementActive = placementState is PlacementState.Placing

    val scrollState = rememberScrollState()
    var isLayoutDropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
      Text(
        text = "${uiState.compositionLayout} ${stringResource(R.string.preview_composition)}",
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
            playerView.setTimeBarScrubbingEnabled(!isOverlayPlacementActive)
            playerView.setUseController(!isOverlayPlacementActive)
            // TODO: b/449957627 - Remove once internal pipeline is migrated to FrameConsumer.
            viewModel.holder = (playerView.videoSurfaceView as SurfaceView).holder
          },
          modifier = Modifier.fillMaxSize(),
        )

        // Draggable Overlay Logic
        if (placementState is PlacementState.Placing) {
          val density = LocalDensity.current
          val renderSize =
            Size(
              width = with(density) { maxWidth.toPx() },
              height = with(density) { maxHeight.toPx() },
            )
          LaunchedEffect(renderSize) { viewModel.onRenderSizeChanged(renderSize) }

          DraggableOverlay(
            bitmap = placementState.overlay.bitmap,
            offset = placementState.currentUiTransformOffset,
            onDrag = { dragAmount -> viewModel.onOverlayDrag(dragAmount) },
            modifier = Modifier.size(width = maxWidth, height = maxHeight),
          )
        }
      }

      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
        modifier =
          modifier.weight(1f).verticalScroll(scrollState, enabled = !isOverlayPlacementActive),
      ) {
        Box(
          modifier = Modifier.graphicsLayer { alpha = if (isOverlayPlacementActive) 0.5f else 1.0f }
        ) {
          VideoSequenceList(
            mediaState = uiState.mediaState,
            isEnabled = !isOverlayPlacementActive,
            onAddItem = { index -> viewModel.addItem(index) },
            onRemoveItem = { index -> viewModel.removeItem(index) },
            onUpdateEffects = { index, effects -> viewModel.updateEffectsForItem(index, effects) },
          )
        }
        if (isOverlayPlacementActive) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
          ) {
            Button(onClick = { viewModel.onEndPlacementClicked() }) {
              Text(text = stringResource(R.string.end_placement_mode))
            }
          }
        } else {
          OverlayEffectsList(
            overlayState = uiState.overlayState,
            onPlaceNewOverlay = { asset -> viewModel.onPlaceNewOverlayClicked(asset) },
            onEditOverlay = { id -> viewModel.onPlaceExistingOverlayClicked(id) },
            onRemoveOverlay = { id -> viewModel.removeOverlay(id) },
          )
        }

        DropDownSpinner(
          isDropDownOpen = isLayoutDropdownExpanded,
          selectedOption = uiState.compositionLayout,
          dropDownOptions = uiState.availableLayouts,
          changeDropDownOpen = { isLayoutDropdownExpanded = it },
          changeSelectedOption = { newSelection ->
            viewModel.onCompositionLayoutChanged(newSelection)
            isLayoutDropdownExpanded = false
          },
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
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
      Row(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.small, 0.dp)) {
        Button(onClick = { viewModel.previewComposition() }) {
          Text(text = stringResource(R.string.preview))
        }
        Spacer(Modifier.weight(1f))
        if (shouldShowSupportingPaneButton) {
          Button(onClick = onNavigateToSupportingPane, enabled = !isOverlayPlacementActive) {
            Text(text = stringResource(R.string.export_settings))
          }
        }
      }
    }
  }

  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  private fun DraggableOverlay(
    bitmap: Bitmap,
    offset: Offset,
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
  ) {
    Box(
      modifier =
        modifier.clipToBounds().draggable2D(state = rememberDraggable2DState { onDrag(it) })
    ) {
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.overlay_preview),
        modifier =
          Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .wrapContentSize(align = Alignment.TopStart, unbounded = true),
      )
    }
  }

  @Composable
  fun OverlayEffectsList(
    overlayState: OverlayState,
    onPlaceNewOverlay: (OverlayAsset) -> Unit,
    onEditOverlay: (UUID) -> Unit,
    onRemoveOverlay: (UUID) -> Unit,
  ) {
    var showAssetSelectionDialog by remember { mutableStateOf(false) }

    if (showAssetSelectionDialog) {
      AssetSelectionDialog(
        onDismissRequest = { showAssetSelectionDialog = false },
        assetOptions = overlayState.availableOverlays,
        onAssetSelected = { asset ->
          onPlaceNewOverlay(asset)
          showAssetSelectionDialog = false
        },
      )
    }

    Column(
      modifier =
        Modifier.padding(vertical = 4.dp)
          .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
          .padding(MaterialTheme.spacing.small),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.placed_overlays),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = MaterialTheme.spacing.mini),
      )
      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

      LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
        items(overlayState.committedOverlays, key = { it.id }) { placedOverlay ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(text = placedOverlay.assetName, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onEditOverlay(placedOverlay.id) }) {
              Text(stringResource(R.string.edit))
            }
            IconButton(onClick = { onRemoveOverlay(placedOverlay.id) }) {
              Icon(
                Icons.TwoTone.Delete,
                contentDescription = stringResource(R.string.delete_overlay),
              )
            }
          }
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

      ElevatedButton(
        onClick = { showAssetSelectionDialog = true },
        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
      ) {
        Text(stringResource(R.string.add_new_overlay))
      }
    }
  }

  @Composable
  fun AssetSelectionDialog(
    onDismissRequest: () -> Unit,
    assetOptions: List<OverlayAsset>,
    onAssetSelected: (OverlayAsset) -> Unit,
  ) {
    Dialog(onDismissRequest = onDismissRequest) {
      Card(shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.standard)) {
          Text(
            text = stringResource(R.string.select_an_overlay_to_place),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.standard),
          )
          LazyColumn {
            items(assetOptions) { asset ->
              Text(
                text = asset.name,
                modifier =
                  Modifier.fillMaxWidth()
                    .clickable { onAssetSelected(asset) }
                    .padding(vertical = MaterialTheme.spacing.standard),
              )
            }
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
  fun VideoSequenceList(
    mediaState: MediaState,
    isEnabled: Boolean,
    onAddItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onUpdateEffects: (index: Int, effects: Set<String>) -> Unit,
  ) {
    var selectedMediaItemIndex by remember { mutableStateOf<Int?>(null) }
    var showEditMediaItemsDialog by remember { mutableStateOf(false) }

    if (showEditMediaItemsDialog) {
      VideoSequenceDialog(
        onDismissRequest = { showEditMediaItemsDialog = false },
        itemOptions = mediaState.availableItems,
        addSelectedVideo = { index -> onAddItem(index) },
      )
    }

    selectedMediaItemIndex?.let { index ->
      val item = mediaState.selectedItems[index]
      EffectSelectionDialog(
        onDismissRequest = { selectedMediaItemIndex = null },
        effectOptions = mediaState.availableEffects,
        currentSelections = item.selectedEffects,
        onEffectsSelected = { newEffects -> onUpdateEffects(index, newEffects) },
      )
    }

    Box(
      modifier =
        Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.small),
      ) {
        Text(
          text = stringResource(R.string.video_sequence_items),
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
          text = stringResource(R.string.add_effects_hint),
          fontSize = 12.sp,
          fontStyle = FontStyle.Italic,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
          itemsIndexed(mediaState.selectedItems) { index, item ->
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier.fillMaxWidth().clickable(enabled = isEnabled) {
                  selectedMediaItemIndex = index
                },
            ) {
              Column(modifier = Modifier.textPadding().weight(1f)) {
                Text(text = "${index + 1}. ${item.title}")
                val effectsText = item.selectedEffects.joinToString().ifEmpty { "None" }
                Text(
                  text = "Effect: $effectsText",
                  fontSize = 12.sp,
                  fontStyle = FontStyle.Italic,
                  color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
              }
              IconButton({ onRemoveItem(index) }, enabled = isEnabled) {
                Icon(Icons.TwoTone.Delete, contentDescription = "Remove item ${index + 1}")
              }
            }
          }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)
        ElevatedButton(
          onClick = { showEditMediaItemsDialog = true },
          enabled = isEnabled,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
          Text(text = stringResource(R.string.edit))
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
                Text(text = "${item.title} ($durationString)")
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
