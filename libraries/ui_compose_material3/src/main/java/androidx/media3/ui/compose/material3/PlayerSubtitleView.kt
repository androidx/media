
/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.ui.compose.material3



import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import androidx.media3.common.text.Cue

import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import android.util.DisplayMetrics
import androidx.compose.ui.platform.LocalContext

/**
 * A Material3 composable that renders subtitles provided by a [CueGroup] from Media3.
 *
 * This component displays both text and bitmap cues according to their layout properties
 * (e.g., position, size, anchor). Text cues are rendered using [Text], while bitmap cues
 * are rendered using [Image]. The subtitle area is aligned to the bottom center of the
 * provided layout space by default.
 * Currently supports SRT, PSG, and ASS subtitles.
 * ASS subtitles are converted to SRT by ExoPlayer for display.
 * SRT subtitles with the same timestamp are displayed vertically in sequence.
 *
 * @param cueGroup The group of cues to display. If null or empty, nothing is rendered.
 * @param modifier The [Modifier] to be applied to the root layout.(Only affects SRT subtitles)
 * @param subtitleStyle The [TextStyle] used for rendering text cues. Defaults to
 *   [MaterialTheme.typography.bodyLarge] with white color and 18sp font size.(Only affects SRT subtitles)
 * @param backgroundColor The background color behind text cues. Defaults to fully
 *   transparent black ([Color.Black.copy(alpha = 0.0f)]).(Only affects SRT subtitles)
 *
 * @sample androidx.media3.ui.compose.material3.SubtitleViewSample
 *
 * Here is a basic usage example:
 *
 * ```
 * @Composable
 * fun VideoPlayerWithSubtitles(exoPlayer: ExoPlayer) {
 *   var currentCueGroup: CueGroup? by remember { mutableStateOf(null) }
 *
 *   DisposableEffect(exoPlayer) {
 *     val listener = object : Player.Listener {
 *       override fun onCues(cueGroup: CueGroup) {
 *         currentCueGroup = cueGroup
 *       }
 *     }
 *     exoPlayer.addListener(listener)
 *     onDispose {
 *       exoPlayer.removeListener(listener)
 *     }
 *   }
 *
 *   Box {
 *     // Your video surface or PlayerView here
 *     SubtitleView(
 *       cueGroup = currentCueGroup,
 *       subtitleStyle = MaterialTheme.typography.bodyLarge.copy(
 *         color = Color.White,
 *         fontSize = 20.sp
 *       ),
 *       backgroundColor = Color.Black.copy(alpha = 0.5f),
 *       modifier = Modifier.align(Alignment.BottomCenter)
 *     )
 *   }
 * }
 * ```
 */
@Composable
@UnstableApi
fun SubtitleView(
    cueGroup: CueGroup?,
    modifier: Modifier = Modifier,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color.White,
        fontSize = 18.sp
    ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.0f)
) {
    if (cueGroup == null || cueGroup.cues.isEmpty()) {
        return
    }

    val (screenWidthDp, screenHeightDp) = getScreenDimensions()

    Box(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            cueGroup.cues.forEach { cue ->
                // Render text cue
                cue.text?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { text ->
                    Text(
                        text = text,
                        style = subtitleStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .background(backgroundColor)
                    )
                }

                // Render bitmap cue
                cue.bitmap?.let { bitmap ->
                    val bitmapWidth = if (cue.size != Cue.DIMEN_UNSET) {
                        (screenWidthDp * cue.size).toFloat()
                    } else {
                        bitmap.width.toFloat()
                    }

                    val bitmapHeight = if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                        (screenHeightDp * cue.bitmapHeight).toFloat()
                    } else {
                        bitmap.height.toFloat()
                    }
                    val x =  cue.position
                    val y =  cue.line
                    val offsetX = when (cue.positionAnchor) {
                        Cue.ANCHOR_TYPE_START -> screenWidthDp * x
                        Cue.ANCHOR_TYPE_MIDDLE -> (screenWidthDp * x) - (bitmapWidth / 2)
                        Cue.ANCHOR_TYPE_END -> (screenWidthDp * x) - bitmapWidth
                        else -> screenWidthDp * x
                    }

                    val offsetY = when (cue.lineAnchor) {
                        Cue.ANCHOR_TYPE_START -> screenHeightDp * y
                        Cue.ANCHOR_TYPE_MIDDLE -> (screenHeightDp * y) - (bitmapHeight / 2)
                        Cue.ANCHOR_TYPE_END -> (screenHeightDp * y) - bitmapHeight
                        else -> screenHeightDp * y
                    }

                    Box(
                        modifier = Modifier
                            .offset(x = offsetX.dp-14.dp , y = offsetY.dp-8.dp) // Adjust the offset as needed
                            .fillMaxSize()
                            .zIndex(cue.zIndex.toFloat())
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Subtitle bitmap cue",
                            modifier = Modifier
                                .width(bitmapWidth.dp)
                                .height(bitmapHeight.dp),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns the current screen dimensions in dp.
 *Fixes the accurate calculation of the app-perceived screen dimensions in dp across devices with different DPIs
 *  (e.g., phones at 320 dpi, TVs at 240 dpi), ensuring PSG subtitles are correctly positioned.
 * @return A [Pair] of [Int] values representing the screen width and height in dp.
 */

@Composable
private fun getScreenDimensions(): Pair<Int, Int> {
    val context = LocalContext.current
    val displayMetrics: DisplayMetrics = context.resources.displayMetrics
    val widthDp = (displayMetrics.widthPixels / displayMetrics.density).toInt()
    val heightDp = (displayMetrics.heightPixels / displayMetrics.density).toInt()
    return Pair(widthDp, heightDp)
}