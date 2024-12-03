/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.effect

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.SDK_INT
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

class EffectActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { EffectDemo() }
  }

  @Composable
  fun EffectDemo() {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val exoPlayer by remember {
      mutableStateOf(ExoPlayer.Builder(context).build().apply { playWhenReady = true })
    }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
      Column(
        modifier = Modifier.fillMaxWidth().padding(paddingValues),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        InputChooser(
          onException = { coroutineScope.launch { snackbarHostState.showSnackbar(it) } }
        ) { uri ->
          exoPlayer.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
          }
        }
        PlayerScreen(exoPlayer)
        Effects(
          onException = { message ->
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
          }
        )
      }
    }
  }

  @Composable
  fun InputChooser(onException: (String) -> Unit, onNewUri: (Uri) -> Unit) {
    var showLocalFilePicker by remember { mutableStateOf(false) }
    Row(
      modifier = Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding)),
      horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_padding)),
    ) {
      Button(onClick = { onException("Button is not yet implemented.") }) {
        Text(text = stringResource(id = R.string.choose_preset_input))
      }
      Button(onClick = { showLocalFilePicker = true }) {
        Text(text = stringResource(id = R.string.choose_local_file))
      }
    }
    if (showLocalFilePicker) {
      LocalFilePicker(
        onException = {
          onException(it)
          showLocalFilePicker = false
        }
      ) { uri ->
        onNewUri(uri)
        showLocalFilePicker = false
      }
    }
  }

  @OptIn(UnstableApi::class)
  @Composable
  fun LocalFilePicker(onException: (String) -> Unit, onFileSelected: (Uri) -> Unit) {
    val context = LocalContext.current
    val localFilePickerLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
          if (uri != null) {
            onFileSelected(uri)
          } else {
            onException("File couldn't be opened. Please try again.")
          }
        },
      )
    val permissionLauncher =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
          if (isGranted) {
            localFilePickerLauncher.launch(arrayOf("video/*"))
          } else {
            onException("Permission was not granted.")
          }
        },
      )
    LaunchedEffect(Unit) {
      val permission =
        if (SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
      val permissionCheck = ContextCompat.checkSelfPermission(context, permission)
      if (permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        localFilePickerLauncher.launch(arrayOf("video/*"))
      } else {
        permissionLauncher.launch(permission)
      }
    }
  }

  @Composable
  fun PlayerScreen(exoPlayer: ExoPlayer) {
    val context = LocalContext.current
    AndroidView(
      factory = { PlayerView(context).apply { player = exoPlayer } },
      modifier =
        Modifier.height(dimensionResource(id = R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.small_padding)),
    )
  }

  @Composable
  fun Effects(onException: (String) -> Unit) {
    Button(onClick = { onException("Button is not yet implemented.") }) {
      Text(text = stringResource(id = R.string.apply_effects))
    }
  }
}
