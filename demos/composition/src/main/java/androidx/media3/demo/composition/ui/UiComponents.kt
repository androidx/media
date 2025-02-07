package androidx.media3.demo.composition.ui

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.media3.demo.composition.R

@Composable
fun <T> DropDownSpinner(
    isDropDownOpen: Boolean,
    selectedOption: T?,
    dropDownOptions: List<T>,
    changeDropDownOpen: (Boolean) -> Unit,
    changeSelectedOption: (T) -> Unit
) {
    Column {
        Box {
            OutlinedTextField(
                value = (selectedOption ?: "").toString(),
                onValueChange = { },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Detect click event on TextField to expand/close dropdown
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            val upEvent = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            upEvent?.let {
                                changeDropDownOpen(!isDropDownOpen)
                            }
                        }
                    },
                readOnly = true
            )
            DropdownMenu(expanded = isDropDownOpen, onDismissRequest = { changeDropDownOpen(false) }) {
                dropDownOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = option.toString()) },
                        onClick = {
                            changeDropDownOpen(false)
                            changeSelectedOption(option)
                        })
                }
            }
        }
    }
}