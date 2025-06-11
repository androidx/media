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

import android.content.pm.ActivityInfo
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.LocaleList
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.media3.common.MimeTypes
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.COMPOSITION_LAYOUT
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.HDR_MODE_DESCRIPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.LAYOUT_EXTRA
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MUXER_OPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.RESOLUTION_HEIGHTS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.SAME_AS_INPUT_OPTION
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

    val compositionLayout = intent.getStringExtra(LAYOUT_EXTRA) ?: COMPOSITION_LAYOUT[0]
    Log.d(TAG, "Received layout of $compositionLayout")
    val viewModel: CompositionPreviewViewModel by viewModels {
      CompositionPreviewViewModelFactory(application, compositionLayout)
    }

    // TODO(b/417361559): Replace Toast with Snackbar
    viewModel.toastMessage.observe(this) { newMessage ->
      newMessage?.let {
        viewModel.toastMessage.value = null
        Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
      }
    }

    setContent {
      CompositionDemoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
                )
              }
            },
            supportingPane = {
              AnimatedPane {
                ExportOptionsPane(
                  viewModel,
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
    modifier: Modifier = Modifier,
  ) {
    val scrollState = rememberScrollState()
    Column {
      Text(
        text = "${viewModel.compositionLayout} ${stringResource(R.string.preview_composition)}",
        fontWeight = FontWeight.Bold,
      )
      AndroidView(
        factory = { context -> PlayerView(context) },
        update = { playerView ->
          playerView.player = viewModel.compositionPlayer
          playerView.setTimeBarScrubbingEnabled(true)
        },
        modifier = Modifier.heightIn(max = 250.dp),
      )
      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
        modifier = modifier.weight(1f).verticalScroll(scrollState),
      ) {
        VideoSequenceList(viewModel)
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
            viewModel.includeBackgroundAudioTrack,
            { checked -> viewModel.includeBackgroundAudioTrack = checked },
          )
        }
        OutputSettings(viewModel)
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
    viewModel: CompositionPreviewViewModel,
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
          selectedOption = viewModel.outputAudioMimeType,
          dropDownOptions =
            listOf(
              SAME_AS_INPUT_OPTION,
              MimeTypes.AUDIO_AAC,
              MimeTypes.AUDIO_AMR_NB,
              MimeTypes.AUDIO_AMR_WB,
            ),
          changeDropDownOpen = { expanded -> isAudioTypeExpanded = expanded },
          changeSelectedOption = { selection -> viewModel.outputAudioMimeType = selection },
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
          selectedOption = viewModel.outputVideoMimeType,
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
          changeSelectedOption = { selection -> viewModel.outputVideoMimeType = selection },
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
        val debugTracingEnabled by viewModel.enableDebugTracing.collectAsState()
        Switch(debugTracingEnabled, { checked -> viewModel.enableDebugTracing(checked) })
      }
      Column(Modifier.selectableGroup()) {
        MUXER_OPTIONS.forEach { text ->
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
              Modifier.selectable(
                  selected = text == viewModel.muxerOption,
                  onClick = { viewModel.muxerOption = text },
                  role = Role.RadioButton,
                )
                .fillMaxWidth(),
          ) {
            Text(text = text, modifier = Modifier.textPadding())
            RadioButton(selected = (text == viewModel.muxerOption), onClick = null)
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
        Button({ viewModel.exportComposition() }) { Text(text = stringResource(R.string.export)) }
      }
      viewModel.exportResultInformation?.let {
        HorizontalDivider(
          thickness = 2.dp,
          modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
        )
        Text(text = it)
      }
    }
  }

  @Composable
  fun VideoSequenceList(viewModel: CompositionPreviewViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
      VideoSequenceDialog(
        { showDialog = false },
        viewModel.mediaItemOptions,
        { index -> viewModel.addItem(index) },
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
          itemsIndexed(viewModel.selectedMediaItems) { index, item ->
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "${index + 1}. ${item.title}",
                modifier = Modifier.textPadding().weight(1f),
              )
              Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconToggleButton(
                  checked = item.applyEffects.value,
                  onCheckedChange = { checked -> viewModel.updateEffects(index, checked) },
                ) {
                  Icon(
                    imageVector =
                      if (item.applyEffects.value) Icons.Filled.Star else Icons.TwoTone.Star,
                    contentDescription = "Apply effects to item ${index + 1}",
                  )
                }
                IconButton({ viewModel.removeItem(index) }) {
                  Icon(Icons.TwoTone.Delete, contentDescription = "Remove item ${index + 1}")
                }
              }
            }
          }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)
        ElevatedButton(
          onClick = { showDialog = true },
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
    itemOptions: List<CompositionPreviewViewModel.Item>,
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
                  Icon(Icons.Filled.Add, contentDescription = "Add item")
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
  fun OutputSettings(viewModel: CompositionPreviewViewModel) {
    var resolutionExpanded by remember { mutableStateOf(false) }
    var hdrExpanded by remember { mutableStateOf(false) }
    var selectedHdrMode by remember { mutableStateOf(HDR_MODE_DESCRIPTIONS.keys.first()) }

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
          selectedOption = viewModel.outputResolution,
          dropDownOptions = RESOLUTION_HEIGHTS,
          changeDropDownOpen = { newExpanded -> resolutionExpanded = newExpanded },
          changeSelectedOption = { newSelection -> viewModel.outputResolution = newSelection },
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
          selectedOption = selectedHdrMode,
          dropDownOptions = HDR_MODE_DESCRIPTIONS.keys.toList(),
          changeDropDownOpen = { newExpanded -> hdrExpanded = newExpanded },
          changeSelectedOption = { newSelection ->
            selectedHdrMode = newSelection
            viewModel.outputHdrMode =
              HDR_MODE_DESCRIPTIONS[newSelection] ?: Composition.HDR_MODE_KEEP_HDR
          },
        )
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
