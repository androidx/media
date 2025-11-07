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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.ui.compose.indicators.TimeText
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import kotlinx.coroutines.CoroutineScope

/**
 * A composable that displays the current position of the player.
 *
 * @param player The [Player] to get the position from.
 * @param modifier The [Modifier] to be applied to the text.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 */
@UnstableApi
@Composable
fun PositionText(
  player: Player,
  modifier: Modifier = Modifier,
  scope: CoroutineScope = rememberCoroutineScope(),
) {
  TimeText(player, modifier, TimeFormat.position(), scope)
}

/**
 * A composable that displays the duration of the media.
 *
 * @param player The [Player] to get the duration from.
 * @param modifier The [Modifier] to be applied to the text.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 */
@UnstableApi
@Composable
fun DurationText(
  player: Player,
  modifier: Modifier = Modifier,
  scope: CoroutineScope = rememberCoroutineScope(),
) {
  TimeText(player, modifier, TimeFormat.duration(), scope)
}

/**
 * A composable that displays the duration of the media.
 *
 * @param player The [Player] to get the duration from.
 * @param modifier The [Modifier] to be applied to the text.
 * @param showNegative Whether to display the remaining time with a minus sign.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 */
@UnstableApi
@Composable
fun RemainingDurationText(
  player: Player,
  modifier: Modifier = Modifier,
  showNegative: Boolean = false,
  scope: CoroutineScope = rememberCoroutineScope(),
) {
  TimeText(player, modifier, TimeFormat.remaining(showNegative), scope)
}

/**
 * A composable that displays the duration of the media.
 *
 * @param player The [Player] to get the duration from.
 * @param modifier The [Modifier] to be applied to the text.
 * @param separator The separator string to be used between the current position and duration.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 */
@UnstableApi
@Composable
fun PositionAndDurationText(
  player: Player,
  modifier: Modifier = Modifier,
  separator: String = " / ",
  scope: CoroutineScope = rememberCoroutineScope(),
) {
  TimeText(player, modifier, TimeFormat.positionAndDuration(separator), scope)
}

/**
 * Progress indicator that represents the [Player's][Player] progress state in textual form.
 *
 * It displays the up-to-date current position and duration of the media, formatted by
 * [getStringForTime].
 *
 * @param player The [Player] to get the progress from.
 * @param modifier The [Modifier] to be applied to the text.
 * @param timeFormat The [TimeFormat] to use for displaying the time.
 * @param scope Coroutine scope to listen to the progress updates from the player.
 */
@UnstableApi
@Composable
fun TimeText(
  player: Player,
  modifier: Modifier = Modifier,
  timeFormat: TimeFormat,
  scope: CoroutineScope = rememberCoroutineScope(),
) {
  TimeText(player, scope = scope) { TimeText(state = this, timeFormat, modifier) }
}

@Composable
private fun TimeText(
  state: ProgressStateWithTickInterval,
  timeFormat: TimeFormat,
  modifier: Modifier,
) {
  val text =
    when (timeFormat.format) {
      TimeFormat.POSITION -> getStringForTime(state.currentPositionMs)
      TimeFormat.DURATION -> getStringForTime(state.durationMs)
      TimeFormat.REMAINING ->
        if (state.durationMs != C.TIME_UNSET) {
          val remainingMs =
            if (timeFormat.showNegative) {
              state.currentPositionMs - state.durationMs
            } else {
              state.durationMs - state.currentPositionMs
            }
          getStringForTime(remainingMs)
        } else {
          getStringForTime(C.TIME_UNSET)
        }

      TimeFormat.POSITION_AND_DURATION ->
        "${getStringForTime(state.currentPositionMs)}${timeFormat.separator}${getStringForTime(state.durationMs)}"

      else -> throw IllegalStateException("Unrecognized TimeFormat ${timeFormat.format}")
    }
  BasicText(text, modifier, style = TextStyle(fontFeatureSettings = "tnum"))
}

/**
 * A class for specifying the format of the time to be displayed by [TimeText].
 *
 * Instances of this class can be created using the factory methods, such as [position], [duration],
 * [remaining], and [positionAndDuration].
 */
@UnstableApi
class TimeFormat
private constructor(
  internal val format: Int,
  internal val separator: String?,
  internal val showNegative: Boolean,
) {
  companion object {
    internal const val POSITION = 0
    internal const val DURATION = 1
    internal const val REMAINING = 2
    internal const val POSITION_AND_DURATION = 3

    /** Creates a [TimeFormat] that displays the current position of the player. */
    fun position(): TimeFormat = TimeFormat(POSITION, null, false)

    /** Creates a [TimeFormat] that displays the total duration of the media. */
    fun duration(): TimeFormat = TimeFormat(DURATION, null, false)

    /**
     * Creates a [TimeFormat] that displays the remaining time of the media.
     *
     * @param showNegative Whether to display the remaining time with a minus sign.
     */
    fun remaining(showNegative: Boolean = false): TimeFormat =
      TimeFormat(REMAINING, null, showNegative)

    /**
     * Creates a [TimeFormat] that displays both the current position and the total duration.
     *
     * @param separator The separator string to be used between the current position and duration.
     */
    fun positionAndDuration(separator: String = " / "): TimeFormat =
      TimeFormat(POSITION_AND_DURATION, separator, false)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TimeFormat) return false
    return format == other.format &&
      separator == other.separator &&
      showNegative == other.showNegative
  }

  override fun hashCode(): Int {
    var result = format.hashCode()
    result = 31 * result + (separator?.hashCode() ?: 0)
    result = 31 * result + showNegative.hashCode()
    return result
  }
}
