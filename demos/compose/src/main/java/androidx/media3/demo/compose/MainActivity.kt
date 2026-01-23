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
package androidx.media3.demo.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.media3.demo.compose.layout.MainScreen
import androidx.media3.demo.compose.layout.SampleChooserScreen
import androidx.media3.demo.compose.viewmodel.ComposeDemoViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
  private val sharedViewModel: ComposeDemoViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { ComposeDemoApp(modifier = Modifier.fillMaxSize(), viewModel = sharedViewModel) }
  }
}

@Composable
private fun ComposeDemoApp(modifier: Modifier = Modifier, viewModel: ComposeDemoViewModel) {
  val navController = rememberNavController()
  NavHost(
    navController = navController,
    startDestination = ROUTE_SAMPLE_CHOOSER,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    composable(ROUTE_SAMPLE_CHOOSER) {
      SampleChooserScreen(
        onPlaylistClick = { selectedMedia ->
          viewModel.selectMediaItems(selectedMedia)
          navController.navigate(ROUTE_PLAYER)
        },
        modifier = modifier.statusBarsPadding(),
      )
    }
    composable(ROUTE_PLAYER) {
      val mediaItems by viewModel.mediaItems.collectAsState()
      MainScreen(mediaItems)
    }
  }
}

private const val ROUTE_SAMPLE_CHOOSER = "sample_chooser"
private const val ROUTE_PLAYER = "player"
