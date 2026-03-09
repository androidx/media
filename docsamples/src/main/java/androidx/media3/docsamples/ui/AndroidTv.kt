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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

/** Snippets for androidtv.md. */
object AndroidTvKt {

  private abstract class DispatchKeyEventExample : Activity() {
    private lateinit var playerView: PlayerView

    // [START dispatch_key_event]
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
      return playerView.dispatchKeyEvent(event!!) || super.dispatchKeyEvent(event)
    }
    // [END dispatch_key_event]
  }

  @Composable
  fun DispatchKeyEventCompose(playerView: PlayerView, modifier: Modifier = Modifier) {
    // [START dispatch_key_event_compose]
    AndroidView(
      modifier = modifier.focusable().onKeyEvent { playerView.dispatchKeyEvent(it.nativeKeyEvent) },
      factory = { playerView },
    )
    // [END dispatch_key_event_compose]
  }

  private abstract class RequestFocusExample : Activity() {
    private lateinit var playerView: PlayerView

    // [START request_focus]
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      // ...
      playerView.requestFocus()
      // ...
    }
    // [END request_focus]
  }
}
