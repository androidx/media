/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.base.Predicates.notNull;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Provides support for interacting with media sessions.
 *
 * <p>This class provides similar functionality to {@link android.media.session.MediaSessionManager}
 * but returns Media3 {@link SessionToken} instances instead of platform classes.
 *
 * <p>All methods are static and take a {@link Context} to access the system service.
 */
@UnstableApi
public final class MediaSessionManager {

  private static final String TAG = "MediaSessionManager";

  /** Listener for changes to the active sessions. */
  public interface OnActiveSessionsChangedListener {
    /** Called when the list of active sessions has changed. */
    void onActiveSessionsChanged(List<SessionToken> activeSessions);
  }

  // We don't use weak references here as it's the app's responsibility to unregister listeners.
  private static final Map<OnActiveSessionsChangedListener, ListenerWrapper> listenersMap =
      new HashMap<>();

  // TODO: b/500320224 - Remove reflection once compileSdk is bumped to 37.
  private static final Supplier<@NullableType Method>
      GET_ACTIVE_SESSIONS_FOR_PACKAGE_METHOD_SUPPLIER =
          Suppliers.memoize(
              new Supplier<@NullableType Method>() {
                @Override
                public @NullableType Method get() {
                  if (Build.VERSION.SDK_INT < 37) {
                    return null;
                  }
                  try {
                    return android.media.session.MediaSessionManager.class.getMethod(
                        "getActiveSessionsForPackage", String.class, ComponentName.class);
                  } catch (ReflectiveOperationException e) {
                    Log.w(TAG, "Failed to resolve getActiveSessionsForPackage method", e);
                    return null;
                  }
                }
              });

  private static final Supplier<@NullableType Method>
      ADD_ON_ACTIVE_SESSIONS_FOR_PACKAGE_CHANGED_LISTENER_METHOD_SUPPLIER =
          Suppliers.memoize(
              new Supplier<@NullableType Method>() {
                @Override
                public @NullableType Method get() {
                  if (Build.VERSION.SDK_INT < 37) {
                    return null;
                  }
                  try {
                    return android.media.session.MediaSessionManager.class.getMethod(
                        "addOnActiveSessionsForPackageChangedListener",
                        String.class,
                        Executor.class,
                        android.media.session.MediaSessionManager.OnActiveSessionsChangedListener
                            .class);
                  } catch (ReflectiveOperationException e) {
                    Log.w(
                        TAG,
                        "Failed to resolve addOnActiveSessionsForPackageChangedListener method",
                        e);
                    return null;
                  }
                }
              });

  private static final Supplier<@NullableType Method>
      REMOVE_ON_ACTIVE_SESSIONS_FOR_PACKAGE_CHANGED_LISTENER_METHOD_SUPPLIER =
          Suppliers.memoize(
              new Supplier<@NullableType Method>() {
                @Override
                public @NullableType Method get() {
                  if (Build.VERSION.SDK_INT < 37) {
                    return null;
                  }
                  try {
                    return android.media.session.MediaSessionManager.class.getMethod(
                        "removeOnActiveSessionsForPackageChangedListener",
                        android.media.session.MediaSessionManager.OnActiveSessionsChangedListener
                            .class);
                  } catch (ReflectiveOperationException e) {
                    Log.w(
                        TAG,
                        "Failed to resolve removeOnActiveSessionsForPackageChangedListener method",
                        e);
                    return null;
                  }
                }
              });

  private MediaSessionManager() {
    // Prevent instantiation.
  }

  /**
   * Retrieves a list of {@link SessionToken} instances for active sessions, optionally filtered by
   * package.
   *
   * <p>Requires {@code android.Manifest.permission.MEDIA_CONTENT_CONTROL} or an enabled
   * notification listener for requesting sessions of other packages.
   *
   * <p>On API levels 37 and above, it automatically works for sessions of your own package without
   * special permissions if {@code packageName} is set to your own package.
   *
   * <p>On API levels below 37, an empty list is returned if the caller does not have the required
   * permissions and requests their own package.
   *
   * <p>If requesting other packages (or all packages) without required permissions, a {@link
   * SecurityException} is thrown.
   *
   * @param context The context to use to access the system service.
   * @param packageName The package name to filter by, or {@code null} for all packages.
   * @param notificationListener The component name of the enabled notification listener, or {@code
   *     null}.
   * @return A {@link ListenableFuture} for the list of {@link SessionToken} instances.
   */
  public static ListenableFuture<List<SessionToken>> getActiveSessions(
      Context context, @Nullable String packageName, @Nullable ComponentName notificationListener) {
    Context applicationContext = context.getApplicationContext();
    android.media.session.MediaSessionManager platformManager =
        applicationContext.getSystemService(android.media.session.MediaSessionManager.class);

    boolean isOwnPackage =
        packageName != null && packageName.equals(applicationContext.getPackageName());
    boolean hasPermission =
        hasMediaContentControlPermission(applicationContext)
            || hasEnabledNotificationListener(applicationContext, notificationListener);

    if (!isOwnPackage && !hasPermission) {
      throw new SecurityException(
          "Requires MEDIA_CONTENT_CONTROL or enabled notification listener");
    }

    if (Build.VERSION.SDK_INT < 37 && isOwnPackage && !hasPermission) {
      return immediateFuture(ImmutableList.of());
    }

    if (packageName != null) {
      ListenableFuture<List<SessionToken>> sessionsForPackage =
          getActiveSessionsForPackageReflection(
              applicationContext, platformManager, packageName, notificationListener);
      if (sessionsForPackage != null) {
        return sessionsForPackage;
      }
    }

    List<MediaController> controllers = platformManager.getActiveSessions(notificationListener);
    if (packageName != null) {
      controllers = filterControllersByPackage(controllers, packageName);
    }

    return convertControllersToTokens(applicationContext, controllers);
  }

  /**
   * Adds a listener for changes to the active sessions, optionally filtered by package.
   *
   * <p>Requires {@code android.Manifest.permission.MEDIA_CONTENT_CONTROL} or an enabled
   * notification listener for requesting sessions of other packages.
   *
   * <p>On API levels 37 and above, it automatically works for sessions of your own package without
   * special permissions if {@code packageName} is set to your own package.
   *
   * <p>On API levels below 37, the listener will not be added and will not receive callbacks if the
   * caller does not have the required permissions and requests their own package.
   *
   * <p>If requesting other packages (or all packages) without required permissions, a {@link
   * SecurityException} is thrown.
   *
   * @param context The context to use to access the system service.
   * @param packageName The package name to filter by, or {@code null} for all packages.
   * @param listener The listener to add.
   * @param executor The executor to execute the callback on.
   * @param notificationListener The component name of the enabled notification listener, or {@code
   *     null}.
   */
  public static void addOnActiveSessionsChangedListener(
      Context context,
      @Nullable String packageName,
      OnActiveSessionsChangedListener listener,
      Executor executor,
      @Nullable ComponentName notificationListener) {
    Context applicationContext = context.getApplicationContext();
    boolean isOwnPackage =
        packageName != null && packageName.equals(applicationContext.getPackageName());
    boolean hasPermission =
        hasMediaContentControlPermission(applicationContext)
            || hasEnabledNotificationListener(applicationContext, notificationListener);

    if (!isOwnPackage && !hasPermission) {
      throw new SecurityException(
          "Requires MEDIA_CONTENT_CONTROL or enabled notification listener");
    }

    if (Build.VERSION.SDK_INT < 37 && isOwnPackage && !hasPermission) {
      return;
    }

    android.media.session.MediaSessionManager platformManager =
        applicationContext.getSystemService(android.media.session.MediaSessionManager.class);

    android.media.session.MediaSessionManager.OnActiveSessionsChangedListener platformListener =
        controllers -> {
          if (controllers == null) {
            return;
          }
          List<MediaController> filteredControllers =
              packageName != null
                  ? filterControllersByPackage(controllers, packageName)
                  : controllers;
          ListenableFuture<List<SessionToken>> allTokensFuture =
              convertControllersToTokens(applicationContext, filteredControllers);
          Futures.addCallback(
              allTokensFuture,
              new FutureCallback<List<SessionToken>>() {
                @Override
                public void onSuccess(List<SessionToken> result) {
                  listener.onActiveSessionsChanged(result);
                }

                @Override
                public void onFailure(Throwable t) {
                  Log.e(TAG, "Failed to create session tokens", t);
                }
              },
              executor);
        };

    synchronized (listenersMap) {
      if (listenersMap.containsKey(listener)) {
        return;
      }

      boolean isPackageSpecific = false;
      if (packageName != null) {
        if (tryAddOnActiveSessionsForPackageChangedListenerReflection(
            platformManager, packageName, platformListener, executor)) {
          isPackageSpecific = true;
        }
      }

      listenersMap.put(listener, new ListenerWrapper(platformListener, isPackageSpecific));
      if (!isPackageSpecific) {
        platformManager.addOnActiveSessionsChangedListener(platformListener, notificationListener);
      }
    }
  }

  /**
   * Removes a listener for changes to the active sessions.
   *
   * @param context The context to use to access the system service.
   * @param listener The listener to remove.
   */
  public static void removeOnActiveSessionsChangedListener(
      Context context, OnActiveSessionsChangedListener listener) {
    Context applicationContext = context.getApplicationContext();
    android.media.session.MediaSessionManager platformManager =
        applicationContext.getSystemService(android.media.session.MediaSessionManager.class);

    ListenerWrapper wrapper;
    synchronized (listenersMap) {
      wrapper = listenersMap.remove(listener);
    }

    if (wrapper != null) {
      if (wrapper.isPackageSpecific) {
        tryRemoveOnActiveSessionsForPackageChangedListenerReflection(
            platformManager, wrapper.platformListener);
      } else {
        platformManager.removeOnActiveSessionsChangedListener(wrapper.platformListener);
      }
    }
  }

  /**
   * Returns a future that resolves to the token for the session that is currently receiving media
   * key events.
   *
   * <p>On API levels before 33, this will return a future that resolves to {@code null}.
   *
   * @param context The context to use to access the system service.
   * @return A {@link ListenableFuture} for the {@link SessionToken}, or a future that resolves to
   *     {@code null} if no session is receiving key events or if the API is not supported.
   */
  public static ListenableFuture<@NullableType SessionToken> getMediaKeyEventSession(
      Context context) {
    if (Build.VERSION.SDK_INT < 33) {
      return immediateFuture(null);
    }
    Context applicationContext = context.getApplicationContext();
    android.media.session.MediaSessionManager platformManager =
        applicationContext.getSystemService(android.media.session.MediaSessionManager.class);
    MediaSession.Token platformToken = platformManager.getMediaKeyEventSession();
    if (platformToken == null) {
      return immediateFuture(null);
    }
    @SuppressWarnings("unchecked")
    ListenableFuture<@NullableType SessionToken> result =
        (ListenableFuture<@NullableType SessionToken>)
            (ListenableFuture<?>)
                SessionToken.createSessionToken(applicationContext, platformToken);
    return result;
  }

  private static ListenableFuture<List<SessionToken>> convertControllersToTokens(
      Context context, List<MediaController> controllers) {
    ImmutableList.Builder<ListenableFuture<SessionToken>> tokenFutures =
        ImmutableList.builderWithExpectedSize(controllers.size());
    for (int i = 0; i < controllers.size(); i++) {
      tokenFutures.add(
          SessionToken.createSessionToken(context, controllers.get(i).getSessionToken()));
    }
    return convertFuturesToTokens(tokenFutures.build());
  }

  private static ListenableFuture<List<SessionToken>> convertFuturesToTokens(
      List<ListenableFuture<SessionToken>> tokenFutures) {
    ListenableFuture<List<@NullableType SessionToken>> listFuture =
        Futures.successfulAsList(tokenFutures);
    return Futures.transform(
        listFuture,
        tokens -> ImmutableList.copyOf(Iterables.filter(tokens, notNull())),
        directExecutor());
  }

  private static boolean hasMediaContentControlPermission(Context context) {
    return context.checkSelfPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        == PackageManager.PERMISSION_GRANTED;
  }

  private static boolean hasEnabledNotificationListener(
      Context context, @Nullable ComponentName notificationListener) {
    if (notificationListener == null) {
      return false;
    }
    if (Build.VERSION.SDK_INT >= 27) {
      NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
      return notificationManager.isNotificationListenerAccessGranted(notificationListener);
    }
    // Fallback for API < 27
    String enabledNotificationListeners =
        Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
    if (enabledNotificationListeners != null) {
      String[] components = Util.split(enabledNotificationListeners, ":");
      for (String component : components) {
        if (component.equals(notificationListener.flattenToString())) {
          return true;
        }
      }
    }
    return false;
  }

  // TODO: b/500320224 - Remove reflection once compileSdk is bumped to 37.
  @Nullable
  @SuppressWarnings("nullness")
  private static ListenableFuture<List<SessionToken>> getActiveSessionsForPackageReflection(
      Context context,
      android.media.session.MediaSessionManager platformManager,
      String packageName,
      @Nullable ComponentName notificationListener) {
    Method method = GET_ACTIVE_SESSIONS_FOR_PACKAGE_METHOD_SUPPLIER.get();
    if (method == null) {
      return null;
    }
    try {
      List<?> tokens = (List<?>) method.invoke(platformManager, packageName, notificationListener);
      if (tokens == null) {
        return null;
      }
      List<ListenableFuture<SessionToken>> tokenFutures = new ArrayList<>(tokens.size());
      for (Object tokenObj : tokens) {
        MediaSession.Token platformToken = (MediaSession.Token) tokenObj;
        tokenFutures.add(SessionToken.createSessionToken(context, platformToken));
      }
      return convertFuturesToTokens(tokenFutures);
    } catch (ReflectiveOperationException e) {
      Log.w(TAG, "Failed to call getActiveSessionsForPackage via reflection", e);
      return null;
    }
  }

  private static boolean tryAddOnActiveSessionsForPackageChangedListenerReflection(
      android.media.session.MediaSessionManager platformManager,
      String packageName,
      android.media.session.MediaSessionManager.OnActiveSessionsChangedListener platformListener,
      Executor executor) {
    Method method = ADD_ON_ACTIVE_SESSIONS_FOR_PACKAGE_CHANGED_LISTENER_METHOD_SUPPLIER.get();
    if (method == null) {
      return false;
    }
    try {
      method.invoke(platformManager, packageName, executor, platformListener);
      return true;
    } catch (ReflectiveOperationException e) {
      Log.w(TAG, "Failed to call addOnActiveSessionsForPackageChangedListener via reflection", e);
      return false;
    }
  }

  @CanIgnoreReturnValue
  private static boolean tryRemoveOnActiveSessionsForPackageChangedListenerReflection(
      android.media.session.MediaSessionManager platformManager,
      android.media.session.MediaSessionManager.OnActiveSessionsChangedListener platformListener) {
    Method method = REMOVE_ON_ACTIVE_SESSIONS_FOR_PACKAGE_CHANGED_LISTENER_METHOD_SUPPLIER.get();
    if (method == null) {
      return false;
    }
    try {
      method.invoke(platformManager, platformListener);
      return true;
    } catch (ReflectiveOperationException e) {
      Log.w(
          TAG, "Failed to call removeOnActiveSessionsForPackageChangedListener via reflection", e);
      return false;
    }
  }

  private static ImmutableList<MediaController> filterControllersByPackage(
      List<MediaController> controllers, String packageName) {
    return ImmutableList.copyOf(
        Iterables.filter(controllers, c -> c.getPackageName().equals(packageName)));
  }

  private static final class ListenerWrapper {
    private final android.media.session.MediaSessionManager.OnActiveSessionsChangedListener
        platformListener;
    private final boolean isPackageSpecific;

    private ListenerWrapper(
        android.media.session.MediaSessionManager.OnActiveSessionsChangedListener platformListener,
        boolean isPackageSpecific) {
      this.platformListener = platformListener;
      this.isPackageSpecific = isPackageSpecific;
    }
  }
}
