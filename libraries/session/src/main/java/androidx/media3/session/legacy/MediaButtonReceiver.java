/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.session.legacy;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static com.google.common.base.Preconditions.checkNotNull;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.Log;
import java.util.List;

/**
 * A media button receiver receives and helps translate hardware media playback buttons, such as
 * those found on wired and wireless headsets, into the appropriate callbacks in your app.
 *
 * <p>You can add this MediaButtonReceiver to your app by adding it directly to your
 * AndroidManifest.xml:
 *
 * <pre>
 * &lt;receiver android:name="androidx.media.session.MediaButtonReceiver" &gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.intent.action.MEDIA_BUTTON" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/receiver&gt;
 * </pre>
 *
 * This class assumes you have a {@link Service} in your app that controls media playback via a
 * {@link MediaSessionCompat}. Once a key event is received by MediaButtonReceiver, this class tries
 * to find a {@link Service} that can handle {@link Intent#ACTION_MEDIA_BUTTON}, and a {@link
 * MediaBrowserServiceCompat} in turn. If an appropriate service is found, this class forwards the
 * key event to the service. If neither is available or more than one valid service/media browser
 * service is found, an {@link IllegalStateException} will be thrown. Thus, your app should have one
 * of the following services to get a key event properly.
 *
 * <h2>Service Handling ACTION_MEDIA_BUTTON</h2>
 *
 * A service can receive a key event by including an intent filter that handles {@link
 * Intent#ACTION_MEDIA_BUTTON}:
 *
 * <pre>
 * &lt;service android:name="com.example.android.MediaPlaybackService" &gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.intent.action.MEDIA_BUTTON" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 *
 * <p class="note"><strong>Note:</strong> Once the service is started, it must start to run in the
 * foreground.
 *
 * <h2>MediaBrowserService</h2>
 *
 * If you already have a {@link MediaBrowserServiceCompat} in your app, MediaButtonReceiver will
 * deliver the received key events to the {@link MediaBrowserServiceCompat} by default. You can
 * handle them in your {@link MediaSessionCompat.Callback}.
 */
@RestrictTo(LIBRARY)
public class MediaButtonReceiver extends BroadcastReceiver {
  private static final String TAG = "MediaButtonReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null
        || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())
        || !intent.hasExtra(Intent.EXTRA_KEY_EVENT)) {
      Log.d(TAG, "Ignore unsupported intent: " + intent);
      return;
    }
    ComponentName mediaButtonServiceComponentName =
        getServiceComponentByAction(context, Intent.ACTION_MEDIA_BUTTON);
    if (mediaButtonServiceComponentName != null) {
      intent.setComponent(mediaButtonServiceComponentName);
      try {
        ContextCompat.startForegroundService(context, intent);
      } catch (/* ForegroundServiceStartNotAllowedException */ IllegalStateException e) {
        if (Build.VERSION.SDK_INT >= 31
            && Api31.instanceOfForegroundServiceStartNotAllowedException(e)) {
          onForegroundServiceStartNotAllowedException(
              Api31.castToForegroundServiceStartNotAllowedException(e));
        } else {
          throw e;
        }
      }
      return;
    }
    ComponentName mediaBrowserServiceComponentName =
        getServiceComponentByAction(context, MediaBrowserServiceCompat.SERVICE_INTERFACE);
    if (mediaBrowserServiceComponentName != null) {
      PendingResult pendingResult = goAsync();
      Context applicationContext = context.getApplicationContext();
      MediaButtonConnectionCallback connectionCallback =
          new MediaButtonConnectionCallback(applicationContext, intent, pendingResult);
      MediaBrowserCompat mediaBrowser =
          new MediaBrowserCompat(
              applicationContext, mediaBrowserServiceComponentName, connectionCallback, null);
      connectionCallback.setMediaBrowser(mediaBrowser);
      mediaBrowser.connect();
      return;
    }
    throw new IllegalStateException(
        "Could not find any Service that handles "
            + Intent.ACTION_MEDIA_BUTTON
            + " or implements a media browser service.");
  }

  /**
   * This method is called when an exception is thrown when calling {@link
   * Context#startForegroundService(Intent)} as a result of receiving a media button event.
   *
   * <p>By default, this method only logs the exception and it can be safely overridden. Apps that
   * find that such a media button event has been legitimately sent, may choose to override this
   * method and take the opportunity to post a notification from where the user journey can
   * continue.
   *
   * <p>This exception can be thrown if a broadcast media button event is received and a media
   * service is found in the manifest that is registered to handle {@link
   * Intent#ACTION_MEDIA_BUTTON}. If this happens on API 31+ and the app is in the background then
   * an exception is thrown.
   *
   * <p>Normally, a media button intent should only be required to be sent by the system in case of
   * a Bluetooth media button event that wants to restart the app. However, in such a case the app
   * gets an exemption and is allowed to start the foreground service. In this case this method will
   * never be called.
   *
   * <p>In all other cases, apps should use a {@linkplain MediaBrowserCompat media browser} to bind
   * to and start the service instead of broadcasting an intent.
   *
   * @param e The exception thrown by the system and caught by this broadcast receiver.
   */
  @RequiresApi(31)
  protected void onForegroundServiceStartNotAllowedException(
      ForegroundServiceStartNotAllowedException e) {
    Log.e(
        TAG,
        "caught exception when trying to start a foreground service from the "
            + "background: "
            + e.getMessage());
  }

  private static class MediaButtonConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
    private final Context context;
    private final Intent intent;
    private final PendingResult pendingResult;

    @Nullable private MediaBrowserCompat mediaBrowser;

    MediaButtonConnectionCallback(Context context, Intent intent, PendingResult pendingResult) {
      this.context = context;
      this.intent = intent;
      this.pendingResult = pendingResult;
    }

    void setMediaBrowser(MediaBrowserCompat mediaBrowser) {
      this.mediaBrowser = mediaBrowser;
    }

    @Override
    public void onConnected() {
      MediaControllerCompat mediaController =
          new MediaControllerCompat(context, checkNotNull(mediaBrowser).getSessionToken());
      KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
      mediaController.dispatchMediaButtonEvent(ke);
      finish();
    }

    @Override
    public void onConnectionSuspended() {
      finish();
    }

    @Override
    public void onConnectionFailed() {
      finish();
    }

    private void finish() {
      checkNotNull(mediaBrowser).disconnect();
      pendingResult.finish();
    }
  }

  /** */
  @Nullable
  public static ComponentName getMediaButtonReceiverComponent(Context context) {
    Intent queryIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    queryIntent.setPackage(context.getPackageName());
    PackageManager pm = context.getPackageManager();
    List<ResolveInfo> resolveInfos = pm.queryBroadcastReceivers(queryIntent, 0);
    if (resolveInfos.size() == 1) {
      ResolveInfo resolveInfo = resolveInfos.get(0);
      return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
    } else if (resolveInfos.size() > 1) {
      Log.w(
          TAG,
          "More than one BroadcastReceiver that handles "
              + Intent.ACTION_MEDIA_BUTTON
              + " was found, returning null.");
    }
    return null;
  }

  @Nullable
  @SuppressWarnings("deprecation")
  private static ComponentName getServiceComponentByAction(Context context, String action) {
    PackageManager pm = context.getPackageManager();
    Intent queryIntent = new Intent(action);
    queryIntent.setPackage(context.getPackageName());
    List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent, 0 /* flags */);
    if (resolveInfos.size() == 1) {
      ResolveInfo resolveInfo = resolveInfos.get(0);
      return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    } else if (resolveInfos.isEmpty()) {
      return null;
    } else {
      throw new IllegalStateException(
          "Expected 1 service that handles " + action + ", found " + resolveInfos.size());
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    /**
     * Returns true if the passed exception is a {@link ForegroundServiceStartNotAllowedException}.
     */
    public static boolean instanceOfForegroundServiceStartNotAllowedException(
        IllegalStateException e) {
      return e instanceof ForegroundServiceStartNotAllowedException;
    }

    /**
     * Casts the {@link IllegalStateException} to a {@link
     * ForegroundServiceStartNotAllowedException} and throws an exception if the cast fails.
     */
    public static ForegroundServiceStartNotAllowedException
        castToForegroundServiceStartNotAllowedException(IllegalStateException e) {
      return (ForegroundServiceStartNotAllowedException) e;
    }
  }
}
