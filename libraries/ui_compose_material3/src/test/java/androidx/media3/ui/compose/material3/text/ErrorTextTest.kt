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

import android.content.Context
import android.util.Pair
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ErrorTextTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun errorMessage_showsCustomMessage() = runComposeUiTest {
    val player = FakePlayer()

    setContent { ErrorText(player = player, customErrorMessage = "Custom Error Override") }

    onNodeWithText("Custom Error Override").assertIsDisplayed()
  }

  @Test
  fun errorMessage_withError_showsProviderMessage() = runComposeUiTest {
    val player =
      FakePlayer().apply {
        setPlayerError(
          PlaybackException(
            /* message = */ null,
            /* cause = */ null,
            /* errorCode = */ PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
          )
        )
      }
    val provider =
      ErrorMessageProvider<PlaybackException> { Pair.create(0, "Provider Error Message") }

    setContent { ErrorText(player = player, errorMessageProvider = provider) }

    onNodeWithText("Provider Error Message").assertIsDisplayed()
  }

  @Test
  fun errorMessage_withoutError_showsNothing() = runComposeUiTest {
    val player = FakePlayer()

    setContent { ErrorText(player = player, modifier = Modifier.testTag("error_overlay")) }

    onNodeWithTag("error_overlay").assertDoesNotExist()
  }

  @Test
  fun errorMessage_withErrorAndDefaultProvider_showsLocalizedMessage() = runComposeUiTest {
    val player =
      FakePlayer().apply {
        setPlayerError(
          PlaybackException(
            /* message = */ null,
            /* cause = */ null,
            /* errorCode = */ PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
          )
        )
      }

    setContent { ErrorText(player = player) }

    val expectedString = context.getString(R.string.media3_error_io_network_connection_failed)
    onNodeWithText(expectedString).assertIsDisplayed()
  }
}
