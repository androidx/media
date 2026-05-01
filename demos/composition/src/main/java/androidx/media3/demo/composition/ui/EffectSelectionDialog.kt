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

@Composable
internal fun EffectSelectionDialog(
  onDismissRequest: () -> Unit,
  effectOptions: List<String>,
  currentSelections: Set<String>,
  onEffectsSelected: (Set<String>) -> Unit,
) {
  var selectedOptions by remember { mutableStateOf(currentSelections) }

  Dialog(onDismissRequest = onDismissRequest) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(modifier = Modifier.padding(MaterialTheme.spacing.standard)) {
        Text(
          text = stringResource(R.string.select_effects),
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
        )
        Column {
          effectOptions.forEach { effectName ->
            Row(
              Modifier.fillMaxWidth()
                .clickable {
                  selectedOptions =
                    if (selectedOptions.contains(effectName)) {
                      selectedOptions - effectName
                    } else {
                      selectedOptions + effectName
                    }
                }
                .padding(vertical = MaterialTheme.spacing.mini),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = selectedOptions.contains(effectName), onCheckedChange = null)
              Text(
                text = effectName,
                modifier = Modifier.padding(start = MaterialTheme.spacing.small),
              )
            }
          }
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
              onEffectsSelected(selectedOptions)
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
