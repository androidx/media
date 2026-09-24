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

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioDeviceInfo;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Tests that raw OEM audio-device callbacks are serialized on the receiver handler. */
@RunWith(AndroidJUnit4.class)
public final class AudioCapabilitiesReceiverWrongThreadTest {

  private static final AudioCapabilities OVERRIDDEN_AUDIO_CAPABILITIES =
      new AudioCapabilities(
          new int[] {C.ENCODING_AC3},
          /* maxChannelCount= */ 6,
          /* speakerLayoutChannelMasks= */ ImmutableList.of(),
          /* spatializerChannelMasks= */ ImmutableList.of());

  private ExecutorService wrongThreadExecutor;

  @Before
  public void setUp() {
    wrongThreadExecutor = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() {
    wrongThreadExecutor.shutdownNow();
  }

  @Test
  @Config(maxSdk = 37) // The workaround isn't needed from API 38+
  public void onAudioDevicesAdded_fromBackgroundThreadWithoutLooper_notifiesListenerOnLooperThread()
      throws Exception {
    TrackingListener listener = new TrackingListener();
    AudioCapabilitiesReceiver audioCapabilitiesReceiver =
        new AudioCapabilitiesReceiver(
            ApplicationProvider.getApplicationContext(),
            listener,
            new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* routedDevice= */ null);
    AudioCapabilities _ = audioCapabilitiesReceiver.register();
    audioCapabilitiesReceiver.overrideCapabilities(OVERRIDDEN_AUDIO_CAPABILITIES);

    wrongThreadExecutor
        .submit(
            () ->
                audioCapabilitiesReceiver.audioDeviceCallback.onAudioDevicesAdded(
                    new AudioDeviceInfo[0]))
        .get();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(listener.invocationCount.get()).isEqualTo(3);
    assertThat(listener.looperlessThreads).isEmpty();
  }

  @Test
  @Config(maxSdk = 37) // The workaround isn't needed from API 38+
  public void
      onAudioDevicesRemoved_fromBackgroundThreadWithoutLooper_notifiesListenerOnLooperThread()
          throws Exception {
    TrackingListener listener = new TrackingListener();
    AudioCapabilitiesReceiver audioCapabilitiesReceiver =
        new AudioCapabilitiesReceiver(
            ApplicationProvider.getApplicationContext(),
            listener,
            new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* routedDevice= */ null);
    AudioCapabilities _ = audioCapabilitiesReceiver.register();
    audioCapabilitiesReceiver.overrideCapabilities(OVERRIDDEN_AUDIO_CAPABILITIES);

    wrongThreadExecutor
        .submit(
            () ->
                audioCapabilitiesReceiver.audioDeviceCallback.onAudioDevicesRemoved(
                    new AudioDeviceInfo[0]))
        .get();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(listener.invocationCount.get()).isEqualTo(3);
    assertThat(listener.looperlessThreads).isEmpty();
  }

  @Test
  public void onAudioDevicesAdded_onCorrectLooperThread_doesntRePost() throws Exception {
    TrackingListener listener = new TrackingListener();
    AudioCapabilitiesReceiver audioCapabilitiesReceiver =
        new AudioCapabilitiesReceiver(
            ApplicationProvider.getApplicationContext(),
            listener,
            new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* routedDevice= */ null);
    AudioCapabilities _ = audioCapabilitiesReceiver.register();
    audioCapabilitiesReceiver.overrideCapabilities(OVERRIDDEN_AUDIO_CAPABILITIES);

    audioCapabilitiesReceiver.audioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[0]);

    // Assert the listener is invoked immediately without needing to idle the main looper.
    assertThat(listener.invocationCount.get()).isEqualTo(3);
    assertThat(listener.looperlessThreads).isEmpty();
  }

  private static final class TrackingListener implements AudioCapabilitiesReceiver.Listener {
    private final Set<Thread> looperlessThreads = Sets.newConcurrentHashSet();
    private final AtomicInteger invocationCount = new AtomicInteger();

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
      invocationCount.incrementAndGet();
      if (Looper.myLooper() == null) {
        looperlessThreads.add(Thread.currentThread());
      }
    }
  }
}
