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

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.demo.compose.R
import androidx.media3.demo.compose.editing.ClippingSlider
import androidx.media3.demo.compose.viewmodel.PlayerLifecycleViewModel
import androidx.media3.demo.compose.viewmodel.rememberPlayerWithLifecycle
import androidx.media3.effect.Presentation
import androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import com.google.common.collect.ImmutableList
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

private const val BITMAP_COUNT = 8

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun EditingPlayerScreen(
  mediaItem: MediaItem,
  playerViewModel: PlayerLifecycleViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val player by rememberPlayerWithLifecycle(playerViewModel, mediaItem)
  val durationMs = rememberProgressStateWithTickCount(player).durationMs
  // A list state that will hold the extracted preview frames of the video for the clipping track.
  val bitmaps by rememberExtractedFrames(context, mediaItem, durationMs)
  var showCropping by rememberSaveable { mutableStateOf(false) }

  BackHandler(enabled = showCropping) { showCropping = false }

  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
    if (showCropping) {
      VideoCropperScreen(player = player, modifier = modifier.fillMaxSize())
    } else {
      EditingPlayerScreen(
        player = player,
        bitmaps = bitmaps,
        mediaItem = mediaItem,
        onCropClick = { showCropping = true },
        modifier = modifier.fillMaxSize(),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun EditingPlayerScreen(
  player: Player?,
  bitmaps: ImmutableList<Bitmap>,
  mediaItem: MediaItem,
  onCropClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
    Player(
      player = player,
      showControls = true,
      bottomControls = { player, showControls ->
        PlayerDefaults.BottomControls(
          player,
          showControls,
          modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
          above = {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(onClick = onCropClick) {
                Icon(
                  painter = painterResource(R.drawable.media3_icon_crop),
                  contentDescription = "Crop",
                )
              }
              MuteButton(player)
            }
          },
          progressSlider = {
            var clippingRange by remember(mediaItem) { mutableStateOf(0L..C.TIME_END_OF_SOURCE) }
            ClippingSlider(
              player = it,
              clippingRangeMs = clippingRange,
              onClippingRangeChange = { clippingRange = it },
              bitmaps = bitmaps,
              onClippingRangeChangeFinished = {
                Log.d("EditingPlayerScreen", "onClippingRangeChangeFinished")
              },
            )
          },
        )
      },
    )
  }
}

@Composable
private fun rememberExtractedFrames(
  context: Context,
  mediaItem: MediaItem,
  durationMs: Long,
  bitmapCount: Int = BITMAP_COUNT,
): State<ImmutableList<Bitmap>> {
  return produceState(initialValue = ImmutableList.of(), mediaItem, durationMs, bitmapCount) {
    if (durationMs <= 0L) return@produceState

    try {
      withContext(Dispatchers.IO) {
        FrameExtractor.Builder(context, mediaItem)
          .setEffects(
            listOf(Presentation.createForWidthAndHeight(50, 50, LAYOUT_SCALE_TO_FIT_WITH_CROP))
          )
          .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
          .build()
          .use { frameExtractor ->
            val positions =
              List(bitmapCount) { i -> i * durationMs / (bitmapCount - 1).coerceAtLeast(1) }
            val futureFrames = positions.map { positionMs -> frameExtractor.getFrame(positionMs) }
            // Fast-path: Await the first frame and immediately publish it to the UI
            val firstBitmap = futureFrames.first().await().bitmap
            value = ImmutableList.copyOf(Collections.nCopies(bitmapCount, firstBitmap))

            // Slow-path: Await all remaining frames and publish the final list
            val allBitmaps = futureFrames.map { it.await().bitmap }
            value = ImmutableList.copyOf(allBitmaps)
          }
      }
    } catch (e: CancellationException) {
      throw e // Don't swallow cancellation
    } catch (e: Exception) {
      Log.e("EditingPlayerScreen", "Error extracting frames", e)
    }
  }
}
