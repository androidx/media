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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end playback tests using scrubbing mode. */
@RunWith(AndroidJUnit4.class)
public class ScrubbingPlaybackTest {
  private static final String TEST_BEAR_URI = "asset:///media/vp9/bear-vp9.webm";
  private static final String TEST_MP4_URI = "asset:///media/mp4/sample_edit_list.mp4";

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void scrubbingPlayback_withSkipMediaCodecFlushingEnabled_dumpsCorrectOutput()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    AtomicLong blockingPresentationTimeUs = new AtomicLong(250_000L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_BEAR_URI).build());
    player.prepare();

    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeEnabled(true);
    player.setScrubbingModeParameters(
        player
            .getScrubbingModeParameters()
            .buildUpon()
            .setAllowSkippingKeyFrameReset(false)
            .build());
    player.seekTo(500);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingMediaCodecFlush).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-flushingDisabled.dump");
  }

  @Test
  public void
      scrubbingPlayback_withSkipMediaCodecFlushingEnabledAndSeekBackwards_dumpsCorrectOutput()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    AtomicLong blockingPresentationTimeUs = new AtomicLong(500_000L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_BEAR_URI).build());
    player.prepare();

    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeEnabled(true);
    player.seekTo(200);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingMediaCodecFlush).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-seekBackwardsFlushingDisabled.dump");
  }

  @Test
  public void
      scrubbingPlayback_withSkipMediaCodecFlushingEnabledAndSeekToBufferInCodec_dumpsCorrectOutput()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    AtomicLong blockingPresentationTimeUs = new AtomicLong(230_000L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_BEAR_URI).build());
    player.prepare();

    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeParameters(
        player
            .getScrubbingModeParameters()
            .buildUpon()
            .setAllowSkippingKeyFrameReset(false)
            .build());
    player.setScrubbingModeEnabled(true);
    player.seekTo(234);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingMediaCodecFlush).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-seekToBufferInCodecFlushingDisabled.dump");
  }

  @Test
  public void scrubbingPlayback_withSkipMediaCodecFlushingDisabled_dumpsCorrectOutput()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    AtomicLong blockingPresentationTimeUs = new AtomicLong(250_000L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_BEAR_URI).build());
    player.prepare();

    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeParameters(
        player
            .getScrubbingModeParameters()
            .buildUpon()
            .setAllowSkippingMediaCodecFlush(false)
            .setAllowSkippingKeyFrameReset(false)
            .build());
    player.setScrubbingModeEnabled(true);
    player.seekTo(500);

    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingMediaCodecFlush).isFalse();
    assertThat(player.getScrubbingModeParameters().allowSkippingKeyFrameReset).isFalse();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-flushingEnabled.dump");
  }

  @Test
  public void scrubbingPlayback_withSkipKeyFrameResetEnabled_dumpsCorrectOutput() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    AtomicLong blockingPresentationTimeUs = new AtomicLong(250_000L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_BEAR_URI).build());
    player.prepare();
    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeEnabled(true);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);

    player.seekTo(500);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingKeyFrameReset).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-skipKeyFrameReset.dump");
  }

  @Test
  public void
      scrubbingPlayback_withSkipKeyFrameResetEnabledAndNonSequentialFrames_dumpsCorrectOutput()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    AtomicLong blockingPresentationTimeUs = new AtomicLong(711_666L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_MP4_URI).build());
    player.prepare();
    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeEnabled(true);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);

    player.seekTo(878);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingKeyFrameReset).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-skipKeyFrameReset-nonSequentialFrames.dump");
  }

  @Test
  public void
      scrubbingPlayback_withSkipKeyFrameResetEnabledAndDifferentSyncPoint_dumpsCorrectOutput()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    AtomicLong blockingPresentationTimeUs = new AtomicLong(1411665L);
    AtomicBoolean hasReceivedOutputBufferPastBlockTime = new AtomicBoolean(false);
    CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer capturingRenderersFactory =
        new CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
            applicationContext,
            clock,
            blockingPresentationTimeUs,
            hasReceivedOutputBufferPastBlockTime);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.addMediaItem(new MediaItem.Builder().setUri(TEST_MP4_URI).build());
    player.prepare();
    // Play until renderer has reached the specified blocked presentation time.
    play(player).untilBackgroundThreadCondition(hasReceivedOutputBufferPastBlockTime::get);
    player.setScrubbingModeEnabled(true);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);

    player.seekTo(1746);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);
    // End blocking in renderer.
    blockingPresentationTimeUs.set(Long.MAX_VALUE);
    player.setScrubbingModeEnabled(false);
    TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    player.release();
    surface.release();

    assertThat(player.getScrubbingModeParameters().allowSkippingKeyFrameReset).isTrue();
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        /* dumpFile= */ "playbackdumps/scrubbing/scrubbing-skipKeyFrameReset-seekToNextGoP.dump");
  }

  /**
   * A @link CapturingRenderersFactory} that provides a custom {@link MediaCodecVideoRenderer} that
   * can block output buffer processing at a specific buffer presentation time.
   */
  private static final class CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer
      extends CapturingRenderersFactory {

    private final AtomicLong blockingPresentationTimeUs;
    private final AtomicBoolean hasReceivedOutputBufferPastBlockTime;

    public CapturingRenderersFactoryWithBlockingMediaCodecVideoRenderer(
        Context context,
        Clock clock,
        AtomicLong blockingPresentationTimeUs,
        AtomicBoolean hasReceivedOutputBufferPastBlockTime) {
      super(context, clock);
      this.blockingPresentationTimeUs = blockingPresentationTimeUs;
      this.hasReceivedOutputBufferPastBlockTime = hasReceivedOutputBufferPastBlockTime;
    }

    @Override
    protected MediaCodecVideoRenderer createMediaCodecVideoRenderer(
        Handler eventHandler, VideoRendererEventListener videoRendererEventListener) {
      return new CapturingMediaCodecVideoRenderer(
          getContext(),
          getMediaCodecAdapterFactory(),
          MediaCodecSelector.DEFAULT,
          DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS,
          /* enableDecoderFallback= */ false,
          eventHandler,
          videoRendererEventListener,
          DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
          /* parseAv1SampleDependencies= */ false) {
        @Override
        protected boolean processOutputBuffer(
            long positionUs,
            long elapsedRealtimeUs,
            @Nullable MediaCodecAdapter codec,
            @Nullable ByteBuffer buffer,
            int bufferIndex,
            int bufferFlags,
            int sampleCount,
            long bufferPresentationTimeUs,
            boolean isDecodeOnlyBuffer,
            boolean isLastBuffer,
            Format format)
            throws ExoPlaybackException {
          if ((bufferPresentationTimeUs - getOutputStreamOffsetUs())
              > blockingPresentationTimeUs.get()) {
            hasReceivedOutputBufferPastBlockTime.set(true);
            return false;
          }
          return super.processOutputBuffer(
              positionUs,
              elapsedRealtimeUs,
              codec,
              buffer,
              bufferIndex,
              bufferFlags,
              sampleCount,
              bufferPresentationTimeUs,
              isDecodeOnlyBuffer,
              isLastBuffer,
              format);
        }
      };
    }
  }
}
