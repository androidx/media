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
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.ui.CompositionPreviewPane
import androidx.media3.demo.composition.ui.ExportOptions
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme
import androidx.media3.demo.composition.ui.theme.spacing

/**
 * An Activity ([AppCompatActivity]) that previews compositions, using
 * [androidx.media3.transformer.CompositionPlayer].
 */
@OptIn(ExperimentalMaterial3Api::class)
class CompositionPreviewActivity : AppCompatActivity() {
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
      viewModelFactory { initializer { CompositionPreviewViewModel(application) } }
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
        var showExportSheet by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        CompositionDemoMainScreen(
          Modifier.fillMaxSize(),
          snackbarHostState,
          showExportSheet,
          { showExportSheet = true },
          { showExportSheet = false },
          viewModel,
          uiState,
          sheetState,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositionDemoMainScreen(
  modifier: Modifier = Modifier,
  snackbarHostState: SnackbarHostState,
  showExportSheet: Boolean,
  onShowSheet: () -> Unit,
  onHideSheet: () -> Unit,
  viewModel: CompositionPreviewViewModel,
  uiState: CompositionPreviewState,
  sheetState: SheetState,
) {
  Scaffold(modifier, snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding
    ->
    CompositionPreviewPane(
      modifier =
        Modifier.padding(innerPadding)
          .padding(MaterialTheme.spacing.standard, MaterialTheme.spacing.small),
      onOpenExportOptions = onShowSheet,
      viewModel = viewModel,
      uiState = uiState,
    )

    if (showExportSheet) {
      ModalBottomSheet(onDismissRequest = onHideSheet, sheetState = sheetState) {
        ExportOptions(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.standard)
              .padding(bottom = MaterialTheme.spacing.standard.times(2)),
          outputSettings = uiState.outputSettingsState,
          exportState = uiState.exportState,
          isDebugTracingEnabled = uiState.isDebugTracingEnabled,
          onAudioMimeTypeChanged = viewModel::onAudioMimeTypeChanged,
          onVideoMimeTypeChanged = viewModel::onVideoMimeTypeChanged,
          onMuxerOptionChanged = viewModel::onMuxerOptionChanged,
          onDebugTracingChanged = viewModel::enableDebugTracing,
          onExport = viewModel::exportComposition,
          onCancel = onHideSheet,
        )
      }
    }
  }
}
