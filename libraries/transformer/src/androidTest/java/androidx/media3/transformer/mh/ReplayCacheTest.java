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

package androidx.media3.transformer.mh;

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Handler;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Util;
import androidx.media3.effect.Contrast;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.transformer.AndroidTestUtil.ReplayVideoRenderer;
import androidx.media3.transformer.PlayerTestListener;
import androidx.media3.transformer.SurfaceTestActivity;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for frame replaying (dynamic effect update). */
@RunWith(AndroidJUnit4.class)
public class ReplayCacheTest {
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  private static final MediaItem VIDEO_MEDIA_ITEM_1 = MediaItem.fromUri(MP4_ASSET.uri);
  private static final MediaItem VIDEO_MEDIA_ITEM_2 =
      MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context context = getInstrumentation().getContext().getApplicationContext();
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

  private ExoPlayer exoPlayer;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    rule.getScenario().close();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (exoPlayer != null) {
                exoPlayer.release();
              }
            });
  }

  @Test
  public void replayOnEveryFrame_withExoPlayer_succeeds()
      throws PlaybackException, TimeoutException {
    assumeTrue(
        "The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite"
            + " using MediaFormat.KEY_ALLOW_FRAME_DROP.",
        !Util.isRunningOnEmulator());
    PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    List<Long> playedFrameTimestampsUs = new ArrayList<>();

    instrumentation.runOnMainSync(
        () -> {
          Renderer videoRenderer = new ReplayVideoRenderer(context);
          exoPlayer =
              new ExoPlayer.Builder(context)
                  .setRenderersFactory(
                      new DefaultRenderersFactory(context) {
                        @Override
                        protected void buildVideoRenderers(
                            Context context,
                            @ExtensionRendererMode int extensionRendererMode,
                            MediaCodecSelector mediaCodecSelector,
                            boolean enableDecoderFallback,
                            Handler eventHandler,
                            VideoRendererEventListener eventListener,
                            long allowedVideoJoiningTimeMs,
                            ArrayList<Renderer> builtVideoRenderers) {
                          builtVideoRenderers.add(videoRenderer);
                        }
                      })
                  .build();
          exoPlayer.setVideoSurfaceView(surfaceView);
          // Adding an EventLogger to use its log output in case the test fails.
          exoPlayer.addAnalyticsListener(new EventLogger());
          exoPlayer.addListener(playerTestListener);
          exoPlayer.setVideoEffects(ImmutableList.of(new Contrast(0.5f)));
          exoPlayer.setVideoFrameMetadataListener(
              new VideoFrameMetadataListener() {
                private final List<Long> replayedFrames = new ArrayList<>();

                @Override
                public void onVideoFrameAboutToBeRendered(
                    long presentationTimeUs,
                    long releaseTimeNs,
                    Format format,
                    @Nullable MediaFormat mediaFormat) {
                  playedFrameTimestampsUs.add(presentationTimeUs);
                  if (replayedFrames.contains(presentationTimeUs)) {
                    return;
                  }
                  replayedFrames.add(presentationTimeUs);
                  instrumentation.runOnMainSync(
                      () -> exoPlayer.setVideoEffects(VideoFrameProcessor.REDRAW));
                }
              });
          exoPlayer.setMediaItems(ImmutableList.of(VIDEO_MEDIA_ITEM_1, VIDEO_MEDIA_ITEM_2));
          exoPlayer.prepare();
          exoPlayer.play();
        });

    playerTestListener.waitUntilPlayerEnded();
    // VIDEO_1 has size 30, VIDEO_2 has size 30, every frame is replayed once, minus one frame that
    // we don't currently replay (the frame at media item transition).
    assertThat(playedFrameTimestampsUs).hasSize(119);
  }
}
