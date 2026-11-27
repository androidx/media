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
package androidx.media3.ui.compose.text

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ErrorState
import androidx.media3.ui.compose.state.rememberErrorState

/**
 * A Composable that provides error information to its [content] lambda, acting as a container for
 * [ErrorState].
 *
 * This function does not render any UI itself. Instead, it manages the state of the
 * [androidx.media3.common.Player.Listener.onPlayerError] and exposes it through an [ErrorState]
 * object. This allows for complete control over the layout and appearance of the error display.
 *
 * @param player The [Player] to get the error information from.
 * @param content The composable content to be displayed, with access to [ErrorState].
 */
@UnstableApi
@Composable
fun ErrorText(player: Player?, content: @Composable ErrorState.() -> Unit) =
  rememberErrorState(player).content()
