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
package androidx.media3.exoplayer.audio;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Tests that raw OEM audio-device callbacks are serialized on the receiver handler. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 23)
public class AudioCapabilitiesReceiverWrongThreadTest {

  private static final String PLAYBACK_THREAD_NAME = "ExoPlayer:Playback";
  private static final String OEM_THREAD_NAME = "AudioMonitorHdmiThread";
  private static final long TEST_TIMEOUT_SECONDS = 5;

  private HandlerThread playbackThread;
  private Handler playbackHandler;

  @Before
  public void setUp() {
    playbackThread = new HandlerThread(PLAYBACK_THREAD_NAME);
    playbackThread.start();
    playbackHandler = new Handler(playbackThread.getLooper());
  }

  @After
  public void tearDown() throws Exception {
    playbackThread.quitSafely();
    playbackThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS));
  }

  @Test
  public void rawThreadDeviceAddition_notifiesListenerOnReceiverHandler() throws Exception {
    assertRawThreadCallbackIsDeliveredOnReceiverHandler(/* added= */ true);
  }

  @Test
  public void rawThreadDeviceRemoval_notifiesListenerOnReceiverHandler() throws Exception {
    assertRawThreadCallbackIsDeliveredOnReceiverHandler(/* added= */ false);
  }

  private void assertRawThreadCallbackIsDeliveredOnReceiverHandler(boolean added)
      throws Exception {
    CapabilityContext context =
        new CapabilityContext(ApplicationProvider.getApplicationContext());
    TrackingListener listener = new TrackingListener();
    AtomicReference<AudioDeviceCallback> callbackReference = new AtomicReference<>();
    AtomicReference<AudioCapabilitiesReceiver> receiverReference = new AtomicReference<>();

    runOnPlayback(
        () -> {
          AudioCapabilitiesReceiver receiver =
              new AudioCapabilitiesReceiver(context, listener);
          listener.receiver = receiver;
          receiver.register();
          receiverReference.set(receiver);
          callbackReference.set(getPrivateField(receiver, "audioDeviceCallback"));
        });

    AtomicReference<Throwable> callbackFailure =
        invokeFromRawOemThread(checkNotNull(callbackReference.get()), added);
    runOnPlayback(() -> {});

    assertThat(callbackFailure.get()).isNull();
    assertThat(listener.threadName).isEqualTo(PLAYBACK_THREAD_NAME);
    assertThat(listener.looperName).isEqualTo(PLAYBACK_THREAD_NAME);
    assertThat(listener.stateAtListener)
        .isEqualTo(getPrivateField(receiverReference.get(), "audioCapabilities"));
  }

  private AtomicReference<Throwable> invokeFromRawOemThread(
      AudioDeviceCallback callback, boolean added) throws Exception {
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    Thread oemThread =
        new Thread(
            () -> {
              try {
                assertThat(Looper.myLooper()).isNull();
                if (added) {
                  callback.onAudioDevicesAdded(new AudioDeviceInfo[0]);
                } else {
                  callback.onAudioDevicesRemoved(new AudioDeviceInfo[0]);
                }
              } catch (Throwable throwable) {
                callbackFailure.set(throwable);
              }
            },
            OEM_THREAD_NAME);
    oemThread.start();
    oemThread.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS));
    assertThat(oemThread.isAlive()).isFalse();
    return callbackFailure;
  }

  private void runOnPlayback(Runnable action) throws Exception {
    CountDownLatch complete = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    playbackHandler.post(
        () -> {
          try {
            action.run();
          } catch (Throwable throwable) {
            failure.set(throwable);
          } finally {
            complete.countDown();
          }
        });
    assertThat(complete.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    if (failure.get() != null) {
      throw new AssertionError("Playback-thread action failed", failure.get());
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T getPrivateField(Object target, String fieldName) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return (T) field.get(target);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Could not read private field " + fieldName, exception);
    }
  }

  private static final class TrackingListener implements AudioCapabilitiesReceiver.Listener {
    @Nullable AudioCapabilitiesReceiver receiver;
    String threadName;
    String looperName;
    AudioCapabilities stateAtListener;

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
      threadName = Thread.currentThread().getName();
      Looper looper = Looper.myLooper();
      looperName = looper == null ? "null" : looper.getThread().getName();
      stateAtListener = getPrivateField(checkNotNull(receiver), "audioCapabilities");
    }
  }

  private static final class CapabilityContext extends ContextWrapper {
    private int nextEncoding = C.ENCODING_AC3;

    CapabilityContext(Context base) {
      super(base);
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
      if (receiver != null) {
        return super.registerReceiver(receiver, filter);
      }
      int encoding = nextEncoding;
      nextEncoding = nextEncoding == C.ENCODING_AC3 ? C.ENCODING_DTS : C.ENCODING_AC3;
      return new Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
          .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
          .putExtra(AudioManager.EXTRA_ENCODINGS, new int[] {encoding})
          .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 6);
    }
  }
}
