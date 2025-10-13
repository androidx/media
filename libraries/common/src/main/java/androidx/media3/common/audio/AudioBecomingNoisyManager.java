/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.common.audio;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Looper;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;

/**
 * Utility class to detect when audio is becoming noisy.
 *
 * <p>The class must be used from a single thread. This can be the main thread as all blocking
 * operations are internally handled on the background {@link Looper} thread provided in the
 * constructor.
 */
@UnstableApi
public final class AudioBecomingNoisyManager {

  private final Context context;
  private final AudioBecomingNoisyReceiver receiver;
  private final HandlerWrapper backgroundHandler;

  private boolean isEnabled;

  /** Listener for becoming noisy events. */
  public interface Listener {
    /** Called when audio is becoming noisy. */
    void onAudioBecomingNoisy();
  }

  /**
   * Creates the audio becoming noisy manager.
   *
   * @param context A {@link Context}.
   * @param backgroundLooper A background {link Looper}.
   * @param eventLooper The event listener {@link Looper}.
   * @param listener The {@link Listener}
   * @param clock The {@link Clock} to schedule handler messages.
   */
  public AudioBecomingNoisyManager(
      Context context,
      Looper backgroundLooper,
      Looper eventLooper,
      Listener listener,
      Clock clock) {
    this.context = context.getApplicationContext();
    this.backgroundHandler = clock.createHandler(backgroundLooper, /* callback= */ null);
    this.receiver =
        new AudioBecomingNoisyReceiver(
            clock.createHandler(eventLooper, /* callback= */ null), listener);
  }

  /**
   * Enables the {@link AudioBecomingNoisyManager} which calls {@link
   * Listener#onAudioBecomingNoisy()} upon receiving an intent of {@link
   * AudioManager#ACTION_AUDIO_BECOMING_NOISY}.
   *
   * @param enabled True if the listener should be notified when audio is becoming noisy.
   */
  @SuppressLint("UnprotectedReceiver") // Protected system broadcasts must not specify protection.
  public void setEnabled(boolean enabled) {
    if (enabled == isEnabled) {
      return;
    }
    if (enabled) {
      backgroundHandler.post(
          () ->
              context.registerReceiver(
                  receiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)));
      isEnabled = true;
    } else {
      backgroundHandler.post(() -> context.unregisterReceiver(receiver));
      isEnabled = false;
    }
  }

  private final class AudioBecomingNoisyReceiver extends BroadcastReceiver {
    private final Listener listener;
    private final HandlerWrapper eventHandler;

    private AudioBecomingNoisyReceiver(HandlerWrapper eventHandler, Listener listener) {
      this.eventHandler = eventHandler;
      this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        eventHandler.post(this::callListenerIfEnabled);
      }
    }

    private void callListenerIfEnabled() {
      if (isEnabled) {
        listener.onAudioBecomingNoisy();
      }
    }
  }
}
