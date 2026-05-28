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

package androidx.media3.ui.compose.material3.text

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.PlayerTokens.ErrorTextStyle
import androidx.media3.ui.compose.text.ErrorText as ErrorStateContainer

/**
 * A Composable for displaying an error message overlay on top of the player.
 *
 * @param player The [Player] instance to observe for errors.
 * @param modifier The [Modifier] to be applied to the layout.
 * @param color The [Color] of the error text.
 * @param customErrorMessage An optional custom message to display permanently.
 * @param errorMessageProvider An [ErrorMessageProvider] used to format the [PlaybackException].
 *   Defaults to a provider with localization support.
 */
@UnstableApi
@Composable
fun ErrorText(
  player: Player?,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  customErrorMessage: CharSequence? = null,
  errorMessageProvider: ErrorMessageProvider<PlaybackException>? =
    rememberDefaultErrorMessageProvider(),
) {
  ErrorStateContainer(player) {
    val displayMessage: CharSequence? =
      remember(error, customErrorMessage, errorMessageProvider) {
        customErrorMessage ?: error?.let { errorMessageProvider?.getErrorMessage(it)?.second }
      }

    displayMessage?.let {
      Text(
        text = it.toString(),
        color = color,
        style = ErrorTextStyle,
        textAlign = TextAlign.Center,
        modifier = modifier,
      )
    }
  }
}
