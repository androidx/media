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
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.TextUtils;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.tasks.Task;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The primary entry point for interacting with the Cast context.
 *
 * <p>This singleton class manages global state associated with Cast playback, including
 * configurations (such as the receiver application id) and resource initialization.
 *
 * <p>The singleton instance must be initialized for Cast playback to function. See {@link
 * #initialize(CastParams)} for details on how initialization works.
 *
 * <p>Must be called on the main process and the main thread.
 */
@MainThread
@UnstableApi
public class Cast {

  private static final String TAG = "Cast";

  // Intentionally mutable static field. Listeners should only be temporary and not cause leaks.
  @SuppressLint({"NonFinalStaticField", "StaticFieldLeak"})
  @Nullable
  private static Cast singletonInstance;

  private final List<SessionManagerListener<CastSession>> pendingListeners;

  private final Set<MediaRouteSelectorListener> pendingMediaRouteSelectorListeners;
  @Nullable private CastParams pendingCastParams;

  @Nullable private final Context context;
  @Nullable private CastContext castContext;
  private @MonotonicNonNull Throwable castContextLoadFailure;
  private boolean isInitOngoing;

  /**
   * Returns a singleton instance of the class.
   *
   * @param context A {@link Context}.
   */
  public static Cast getSingletonInstance(Context context) {
    verifyMainThread();
    checkNotNull(context);
    if (singletonInstance == null) {
      singletonInstance = new Cast(context);
    }
    checkState(singletonInstance.context != null || !singletonInstance.needsInitialization());
    return singletonInstance;
  }

  /**
   * Returns a singleton instance of the class that's already initialized with the given Cast
   * context.
   */
  /* package */ static Cast getSingletonInstance(CastContext castContext) {
    verifyMainThread();
    checkNotNull(castContext);
    if (singletonInstance == null) {
      singletonInstance = new Cast(/* context= */ null);
    }
    if (singletonInstance.needsInitialization()) {
      singletonInstance.setCastContext(castContext);
    }
    checkState(singletonInstance.context != null || !singletonInstance.needsInitialization());
    return singletonInstance;
  }

  /** Equivalent to {@link #initialize(CastParams) initialize(CastParams.DEFAULT)}. */
  public void initialize() {
    initialize(CastParams.DEFAULT);
  }

  /**
   * Initializes the singleton instance.
   *
   * <p>This method triggers asynchronous initialization using the provided {@link CastParams}. The
   * Cast context loading is offloaded to {@link BackgroundExecutor}.
   *
   * <p>Calling this method after initialization has started (and possibly completed) applies the
   * newly provided {@link CastParams Cast parameters}.
   *
   * <p>Initialization must occur before the creation of a {@link RemoteCastPlayer} or the use of
   * Cast UI widgets, such as the {@link MediaRouteButtonFactory} or the {@link MediaRouteButtonKt}
   * composable. The recommended way to do this is calling {@link #initialize(CastParams)} in the
   * {@link Application#onCreate()} method as follows:
   *
   * <pre>{@code
   * public class MainApplication extends Application {
   *   &#64;Override
   *   public void onCreate() {
   *     super.onCreate();
   *     CastParams castParams = // Build your Cast configurations or use CastParams.DEFAULT.
   *     Cast.getSingletonInstance(this).initialize(castParams);
   *   }
   * }
   * }</pre>
   *
   * Then the application can configure the {@code MainApplication} in the {@code
   * AndroidManifest.xml} as follows:
   *
   * <pre>{@code
   * <application
   *     android:name=".MainApplication">
   *     ...
   * </application>
   * }</pre>
   *
   * <p>If the application doesn't call this method before initialization is needed (by Cast UI
   * widgets or {@link RemoteCastPlayer}), but provides the cast options tag in the manifest, then
   * those options are used to perform initialization without the need to call {@link #initialize}
   * explicitly. For example:
   *
   * <pre>{@code
   * <meta-data
   *     android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
   *     android:value="..." />
   * }</pre>
   *
   * <p>However, using manifest-provided Cast options is not recommended because they include
   * options incompatible with Media3 such as automatic media session management, which Media3
   * provides built-in support for.
   *
   * @throws IllegalStateException if this method is not called on the main process.
   */
  public void initialize(CastParams castParams) {
    checkNotNull(castParams, "castParams must not be null.");
    initialize(
        () ->
            CastContext.getSharedInstance(
                // This assertion should never fail. If context == null, then needsInit == false.
                // If needsInit == false, this lambda shouldn't run.
                checkNotNull(context),
                BackgroundExecutor.get(),
                new DefaultCastOptionsProvider(),
                castParams.toCastOptionsModifier()),
        castParams);
  }

  @VisibleForTesting
  /* package */ void initialize(
      CastContextInitializer castContextInitializer, CastParams castParams) {
    verifyMainThread();
    if (castContext != null) {
      castContext.applyOptionsModifier(castParams.toCastOptionsModifier());
      return;
    }
    if (castContextLoadFailure != null) {
      Log.w(TAG, "Tried to initialize Cast after already failed initialization.");
      return;
    }
    if (isInitOngoing) {
      pendingCastParams = castParams;
      return;
    }
    initialize(castContextInitializer);
  }

  @VisibleForTesting
  /* package */ void initialize(CastContextInitializer castContextInitializer) {
    verifyMainThread();
    if (!needsInitialization()) {
      Log.w(TAG, "Tried to initialize an already initialized Cast.");
      return;
    }
    castContextInitializer
        .init()
        .addOnCompleteListener(
            task -> {
              if (task.isSuccessful()) {
                setCastContext(task.getResult());
              } else {
                onCastContextLoadFailure(task.getException());
              }
            });
    isInitOngoing = true;
  }

  /**
   * Returns true if initialization has not yet started.
   *
   * @see #initialize
   */
  public boolean needsInitialization() {
    return castContext == null && castContextLoadFailure == null && !isInitOngoing;
  }

  /**
   * Ensures that the singleton instance is initialized.
   *
   * <p>If the singleton instance is already initialized, this method returns immediately. If the
   * singleton instance hasn't been initialized yet, this method triggers asynchronous
   * initialization using the manifest-configured options provider.
   *
   * <p>This method is intended to maintain backwards compatibility with media3 versions that used
   * the CastSDK manifest-based OptionsProvider. Once the preferred {@link #initialize} has been
   * available for a while in a stable release, this method may be removed.
   *
   * @param context A {@link Context}.
   * @throws IllegalStateException If any of the following condition occurs:
   *     <ul>
   *       <li>This method is not called on the main thread.
   *       <li>The singleton instance was not initialized via {@link Cast#initialize()} by the app
   *           yet and there is no manifest-configured options provider in the app's manifest for
   *           automatically initializing the singleton instance.
   *     </ul>
   */
  /* package */ void ensureInitialized(Context context) {
    ensureInitialized(() -> CastContext.getSharedInstance(context, BackgroundExecutor.get()));
  }

  @VisibleForTesting
  /* package */ void ensureInitialized(CastContextInitializer castContextInitializer) {
    verifyMainThread();
    if (!needsInitialization()) {
      return;
    }
    try {
      initialize(castContextInitializer);
    } catch (IllegalStateException exception) {
      // We want to rethrow the exception with a different message that instructs the app developer
      // to call initialize() in the Application#onCreate() method.
      throw new IllegalStateException(
          "Must initialize Cast prior to using it. To achieve this, call"
              + " androidx.media3.cast.Cast.getSingletoninstance(context).initialize() in"
              + " Application#onCreate() method.",
          exception);
    }
  }

  /**
   * Returns the error that caused the cast context load to fail. Returns null if initialization has
   * not failed or hasn't completed.
   */
  // TODO: b/459879564 - Use this method to show an appropriate error message in Cast UI
  // affordances.
  @Nullable
  public Throwable getCastContextLoadFailure() {
    return castContextLoadFailure;
  }

  /**
   * Registers the given session manager listener.
   *
   * <p>If no Cast context is available at the time of the invocation, registration is deferred
   * until a Cast context is available.
   *
   * @param listener The listener to register.
   */
  public void addSessionManagerListener(SessionManagerListener<CastSession> listener) {
    verifyMainThread();
    if (castContext != null) {
      castContext.getSessionManager().addSessionManagerListener(listener, CastSession.class);
    } else {
      pendingListeners.add(listener);
    }
  }

  /** Unregisters the given session manager listener. */
  public void removeSessionManagerListener(SessionManagerListener<CastSession> listener) {
    verifyMainThread();
    if (castContext != null) {
      castContext.getSessionManager().removeSessionManagerListener(listener, CastSession.class);
    } else {
      pendingListeners.remove(listener);
    }
  }

  @Nullable
  /* package */ MediaRouteSelector registerListenerAndGetCurrentSelector(
      MediaRouteSelectorListener listener) {
    checkNotNull(listener);
    if (castContext != null) {
      MediaRouteSelector selector = castContext.getMergedSelector();
      return (selector == null) ? MediaRouteSelector.EMPTY : selector;
    }
    if (castContextLoadFailure != null) {
      return MediaRouteSelector.EMPTY;
    }
    pendingMediaRouteSelectorListeners.add(listener);
    return null;
  }

  /* package */ void unregisterListener(MediaRouteSelectorListener listener) {
    pendingMediaRouteSelectorListeners.remove(listener);
  }

  /**
   * Returns the ongoing Cast session, or null if there's no ongoing Cast session, or there's no
   * Cast context available.
   */
  @Nullable
  public CastSession getCurrentCastSession() {
    verifyMainThread();
    return castContext != null ? castContext.getSessionManager().getCurrentCastSession() : null;
  }

  /**
   * If a Cast context is available, ends any ongoing Cast session. Otherwise, does nothing.
   *
   * @param stopCasting Whether to stop the receiver application.
   */
  public void endCurrentSession(boolean stopCasting) {
    verifyMainThread();
    if (castContext != null) {
      castContext.getSessionManager().endCurrentSession(stopCasting);
    }
  }

  private void setCastContext(CastContext castContext) {
    verifyMainThread();
    isInitOngoing = false;
    this.castContext = castContext;
    if (pendingCastParams != null) {
      castContext.applyOptionsModifier(pendingCastParams.toCastOptionsModifier());
      pendingCastParams = null;
    }
    SessionManager sessionManager = castContext.getSessionManager();
    for (SessionManagerListener<CastSession> listener : pendingListeners) {
      sessionManager.addSessionManagerListener(listener, CastSession.class);
    }
    CastSession castSession = sessionManager.getCurrentCastSession();
    String sessionId = castSession != null ? castSession.getSessionId() : null;
    // We don't expect the id to be null, but we still check to satisfy non-nullability requirements
    // of the listener.
    if (castSession != null && castSession.isConnected() && sessionId != null) {
      for (SessionManagerListener<CastSession> listener : pendingListeners) {
        listener.onSessionStarted(castSession, sessionId);
      }
    }
    pendingListeners.clear();
    MediaRouteSelector selector = castContext.getMergedSelector();
    selector = (selector == null) ? MediaRouteSelector.EMPTY : selector;
    notifyPendingMediaRouteSelectorListeners(selector);
  }

  private void onCastContextLoadFailure(@Nullable Exception exception) {
    isInitOngoing = false;
    pendingCastParams = null;
    castContextLoadFailure =
        exception != null
            ? exception
            : new UnknownError("Cast context load failed with a null exception.");
    Log.e(TAG, "Failed to load CastContext", castContextLoadFailure);
    // Cast context load has failed, the selector won't become non-empty. Notifying listeners with
    // an empty selector ensures that listeners are not left pending forever.
    notifyPendingMediaRouteSelectorListeners(MediaRouteSelector.EMPTY);
  }

  private void notifyPendingMediaRouteSelectorListeners(MediaRouteSelector selector) {
    for (MediaRouteSelectorListener listener : pendingMediaRouteSelectorListeners) {
      listener.onMediaRouteSelectorChanged(selector);
    }
    pendingMediaRouteSelectorListeners.clear();
  }

  private Cast(@Nullable Context context) {
    checkRunningOnMainProcess(context);
    this.context = (context != null) ? context.getApplicationContext() : null;
    pendingListeners = new ArrayList<>();
    pendingMediaRouteSelectorListeners = new HashSet<>();
  }

  @VisibleForTesting
  /* package */ static void reset() {
    verifyMainThread();
    singletonInstance = null;
  }

  /**
   * Verifies that this instance is executing on the application's main process.
   *
   * <p>This verification is crucial because both the underlying {@link CastContext} and Android's
   * {@link androidx.mediarouter.media.MediaRouter} are designed to operate exclusively on the main
   * process. Failure to do so will lead to unpredictable behavior.
   *
   * @param context The application context.
   * @throws IllegalStateException if the active process is not the application's main process.
   */
  private void checkRunningOnMainProcess(
      @UnderInitialization Cast this, @Nullable Context context) {
    if (context == null) {
      return;
    }
    // Only check on Android P and above because apps must support newer versions of Android. This
    // check should be sufficient to ensure that the app is running in the main process.
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      String mainProcessName = context.getPackageName();
      String processName = Application.getProcessName();
      if (!TextUtils.equals(processName, mainProcessName)) {
        throw new IllegalStateException(
            Util.formatInvariant(
                "The method must be called on the main process (%s), but was called on the process"
                    + " (%s).",
                mainProcessName, processName));
      }
    }
  }

  @VisibleForTesting
  /* package */ interface CastContextInitializer {

    Task<CastContext> init();
  }

  /* package */ abstract static class MediaRouteSelectorListener {
    void onMediaRouteSelectorChanged(MediaRouteSelector selector) {}
  }
}
