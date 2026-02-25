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
package androidx.media3.exoplayer.dash.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.Dumper;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** End-to-end tests for the behavior of clipping with DASH media. */
@RunWith(RobolectricTestParameterInjector.class)
public final class ClippingDashPlaybackTest {

  private static final String TEST_DASH_URI = "asset:///media/cmaf/multi-segment/manifest.mpd";

  @TestParameter private boolean enableMediaPeriodClipping;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void playback_clipped() throws Exception {
    Pair<ExoPlayer, PlaybackOutput> setupData = setUpPlayerAndCapturingOutputForClippingTest();
    ExoPlayer player = setupData.first;
    PlaybackOutput playbackOutput = setupData.second;

    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(TEST_DASH_URI)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(600)
                    .setEndPositionMs(1200)
                    .build())
            .build());
    player.prepare();
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        playbackOutput,
        "playbackdumps/cmaf/clipped.dump");
  }

  @Test
  public void playback_clippedWithSeek() throws Exception {
    Pair<ExoPlayer, PlaybackOutput> setupData = setUpPlayerAndCapturingOutputForClippingTest();
    ExoPlayer player = setupData.first;
    PlaybackOutput playbackOutput = setupData.second;

    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(TEST_DASH_URI)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(600)
                    .setEndPositionMs(1200)
                    .build())
            .build());
    player.prepare();
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.seekTo(300);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        playbackOutput,
        "playbackdumps/cmaf/clipped_seek.dump");
  }

  private Pair<ExoPlayer, PlaybackOutput> setUpPlayerAndCapturingOutputForClippingTest() {
    Context context = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    // Capture the loaded segments in addition to the decoded samples to capture the loading
    // pattern and verify that loading stops once all required samples are available.
    CapturingMediaSourceEventListener capturingMediaSourceEventListener =
        new CapturingMediaSourceEventListener();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(context, clock) {
          @Override
          public void dump(Dumper dumper) {
            capturingMediaSourceEventListener.dump(dumper);
            super.dump(dumper);
          }
        };
    MediaSource.Factory dashFactory =
        new DashMediaSource.Factory(new DefaultDataSource.Factory(context));
    Handler testHandler = new Handler(Looper.myLooper());
    ExoPlayer player =
        new ExoPlayer.Builder(context, capturingRenderersFactory)
            .setMediaSourceFactory(
                new ForwardingMediaSourceFactory(dashFactory) {
                  @Override
                  public MediaSource createMediaSource(MediaItem mediaItem) {
                    MediaSource dashSource = super.createMediaSource(mediaItem);
                    dashSource.addEventListener(testHandler, capturingMediaSourceEventListener);
                    return new ClippingMediaSource.Builder(dashSource)
                        .setStartPositionUs(mediaItem.clippingConfiguration.startPositionUs)
                        .setEndPositionUs(mediaItem.clippingConfiguration.endPositionUs)
                        .setEnableClippingInMediaPeriod(enableMediaPeriodClipping)
                        .build();
                  }
                })
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    return Pair.create(player, playbackOutput);
  }

  private static final class CapturingMediaSourceEventListener
      implements MediaSourceEventListener, Dumper.Dumpable {

    private final ImmutableList.Builder<Long> videoChunkLoadStartTimes;
    private final ImmutableList.Builder<Long> audioChunkLoadStartTimes;

    private CapturingMediaSourceEventListener() {
      videoChunkLoadStartTimes = ImmutableList.builder();
      audioChunkLoadStartTimes = ImmutableList.builder();
    }

    @Override
    public void onLoadStarted(
        int windowIndex,
        @Nullable MediaSource.MediaPeriodId mediaPeriodId,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        int retryCount) {
      if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) {
        return;
      }
      ImmutableList.Builder<Long> chunkLoadStartTimes =
          mediaLoadData.trackType == C.TRACK_TYPE_VIDEO
              ? videoChunkLoadStartTimes
              : audioChunkLoadStartTimes;
      chunkLoadStartTimes.add(mediaLoadData.mediaStartTimeMs);
    }

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("video chunk loads");
      for (Long chunkStartTimeUs : videoChunkLoadStartTimes.build()) {
        dumper.add("chunk start time", chunkStartTimeUs);
      }
      dumper.endBlock();
      dumper.startBlock("audio chunk loads");
      for (Long chunkStartTimeUs : audioChunkLoadStartTimes.build()) {
        dumper.add("chunk start time", chunkStartTimeUs);
      }
      dumper.endBlock();
    }
  }
}
