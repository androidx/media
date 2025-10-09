/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.cast;

import android.content.Context;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.Collections;
import java.util.List;

/** A convenience {@link OptionsProvider} to target the default cast receiver app. */
@UnstableApi
public final class DefaultCastOptionsProvider implements OptionsProvider {

  /**
   * App id that points to the Default Media Receiver app with basic DRM support.
   *
   * <p>Applications that require more complex DRM authentication should <a
   * href="https://developers.google.com/cast/docs/web_receiver/streaming_protocols#drm">create a
   * custom receiver application</a>.
   */
  public static final String APP_ID_DEFAULT_RECEIVER_WITH_DRM = "A12D4273";

  @Override
  public CastOptions getCastOptions(Context context) {
    return new CastOptions.Builder()
        .setResumeSavedSession(false)
        .setEnableReconnectionService(false)
        .setReceiverApplicationId(APP_ID_DEFAULT_RECEIVER_WITH_DRM)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setRemoteToLocalEnabled(true)
        .build();
  }

  @Override
  public List<SessionProvider> getAdditionalSessionProviders(Context context) {
    return Collections.emptyList();
  }
}
