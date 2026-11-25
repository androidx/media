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

package androidx.media3.demo.compose.layout

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.demo.compose.motionphoto.PlaybackRangeIndicator
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.motionphoto.MotionPhoto
import androidx.media3.ui.compose.motionphoto.MotionPhotoConfig
import androidx.media3.ui.compose.motionphoto.rememberMotionPhotoState
import java.util.Locale

private const val OFFSET_STEP_MS = 500L
private const val MAX_OFFSET_MS = 2_000L

private val PRESETS =
  listOf(
    "Default" to MotionPhotoConfig.DEFAULT,
    "Play to photo" to MotionPhotoConfig.PLAY_TO_PHOTO,
    "Loop" to MotionPhotoConfig.LOOP,
  )

private val BOUNDARIES =
  listOf(
    MotionPhotoConfig.Boundary.START_OF_VIDEO,
    MotionPhotoConfig.Boundary.PHOTO_FRAME,
    MotionPhotoConfig.Boundary.END_OF_VIDEO,
  )

private enum class ConfigMode {
  PRESETS,
  CUSTOM,
}

/**
 * Screen for displaying and interacting with a Motion Photo in the Compose demo.
 *
 * @param mediaItem The [MediaItem] representing the Motion Photo.
 * @param modifier The [Modifier] to be applied to the layout.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
internal fun MotionPhotoScreen(mediaItem: MediaItem, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  @OptIn(ExperimentalApi::class)
  val player =
    remember(context) {
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context)
            .setEnableClippingInMediaPeriod(true)
            .setAlwaysWrapInClippingMediaSource(true)
        )
        .build()
        .apply { addAnalyticsListener(EventLogger()) }
    }
  DisposableEffect(player) { onDispose { player.release() } }

  val appContext = context.applicationContext
  val bitmapLoader = remember(appContext) { DataSourceBitmapLoader.Builder(appContext).build() }
  val motionPhotoState = rememberMotionPhotoState(player = player, mediaItem = mediaItem)

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, motionPhotoState, player) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
          player.prepare()
        }
      } else if (event == Lifecycle.Event.ON_STOP) {
        motionPhotoState.showStillPhoto()
        player.stop()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  var configMode by remember(motionPhotoState) { mutableStateOf<ConfigMode?>(null) }
  var showCustomSheet by remember(motionPhotoState) { mutableStateOf(false) }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    MotionPhoto(
      state = motionPhotoState,
      contentDescription = mediaItem.mediaMetadata.title?.toString(),
      modifier = Modifier.fillMaxSize().noRippleClickable(motionPhotoState::toggle),
      bitmapLoader = bitmapLoader,
    )

    val chipColors =
      FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
      )
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (configMode == ConfigMode.PRESETS) {
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for ((label, config) in PRESETS) {
            FilterChip(
              selected = motionPhotoState.config == config,
              onClick = { motionPhotoState.config = config },
              label = { Text(label) },
              colors = chipColors,
            )
          }
        }
      }
      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
          selected = configMode == ConfigMode.PRESETS,
          onClick = {
            if (configMode != ConfigMode.PRESETS) {
              configMode = ConfigMode.PRESETS
              showCustomSheet = false
              motionPhotoState.config = MotionPhotoConfig.DEFAULT
            }
          },
          label = { Text("Presets") },
          colors = chipColors,
        )
        FilterChip(
          selected = configMode == ConfigMode.CUSTOM,
          onClick = {
            if (configMode != ConfigMode.CUSTOM) {
              configMode = ConfigMode.CUSTOM
              motionPhotoState.config = MotionPhotoConfig.DEFAULT
            }
            showCustomSheet = true
          },
          label = { Text("Custom") },
          colors = chipColors,
        )
      }
    }

    if (showCustomSheet) {
      ModalBottomSheet(
        onDismissRequest = { showCustomSheet = false },
        sheetState =
          rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
          ),
      ) {
        val config = motionPhotoState.config
        PlaybackRangeIndicator(
          player = player,
          config = config,
          modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .weight(1f, fill = false)
              .verticalScroll(rememberScrollState())
              .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          BoundaryControl(
            title = "Start",
            boundary = config.rangeStart,
            onChange = { rangeStart ->
              motionPhotoState.config = motionPhotoState.config.copy(rangeStart = rangeStart)
            },
          )
          BoundaryControl(
            title = "End",
            boundary = config.rangeEnd,
            onChange = { rangeEnd ->
              motionPhotoState.config = motionPhotoState.config.copy(rangeEnd = rangeEnd)
            },
          )
          HorizontalDivider()
          ConfigSwitchRow(
            label = "Loop motion",
            checked = config.loopMode != MotionPhotoConfig.LOOP_MODE_OFF,
            onCheckedChange = { isLooping ->
              motionPhotoState.config =
                motionPhotoState.config.copy(
                  loopMode =
                    if (isLooping) MotionPhotoConfig.LOOP_MODE_FROM_RANGE_START
                    else MotionPhotoConfig.LOOP_MODE_OFF
                )
            },
          )
          ConfigSwitchRow(
            label = "Resume from paused motion",
            supportingText = "Rewinds to start when off",
            checked = config.resumeVideoFromPreviousPosition,
            onCheckedChange = { resume ->
              motionPhotoState.config =
                motionPhotoState.config.copy(resumeVideoFromPreviousPosition = resume)
            },
          )
        }
      }
    }
  }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoundaryControl(
  title: String,
  boundary: MotionPhotoConfig.Boundary,
  onChange: (MotionPhotoConfig.Boundary) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      BOUNDARIES.forEachIndexed { index, option ->
        SegmentedButton(
          selected = option == boundary.origin,
          onClick = { onChange(option.plusMs(boundary.offsetMs)) },
          shape = SegmentedButtonDefaults.itemShape(index = index, count = BOUNDARIES.size),
          label = { Text(boundaryLabel(option), maxLines = 1) },
        )
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalIconButton(
        onClick = { onChange(boundary.minusMs(OFFSET_STEP_MS)) },
        enabled = boundary.offsetMs - OFFSET_STEP_MS >= -MAX_OFFSET_MS,
      ) {
        Icon(
          painterResource(R.drawable.media3_icon_minus),
          contentDescription = "Decrease ${title.lowercase(Locale.ROOT)} offset",
        )
      }
      Text(
        text = formatOffsetMs(boundary.offsetMs),
        modifier = Modifier.width(72.dp),
        textAlign = TextAlign.Center,
      )
      FilledTonalIconButton(
        onClick = { onChange(boundary.plusMs(OFFSET_STEP_MS)) },
        enabled = boundary.offsetMs + OFFSET_STEP_MS <= MAX_OFFSET_MS,
      ) {
        Icon(
          painterResource(R.drawable.media3_icon_plus),
          contentDescription = "Increase ${title.lowercase(Locale.ROOT)} offset",
        )
      }
      Text(offsetDescription(boundary), style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun ConfigSwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  supportingText: String? = null,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
      if (supportingText != null) {
        Text(
          supportingText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

private fun offsetDescription(boundary: MotionPhotoConfig.Boundary): String {
  val boundaryName = boundaryLabel(boundary.origin).lowercase(Locale.ROOT)
  return when {
    boundary.offsetMs > 0L -> "${boundary.offsetMs}ms after $boundaryName"
    boundary.offsetMs < 0L -> "${-boundary.offsetMs}ms before $boundaryName"
    else -> "at $boundaryName"
  }
}

private fun boundaryLabel(origin: MotionPhotoConfig.Boundary): String =
  when (origin) {
    MotionPhotoConfig.Boundary.PHOTO_FRAME -> "Photo frame"
    MotionPhotoConfig.Boundary.START_OF_VIDEO -> "Video start"
    else -> "Video end"
  }

private fun formatOffsetMs(offsetMs: Long): String =
  if (offsetMs > 0L) "+${offsetMs}ms" else "${offsetMs}ms"
