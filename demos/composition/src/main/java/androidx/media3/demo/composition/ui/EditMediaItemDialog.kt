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
package androidx.media3.demo.composition.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.ui.theme.spacing
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun EditMediaItemDialog(
  onDismissRequest: () -> Unit,
  effectOptions: List<String>,
  currentEffects: Set<String>,
  currentSpeed: Float,
  onSave: (effects: Set<String>, speed: Float) -> Unit,
) {
  var selectedEffects by remember { mutableStateOf(currentEffects) }
  var speed by remember { mutableStateOf(currentSpeed) }

  Dialog(onDismissRequest = onDismissRequest) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(modifier = Modifier.padding(MaterialTheme.spacing.standard)) {
        Text(
          text = stringResource(R.string.edit_media_item),
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
        )

        // Effects Section
        Text(
          text = stringResource(R.string.select_effects),
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(vertical = MaterialTheme.spacing.mini),
        )
        Column {
          effectOptions.forEach { effectName ->
            Row(
              Modifier.fillMaxWidth()
                .clickable {
                  selectedEffects =
                    if (selectedEffects.contains(effectName)) {
                      selectedEffects - effectName
                    } else {
                      selectedEffects + effectName
                    }
                }
                .padding(vertical = MaterialTheme.spacing.mini),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = selectedEffects.contains(effectName), onCheckedChange = null)
              Text(
                text = effectName,
                modifier = Modifier.padding(start = MaterialTheme.spacing.small),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.standard))

        // Speed Section
        Text(
          text = stringResource(R.string.select_speed),
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(vertical = MaterialTheme.spacing.mini),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Slider(
            value = speedToSliderValue(speed),
            onValueChange = { sliderValue -> speed = sliderValueToSpeed(sliderValue) },
            modifier = Modifier.weight(1f),
          )
          Text(
            text = stringResource(R.string.speed_label, speed),
            modifier = Modifier.padding(start = MaterialTheme.spacing.small),
          )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.standard))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          OutlinedButton(
            onClick = onDismissRequest,
            modifier = Modifier.padding(end = MaterialTheme.spacing.small),
          ) {
            Text(stringResource(R.string.cancel))
          }
          Button(
            onClick = {
              onSave(selectedEffects, speed)
              onDismissRequest()
            }
          ) {
            Text(stringResource(R.string.ok))
          }
        }
      }
    }
  }
}

private const val MIN_SPEED = 0.1f
private const val MAX_SPEED = 10f
private const val SPEED_THRESHOLD = 1f
private const val SPEED_INCREMENT_FINE = 0.1f
private const val SPEED_INCREMENT_COARSE = 0.5f

private val LOG_SPEED_RANGE = log10(MAX_SPEED / MIN_SPEED)

// Converts a speed value to a slider value. The logarithmic mapping supports speeds from MIN_SPEED
// to MAX_SPEED.
private fun speedToSliderValue(speed: Float): Float {
  if (speed <= 0f) return 0f
  return (log10(speed / MIN_SPEED) / LOG_SPEED_RANGE).coerceIn(0f, 1f)
}

private fun sliderValueToSpeed(value: Float): Float {
  val rawSpeed = MIN_SPEED * (MAX_SPEED / MIN_SPEED).toDouble().pow(value.toDouble()).toFloat()
  return roundSpeed(rawSpeed)
}

private fun roundSpeed(speed: Float): Float {
  return if (speed > SPEED_THRESHOLD) {
    // Snap to coarse increments (e.g., 1.5, 2.0)
    (speed / SPEED_INCREMENT_COARSE).roundToInt() * SPEED_INCREMENT_COARSE
  } else {
    // Snap to fine increments (e.g., 0.8, 0.9)
    (speed / SPEED_INCREMENT_FINE).roundToInt() * SPEED_INCREMENT_FINE
  }
}
