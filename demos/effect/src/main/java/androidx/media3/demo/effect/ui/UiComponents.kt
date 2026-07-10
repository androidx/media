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
package androidx.media3.demo.effect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.media3.demo.effect.R

internal val COLORS =
  listOf(
    Color.Black,
    Color.DarkGray,
    Color.Gray,
    Color.LightGray,
    Color.White,
    Color.Red,
    Color.Green,
    Color.Blue,
    Color.Yellow,
    Color.Cyan,
    Color.Magenta,
  )

private val COLOR_NAME_RES_IDS =
  mapOf(
    Color.Black to R.string.color_black,
    Color.DarkGray to R.string.color_dark_gray,
    Color.Gray to R.string.color_gray,
    Color.LightGray to R.string.color_light_gray,
    Color.White to R.string.color_white,
    Color.Red to R.string.color_red,
    Color.Green to R.string.color_green,
    Color.Blue to R.string.color_blue,
    Color.Yellow to R.string.color_yellow,
    Color.Cyan to R.string.color_cyan,
    Color.Magenta to R.string.color_magenta,
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> GenericExposedDropdownMenu(
  label: String,
  selectedValue: T,
  options: List<T>,
  onOptionSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
  itemLabelProvider: @Composable (T) -> String = { it.toString() },
  leadingIconProvider: @Composable ((T) -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      modifier =
        Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
      value = itemLabelProvider(selectedValue),
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      colors = ExposedDropdownMenuDefaults.textFieldColors(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(itemLabelProvider(option), style = MaterialTheme.typography.bodyLarge) },
          onClick = {
            onOptionSelected(option)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
          leadingIcon = leadingIconProvider?.let { { it(option) } },
        )
      }
    }
  }
}

@Composable
private fun getColorName(color: Color): String {
  return COLOR_NAME_RES_IDS[color]?.let { stringResource(it) }
    ?: stringResource(R.string.unknown_color)
}

@Composable
internal fun ColorsDropDownMenu(
  color: Color,
  modifier: Modifier =
    Modifier.fillMaxWidth().padding(bottom = dimensionResource(R.dimen.large_padding)),
  onItemSelected: (Color) -> Unit,
) {
  GenericExposedDropdownMenu(
    label = stringResource(R.string.text_color),
    selectedValue = color,
    options = COLORS,
    onOptionSelected = onItemSelected,
    itemLabelProvider = { getColorName(it) },
    leadingIconProvider = { ColorCircle(it) },
    modifier = modifier,
  )
}

@Composable
private fun ColorCircle(color: Color) {
  Box(
    modifier =
      Modifier.size(dimensionResource(R.dimen.color_circle_size)).background(color, CircleShape)
  )
}
