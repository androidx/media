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
package androidx.media3.demo.effect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

internal val COLOR_NAME_RES_IDS =
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

@Composable
fun DropdownControlItem(
  title: String,
  value: String,
  options: List<String> = emptyList(),
  onValueChange: ((String) -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable { if (options.isNotEmpty()) expanded = true }
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }

    Box {
      Surface(
        modifier = Modifier.height(36.dp).clickable { if (options.isNotEmpty()) expanded = true }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 12.dp),
        ) {
          Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        }
      }

      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
          DropdownMenuItem(
            text = { Text(text = option) },
            onClick = {
              onValueChange?.invoke(option)
              expanded = false
            },
          )
        }
      }
    }
  }
}

@Composable
private fun getColorName(color: Color): String {
  return COLOR_NAME_RES_IDS[color]?.let { stringResource(it) }
    ?: stringResource(R.string.unknown_color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorsDropDownMenu(color: Color, onItemSelected: (Color) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = Modifier.fillMaxWidth().padding(bottom = dimensionResource(R.dimen.large_padding)),
  ) {
    OutlinedTextField(
      modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
      value = getColorName(color),
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(stringResource(R.string.text_color)) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      colors = ExposedDropdownMenuDefaults.textFieldColors(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      for (color in COLORS) {
        DropdownMenuItem(
          text = { Text(getColorName(color), style = MaterialTheme.typography.bodyLarge) },
          onClick = {
            onItemSelected(color)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
          leadingIcon = {
            Box(
              modifier =
                Modifier.size(dimensionResource(R.dimen.color_circle_size))
                  .background(color, CircleShape)
            )
          },
        )
      }
    }
  }
}
