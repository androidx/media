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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

@Composable
internal fun GapDurationDialog(onDismissRequest: () -> Unit, onConfirm: (Long) -> Unit) {
  var durationSeconds by remember { mutableFloatStateOf(5f) }

  Dialog(onDismissRequest) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(MaterialTheme.spacing.standard),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(text = stringResource(R.string.gap_duration_title), fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.seconds_label, durationSeconds))
        Slider(
          value = durationSeconds,
          onValueChange = { durationSeconds = it },
          valueRange = 0.1f..30f,
          steps = 299,
          modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          OutlinedButton(onClick = onDismissRequest) {
            Text(text = stringResource(R.string.cancel))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(onClick = { onConfirm((durationSeconds * 1_000_000).toLong()) }) {
            Text(text = stringResource(R.string.ok))
          }
        }
      }
    }
  }
}
