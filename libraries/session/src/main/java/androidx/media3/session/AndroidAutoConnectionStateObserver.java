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

import static android.os.Build.VERSION.SDK_INT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import androidx.media3.common.util.BackgroundExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Util class to observe the connection state to Android Auto. */
/* package */ final class AndroidAutoConnectionStateObserver {

  private static final Uri QUERY_URI = Uri.parse("content://androidx.car.app.connection");
  private static final String QUERY_COLUMN = "CarConnectionState";
  private static final String BROADCAST_INTENT =
      "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";

  private final Context context;
  private final Runnable listener;
  private final Executor backgroundExecutor;
  private final AndroidAutoChangeReceiver changeReceiver;
  private final AtomicBoolean isConnected;
  private final AtomicBoolean isReleased;

  /**
   * Creates the observer.
   *
   * @param context A {@link Context}.
   * @param onConnectionStateChanged Called when the return value of {@link #isConnected()} changed.
   *     Will be called on a background thread.
   */
  public AndroidAutoConnectionStateObserver(Context context, Runnable onConnectionStateChanged) {
    this.context = context.getApplicationContext();
    this.listener = onConnectionStateChanged;
    this.backgroundExecutor = BackgroundExecutor.get();
    this.changeReceiver = new AndroidAutoChangeReceiver();
    this.isConnected = new AtomicBoolean();
    this.isReleased = new AtomicBoolean();
    backgroundExecutor.execute(
        () -> {
          IntentFilter intentFilter = new IntentFilter(BROADCAST_INTENT);
          if (SDK_INT >= 33) {
            this.context.registerReceiver(changeReceiver, intentFilter, Context.RECEIVER_EXPORTED);
          } else {
            this.context.registerReceiver(changeReceiver, intentFilter);
          }
          updateConnectionState();
        });
  }

  /** Release the observer. */
  public void release() {
    if (isReleased.getAndSet(true)) {
      return;
    }
    backgroundExecutor.execute(() -> context.unregisterReceiver(changeReceiver));
  }

  /** Returns whether the device is currently connected to Android Auto. */
  public boolean isConnected() {
    return isConnected.get();
  }

  private void updateConnectionState() {
    boolean oldValue = isConnected.get();
    boolean newValue = queryConnectionState();
    isConnected.set(newValue);
    if (oldValue != newValue && !isReleased.get()) {
      listener.run();
    }
  }

  private boolean queryConnectionState() {
    // Query the Android Auto content provider and check if it returns at least one non-zero entry
    // for the requested query column.
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                QUERY_URI,
                new String[] {QUERY_COLUMN},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* orderBy= */ null)) {
      if (cursor == null) {
        return false;
      }
      int columnIndex = cursor.getColumnIndex(QUERY_COLUMN);
      if (columnIndex == -1) {
        return false;
      }
      if (!cursor.moveToNext()) {
        return false;
      }
      return cursor.getInt(columnIndex) != 0;
    } catch (Exception e) {
      return false;
    }
  }

  private final class AndroidAutoChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      backgroundExecutor.execute(AndroidAutoConnectionStateObserver.this::updateConnectionState);
    }
  }
}
