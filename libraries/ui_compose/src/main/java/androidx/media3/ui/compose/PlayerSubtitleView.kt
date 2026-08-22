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

package androidx.media3.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

@Composable
@UnstableApi
fun PlayerSubtitleView(
    cueGroup: CueGroup?,
    modifier: Modifier = Modifier,
    videoSizeDp: IntSize,
    subtitleStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = DEFAULT_FONT_SIZE
    ),
    backgroundColor: Color = Color.Black.copy(alpha = 0.0f),
    sourceVideoWidth: Int,
    sourceVideoHeight: Int,
    forcePGSCenter: Boolean = false
) {
    if (cueGroup == null || cueGroup.cues.isEmpty()) {
        return
    }

    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()

    val videoSourceWidth = sourceVideoWidth

    val displayedVideoRect = if (videoSourceWidth > 0 && sourceVideoHeight > 0) {
        calculateDisplayedVideoRect(
            containerWidthDp = videoSizeDp.width,
            containerHeightDp = videoSizeDp.height,
            sourceWidthPx = videoSourceWidth,
            sourceHeightPx = sourceVideoHeight,
            density = density
        )
    } else {
        VideoDisplayRect(videoSizeDp.width.toFloat(), videoSizeDp.height.toFloat(), 0f, 0f)
    }

    Log.d("SubtitleView", "Video container: ${videoSizeDp.width}x${videoSizeDp.height}cueGroup.cues.size ${cueGroup.cues.size}")
    Log.d("SubtitleView", "Video source: ${videoSourceWidth}x$sourceVideoHeight")
    Log.d("SubtitleView", "Displayed video: ${displayedVideoRect.widthDp}x${displayedVideoRect.heightDp} at (${displayedVideoRect.offsetXDp}, ${displayedVideoRect.offsetYDp})")

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = if (modifier == Modifier) {
                Modifier.align(Alignment.BottomCenter)
            } else {
                modifier
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(SUBTITLE_SPACING),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                cueGroup.cues.forEach { cue ->
                    cue.text?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { text ->
                        val centeredTextStyle = subtitleStyle.copy(textAlign = TextAlign.Center)

                        val textLayoutResult = textMeasurer.measure(
                            text = text,
                            style = centeredTextStyle
                        )

                        val textWidthDp = textLayoutResult.size.width / density
                        val textHeightDp = textLayoutResult.size.height / density

                        Box(
                            modifier = Modifier
                                .padding(bottom = SUBTITLE_BOTTOM_PADDING)
                                .semantics { this.text = AnnotatedString(text) },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .width(textWidthDp.dp)
                                    .height(textHeightDp.dp)
                            ) {
                                if (backgroundColor.alpha > 0) {
                                    drawRect(
                                        color = backgroundColor,
                                        size = size
                                    )
                                }

                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = text,
                                    style = centeredTextStyle,
                                    topLeft = Offset.Zero
                                )
                            }
                        }
                    }
                }
            }
        }

        if (cueGroup.cues.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val validBitmapData = cueGroup.cues.mapNotNull { cue ->
                            cue.bitmap?.let { bmp ->
                                cue to bmp
                            }
                        }

                        val imageBitmaps = validBitmapData.map { it.second.asImageBitmap() }

                        onDrawWithContent {
                            validBitmapData.forEachIndexed { index, (cue, bitmap)->
                                val imageBitmap = imageBitmaps[index]

                                val (x, y, positionAnchor, lineAnchor) = if (forcePGSCenter) {
                                    SubtitleAnchorInfo(
                                        FORCE_PGS_CENTER_X,
                                        FORCE_PGS_CENTER_Y,
                                        Cue.ANCHOR_TYPE_MIDDLE,
                                        Cue.ANCHOR_TYPE_START
                                    )
                                } else {
                                    val resolvedX = if (cue.position != Cue.DIMEN_UNSET) cue.position.coerceIn(0f, 1f) else 0.5f
                                    val resolvedY = if (cue.line != Cue.DIMEN_UNSET) cue.line.coerceIn(0f, 1f) else 0.5f
                                    SubtitleAnchorInfo(
                                        resolvedX, resolvedY,
                                        cue.positionAnchor, cue.lineAnchor
                                    )
                                }

                                val originalBitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                                val videoRectWidthPx = displayedVideoRect.widthDp * density
                                val videoRectHeightPx = displayedVideoRect.heightDp * density
                                val videoOffsetX = displayedVideoRect.offsetXDp * density
                                val videoOffsetY = displayedVideoRect.offsetYDp * density

                                val (targetWidthPx, targetHeightPx) = run {
                                    if (forcePGSCenter) {
                                        bitmap.width.toFloat() to bitmap.height.toFloat()
                                    } else if (cue.size != Cue.DIMEN_UNSET) {
                                        val targetW = videoRectWidthPx * cue.size
                                        targetW to (targetW / originalBitmapAspectRatio)
                                    } else if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                                        val targetH = videoRectHeightPx * cue.bitmapHeight
                                        (targetH * originalBitmapAspectRatio) to targetH
                                    } else {
                                        val scale = if (videoSourceWidth > 0) videoRectWidthPx / videoSourceWidth.toFloat() else 1f
                                        (bitmap.width * scale) to (bitmap.height * scale)
                                    }
                                }

                                val contentOffsetX = when (positionAnchor) {
                                    Cue.ANCHOR_TYPE_START -> videoRectWidthPx * x
                                    Cue.ANCHOR_TYPE_MIDDLE -> videoRectWidthPx * x - targetWidthPx / 2
                                    Cue.ANCHOR_TYPE_END -> videoRectWidthPx * x - targetWidthPx
                                    else -> videoRectWidthPx * x
                                }

                                val contentOffsetY = when (lineAnchor) {
                                    Cue.ANCHOR_TYPE_START -> videoRectHeightPx * y
                                    Cue.ANCHOR_TYPE_MIDDLE -> videoRectHeightPx * y - targetHeightPx / 2
                                    Cue.ANCHOR_TYPE_END -> videoRectHeightPx * y - targetHeightPx
                                    else -> videoRectHeightPx * y
                                }

                                val finalX = videoOffsetX + contentOffsetX
                                val finalY = videoOffsetY + contentOffsetY

                                drawImage(
                                    image = imageBitmap,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(bitmap.width, bitmap.height),
                                    dstOffset = IntOffset(finalX.toInt(), finalY.toInt()),
                                    dstSize = IntSize(targetWidthPx.toInt(), targetHeightPx.toInt()),
                                    filterQuality = FilterQuality.Low
                                )
                            }
                        }
                    }
            )
        }
    }
}

/**
 * 数据类表示视频显示区域
 */
data class VideoDisplayRect(
    val widthDp: Float,
    val heightDp: Float,
    val offsetXDp: Float,
    val offsetYDp: Float
)

/**
 * 计算视频在容器中按 ContentScale.Fit（保持宽高比，居中）显示时的实际尺寸和偏移。
 *
 * @return VideoDisplayRect(显示宽度 dp, 显示高度 dp, X偏移 dp, Y偏移 dp)
 */
private fun calculateDisplayedVideoRect(
    containerWidthDp: Int,
    containerHeightDp: Int,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    density: Float
): VideoDisplayRect {
    val containerWidthPx = containerWidthDp * density
    val containerHeightPx = containerHeightDp * density

    val sourceAspectRatio = sourceWidthPx.toFloat() / sourceHeightPx.toFloat()
    val containerAspectRatio = containerWidthPx / containerHeightPx

    val (displayedWidthPx, displayedHeightPx) = if (sourceAspectRatio > containerAspectRatio) {
        val w = containerWidthPx
        val h = w / sourceAspectRatio
        w to h
    } else {
        val h = containerHeightPx
        val w = h * sourceAspectRatio
        w to h
    }

    val offsetX = (containerWidthPx - displayedWidthPx) / 2
    val offsetY = (containerHeightPx - displayedHeightPx) / 2

    return VideoDisplayRect(
        widthDp = displayedWidthPx / density,
        heightDp = displayedHeightPx / density,
        offsetXDp = offsetX / density,
        offsetYDp = offsetY / density
    )
}

private data class SubtitleAnchorInfo(
    val x: Float,
    val y: Float,
    val positionAnchor: Int,
    val lineAnchor: Int
)

private val DEFAULT_FONT_SIZE = 22.sp
private val SUBTITLE_SPACING = 4.dp
private val SUBTITLE_BOTTOM_PADDING = 8.dp
private const val FORCE_PGS_CENTER_X = 0.5f
private const val FORCE_PGS_CENTER_Y = 0.90f
