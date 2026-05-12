/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.media3.session;

import static androidx.media3.common.util.Util.postOrRun;
import static androidx.media3.session.LegacyConversions.extractMaxCommandsForMediaItemFromRootHints;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Log;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.legacy.MediaBrowserCompat.MediaItem;
import androidx.media3.session.legacy.MediaBrowserServiceCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import androidx.media3.session.legacy.MediaSessionManager;
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of {@link MediaBrowserServiceCompat} for interoperability between {@link
 * MediaLibraryService} and {@code android.support.v4.media.MediaBrowserCompat}.
 */
/* package */ class MediaSessionServiceLegacyStub extends MediaBrowserServiceCompat {

  private static final String TAG = "MSSLegacyStub";

  private final MediaSessionManager manager;
  private final MediaSessionImpl sessionImpl;
  private final ConnectedControllersManager<RemoteUserInfo> connectedControllersManager;

  /** Creates a new instance. Caller must call {@link #initialize} to the instance. */
  public MediaSessionServiceLegacyStub(MediaSessionImpl sessionImpl) {
    super();
    manager = MediaSessionManager.getSessionManager(sessionImpl.getContext());
    this.sessionImpl = sessionImpl;
    connectedControllersManager = new ConnectedControllersManager<>(sessionImpl);
  }

  public void initialize(MediaSessionCompat.Token token) {
    attachToBaseContext(sessionImpl.getContext());
    onCreate();
    setSessionToken(token);
  }

  @Override
  @Nullable
  public BrowserRoot onGetRoot(
      @Nullable String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    RemoteUserInfo info = getCurrentBrowserInfo();
    MediaSession.ControllerInfo controller =
        createControllerInfo(info, rootHints != null ? rootHints : Bundle.EMPTY);

    AtomicReference<MediaSession.ConnectionResult> resultReference = new AtomicReference<>();
    ConditionVariable haveResult = new ConditionVariable();
    postOrRun(
        sessionImpl.getApplicationHandler(),
        () -> {
          ListenableFuture<MediaSession.ConnectionResult> connectionResultFuture =
              sessionImpl.onConnectOnHandler(controller);
          Futures.addCallback(
              connectionResultFuture,
              new FutureCallback<MediaSession.ConnectionResult>() {
                @Override
                public void onSuccess(MediaSession.ConnectionResult result) {
                  resultReference.set(result);
                  if (result.isAccepted) {
                    connectedControllersManager.addController(
                        info,
                        controller,
                        result.availableSessionCommands,
                        result.availablePlayerCommands);
                  }
                  haveResult.open();
                }

                @Override
                public void onFailure(Throwable t) {
                  // Should not happen, onConnect should at least return a rejected result.
                  resultReference.set(MediaSession.ConnectionResult.reject());
                  haveResult.open();
                }
              },
              directExecutor());
        });
    try {
      haveResult.block();
    } catch (InterruptedException e) {
      Log.e(TAG, "Couldn't get a result from onConnect", e);
      return null;
    }
    MediaSession.ConnectionResult result = resultReference.get();
    if (!result.isAccepted) {
      return null;
    }
    // No library root, but keep browser compat connected to allow getting session.
    return MediaUtils.defaultBrowserRoot;
  }

  @Override
  public void onLoadChildren(@Nullable String parentId, Result<List<MediaItem>> result) {
    result.sendResult(/* result= */ null);
  }

  public ControllerInfo createControllerInfo(RemoteUserInfo info, Bundle rootHints) {
    return new ControllerInfo(
        info,
        ControllerInfo.LEGACY_CONTROLLER_VERSION,
        ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        manager.isTrustedForMediaControl(info),
        /* cb= */ null,
        /* connectionHints= */ rootHints,
        extractMaxCommandsForMediaItemFromRootHints(rootHints),
        /* isPackageNameVerified= */ true);
  }

  public final MediaSessionManager getMediaSessionManager() {
    return manager;
  }

  public final ConnectedControllersManager<RemoteUserInfo> getConnectedControllersManager() {
    return connectedControllersManager;
  }
}
