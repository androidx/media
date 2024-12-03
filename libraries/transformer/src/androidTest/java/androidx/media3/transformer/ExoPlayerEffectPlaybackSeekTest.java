/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.view.SurfaceView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for seeking when using {@link ExoPlayer#setVideoEffects(List)}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerEffectPlaybackSeekTest {

  // This timeout is made longer for emulators - see
  // ExternalTextureManager.SURFACE_TEXTURE_TIMEOUT_MS.
  private static final long TEST_TIMEOUT_MS = 60_000;

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getContext().getApplicationContext();
  private @MonotonicNonNull ExoPlayer player;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    rule.getScenario().close();
    instrumentation.runOnMainSync(
        () -> {
          if (player != null) {
            player.release();
          }
        });
  }

  @Test
  public void seekTo_frameNotRenderedToSurfaceTexture_unblocksFrameProcessing() throws Exception {
    // The test aims to test the scenario that
    //   1. MCVR (MediaCodecVideoRenderer) registered a frame to DVFP(DefaultVideoFrameProcessor)'s
    //     ETM (ExternalTextureManager)
    //   2. MCVR then have MediaCodec render that frame to DVFP
    //     a. When ETM receives the frame available callback, it posts the handling of the frame
    //        onto the GL thread
    //   3. The player seeks, MCVR flushes the DVFP. This subsequently flushes the GL thread
    // This test ensures playback continues regardless if the frame handling logic (2.a) is run or
    // not. The test overrides the video renderer so that a frame is registered to DVFP, but not
    // rendered by MediaCodec.
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int frameIndexToSkip = 15;
    ConditionVariable frameSkippedCondition = new ConditionVariable();

    MediaCodecVideoRenderer videoRenderer =
        new MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT) {
          private int numberOfFramesRendered;

          // Overriding V21 is sufficient as we don't have test running below API26.
          @Override
          protected void renderOutputBufferV21(
              MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
            numberOfFramesRendered++;
            if (numberOfFramesRendered == frameIndexToSkip) {
              frameSkippedCondition.open();
              return;
            }
            super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
          }

          @Override
          protected boolean shouldDropOutputBuffer(
              long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
            return false;
          }

          @Override
          protected boolean shouldDropBuffersToKeyframe(
              long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
            return false;
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          player =
              new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
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
                            ArrayList<Renderer> out) {
                          out.add(videoRenderer);
                        }
                      })
                  .build();

          player.setPlayWhenReady(true);
          player.setVideoSurfaceView(surfaceView);
          // Use an empty list to enable effect playback.
          player.setVideoEffects(ImmutableList.of());
          // Adding an EventLogger to use its log output in case the test fails.
          player.addAnalyticsListener(new EventLogger());
          player.addListener(listener);
          player.setMediaItem(MediaItem.fromUri(MP4_ASSET.uri));
          player.prepare();
        });

    // Wait until the frame is skipped, and checks enough frames are skipped when block() returns.
    checkState(frameSkippedCondition.block(TEST_TIMEOUT_MS));
    instrumentation.runOnMainSync(() -> player.seekTo(0));
    listener.waitUntilPlayerEnded();
  }
}
