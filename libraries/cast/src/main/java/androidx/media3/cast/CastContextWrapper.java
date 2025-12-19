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

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.tasks.Task;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Wraps the Cast context.
 *
 * <p>Optionally performs the loading of the Cast context asynchronously, hiding asynchrony from
 * clients.
 *
 * <p>Must be called on the main thread.
 */
@MainThread
@UnstableApi
public class CastContextWrapper {

  private static final String TAG = "CastContextWrapper";

  // Intentionally mutable static field. Listeners should only be temporary and not cause leaks.
  @SuppressLint({"NonFinalStaticField", "StaticFieldLeak"})
  @Nullable
  private static CastContextWrapper singletonInstance;

  private final List<SessionManagerListener<CastSession>> pendingListeners;

  private final Set<MediaRouteSelectorListener> pendingMediaRouteSelectorListeners;

  @Nullable private CastContext castContext;
  private @MonotonicNonNull Throwable castContextLoadFailure;
  private boolean isInitOngoing;

  /** Returns a singleton instance of {@link CastContextWrapper}. */
  public static CastContextWrapper getSingletonInstance() {
    verifyMainThread();
    if (singletonInstance == null) {
      singletonInstance = new CastContextWrapper();
    }
    return singletonInstance;
  }

  /**
   * Initializes this wrapper with the given Cast context, and returns this wrapper for convenience.
   *
   * <p>Consider using {@link #asyncInit asynchronous initialization} to account for module load
   * errors, or to perform the Cast module loading on a background thread.
   */
  @CanIgnoreReturnValue
  public CastContextWrapper initWithContext(CastContext castContext) {
    verifyMainThread();
    checkNotNull(castContext);
    if (needsInitialization()) {
      setCastContext(castContext);
    }
    return this;
  }

  /**
   * Triggers asynchronous initialization of the CastContext.
   *
   * <p>Does nothing if {@link #needsInitialization() initialization} is not needed.
   *
   * <p>Cast context loading is offloaded to {@link BackgroundExecutor}.
   *
   * @param context A {@link Context}.
   */
  public void asyncInit(Context context) {
    asyncInit(() -> CastContext.getSharedInstance(context, BackgroundExecutor.get()));
  }

  @VisibleForTesting
  /* package */ void asyncInit(CastContextInitializer castContextInitializer) {
    verifyMainThread();
    if (!needsInitialization()) {
      Log.w(TAG, "Tried to initialize an already initialized CastContextWrapper.");
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
   * @see #asyncInit
   * @see #initWithContext
   */
  public boolean needsInitialization() {
    return castContext == null && castContextLoadFailure == null && !isInitOngoing;
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

  private CastContextWrapper() {
    pendingListeners = new ArrayList<>();
    pendingMediaRouteSelectorListeners = new HashSet<>();
  }

  @VisibleForTesting
  /* package */ static void reset() {
    verifyMainThread();
    singletonInstance = null;
  }

  @VisibleForTesting
  /* package */ interface CastContextInitializer {

    Task<CastContext> init();
  }

  /* package */ abstract static class MediaRouteSelectorListener {
    void onMediaRouteSelectorChanged(MediaRouteSelector selector) {}
  }
}
