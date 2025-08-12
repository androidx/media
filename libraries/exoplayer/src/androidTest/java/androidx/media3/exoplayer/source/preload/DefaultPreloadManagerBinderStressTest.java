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
package androidx.media3.exoplayer.source.preload;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.test.utils.BinderStressCreator;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Stress test for {@link DefaultPreloadManager} creation and usage under increased stress from
 * system binder calls, verifying that no binder calls happen on the {@link DefaultPreloadManager}
 * main thread.
 */
@RunWith(AndroidJUnit4.class)
public class DefaultPreloadManagerBinderStressTest {

  @Test
  public void binderStressTest() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    SurfaceView surfaceView = TestUtil.createSurfaceView(context);

    BinderStressCreator.verifyNoSystemBinderCalls(
        /* systemUnderTest= */ () -> {
          DefaultPreloadManager.Builder builder =
              new DefaultPreloadManager.Builder(
                      context,
                      rankingData ->
                          DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED)
                  .setBandwidthMeter(new DefaultBandwidthMeter.Builder(context).build());
          MediaItem mediaItem = MediaItem.fromUri("http://test.test");

          DefaultPreloadManager preloadManager = builder.build();
          preloadManager.addListener(new PreloadManagerListener() {});
          preloadManager.add(mediaItem, /* rankingData= */ 1);
          preloadManager.invalidate();

          ExoPlayer player =
              builder.buildExoPlayer(
                  new ExoPlayer.Builder(context)
                      .setHandleAudioBecomingNoisy(true)
                      .setDeviceVolumeControlEnabled(true)
                      .setWakeMode(C.WAKE_MODE_NETWORK)
                      .setSuppressPlaybackOnUnsuitableOutput(true));
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
          player.setMediaSource(checkNotNull(preloadManager.getMediaSource(mediaItem)));
          player.prepare();
          player.play();

          preloadManager.release();
          return player;
        },
        /* cleanUp= */ ExoPlayer::release);
  }
}
