/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.demo.shortform.lazycolumn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.lazycolumn.composable.ShortFormLazyColumn

@UnstableApi
class LazyColumnActivity : ComponentActivity() {
    companion object {
        const val LOAD_CONTROL_MIN_BUFFER_MS = 3_000
        const val LOAD_CONTROL_MAX_BUFFER_MS = 20_000
        const val LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS = 500
        const val PLAYER_CACHE_DIRECTORY = "exo_player"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    ShortFormContent()
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun ShortFormContent() {
    val context = LocalContext.current
    val playerManager = remember {
        LazyColumnPlayerManager(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            playerManager.release()
        }
    }

    ShortFormLazyColumn(
        playerManager = playerManager
    )
}