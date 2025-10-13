/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.common.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Looper;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Utility class to handle a {@link WifiLock}
 *
 * <p>The handling of wifi locks requires the {@link android.Manifest.permission#WAKE_LOCK}
 * permission.
 *
 * <p>The class must be used from a single thread. This can be the main thread as all blocking
 * operations are internally handled on the background {@link Looper} thread provided in the
 * constructor.
 */
@UnstableApi
public final class WifiLockManager {

  private static final String TAG = "WifiLockManager";
  private static final String WIFI_LOCK_TAG = "ExoPlayer:WifiLockManager";
  private static final int UNREACTIVE_WIFILOCK_HANDLER_RELEASE_DELAY_MS = 1000;

  private final WifiLockManagerInternal wifiLockManagerInternal;
  private final HandlerWrapper wifiLockHandler;
  private final HandlerWrapper mainHandler;

  private boolean enabled;
  private boolean stayAwake;

  /**
   * Creates the wifi lock manager.
   *
   * @param context A {@link Context}
   * @param wifiLockLooper A background {@link Looper} to call wifi lock system calls on.
   * @param clock The {@link Clock} to schedule handler messages.
   */
  public WifiLockManager(Context context, Looper wifiLockLooper, Clock clock) {
    wifiLockManagerInternal = new WifiLockManagerInternal(context.getApplicationContext());
    wifiLockHandler = clock.createHandler(wifiLockLooper, /* callback= */ null);
    mainHandler = clock.createHandler(Looper.getMainLooper(), /* callback= */ null);
  }

  /**
   * Sets whether to enable the usage of a {@link WifiLock}.
   *
   * <p>By default, wifi lock handling is not enabled. Enabling will acquire the wifi lock if
   * necessary. Disabling will release the wifi lock if held.
   *
   * <p>Enabling {@link WifiLock} requires the {@link android.Manifest.permission#WAKE_LOCK}.
   *
   * @param enabled True if the player should handle a {@link WifiLock}.
   */
  public void setEnabled(boolean enabled) {
    if (this.enabled == enabled) {
      return;
    }
    this.enabled = enabled;
    postUpdateWifiLock(enabled, stayAwake);
  }

  /**
   * Sets whether to acquire or release the {@link WifiLock}.
   *
   * <p>The wifi lock will not be acquired unless handling has been enabled through {@link
   * #setEnabled(boolean)}.
   *
   * @param stayAwake True if the player should acquire the {@link WifiLock}. False if it should
   *     release.
   */
  public void setStayAwake(boolean stayAwake) {
    if (this.stayAwake == stayAwake) {
      return;
    }
    this.stayAwake = stayAwake;
    if (enabled) {
      postUpdateWifiLock(/* enabled= */ true, stayAwake);
    }
  }

  private void postUpdateWifiLock(boolean enabled, boolean stayAwake) {
    if (shouldAcquireWifilock(enabled, stayAwake)) {
      wifiLockHandler.post(() -> wifiLockManagerInternal.updateWifiLock(enabled, stayAwake));
    } else {
      // When we are about to release a Wifi lock, add emergency safeguard on main thread in case
      // the lock handler thread is unresponsive.
      Runnable emergencyRelease = wifiLockManagerInternal::forceReleaseWifiLock;
      mainHandler.postDelayed(emergencyRelease, UNREACTIVE_WIFILOCK_HANDLER_RELEASE_DELAY_MS);
      wifiLockHandler.post(
          () -> {
            mainHandler.removeCallbacks(emergencyRelease);
            wifiLockManagerInternal.updateWifiLock(enabled, stayAwake);
          });
    }
  }

  private static boolean shouldAcquireWifilock(boolean enabled, boolean stayAwake) {
    return enabled && stayAwake;
  }

  /** Internal methods called on the wifi lock Looper. */
  private static final class WifiLockManagerInternal {

    private final Context applicationContext;

    private @MonotonicNonNull WifiLock wifiLock;

    public WifiLockManagerInternal(Context applicationContext) {
      this.applicationContext = applicationContext;
    }

    public void updateWifiLock(boolean enabled, boolean stayAwake) {
      if (enabled && wifiLock == null) {
        if (applicationContext.checkSelfPermission(Manifest.permission.WAKE_LOCK)
            != PackageManager.PERMISSION_GRANTED) {
          Log.w(TAG, "WAKE_LOCK permission not granted, can't acquire wake lock for playback");
          return;
        }
        WifiManager wifiManager =
            (WifiManager)
                applicationContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
          Log.w(TAG, "WifiManager is null, therefore not creating the WifiLock.");
          return;
        }
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG);
        wifiLock.setReferenceCounted(false);
      }

      if (wifiLock == null) {
        return;
      }

      if (shouldAcquireWifilock(enabled, stayAwake)) {
        wifiLock.acquire();
      } else {
        wifiLock.release();
      }
    }

    private synchronized void forceReleaseWifiLock() {
      if (wifiLock != null) {
        wifiLock.release();
      }
    }
  }
}
