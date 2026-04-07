/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.demo.effect.ui

import android.Manifest
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.PlaylistHolder
import androidx.media3.demo.effect.R

@Composable
internal fun InputSelector(
  playlistHolderList: List<PlaylistHolder>,
  onException: (String) -> Unit,
  onNewMediaItems: (List<MediaItem>) -> Unit,
) {
  var showPresetInputChooser by remember { mutableStateOf(false) }
  var showLocalFileChooser by remember { mutableStateOf(false) }
  Row(
    Modifier.padding(vertical = dimensionResource(id = R.dimen.regular_padding)),
    horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.regular_padding)),
  ) {
    Button(onClick = { showPresetInputChooser = true }) {
      Text(text = stringResource(id = R.string.choose_preset_input))
    }
    Button(onClick = { showLocalFileChooser = true }) {
      Text(text = stringResource(id = R.string.choose_local_file))
    }
  }
  if (showPresetInputChooser) {
    if (playlistHolderList.isNotEmpty()) {
      PresetInputChooser(
        playlistHolderList,
        onDismissRequest = { showPresetInputChooser = false },
      ) { mediaItems ->
        onNewMediaItems(mediaItems)
        showPresetInputChooser = false
      }
    } else {
      onException(stringResource(id = R.string.no_loaded_playlists_error))
      showPresetInputChooser = false
    }
  }
  if (showLocalFileChooser) {
    LocalFileChooser(
      onException = { message ->
        onException(message)
        showLocalFileChooser = false
      }
    ) { mediaItems ->
      onNewMediaItems(mediaItems)
      showLocalFileChooser = false
    }
  }
}

@Composable
private fun PresetInputChooser(
  playlistHolderList: List<PlaylistHolder>,
  onDismissRequest: () -> Unit,
  onInputSelected: (List<MediaItem>) -> Unit,
) {
  var selectedOption by remember { mutableStateOf(playlistHolderList.first()) }

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(stringResource(id = R.string.choose_preset_input)) },
    confirmButton = {
      Button(onClick = { onInputSelected(selectedOption.mediaItems) }) {
        Text(text = stringResource(id = R.string.ok))
      }
    },
    text = {
      Column {
        playlistHolderList.forEach { playlistHolder ->
          Row(
            Modifier.fillMaxWidth()
              .selectable(
                (playlistHolder == selectedOption),
                onClick = { selectedOption = playlistHolder },
              ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = (playlistHolder == selectedOption),
              onClick = { selectedOption = playlistHolder },
            )
            Text(playlistHolder.title)
          }
        }
      }
    },
  )
}

@OptIn(UnstableApi::class)
@Composable
private fun LocalFileChooser(
  onException: (String) -> Unit,
  onFileSelected: (List<MediaItem>) -> Unit,
) {
  val context = LocalContext.current
  val localFileChooserLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument(),
      onResult = { uri: Uri? ->
        if (uri != null) {
          onFileSelected(listOf(MediaItem.fromUri(uri)))
        } else {
          onException(context.getString(R.string.can_not_open_file_error))
        }
      },
    )
  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
      onResult = { isGranted: Boolean ->
        if (isGranted) {
          localFileChooserLauncher.launch(arrayOf("video/*"))
        } else {
          onException(context.getString(R.string.permission_not_granted_error))
        }
      },
    )
  LaunchedEffect(Unit) {
    val permission =
      if (SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
      else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionCheck = ContextCompat.checkSelfPermission(context, permission)
    if (permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      localFileChooserLauncher.launch(arrayOf("video/*"))
    } else {
      permissionLauncher.launch(permission)
    }
  }
}
