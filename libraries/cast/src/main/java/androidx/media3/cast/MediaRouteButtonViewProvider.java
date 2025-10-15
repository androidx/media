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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.media3.common.ViewProvider;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.UnstableApi;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.common.util.concurrent.ListenableFuture;

/** A provider of a media route button view to be displayed in the player UI. */
@UnstableApi
public final class MediaRouteButtonViewProvider implements ViewProvider {

  /**
   * Returns a {@link ListenableFuture} with the view of a media route button.
   *
   * <p>If a media route button cannot be provided, the future may fail with an exception. Consumers
   * should handle the failure gracefully, for example by not showing the media route button.
   *
   * <p>Clicking on the media route button opens a dialog that allows the user to select a remote
   * device for transferring media.
   *
   * @param viewGroup The parent {@link ViewGroup} into which the returned view will be inserted.
   * @return A {@link ListenableFuture} that will resolve to the media route button {@link View}.
   */
  @Override
  public ListenableFuture<View> getView(ViewGroup viewGroup) {
    Context context = viewGroup.getContext();
    LayoutInflater inflater = LayoutInflater.from(context);
    MediaRouteButton mediaRouteButton =
        (MediaRouteButton)
            inflater.inflate(
                R.layout.media_route_button_view, viewGroup, /* attachToRoot= */ false);
    return CallbackToFutureAdapter.getFuture(
        completer ->
            CastButtonFactory.setUpMediaRouteButton(
                    context, BackgroundExecutor.get(), mediaRouteButton)
                .addOnSuccessListener(unused -> completer.set(mediaRouteButton))
                .addOnFailureListener(e -> completer.setException(e)));
  }
}
