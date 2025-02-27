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
package androidx.media3.exoplayer;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.test.utils.BinderStressCreator;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Stress test for ExoPlayer creation and usage under increased stress from system binder calls,
 * verifying that no binder calls happen on the ExoPlayer main thread.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 23)
public class ExoPlayerBinderStressTest {

  @Test
  public void binderStressTest() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    SurfaceView surfaceView = TestUtil.createSurfaceView(context);

    BinderStressCreator.verifyNoSystemBinderCalls(
        /* systemUnderTest= */ () -> {
          ExoPlayer player =
              new ExoPlayer.Builder(context)
                  .setHandleAudioBecomingNoisy(true)
                  .setDeviceVolumeControlEnabled(true)
                  .setWakeMode(C.WAKE_MODE_NETWORK)
                  .setSuppressPlaybackOnUnsuitableOutput(true)
                  .setBandwidthMeter(new DefaultBandwidthMeter.Builder(context).build())
                  .build();
          player.setTrackSelectionParameters(
              new TrackSelectionParameters.Builder()
                  .setViewportSizeToPhysicalDisplaySize(/* viewportOrientationMayChange= */ true)
                  .setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                  .build());
          player.addAnalyticsListener(new EventLogger());
          player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
          player.setDeviceVolume(/* volume= */ 1, C.VOLUME_FLAG_SHOW_UI);
          player.setAudioSessionId(C.AUDIO_SESSION_ID_UNSET);
          player.setVideoSurfaceView(surfaceView);
          player.setMediaItem(MediaItem.fromUri("http://test.test"));
          player.prepare();
          player.play();
          return player;
        },
        /* cleanUp= */ ExoPlayer::release);
  }
}
