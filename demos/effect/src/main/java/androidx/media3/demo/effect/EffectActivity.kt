/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.demo.effect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

class EffectActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { EffectDemo() }
  }

  @Composable
  fun EffectDemo() {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
      Column(
        modifier = Modifier.fillMaxWidth().padding(paddingValues),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        InputChooser(
          onButtonClick = {
            coroutineScope.launch {
              snackbarHostState.showSnackbar(message = "Button is not yet implemented.")
            }
          }
        )
        PlayerScreen()
        Effects(
          onButtonClick = {
            coroutineScope.launch {
              snackbarHostState.showSnackbar(message = "Button is not yet implemented.")
            }
          }
        )
      }
    }
  }

  @Composable
  fun InputChooser(onButtonClick: () -> Unit) {
    Row(
      modifier = Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding)),
      horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_padding)),
    ) {
      Button(onClick = onButtonClick) {
        Text(text = stringResource(id = R.string.choose_preset_input))
      }
      Button(onClick = onButtonClick) {
        Text(text = stringResource(id = R.string.choose_local_file))
      }
    }
  }

  @Composable
  fun PlayerScreen() {
    val context = LocalContext.current
    AndroidView(
      factory = { PlayerView(context).apply {} },
      modifier =
        Modifier.height(dimensionResource(id = R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.small_padding)),
    )
  }

  @Composable
  fun Effects(onButtonClick: () -> Unit) {
    Button(onClick = onButtonClick) { Text(text = stringResource(id = R.string.apply_effects)) }
  }
}
