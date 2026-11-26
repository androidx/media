/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.compose.motionphoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import androidx.media3.ui.compose.motionphoto.MotionPhotoConfig
import androidx.media3.ui.compose.state.rememberErrorState

private val INDICATOR_TRACK_HEIGHT = 8.dp
private val INDICATOR_TRACK_BORDER_WIDTH = 1.dp
private val INDICATOR_TICK_HEIGHT = 16.dp
private val INDICATOR_TICK_WIDTH = 2.dp
private val INDICATOR_TICK_GAP = 2.dp
private val INDICATOR_EMPTY_MARKER_RADIUS = 4.5.dp
private val INDICATOR_EMPTY_MARKER_BORDER_WIDTH = 1.5.dp

/**
 * Displays a read-only visual indicator of a Motion Photo's playback range for the entered [config]
 * against [player]'s video duration and container photo frame metadata, including the full video
 * span, active range (or empty-range markers), photo frame marker, and millisecond summary text.
 *
 * Loading, error, zero-duration, and resolved states share the same vertical layout so surrounding
 * controls stay put as video metadata arrives.
 *
 * @param player The [Player] providing the video duration, container [MotionPhotoMetadata], and
 *   playback error state.
 * @param config The current [MotionPhotoConfig] entered in the UI.
 * @param modifier The [Modifier] to be applied to the indicator.
 * @param containerColor The surface color behind the indicator, used to carve a contrast gap around
 *   the photo frame tick and empty-range markers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackRangeIndicator(
  player: Player,
  config: MotionPhotoConfig,
  modifier: Modifier = Modifier,
  containerColor: Color = BottomSheetDefaults.ContainerColor,
) {
  val playerError = rememberErrorState(player).error
  var videoInfo by remember(player) { mutableStateOf(readPlayerVideoInfo(player)) }
  LaunchedEffect(player) {
    videoInfo = readPlayerVideoInfo(player)
    player.listenTo(
      Player.EVENT_TIMELINE_CHANGED,
      Player.EVENT_MEDIA_ITEM_TRANSITION,
      Player.EVENT_TRACKS_CHANGED,
      Player.EVENT_PLAYBACK_STATE_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) {
      videoInfo = readPlayerVideoInfo(player)
    }
  }
  val playbackRange = resolvePlaybackRange(videoInfo, config)
  val resolvedRange = playbackRange?.takeIf { it.videoDurationMs > 0L }
  val usesPhotoFrameBoundary =
    config.rangeStart.origin == MotionPhotoConfig.Boundary.PHOTO_FRAME ||
      config.rangeEnd.origin == MotionPhotoConfig.Boundary.PHOTO_FRAME
  val hasPhotoFrame = resolvedRange != null && resolvedRange.photoFramePositionMs != C.TIME_UNSET
  val clampedPhotoFrameMs =
    if (resolvedRange != null && hasPhotoFrame) {
      minOf(resolvedRange.photoFramePositionMs, resolvedRange.videoDurationMs)
    } else {
      C.TIME_UNSET
    }

  val topLineText: String
  val topLineAlignment: Alignment.Horizontal
  when {
    resolvedRange != null && hasPhotoFrame -> {
      val photoFraction = clampedPhotoFrameMs.toFloat() / resolvedRange.videoDurationMs
      topLineText = "Photo frame (${resolvedRange.photoFramePositionMs}ms)"
      topLineAlignment = BiasAlignment.Horizontal(2f * photoFraction - 1f)
    }
    resolvedRange != null && usesPhotoFrameBoundary -> {
      topLineText = "No photo frame in this file, using video end"
      topLineAlignment = Alignment.Start
    }
    else -> {
      // Reserve the label row's height while loading or when no photo-frame status applies.
      topLineText = ""
      topLineAlignment = Alignment.Start
    }
  }

  val videoStartLabel = if (resolvedRange != null) "Video start (0ms)" else "Video start"
  val videoEndLabel =
    if (resolvedRange != null) "Video end (${resolvedRange.videoDurationMs}ms)" else "Video end"

  val summaryText: String
  val isErrorSummary: Boolean
  val isPlaceholderSummary: Boolean
  val accessibilityDescription: String
  if (resolvedRange == null) {
    summaryText =
      when {
        playerError != null -> "Couldn't load the video"
        playbackRange == null -> "Waiting for video…"
        else -> "No motion in this file"
      }
    isErrorSummary = playerError != null
    isPlaceholderSummary = playerError == null
    accessibilityDescription = if (summaryText.endsWith('…')) summaryText else "$summaryText."
  } else if (resolvedRange.isEmpty) {
    summaryText =
      "Nothing to play: the end (${resolvedRange.endPositionMs}ms) is not after the start" +
        " (${resolvedRange.startPositionMs}ms)"
    isErrorSummary = true
    isPlaceholderSummary = false
    accessibilityDescription = buildString {
      append(
        "Nothing to play, end at ${resolvedRange.endPositionMs}ms is not after start at" +
          " ${resolvedRange.startPositionMs}ms. Video length ${resolvedRange.videoDurationMs}ms"
      )
      appendPhotoFrameAccessibility(resolvedRange, hasPhotoFrame, usesPhotoFrameBoundary)
    }
  } else {
    val rangeDurationMs = resolvedRange.endPositionMs - resolvedRange.startPositionMs
    val rangeDurationText = if (rangeDurationMs == 0L) "<1ms" else "${rangeDurationMs}ms"
    summaryText =
      "Range: ${resolvedRange.startPositionMs}ms to ${resolvedRange.endPositionMs}ms" +
        " ($rangeDurationText long)"
    isErrorSummary = false
    isPlaceholderSummary = false
    accessibilityDescription = buildString {
      if (
        resolvedRange.startPositionMs == 0L &&
          resolvedRange.endPositionMs == resolvedRange.videoDurationMs
      ) {
        append("Full video range, 0ms to ${resolvedRange.videoDurationMs}ms")
      } else {
        append(
          "Range from ${resolvedRange.startPositionMs}ms to ${resolvedRange.endPositionMs}ms," +
            " $rangeDurationText long, out of ${resolvedRange.videoDurationMs}ms"
        )
      }
      appendPhotoFrameAccessibility(resolvedRange, hasPhotoFrame, usesPhotoFrameBoundary)
    }
  }

  val tabularLabelSmall = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
  val tabularBodySmall = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
  val tabularBodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
  val trackBorderColor = MaterialTheme.colorScheme.outline
  val activeColor = MaterialTheme.colorScheme.primary
  val errorColor = MaterialTheme.colorScheme.error
  val tickColor = MaterialTheme.colorScheme.onSurface

  Column(
    modifier =
      modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = accessibilityDescription
      },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = topLineText,
      modifier = Modifier.align(topLineAlignment),
      style = tabularLabelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(INDICATOR_TICK_HEIGHT)) {
      val trackHeight = INDICATOR_TRACK_HEIGHT.toPx()
      val borderWidth = INDICATOR_TRACK_BORDER_WIDTH.toPx()
      val inset = trackHeight / 2f
      val spanWidth = size.width - trackHeight
      if (spanWidth <= 0f) {
        return@Canvas
      }

      scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
        // Draw the full video bar as an outline-colored pill with an inner track-colored pill.
        drawLine(
          color = trackBorderColor,
          start = Offset(inset, center.y),
          end = Offset(size.width - inset, center.y),
          strokeWidth = trackHeight,
          cap = StrokeCap.Round,
        )
        drawLine(
          color = trackColor,
          start = Offset(inset, center.y),
          end = Offset(size.width - inset, center.y),
          strokeWidth = trackHeight - 2f * borderWidth,
          cap = StrokeCap.Round,
        )

        if (resolvedRange != null) {
          val durationMs = resolvedRange.videoDurationMs
          fun x(positionMs: Long): Float = inset + spanWidth * positionMs / durationMs
          fun segment(color: Color, fromMs: Long, toMs: Long) {
            drawLine(
              color = color,
              start = Offset(x(fromMs), center.y),
              end = Offset(x(toMs), center.y),
              strokeWidth = trackHeight,
              cap = StrokeCap.Round,
            )
          }

          if (resolvedRange.isEmpty) {
            if (resolvedRange.startPositionMs != resolvedRange.endPositionMs) {
              segment(
                errorColor.copy(alpha = 0.25f),
                resolvedRange.endPositionMs,
                resolvedRange.startPositionMs,
              )
            }
          } else {
            segment(activeColor, resolvedRange.startPositionMs, resolvedRange.endPositionMs)
          }

          if (hasPhotoFrame) {
            val tickX = x(clampedPhotoFrameMs)
            val tickWidth = INDICATOR_TICK_WIDTH.toPx()
            val gapWidth = tickWidth + 2f * INDICATOR_TICK_GAP.toPx()
            // Carve a sheet-colored gap on both sides of the tick so it stays visible over the
            // primary bar in dark theme.
            drawLine(
              color = containerColor,
              start = Offset(tickX, center.y - inset),
              end = Offset(tickX, center.y + inset),
              strokeWidth = gapWidth,
            )
            drawLine(
              color = tickColor,
              start = Offset(tickX, tickWidth / 2f),
              end = Offset(tickX, size.height - tickWidth / 2f),
              strokeWidth = tickWidth,
              cap = StrokeCap.Round,
            )
          }

          if (resolvedRange.isEmpty) {
            val markerRadius = INDICATOR_EMPTY_MARKER_RADIUS.toPx()
            val markerBorder = INDICATOR_EMPTY_MARKER_BORDER_WIDTH.toPx()
            val startX = x(resolvedRange.startPositionMs)
            val endX = x(resolvedRange.endPositionMs)
            fun drawEmptyMarker(markerX: Float) {
              drawCircle(
                color = containerColor,
                radius = markerRadius + markerBorder,
                center = Offset(markerX, center.y),
              )
              drawCircle(
                color = errorColor,
                radius = markerRadius,
                center = Offset(markerX, center.y),
              )
            }
            if (endX != startX) {
              drawEmptyMarker(endX)
            }
            drawEmptyMarker(startX)
          }
        }
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = videoStartLabel,
        modifier = Modifier.weight(1f),
        style = tabularBodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = videoEndLabel,
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.End,
        style = tabularBodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Text(
      text = summaryText,
      style = tabularBodyMedium,
      color =
        when {
          isErrorSummary -> MaterialTheme.colorScheme.error
          isPlaceholderSummary -> MaterialTheme.colorScheme.onSurfaceVariant
          else -> MaterialTheme.colorScheme.onSurface
        },
    )
  }
}

@Immutable
private data class PlayerVideoInfo(
  val videoDurationUs: Long,
  val photoPresentationTimestampUs: Long,
  val isPhotoFrameResolved: Boolean,
)

@Immutable
private data class PlaybackRange(
  val videoDurationMs: Long,
  val photoFramePositionMs: Long,
  val startPositionMs: Long,
  val endPositionMs: Long,
  val isEmpty: Boolean,
)

@androidx.annotation.OptIn(UnstableApi::class)
private fun readPlayerVideoInfo(player: Player): PlayerVideoInfo {
  val timeline =
    if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) player.currentTimeline
    else Timeline.EMPTY
  val videoDurationUs =
    if (!timeline.isEmpty && player.currentMediaItemIndex in 0 until timeline.windowCount) {
      val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
      val durationMs =
        timeline.getPeriod(window.firstPeriodIndex, Timeline.Period()).originalDurationMs
      Util.msToUs(durationMs)
    } else {
      C.TIME_UNSET
    }
  val canGetTracks = player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
  val hasTracks = canGetTracks && !player.currentTracks.isEmpty
  val isPhotoFrameResolved =
    !canGetTracks ||
      hasTracks ||
      player.playbackState == Player.STATE_READY ||
      player.playbackState == Player.STATE_ENDED
  val photoPresentationTimestampUs =
    if (canGetTracks) findPhotoPresentationTimestampUs(player) else C.TIME_UNSET
  return PlayerVideoInfo(
    videoDurationUs = videoDurationUs,
    photoPresentationTimestampUs = photoPresentationTimestampUs,
    isPhotoFrameResolved = isPhotoFrameResolved,
  )
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun findPhotoPresentationTimestampUs(player: Player): Long {
  for (group in player.currentTracks.groups) {
    for (i in 0 until group.length) {
      val metadata = group.getTrackFormat(i).metadata ?: continue
      for (j in 0 until metadata.length()) {
        val entry = metadata.get(j)
        if (entry is MotionPhotoMetadata && entry.photoPresentationTimestampUs >= 0L) {
          return entry.photoPresentationTimestampUs
        }
      }
    }
  }
  return C.TIME_UNSET
}

// Mirrors MotionPhotoState's boundary fallback, clamping, and empty-range rules (keep in sync with
// MotionPhotoState.syncMediaItem).
@androidx.annotation.OptIn(UnstableApi::class)
private fun resolvePlaybackRange(
  videoInfo: PlayerVideoInfo,
  config: MotionPhotoConfig,
): PlaybackRange? {
  val videoDurationUs = videoInfo.videoDurationUs
  if (videoDurationUs < 0L || !videoInfo.isPhotoFrameResolved) {
    return null
  }
  val photoUs = videoInfo.photoPresentationTimestampUs
  fun resolveBoundary(boundary: MotionPhotoConfig.Boundary): MotionPhotoConfig.Boundary =
    if (boundary.origin == MotionPhotoConfig.Boundary.PHOTO_FRAME && photoUs == C.TIME_UNSET) {
      MotionPhotoConfig.Boundary.END_OF_VIDEO.plusMs(boundary.offsetMs)
    } else {
      boundary
    }

  fun boundaryBaseUs(boundary: MotionPhotoConfig.Boundary): Long =
    when (boundary.origin) {
      MotionPhotoConfig.Boundary.START_OF_VIDEO -> 0L
      MotionPhotoConfig.Boundary.PHOTO_FRAME -> photoUs
      else -> videoDurationUs
    }

  val startBoundary = resolveBoundary(config.rangeStart)
  val endBoundary = resolveBoundary(config.rangeEnd)
  val startUs = addOffsetUs(boundaryBaseUs(startBoundary), Util.msToUs(startBoundary.offsetMs))
  val endUs =
    if (
      endBoundary.origin == MotionPhotoConfig.Boundary.END_OF_VIDEO && endBoundary.offsetMs >= 0L
    ) {
      videoDurationUs
    } else {
      addOffsetUs(boundaryBaseUs(endBoundary), Util.msToUs(endBoundary.offsetMs))
    }
  val isEmpty = startUs >= videoDurationUs || endUs <= startUs
  return PlaybackRange(
    videoDurationMs = Util.usToMs(videoDurationUs),
    photoFramePositionMs = Util.usToMs(photoUs),
    startPositionMs = Util.usToMs(startUs.coerceAtMost(videoDurationUs)),
    endPositionMs = Util.usToMs(endUs.coerceAtMost(videoDurationUs)),
    isEmpty = isEmpty,
  )
}

private fun addOffsetUs(baseUs: Long, offsetUs: Long): Long =
  if (offsetUs > 0L && baseUs > Long.MAX_VALUE - offsetUs) {
    Long.MAX_VALUE
  } else {
    // A position before the start of the video is its start. This also keeps extreme negative
    // offsets away from the C.TIME_UNSET and C.TIME_END_OF_SOURCE sentinels.
    maxOf(0L, baseUs + offsetUs)
  }

private fun StringBuilder.appendPhotoFrameAccessibility(
  playbackRange: PlaybackRange,
  hasPhotoFrame: Boolean,
  usesPhotoFrameBoundary: Boolean,
) {
  if (hasPhotoFrame) {
    append(", photo frame at ${playbackRange.photoFramePositionMs}ms.")
  } else if (usesPhotoFrameBoundary) {
    append(". No photo frame in this file, using video end.")
  } else {
    append(".")
  }
}
