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

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.UnstableApi;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.common.util.concurrent.ListenableFuture;

/** A factory class to set up a media route button. */
@UnstableApi
public final class MediaRouteButtonFactory {

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
   * <p>The callback of the returned {@link ListenableFuture} may be called on a different thread
   * than the caller's thread. If the caller wants to update the UI in the callbacks, it is
   * responsible for forwarding the callback to the UI thread.
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
   *     route button is set up successfully. The future may fail with {link
   *     IllegalArgumentException} if the menu doesn't contain a menu item with the given {@code
   *     menuResourceId} identifier or the menu item doesn't have a {@link
   *     androidx.mediarouter.app.MediaRouteActionProvider} as its action provider, and may fail
   *     with {@link IllegalStateException} if this method is not called on the main thread.
   */
  public static ListenableFuture<MenuItem> setUpMediaRouteButton(
      Context context, Menu menu, int menuResourceId) {
    return CallbackToFutureAdapter.getFuture(
        completer ->
            CastButtonFactory.setUpMediaRouteButton(
                    context, BackgroundExecutor.get(), menu, menuResourceId)
                .addOnSuccessListener(completer::set)
                .addOnFailureListener(completer::setException));
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
   * <p>Clicking on the media route button opens a dialog that allows the user to select a remote
   * device for transferring media.
   *
   * <p>See {@link MediaRouteButton} for more details.
   *
   * @param context The {@link Context} for creating the media route button.
   * @param button The {@link MediaRouteButton} to set up.
   * @return A {@link ListenableFuture} that will resolve to null when the media route button is
   *     successfully set up. The future may fail with {@link IllegalStateException} if this method
   *     is not called on the main thread.
   */
  public static ListenableFuture<Void> setUpMediaRouteButton(
      Context context, MediaRouteButton button) {
    return CallbackToFutureAdapter.getFuture(
        completer ->
            CastButtonFactory.setUpMediaRouteButton(context, BackgroundExecutor.get(), button)
                .addOnSuccessListener(unused -> completer.set(null))
                .addOnFailureListener(completer::setException));
  }

  private MediaRouteButtonFactory() {}
}
