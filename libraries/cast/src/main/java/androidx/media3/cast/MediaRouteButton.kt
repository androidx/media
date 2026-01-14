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

import android.content.Intent
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.cast.CastContextWrapper.MediaRouteSelectorListener
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter.RouteInfo
import androidx.mediarouter.media.MediaTransferReceiver

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that displays a media route
 * button.
 *
 * Clicking the button displays the route chooser dialog for transferring media or the route
 * controller dialog to control the remote playback. The default behavior prioritizes launching the
 * system's route chooser / controller dialog if available and falls back to an in-app dialog
 * otherwise.
 *
 * The button's tint color can be customized by providing a [LocalContentColor] in the composition
 * hierarchy.
 *
 * ```kotlin
 *  CompositionLocalProvider(LocalContentColor provides Color.Blue) {
 *    MediaRouteButton(modifier = modifier)
 * }
 * ```
 *
 * @param modifier the [Modifier] to be applied to the button.
 * @throws IllegalStateException if any of the following condition occurs:
 *     - This method is not called on the main thread.
 *     - The [CastContextWrapper] has not been initialized via [CastContextWrapper.asyncInit()]
 *       before this method is called.
 */
@MainThread
@UnstableApi
@Composable
fun MediaRouteButton(modifier: Modifier = Modifier) {
  CastUtils.verifyMainThread()
  MediaRouteButtonContainer() {
    var showDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { showDialog = true }, modifier) { mediaRouteButtonIcon() }
    if (showDialog) {
      MediaRouteDialog { showDialog = false }
    }
  }
}

@Composable
private fun MediaRouteButtonState.MediaRouteDialog(onDismissRequest: () -> Unit) {
  val isOutputSwitcherEnabled =
    mediaRouter?.routerParams?.isOutputSwitcherEnabled ?: false && isMediaTransferEnabled()
  if (isOutputSwitcherEnabled && SystemOutputSwitcherDialogController.showDialog(context)) {
    return
  }
  // If the output switcher is disabled or fails to open, then show the standard media route
  // dialogs instead.
  if (isConnectedToRemote) {
    MediaRouteControllerDialog(onDismissRequest)
  } else {
    MediaRouteChooserDialog(onDismissRequest)
  }
}

private fun MediaRouteButtonState.isMediaTransferEnabled(): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    return false
  }
  val queryIntent = Intent(context, MediaTransferReceiver::class.java)
  queryIntent.setPackage(context.packageName)
  val pm = context.packageManager
  val resolveInfos = pm.queryBroadcastReceivers(queryIntent, 0)
  val isMediaTransferDeclared = resolveInfos.isNotEmpty()
  val routerParams = mediaRouter?.routerParams
  return isMediaTransferDeclared &&
    (routerParams == null || routerParams!!.isMediaTransferReceiverEnabled)
}

@Composable
private fun MediaRouteButtonState.MediaRouteChooserDialog(onDismissRequest: () -> Unit) {
  DisposableEffect(Unit) {
    val dialog = MediaRouteChooserDialog(context, R.style.AppThemeDialog)
    dialog.routeSelector = selector
    dialog.setOnDismissListener { onDismissRequest() }
    dialog.show()
    onDispose { dialog.dismiss() }
  }
}

@Composable
private fun MediaRouteButtonState.MediaRouteControllerDialog(onDismissRequest: () -> Unit) {
  DisposableEffect(Unit) {
    val dialog = MediaRouteControllerDialog(context, R.style.AppThemeDialog)
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
internal fun MediaRouteButtonContainer(content: @Composable MediaRouteButtonState.() -> Unit) {
  val context = LocalContext.current
  var selector by remember { mutableStateOf(MediaRouteSelector.EMPTY) }
  LaunchedEffect(context) {
    val castContextWrapper = CastContextWrapper.getSingletonInstance(context)
    castContextWrapper.ensureInitialized(context)
    val mediaRouteSelectorListener: MediaRouteSelectorListener =
      object : MediaRouteSelectorListener() {
        override fun onMediaRouteSelectorChanged(mediaRouteSelector: MediaRouteSelector) {
          selector = mediaRouteSelector
        }
      }
    val currentSelector =
      castContextWrapper.registerListenerAndGetCurrentSelector(mediaRouteSelectorListener)
    if (currentSelector != null) {
      selector = currentSelector
    }
  }
  if (!selector.isEmpty) {
    rememberMediaRouteButtonState(context, selector).content()
  }
}

private val mediaRouteButtonIcon: @Composable MediaRouteButtonState.() -> Unit =
  @Composable {
    val painter =
      when (connectionState) {
        RouteInfo.CONNECTION_STATE_CONNECTED ->
          painterResource(R.drawable.media_route_button_connected)
        else -> painterResource(R.drawable.media_route_button_disconnected)
      }
    val contentDescription =
      when (connectionState) {
        RouteInfo.CONNECTION_STATE_CONNECTED ->
          stringResource(R.string.media_route_button_connected)
        RouteInfo.CONNECTION_STATE_CONNECTING ->
          stringResource(R.string.media_route_button_connecting)
        else -> stringResource(R.string.media_route_button_disconnected)
      }
    Icon(painter, contentDescription)
  }
