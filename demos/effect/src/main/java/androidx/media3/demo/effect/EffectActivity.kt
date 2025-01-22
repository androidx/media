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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.SDK_INT
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.launch

class EffectActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val playlistHolderList = mutableStateOf<List<PlaylistHolder>>(emptyList())
    lifecycleScope.launch {
      playlistHolderList.value =
        loadPlaylistsFromJson(JSON_FILENAME, this@EffectActivity, "EffectActivity")
    }
    setContent { EffectDemo(playlistHolderList.value) }
  }

  @OptIn(UnstableApi::class)
  @Composable
  private fun EffectDemo(playlistHolderList: List<PlaylistHolder>) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val exoPlayer by remember {
      mutableStateOf(ExoPlayer.Builder(context).build().apply { playWhenReady = true })
    }
    var effectsEnabled by remember { mutableStateOf(false) }

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
          playlistHolderList,
          onException = { message ->
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
          },
        ) { mediaItems ->
          effectsEnabled = true
          exoPlayer.apply {
            setMediaItems(mediaItems)
            setVideoEffects(emptyList())
            prepare()
          }
        }
        PlayerScreen(exoPlayer)
        EffectControls(
          effectsEnabled,
          onApplyEffectsClicked = { videoEffects ->
            exoPlayer.apply {
              setVideoEffects(videoEffects)
              prepare()
            }
          },
        )
      }
    }
  }

  @Composable
  private fun InputChooser(
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
            onException(getString(R.string.can_not_open_file_error))
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
            onException(getString(R.string.permission_not_granted_error))
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

  @Composable
  private fun PlayerScreen(exoPlayer: ExoPlayer) {
    val context = LocalContext.current
    AndroidView(
      factory = { PlayerView(context).apply { player = exoPlayer } },
      modifier =
        Modifier.height(dimensionResource(id = R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.regular_padding)),
    )
  }

  @OptIn(UnstableApi::class)
  @Composable
  private fun EffectControls(enabled: Boolean, onApplyEffectsClicked: (List<Effect>) -> Unit) {
    var effectControlsState by remember { mutableStateOf(EffectControlsState()) }

    Button(
      enabled = enabled && effectControlsState.effectsChanged,
      onClick = {
        val effectsList = mutableListOf<Effect>()

        if (effectControlsState.contrastValue != 0f) {
          effectsList += Contrast(effectControlsState.contrastValue)
        }

        val overlaysBuilder = ImmutableList.builder<TextureOverlay>()
        if (effectControlsState.confettiOverlayChecked) {
          overlaysBuilder.add(ConfettiOverlay())
        }
        effectsList += OverlayEffect(overlaysBuilder.build())

        onApplyEffectsClicked(effectsList)
        effectControlsState = effectControlsState.copy(effectsChanged = false)
      },
    ) {
      Text(text = stringResource(id = R.string.apply_effects))
    }

    EffectControlsList(enabled, effectControlsState) { newEffectControlsState ->
      effectControlsState = newEffectControlsState
    }
  }

  @Composable
  private fun EffectControlsList(
    enabled: Boolean,
    effectControlsState: EffectControlsState,
    onEffectControlsStateChange: (EffectControlsState) -> Unit,
  ) {
    LazyColumn(Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding))) {
      item {
        EffectItem(
          name = stringResource(id = R.string.contrast),
          enabled = enabled,
          onCheckedChange = {
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, contrastValue = 0f)
            )
          },
        ) {
          Row {
            Text(
              text = "%.2f".format(effectControlsState.contrastValue),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
            )
            Slider(
              value = effectControlsState.contrastValue,
              onValueChange = { newContrastValue ->
                val newRoundedContrastValue = "%.2f".format(newContrastValue).toFloat()
                onEffectControlsStateChange(
                  effectControlsState.copy(
                    effectsChanged = true,
                    contrastValue = newRoundedContrastValue,
                  )
                )
              },
              valueRange = -1f..1f,
              modifier = Modifier.weight(4f),
            )
          }
        }
      }
      item {
        EffectItem(
          name = stringResource(R.string.confetti_overlay),
          enabled = enabled,
          onCheckedChange = { checked ->
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, confettiOverlayChecked = checked)
            )
          },
        )
      }
    }
  }

  @Composable
  fun EffectItem(
    name: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit = {},
  ) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Card(
      modifier =
        Modifier.padding(
            vertical = dimensionResource(id = R.dimen.small_padding),
            horizontal = dimensionResource(id = R.dimen.regular_padding),
          )
          .clickable(enabled = enabled && !checked) {
            checked = !checked
            onCheckedChange(checked)
          }
    ) {
      Column(
        Modifier.padding(dimensionResource(id = R.dimen.large_padding))
          .animateContentSize(animationSpec = tween(durationMillis = 200, easing = LinearEasing))
      ) {
        Row {
          Column(Modifier.weight(1f).padding(dimensionResource(id = R.dimen.large_padding))) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
          }
          Checkbox(
            enabled = enabled,
            checked = checked,
            onCheckedChange = {
              checked = !checked
              onCheckedChange(checked)
            },
          )
        }
        if (checked) {
          content()
        }
      }
    }
  }

  data class EffectControlsState(
    val effectsChanged: Boolean = false,
    val contrastValue: Float = 0f,
    val confettiOverlayChecked: Boolean = false,
  )

  companion object {
    const val JSON_FILENAME = "media.playlist.json"
  }
}
