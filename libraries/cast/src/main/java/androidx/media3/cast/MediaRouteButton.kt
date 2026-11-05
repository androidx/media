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
package androidx.media3.cast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.R as AppCompatR
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.cast.Cast.MediaRouteSelectorListener
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter.RouteInfo

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that displays a media route
 * button.
 *
 * ![MediaRouteButton
 * image](https://developer.android.com/images/reference/androidx/media3/cast/media-route-button.svg)
 *
 * Clicking the button displays the route chooser dialog for transferring media or the route
 * controller dialog to control the remote playback. The default behavior prioritizes launching the
 * system's route chooser / controller dialog if available and falls back to an in-app dialog
 * otherwise.
 *
 * If the Cast context fails to load (see [Cast.getCastContextLoadFailure]), the button is hidden on
 * TV, Automotive, and Wear OS devices, and remains visible on other form factors (such as phones
 * and tablets), where clicking it displays an in-app Cast loading error dialog prompting the user
 * to update Google Play services.
 *
 * The button's tint color can be customized by providing a
 * [androidx.compose.material3.LocalContentColor] in the composition hierarchy.
 *
 * ```kotlin
 *  CompositionLocalProvider(LocalContentColor provides Color.Blue) {
 *    MediaRouteButton(modifier = modifier)
 * }
 * ```
 *
 * Consumers can also use the [MediaRouteButtonState] to control inactivity timeout.
 *
 * ```kotlin
 * @Composable
 * fun MyMediaControls() {
 *     val mediaRouteState = rememberMediaRouteButtonState()
 *
 *     // Pause inactivity timeout if the routing picker is visible
 *     LaunchedEffect(mediaRouteState.isPickerVisible) {
 *         if (mediaRouteState.isPickerVisible) {
 *             stopTimer()
 *         } else {
 *             startTimer()
 *         }
 *     }
 *
 *     // Pass the hoisted state here
 *     MediaRouteButton(modifier, mediaRouteState)
 * }
 * ```
 *
 * @param modifier the [Modifier] to be applied to the button.
 * @param state the [MediaRouteButtonState] to be used for the button.
 * @throws IllegalStateException if any of the following condition occurs:
 *     - This method is not called on the main thread.
 *     - The [Cast] has not been initialized via [Cast.initialize()] before this method is called.
 */
@MainThread
@UnstableApi
@Composable
fun MediaRouteButton(
  modifier: Modifier = Modifier,
  state: MediaRouteButtonState = rememberMediaRouteButtonState(),
) {
  CastUtils.verifyMainThread()
  val context = LocalContext.current
  MediaRouteButtonContainer {
    IconButton(
      onClick = {
        if (castInitializationFailed) {
          state.isPickerVisible = true
        } else {
          val routerParams = mediaRouter.routerParams
          val isOutputSwitcherEnabled =
            routerParams != null &&
              routerParams.isOutputSwitcherEnabled &&
              routerParams.isMediaTransferReceiverEnabled
          val outputSwitcherLaunched =
            isOutputSwitcherEnabled && SystemOutputSwitcherDialogController.showDialog(context)
          // If the system output switcher was launched we don't show the media route dialogs.
          state.isPickerVisible = !outputSwitcherLaunched
        }
      },
      modifier = modifier,
      enabled = isReady || castInitializationFailed,
    ) {
      mediaRouteButtonIcon()
    }
    if (state.isPickerVisible && isReady) {
      MediaRouteDialog(onDismissRequest = { state.isPickerVisible = false })
    }
    if (state.isPickerVisible && castInitializationFailed) {
      CastErrorDialog(onDismissRequest = { state.isPickerVisible = false })
    }
  }
}

private const val TAG = "Media3MediaRouteButton"
private const val LEARN_MORE_URL = "https://support.google.com/googleplay/answer/9037938"

@Composable
private fun CastErrorDialog(onDismissRequest: () -> Unit) {
  val context = LocalContext.current
  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(stringResource(R.string.media_route_button_error_dialog_title)) },
    text = {
      Column {
        Text(stringResource(R.string.media_route_button_error_dialog_message))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.media_route_button_error_dialog_learn_more),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelLarge,
          modifier =
            Modifier.clickable(role = Role.Button) {
              onDismissRequest()
              val intent =
                Intent(Intent.ACTION_VIEW, LEARN_MORE_URL.toUri()).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
              try {
                context.startActivity(intent)
              } catch (e: ActivityNotFoundException) {
                Log.w(
                  TAG,
                  "Failed to launch intent to learn more about updating Google Play services",
                  e,
                )
              }
            },
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismissRequest) {
        Text(stringResource(R.string.media_route_button_error_dialog_ok))
      }
    },
  )
}

private val mediaRouteButtonIcon: @Composable MediaRouterState.() -> Unit =
  @Composable {
    if (castInitializationFailed) {
      Icon(
        painter = painterResource(R.drawable.media_route_button_disconnected),
        contentDescription = stringResource(R.string.media_route_button_error),
      )
    } else {
      val (iconRes, descRes) =
        when (connectionState) {
          RouteInfo.CONNECTION_STATE_CONNECTED ->
            R.drawable.media_route_button_connected to R.string.media_route_button_connected
          RouteInfo.CONNECTION_STATE_CONNECTING ->
            R.drawable.media_route_button_disconnected to R.string.media_route_button_connecting
          else ->
            R.drawable.media_route_button_disconnected to R.string.media_route_button_disconnected
        }
      Icon(painter = painterResource(iconRes), contentDescription = stringResource(descRes))
    }
  }

@Composable
private fun MediaRouterState.MediaRouteDialog(onDismissRequest: () -> Unit) {
  if (isConnectedToRemote) {
    MediaRouteControllerDialog(onDismissRequest)
  } else {
    MediaRouteChooserDialog(onDismissRequest)
  }
}

@Composable
private fun MediaRouterState.MediaRouteChooserDialog(onDismissRequest: () -> Unit) {
  DisposableEffect(Unit) {
    val theme = resolveDialogTheme(context)
    val dialog = MediaRouteChooserDialog(context, theme)
    dialog.routeSelector = selector
    dialog.setOnDismissListener { onDismissRequest() }
    dialog.show()
    onDispose { dialog.dismiss() }
  }
}

@Composable
private fun MediaRouterState.MediaRouteControllerDialog(onDismissRequest: () -> Unit) {
  DisposableEffect(Unit) {
    val theme = resolveDialogTheme(context)
    val dialog = MediaRouteControllerDialog(context, theme)
    dialog.setOnDismissListener { onDismissRequest() }
    dialog.show()
    onDispose { dialog.dismiss() }
  }
}

/**
 * A state container for a media route button.
 *
 * @param content The composable content to be displayed for the media route button.
 */
@Composable
@VisibleForTesting
internal fun MediaRouteButtonContainer(content: @Composable MediaRouterState.() -> Unit) {
  val context = LocalContext.current
  val showButtonUponCastInitError = remember(context) { showButtonUponCastInitError(context) }
  var selector by remember { mutableStateOf(MediaRouteSelector.EMPTY) }
  var castInitializationFailed by remember { mutableStateOf(false) }

  DisposableEffect(context) {
    val cast = Cast.getSingletonInstance(context)
    cast.ensureInitialized(context)

    val mediaRouteSelectorListener: MediaRouteSelectorListener =
      object : MediaRouteSelectorListener() {
        override fun onMediaRouteSelectorChanged(mediaRouteSelector: MediaRouteSelector) {
          selector = mediaRouteSelector
          // Catch asynchronous initialization failures
          // Resets castInitializationFailed to false on success, or true if loading failed
          castInitializationFailed = cast.castContextLoadFailure != null
        }
      }

    val currentSelector = cast.registerListenerAndGetCurrentSelector(mediaRouteSelectorListener)
    if (currentSelector != null) {
      selector = currentSelector
    }

    // Check if it already failed asynchronously before the listener was added
    if (cast.castContextLoadFailure != null) {
      castInitializationFailed = true
    }
    onDispose { cast.unregisterListener(mediaRouteSelectorListener) }
  }

  // On form factors that don't support casting (e.g. TV, Automotive, Wear OS), hide the button
  // if CastContext failed to load. On supported form factors (e.g. phones and tablets), keep the
  // button visible so users can tap it to see the recovery dialog.
  val isButtonVisible =
    (!selector.isEmpty && !castInitializationFailed) ||
      (castInitializationFailed && showButtonUponCastInitError)
  if (isButtonVisible) {
    rememberMediaRouterState(context, selector, castInitializationFailed).content()
  }
}

private fun showButtonUponCastInitError(context: Context): Boolean =
  !Util.isTv(context) && !Util.isAutomotive(context) && !Util.isWear(context)

/**
 * Returns the theme to be used for the media route dialogs.
 *
 * If the app theme defines a `colorPrimary`, this method returns 0 to use the app's theme.
 * Otherwise, it returns [R.style.AppThemeDialog] as a fallback to ensure the dialogs are styled
 * correctly and to avoid crashes due to missing or translucent attributes.
 */
@VisibleForTesting
internal fun resolveDialogTheme(context: Context): Int {
  val theme = context.theme ?: return R.style.AppThemeDialog
  val value = TypedValue()
  // Resolve the AppCompat colorPrimary attribute from the current theme.
  // We check this attribute because MediaRouter dialogs use it to calculate contrast.
  if (theme.resolveAttribute(AppCompatR.attr.colorPrimary, value, true)) {
    val color =
      if (value.resourceId != 0) {
        // Resolve the color resource using the current theme.
        val resources = context.resources ?: return R.style.AppThemeDialog
        resources.getColor(value.resourceId, theme)
      } else {
        // Use the literal color data.
        value.data
      }

    // MediaRouterThemeHelper crashes if the resolved color is translucent.
    // Return 0 (use app theme) ONLY if the color is fully opaque (alpha == 255).
    if (Color.alpha(color) == 255) {
      return 0
    }
  }

  // Fallback to the dedicated dialog theme if colorPrimary is missing or translucent.
  // This ensures the dialog has a valid, opaque primary color as defined in styles.xml.
  return R.style.AppThemeDialog
}
