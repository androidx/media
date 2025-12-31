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
package androidx.media3.cast;

import static androidx.media3.cast.CastUtils.verifyMainThread;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.MainThread;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuItemCompat;
import androidx.media3.cast.CastContextWrapper.MediaRouteSelectorListener;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.mediarouter.app.MediaRouteActionProvider;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A factory class to set up a media route button.
 *
 * <p>Must be called on the main thread.
 */
@MainThread
@UnstableApi
public final class MediaRouteButtonFactory {

  /* package */ static final String MESSAGE_FAILED_WITH_NULL_MEDIA_ROUTE_BUTTON =
      "media route button can't be null.";
  /* package */ static final String MESSAGE_FAILED_TO_GET_SELECTOR =
      "media route button failed to get the media route selector.";
  /* package */ static final String MESSAGE_FAILED_TO_GET_MENU_ITEM =
      "menu doesn't contain a menu item";
  /* package */ static final String MESSAGE_FAILED_TO_GET_MEDIA_ROUTE_ACTION_PROVIDER =
      "menu item doesn't have a MediaRouteActionProvider.";

  /**
   * Sets up a media route button in the action bar menu with an asynchronous callback, which will
   * not block the caller thread. Returns a {@link ListenableFuture} with the menu item of the media
   * route button
   *
   * <p>The application should define a menu resource to include the {@link
   * androidx.mediarouter.app.MediaRouteActionProvider} as the action provider of the menu item.
   *
   * <pre>{@code
   * <menu xmlns:android="http://schemas.android.com/apk/res/android"
   *         xmlns:app="http://schemas.android.com/apk/res-auto">
   *     <item android:id="@+id/media_route_menu_item"
   *         android:title="@string/media_route_menu_title"
   *         app:showAsAction="always"
   *         app:actionProviderClass="androidx.mediarouter.app.MediaRouteActionProvider"/>
   * </menu>
   * }</pre>
   *
   * Then the application can inflate the /res/menu/sample_media_route_button_menu.xml and set up
   * the media route button in the action bar menu as follows.
   *
   * <pre>{@code
   * public class MyActivity extends AppCompatActivity {
   *     ...
   *     @Override
   *     public boolean onCreateOptionsMenu(Menu menu) {
   *         ...
   *         getMenuInflater().inflate(R.menu.sample_media_route_button_menu, menu);
   *         ListenableFuture<MenuItem> menuItemFuture =
   *             MediaRouteButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item);
   *         Futures.addCallback(
   *             menuItemFuture,
   *             new FutureCallback<MenuItem>() {
   *               @Override
   *               public void onSuccess(MenuItem menuItem) {
   *                 // Do something with the menu item.
   *               }
   *
   *               @Override
   *               public void onFailure(Throwable t) {
   *                 // Handle the failure.
   *               }
   *             },
   *             executor);
   *         ...
   *     }
   * }
   * }</pre>
   *
   * <p>If setting up the media route button succeeds, the future will resolve to the menu item of
   * the media route button. The application can do further operations with the menu item, such as
   * showing an introductory overlay to highlight the media route button to users.
   *
   * <p>If setting up the media route button fails, the future may fail with an exception. Consumers
   * should handle the failure gracefully, for example by not showing the media route button.
   *
   * <p>The method must be called on the main thread. The callback of the returned {@link
   * ListenableFuture} may be called on a different thread than the caller's thread. If the caller
   * wants to update the UI in the callbacks, it is responsible for forwarding the callback to the
   * UI thread.
   *
   * <p>Clicking on the media route button opens a dialog that allows the user to select a remote
   * device for transferring media.
   *
   * <p>See {@link androidx.mediarouter.app.MediaRouteActionProvider} for more details.
   *
   * @param context The {@link Context} for creating the media route button.
   * @param menu The {@link Menu} to which the menu item with the button will be added.
   * @param menuResourceId The resource ID of the menu item for the media route button.
   * @return A {@link ListenableFuture} that resolves to the created {@link MenuItem} when the media
   *     route button is set up successfully. The future may fail with {@link IllegalStateException}
   *     if this method is failed to set a {@link MediaRouteSelector} to the menu item. The
   *     completer of the returned {@link ListenableFuture} may be called on a different thread than
   *     the caller's thread.
   * @throws IllegalArgumentException if the menu doesn't contain a menu item with the given {@code
   *     menuResourceId} identifier or the menu item doesn't have a {@link
   *     androidx.mediarouter.app.MediaRouteActionProvider} as its action provider.
   * @throws IllegalStateException if this method is not called on the main thread.
   */
  @CanIgnoreReturnValue
  public static ListenableFuture<MenuItem> setUpMediaRouteButton(
      Context context, Menu menu, int menuResourceId) {
    verifyMainThread();
    MenuItem mediaRouteMenuItem = menu.findItem(menuResourceId);
    if (mediaRouteMenuItem == null) {
      throw new IllegalArgumentException(MESSAGE_FAILED_TO_GET_MENU_ITEM);
    }
    MediaRouteActionProvider mediaRouteActionProvider =
        (MediaRouteActionProvider) MenuItemCompat.getActionProvider(mediaRouteMenuItem);
    if (mediaRouteActionProvider == null) {
      throw new IllegalArgumentException(MESSAGE_FAILED_TO_GET_MEDIA_ROUTE_ACTION_PROVIDER);
    }
    return setUpMediaRouteButton(context, mediaRouteMenuItem, mediaRouteActionProvider);
  }

  /**
   * Sets up a media route button with an asynchronous callback, which will not block the caller
   * thread. Returns a {@link ListenableFuture} with the menu item of the media route button.
   *
   * <p>This method is used internally and for testing purposes. It accepts the {@link
   * MediaRouteActionProvider} as a direct parameter to facilitate unit testing, bypassing
   * limitations where a MenuItem cannot be configured with a non-null {@link
   * MediaRouteActionProvider} in test environments.
   *
   * @param mediaRouteMenuItem The {@link MenuItem} of the media route button.
   * @param mediaRouteActionProvider The {@link MediaRouteActionProvider} of the media route button.
   * @return A {@link ListenableFuture} that resolves to the created {@link MenuItem} when the media
   *     route button is set up successfully. The future may fail with {@link IllegalStateException}
   *     if this method is failed to set a {@link MediaRouteSelector} to the menu item. The
   *     completer of the returned {@link ListenableFuture} may be called on a different thread than
   *     the caller's thread.
   */
  /* package */ static ListenableFuture<MenuItem> setUpMediaRouteButton(
      Context context,
      MenuItem mediaRouteMenuItem,
      MediaRouteActionProvider mediaRouteActionProvider) {
    ListenableFuture<Void> setUpFuture =
        setUpMediaRouteButton(context, mediaRouteActionProvider::setRouteSelector);
    return Futures.transform(setUpFuture, unused -> mediaRouteMenuItem, BackgroundExecutor.get());
  }

  /**
   * Sets up a media route button with an asynchronous callback, which will not block the caller
   * thread.
   *
   * <p>The application can add a {@link MediaRouteButton} to their activity layout .xml file. Then
   * the application can set up the media route button as follows.
   *
   * <pre>{@code
   * public class MyActivity extends AppCompatActivity {
   *     ...
   *     @Override
   *     public void onCreate(Bundle savedInstanceState) {
   *         ...
   *         MediaRouteButton button = findViewById(R.id.media_route_button);
   *         ListenableFuture<Void> setUpFuture =
   *             MediaRouteButtonFactory.setUpMediaRouteButton(this, button);
   *         Futures.addCallback(
   *             setUpFuture,
   *             new FutureCallback<Void>() {
   *               @Override
   *               public void onSuccess(Void unused) {
   *                 // Indicate that the media route button is set up successfully.
   *               }
   *
   *               @Override
   *               public void onFailure(Throwable t) {
   *                 // Handle the failure.
   *               }
   *             },
   *             executor);
   *         ...
   *     }
   * }
   * }</pre>
   *
   * <p>If setting up the media route button succeeds, the future will resolve to null and indicate
   * that the media route button is set up successfully.
   *
   * <p>If setting up the media route button fails, the future may fail with an exception. Consumers
   * should handle the failure gracefully, for example by not showing the media route button.
   *
   * <p>The method must be called on the main thread. The returned {@link ListenableFuture} may be
   * called on a different thread than the caller's thread. If the caller wants to update the UI in
   * the callbacks, it is responsible for forwarding the callback to the UI thread.
   *
   * <p>Clicking on the media route button opens a dialog that allows the user to select a remote
   * device for transferring media.
   *
   * <p>See {@link MediaRouteButton} for more details.
   *
   * @param context The {@link Context} for creating the media route button.
   * @param button The {@link MediaRouteButton} to set up.
   * @return A {@link ListenableFuture} that will resolve to null when the media route button is
   *     successfully set up. The future may fail with {@link IllegalStateException} if this method
   *     is failed to set a {@link MediaRouteSelector} to the media route button. The completer of
   *     the returned {@link ListenableFuture} may be called on a different thread than the caller's
   *     thread.
   * @throws IllegalArgumentException if the media route button is null.
   * @throws IllegalStateException if this method is not called on the main thread.
   */
  @CanIgnoreReturnValue
  public static ListenableFuture<Void> setUpMediaRouteButton(
      Context context, MediaRouteButton button) {
    verifyMainThread();
    checkNotNull(button, MESSAGE_FAILED_WITH_NULL_MEDIA_ROUTE_BUTTON);
    return setUpMediaRouteButton(context, button::setRouteSelector);
  }

  /**
   * Sets up a media route button with an asynchronous callback, which will not block the caller
   * thread.
   *
   * @param context The {@link Context} for creating the media route button.
   * @param onCompletion The {@link Consumer<MediaRouteSelector>} to be called when the {@link
   *     MediaRouteSelector} is set.
   * @return A {@link ListenableFuture} that will resolve to null when the media route button is
   *     successfully set up. The future may fail with {@link IllegalStateException} if this method
   *     is not called on the main thread or it is failed to set a {@link MediaRouteSelector} to the
   *     media route button.
   */
  private static ListenableFuture<Void> setUpMediaRouteButton(
      Context context, Consumer<MediaRouteSelector> onCompletion) {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    return CallbackToFutureAdapter.getFuture(
        completer -> {
          final MediaRouteSelectorListener mediaRouteSelectorListener =
              new MediaRouteSelectorListener() {
                @Override
                void onMediaRouteSelectorChanged(MediaRouteSelector selector) {
                  if (!selector.isEmpty()) {
                    onCompletion.accept(selector);
                    completer.set(null);
                  } else {
                    completer.setException(
                        new IllegalStateException(MESSAGE_FAILED_TO_GET_SELECTOR));
                  }
                }
              };
          MediaRouteSelector selector =
              castContextWrapper.registerListenerAndGetCurrentSelector(mediaRouteSelectorListener);
          if (selector != null) {
            if (!selector.isEmpty()) {
              onCompletion.accept(selector);
              completer.set(null);
            } else {
              completer.setException(new IllegalStateException(MESSAGE_FAILED_TO_GET_SELECTOR));
            }
          }
          if (castContextWrapper.needsInitialization()) {
            // TODO: b/452356348 - Apps need to initialize the CastContextWrapper. The media3 needs
            // to throws an exception if the CastContextWrapper is not initialized.
            castContextWrapper.asyncInit(context);
          }
          completer.addCancellationListener(
              () -> castContextWrapper.unregisterListener(mediaRouteSelectorListener),
              ContextCompat.getMainExecutor(context));
          return "MediaRouteButtonFactory.setUpMediaRouteButton";
        });
  }

  private MediaRouteButtonFactory() {}
}
