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
package androidx.media3.effect.playservices;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.VideoFrameProcessingException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.media.effect.enhancement.Enhancement;
import com.google.android.gms.media.effect.enhancement.EnhancementClient;
import com.google.android.gms.media.effect.enhancement.EnhancementMode;
import com.google.android.gms.media.effect.enhancement.EnhancementOptions;
import com.google.android.gms.media.effect.enhancement.EnhancementSession;
import com.google.android.gms.media.effect.enhancement.EnhancementSessionCallback;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.Executor;

/**
 * Manages Google Play services {@link EnhancementClient} connection and {@link EnhancementSession}
 * lifecycle.
 *
 * <p>Handles device capability verification, optional module installation via Google Play services,
 * and session establishment using task chaining.
 */
@RequiresApi(30)
/* package */ final class EnhancementSessionManager {

  /** Listener for session lifecycle events. */
  interface Listener {
    /** Called when the enhancement session has been successfully created and is ready. */
    void onSessionReady(EnhancementSession session, EnhancementOptions options);

    /** Called when an unrecoverable error occurs during session initialization. */
    void onError(VideoFrameProcessingException exception);
  }

  /** Abstraction over {@link EnhancementClient} for testing. */
  interface Client {
    Task<Boolean> isDeviceSupported();

    Task<Boolean> isModuleInstalled();

    Task<Boolean> installModule(@Nullable EnhancementClient.InstallStatusCallback callback);

    Task<EnhancementSession> createSession(
        EnhancementOptions options, EnhancementSessionCallback callback);
  }

  private final Object lock = new Object();
  private final Executor handlerExecutor;
  private final Listener listener;
  private final Client client;

  @GuardedBy("lock")
  @Nullable
  private EnhancementSession session;

  @GuardedBy("lock")
  private boolean isInitialized;

  @GuardedBy("lock")
  private boolean isReleased;

  @GuardedBy("lock")
  private boolean hasNotifiedError;

  /**
   * Creates an instance.
   *
   * @param context The application context.
   * @param handlerExecutor The executor on which session callbacks and listener events are run.
   * @param listener The listener notified of session readiness or errors.
   */
  public EnhancementSessionManager(Context context, Executor handlerExecutor, Listener listener) {
    this(handlerExecutor, listener, new PlayServicesClientAdapter(context));
  }

  @VisibleForTesting
  /* package */ EnhancementSessionManager(
      Executor handlerExecutor, Listener listener, Client client) {
    this.handlerExecutor = handlerExecutor;
    this.listener = listener;
    this.client = client;
  }

  /**
   * Initializes an enhancement session asynchronously.
   *
   * <p>Must be called at most once.
   *
   * @param width Input frame width in pixels.
   * @param height Input frame height in pixels.
   * @param isTonemappingEnabled Whether tone mapping is enabled.
   * @param isDeblurAndDenoiseVideoEnabled Whether video deblurring and denoising is enabled.
   * @param isUpscaleVideoEnabled Whether 2x video upscaling is enabled.
   */
  public void initializeSession(
      @IntRange(from = 1) int width,
      @IntRange(from = 1) int height,
      boolean isTonemappingEnabled,
      boolean isDeblurAndDenoiseVideoEnabled,
      boolean isUpscaleVideoEnabled) {
    checkArgument(width >= 1);
    checkArgument(height >= 1);
    synchronized (lock) {
      checkState(!isInitialized, "initializeSession must only be called once");
      isInitialized = true;
      if (isReleased) {
        return;
      }
    }

    EnhancementOptions options =
        new EnhancementOptions(
            width,
            height,
            EnhancementMode.SURFACE,
            isTonemappingEnabled,
            /* isDeblurAndDenoisePhotoEnabled= */ false,
            isDeblurAndDenoiseVideoEnabled,
            /* isUpscalePhotoEnabled= */ false,
            isUpscaleVideoEnabled);

    client
        .isDeviceSupported()
        .onSuccessTask(
            handlerExecutor,
            supported -> {
              if (supported == null || !supported) {
                throw new VideoFrameProcessingException(
                    "Device not supported for Google Play services video enhancement");
              }
              return client.isModuleInstalled();
            })
        .onSuccessTask(
            handlerExecutor,
            installed -> {
              if (installed != null && installed) {
                return Tasks.forResult(Boolean.TRUE);
              }
              synchronized (lock) {
                if (isReleased) {
                  return Tasks.forCanceled();
                }
              }
              return client.installModule(/* callback= */ null);
            })
        .onSuccessTask(
            handlerExecutor,
            installed -> {
              if (installed == null || !installed) {
                throw new VideoFrameProcessingException(
                    "Failed to install Google Play services video enhancement module");
              }
              synchronized (lock) {
                if (isReleased) {
                  return Tasks.forCanceled();
                }
              }
              return client.createSession(options, new SessionCallback(options));
            })
        .onSuccessTask(
            handlerExecutor,
            createdSession -> {
              if (createdSession == null) {
                throw new VideoFrameProcessingException("Session creation returned null");
              }
              return Tasks.forResult(createdSession);
            })
        .addOnFailureListener(
            handlerExecutor,
            e ->
                notifyError(
                    e instanceof VideoFrameProcessingException
                        ? (VideoFrameProcessingException) e
                        : new VideoFrameProcessingException(e)));
  }

  /** Releases the underlying enhancement session and resources. */
  public void release() {
    @Nullable EnhancementSession sessionToRelease;
    synchronized (lock) {
      isReleased = true;
      sessionToRelease = session;
      session = null;
    }
    if (sessionToRelease != null) {
      sessionToRelease.release();
    }
  }

  private void notifyError(VideoFrameProcessingException exception) {
    synchronized (lock) {
      if (isReleased || hasNotifiedError) {
        return;
      }
      hasNotifiedError = true;
    }
    listener.onError(exception);
  }

  /* package */ static final class PlayServicesClientAdapter implements Client {
    private final EnhancementClient enhancementClient;

    PlayServicesClientAdapter(Context context) {
      enhancementClient = Enhancement.getClient(context.getApplicationContext());
    }

    @Override
    public Task<Boolean> isDeviceSupported() {
      return enhancementClient.isDeviceSupported();
    }

    @Override
    public Task<Boolean> isModuleInstalled() {
      return enhancementClient.isModuleInstalled();
    }

    @Override
    public Task<Boolean> installModule(@Nullable EnhancementClient.InstallStatusCallback callback) {
      return enhancementClient.installModule(callback);
    }

    @Override
    public Task<EnhancementSession> createSession(
        EnhancementOptions options, EnhancementSessionCallback callback) {
      return enhancementClient.createSession(options, callback);
    }
  }

  private final class SessionCallback implements EnhancementSessionCallback {
    private final EnhancementOptions options;

    SessionCallback(EnhancementOptions options) {
      this.options = options;
    }

    @Override
    public void onSessionCreated(EnhancementSession session) {
      handlerExecutor.execute(
          () -> {
            boolean shouldRelease;
            synchronized (lock) {
              shouldRelease = isReleased;
              if (!shouldRelease) {
                EnhancementSessionManager.this.session = session;
              }
            }
            if (shouldRelease) {
              session.release();
              return;
            }
            listener.onSessionReady(session, options);
          });
    }

    @Override
    public void onSessionCreationFailed(Status status) {
      handlerExecutor.execute(
          () ->
              notifyError(new VideoFrameProcessingException("Session creation failed: " + status)));
    }

    @Override
    public void onSessionDisconnected(Status status) {
      handlerExecutor.execute(
          () -> notifyError(new VideoFrameProcessingException("Session disconnected: " + status)));
    }

    @Override
    public void onSessionDestroyed() {}
  }
}
