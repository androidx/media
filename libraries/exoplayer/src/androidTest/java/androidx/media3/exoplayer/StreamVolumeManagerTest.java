/*
 * Copyright 2020 The Android Open Source Project
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
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.test.utils.DummyMainThread;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link StreamVolumeManager}. */
@RunWith(AndroidJUnit4.class)
public class StreamVolumeManagerTest {

  private static final long TIMEOUT_MS = 1_000;

  private AudioManager audioManager;
  private TestListener testListener;
  private DummyMainThread testThread;
  private StreamVolumeManager streamVolumeManager;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();

    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    testListener = new TestListener();

    testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            streamVolumeManager =
                new StreamVolumeManager(
                    context, new Handler(Looper.myLooper()), testListener, C.STREAM_TYPE_DEFAULT));
  }

  @After
  public void tearDown() {
    testThread.runOnMainThread(() -> streamVolumeManager.release());
    testThread.release();
  }

  @Test
  @SdkSuppress(minSdkVersion = 28)
  public void getMinVolume_returnsStreamMinVolume() {
    testThread.runOnMainThread(
        () -> {
          int streamMinVolume = audioManager.getStreamMinVolume(C.STREAM_TYPE_DEFAULT);
          assertThat(streamVolumeManager.getMinVolume()).isEqualTo(streamMinVolume);
        });
  }

  @Test
  public void getMaxVolume_returnsStreamMaxVolume() {
    testThread.runOnMainThread(
        () -> {
          int streamMaxVolume = audioManager.getStreamMaxVolume(C.STREAM_TYPE_DEFAULT);
          assertThat(streamVolumeManager.getMaxVolume()).isEqualTo(streamMaxVolume);
        });
  }

  @Test
  public void getVolume_returnsStreamVolume() {
    testThread.runOnMainThread(
        () -> {
          int streamVolume = audioManager.getStreamVolume(C.STREAM_TYPE_DEFAULT);
          assertThat(streamVolumeManager.getVolume()).isEqualTo(streamVolume);
        });
  }

  @Test
  public void setVolume_changesStreamVolume() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume) {
            return;
          }
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          int oldVolume = streamVolumeManager.getVolume();
          int targetVolume = oldVolume == maxVolume ? minVolume : maxVolume;

          streamVolumeManager.setVolume(targetVolume, volumeFlags);

          assertThat(streamVolumeManager.getVolume()).isEqualTo(targetVolume);
          assertThat(testListener.lastStreamVolume).isEqualTo(targetVolume);
          assertThat(audioManager.getStreamVolume(C.STREAM_TYPE_DEFAULT)).isEqualTo(targetVolume);
        });
  }

  @Test
  public void setVolume_withOutOfRange_isIgnored() {
    testThread.runOnMainThread(
        () -> {
          int maxVolume = streamVolumeManager.getMaxVolume();
          int minVolume = streamVolumeManager.getMinVolume();
          int oldVolume = streamVolumeManager.getVolume();
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(maxVolume + 1, volumeFlags);
          assertThat(streamVolumeManager.getVolume()).isEqualTo(oldVolume);

          streamVolumeManager.setVolume(minVolume - 1, volumeFlags);
          assertThat(streamVolumeManager.getVolume()).isEqualTo(oldVolume);
        });
  }

  @Test
  public void increaseVolume_increasesStreamVolumeByOne() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume) {
            return;
          }
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(minVolume, volumeFlags);
          int targetVolume = minVolume + 1;

          streamVolumeManager.increaseVolume(volumeFlags);

          assertThat(streamVolumeManager.getVolume()).isEqualTo(targetVolume);
          assertThat(testListener.lastStreamVolume).isEqualTo(targetVolume);
          assertThat(audioManager.getStreamVolume(C.STREAM_TYPE_DEFAULT)).isEqualTo(targetVolume);
        });
  }

  @Test
  public void increaseVolume_onMaxVolume_isIgnored() {
    testThread.runOnMainThread(
        () -> {
          int maxVolume = streamVolumeManager.getMaxVolume();
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(maxVolume, volumeFlags);
          streamVolumeManager.increaseVolume(volumeFlags);

          assertThat(streamVolumeManager.getVolume()).isEqualTo(maxVolume);
        });
  }

  @Test
  public void decreaseVolume_decreasesStreamVolumeByOne() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume) {
            return;
          }
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(maxVolume, volumeFlags);
          int targetVolume = maxVolume - 1;

          streamVolumeManager.decreaseVolume(volumeFlags);

          assertThat(streamVolumeManager.getVolume()).isEqualTo(targetVolume);
          assertThat(testListener.lastStreamVolume).isEqualTo(targetVolume);
          assertThat(audioManager.getStreamVolume(C.STREAM_TYPE_DEFAULT)).isEqualTo(targetVolume);
        });
  }

  @Test
  public void decreaseVolume_onMinVolume_isIgnored() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(minVolume, volumeFlags);
          streamVolumeManager.decreaseVolume(volumeFlags);

          assertThat(streamVolumeManager.getVolume()).isEqualTo(minVolume);
        });
  }

  @Test
  public void setVolumeMuted_changesMuteState() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume || minVolume > 0) {
            return;
          }
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          streamVolumeManager.setVolume(maxVolume, volumeFlags);
          assertThat(streamVolumeManager.isMuted()).isFalse();

          streamVolumeManager.setMuted(true, volumeFlags);
          assertThat(streamVolumeManager.isMuted()).isTrue();
          assertThat(testListener.lastStreamVolumeMuted).isTrue();

          streamVolumeManager.setMuted(false, volumeFlags);
          assertThat(streamVolumeManager.isMuted()).isFalse();
          assertThat(testListener.lastStreamVolumeMuted).isFalse();
          assertThat(testListener.lastStreamVolume).isEqualTo(maxVolume);
        });
  }

  @Test
  public void setStreamType_toNonDefaultType_notifiesStreamTypeAndVolume() {
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume) {
            return;
          }
          int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

          int testStreamType = C.STREAM_TYPE_ALARM; // not STREAM_TYPE_DEFAULT, i.e. MUSIC
          int testStreamVolume = audioManager.getStreamVolume(testStreamType);

          int oldVolume = streamVolumeManager.getVolume();
          if (oldVolume == testStreamVolume) {
            int differentVolume = oldVolume == minVolume ? maxVolume : minVolume;
            streamVolumeManager.setVolume(differentVolume, volumeFlags);
          }

          streamVolumeManager.setStreamType(testStreamType);

          assertThat(testListener.lastStreamType).isEqualTo(testStreamType);
          assertThat(testListener.lastStreamVolume).isEqualTo(testStreamVolume);
          assertThat(streamVolumeManager.getVolume()).isEqualTo(testStreamVolume);
        });
  }

  @Test
  public void onStreamVolumeChanged_isCalled_whenAudioManagerChangesIt() throws Exception {
    AtomicInteger targetVolumeRef = new AtomicInteger();
    testThread.runOnMainThread(
        () -> {
          int minVolume = streamVolumeManager.getMinVolume();
          int maxVolume = streamVolumeManager.getMaxVolume();
          if (minVolume == maxVolume) {
            return;
          }

          int oldVolume = streamVolumeManager.getVolume();
          int targetVolume = oldVolume == maxVolume ? minVolume : maxVolume;
          targetVolumeRef.set(targetVolume);

          audioManager.setStreamVolume(C.STREAM_TYPE_DEFAULT, targetVolume, /* flags= */ 0);
        });

    testListener.onStreamVolumeChangedLatch.await(TIMEOUT_MS, MILLISECONDS);
    assertThat(testListener.lastStreamVolume).isEqualTo(targetVolumeRef.get());
  }

  private static class TestListener implements StreamVolumeManager.Listener {

    private @C.StreamType int lastStreamType;
    private int lastStreamVolume;
    private boolean lastStreamVolumeMuted;
    public final CountDownLatch onStreamVolumeChangedLatch;

    public TestListener() {
      onStreamVolumeChangedLatch = new CountDownLatch(1);
    }

    @Override
    public void onStreamTypeChanged(@C.StreamType int streamType) {
      lastStreamType = streamType;
    }

    @Override
    public void onStreamVolumeChanged(int streamVolume, boolean streamMuted) {
      lastStreamVolume = streamVolume;
      lastStreamVolumeMuted = streamMuted;
      onStreamVolumeChangedLatch.countDown();
    }
  }
}
