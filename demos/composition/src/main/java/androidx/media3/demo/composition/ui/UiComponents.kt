/*
 * Copyright 2025 The Android Open Source Project
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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun <T> DropDownSpinner(
  isDropDownOpen: Boolean,
  selectedOption: T?,
  dropDownOptions: List<T>,
  changeDropDownOpen: (Boolean) -> Unit,
  changeSelectedOption: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Box {
      OutlinedTextField(
        value = (selectedOption ?: "").toString(),
        onValueChange = {},
        trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
        modifier =
          Modifier.fillMaxWidth().pointerInput(Unit) {
            // Detect click event on TextField to expand/close dropdown
            awaitEachGesture {
              awaitFirstDown(pass = PointerEventPass.Initial)
              val upEvent = waitForUpOrCancellation(pass = PointerEventPass.Initial)
              upEvent?.let { changeDropDownOpen(!isDropDownOpen) }
            }
          },
        readOnly = true,
      )
      DropdownMenu(expanded = isDropDownOpen, onDismissRequest = { changeDropDownOpen(false) }) {
        dropDownOptions.forEach { option ->
          DropdownMenuItem(
            text = { Text(text = option.toString()) },
            onClick = {
              changeDropDownOpen(false)
              changeSelectedOption(option)
            },
          )
        }
      }
    }
  }
}
