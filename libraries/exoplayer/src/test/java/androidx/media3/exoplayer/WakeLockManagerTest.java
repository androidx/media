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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.HandlerThread;
import android.os.PowerManager.WakeLock;
import androidx.media3.common.util.Clock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowPowerManager;

/** Unit tests for {@link WakeLockManager} */
@RunWith(AndroidJUnit4.class)
public class WakeLockManagerTest {

  private Context context;
  private HandlerThread handlerThread;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    handlerThread = new HandlerThread("wakeLockManagerTest");
    handlerThread.start();
  }

  @After
  public void tearDown() {
    handlerThread.quit();
  }

  @Test
  public void stayAwakeFalse_wakeLockIsNeverHeld() {
    WakeLockManager wakeLockManager =
        new WakeLockManager(context, handlerThread.getLooper(), Clock.DEFAULT);
    wakeLockManager.setEnabled(true);
    wakeLockManager.setStayAwake(false);
    shadowOf(handlerThread.getLooper()).idle();

    WakeLock wakeLock = ShadowPowerManager.getLatestWakeLock();
    assertThat(wakeLock.isHeld()).isFalse();

    wakeLockManager.setEnabled(false);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(wakeLock.isHeld()).isFalse();
  }

  @Test
  public void stayAwakeTrue_wakeLockIsOnlyHeldWhenEnabled() {
    WakeLockManager wakeLockManager =
        new WakeLockManager(context, handlerThread.getLooper(), Clock.DEFAULT);
    wakeLockManager.setEnabled(true);
    wakeLockManager.setStayAwake(true);
    shadowOf(handlerThread.getLooper()).idle();

    WakeLock wakeLock = ShadowPowerManager.getLatestWakeLock();

    assertThat(wakeLock.isHeld()).isTrue();

    wakeLockManager.setEnabled(false);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(wakeLock.isHeld()).isFalse();
  }
}
