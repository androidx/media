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
package androidx.media3.demo.composition

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.COMPOSITION_LAYOUT
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.LAYOUT_EXTRA
import androidx.media3.demo.composition.ui.DropDownSpinner
import androidx.media3.demo.composition.ui.theme.CompositionDemoTheme

class MainActivity : ComponentActivity() {
  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CompositionDemoTheme {
        Scaffold(
          topBar = { TopAppBar(title = { Text(text = stringResource(R.string.app_name)) }) },
          modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
          PresetSelector(
            startPreviewActivity = ::startPreviewActivity,
            modifier = Modifier.padding(innerPadding).padding(8.dp).fillMaxWidth(),
          )
        }
      }
    }
  }

  fun startPreviewActivity(layoutSelection: String) {
    val intent = Intent(this, CompositionPreviewActivity::class.java)
    Log.d(TAG, "Sending layout of $layoutSelection")
    intent.putExtra(LAYOUT_EXTRA, layoutSelection)
    startActivity(intent)
  }

  @Composable
  fun PresetSelector(startPreviewActivity: (String) -> Unit, modifier: Modifier = Modifier) {
    var selectedPreset by remember { mutableStateOf(COMPOSITION_LAYOUT[0]) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
      Text(text = stringResource(R.string.layout_preset))
      // TODO(b/417364200): Improve layout selection flow
      DropDownSpinner(
        isDropDownOpen = expanded,
        selectedOption = selectedPreset,
        dropDownOptions = COMPOSITION_LAYOUT,
        changeDropDownOpen = { newExpandedState -> expanded = newExpandedState },
        changeSelectedOption = { newSelection -> selectedPreset = newSelection },
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = {
          startPreviewActivity(selectedPreset)
          Log.d(TAG, "Selected: $selectedPreset")
        },
        modifier = Modifier.align(Alignment.End),
      ) {
        Text(stringResource(R.string.select))
      }
    }
  }

  companion object {
    const val TAG = "MainActivity"
  }
}
