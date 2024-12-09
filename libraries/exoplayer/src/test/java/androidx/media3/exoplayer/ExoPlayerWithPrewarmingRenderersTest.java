/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExoPlayer} with the pre-warming render feature. */
@RunWith(AndroidJUnit4.class)
public class ExoPlayerWithPrewarmingRenderersTest {

  private Context context;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.forAllSupportedMimeTypes();

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void play_withTwoItemsAndPrewarming_secondaryRendererisEnabledButNotStarted()
      throws Exception {
    Clock fakeClock = new FakeClock(/* isAutoAdvancing= */ true);
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .setRenderersFactory(
                new FakeRenderersFactorySupportingSecondaryVideoRenderer(fakeClock))
            .build();
    Renderer videoRenderer = player.getRenderer(/* index= */ 0);
    Renderer secondaryVideoRenderer = player.getSecondaryRenderer(/* index= */ 0);
    // Set a playlist that allows a new renderer to be enabled early.
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT)));
    player.prepare();

    // Play a bit until the second renderer is being pre-warmed.
    player.play();
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_ENABLED);
    @Renderer.State int videoState1 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState1 = secondaryVideoRenderer.getState();
    // Play until second item is started.
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_STARTED);
    @Renderer.State int videoState2 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState2 = secondaryVideoRenderer.getState();
    player.release();

    assertThat(videoState1).isEqualTo(Renderer.STATE_STARTED);
    assertThat(secondaryVideoState1).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoState2).isEqualTo(Renderer.STATE_DISABLED);
    assertThat(secondaryVideoState2).isEqualTo(Renderer.STATE_STARTED);
  }

  @Test
  public void
      play_withThreeItemsAndPrewarming_playerSuccessfullyPrewarmsAndSwapsBackToPrimaryRenderer()
          throws Exception {
    Clock fakeClock = new FakeClock(/* isAutoAdvancing= */ true);
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .setRenderersFactory(
                new FakeRenderersFactorySupportingSecondaryVideoRenderer(fakeClock))
            .build();
    Renderer videoRenderer = player.getRenderer(/* index= */ 0);
    Renderer secondaryVideoRenderer = player.getSecondaryRenderer(/* index= */ 0);
    // Set a playlist that allows a new renderer to be enabled early.
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT)));
    player.prepare();

    // Play a bit until the secondary renderer is being pre-warmed.
    player.play();
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_ENABLED);
    @Renderer.State int videoState1 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState1 = secondaryVideoRenderer.getState();
    // Play until until the primary renderer is being pre-warmed.
    run(player)
        .untilBackgroundThreadCondition(() -> videoRenderer.getState() == Renderer.STATE_ENABLED);
    @Renderer.State int videoState2 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState2 = secondaryVideoRenderer.getState();
    // Play until past transition back to primary renderer for third media item.
    run(player)
        .untilBackgroundThreadCondition(() -> videoRenderer.getState() == Renderer.STATE_STARTED);
    @Renderer.State int videoState3 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState3 = secondaryVideoRenderer.getState();
    player.release();

    assertThat(videoState1).isEqualTo(Renderer.STATE_STARTED);
    assertThat(videoState2).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoState3).isEqualTo(Renderer.STATE_STARTED);
    assertThat(secondaryVideoState1).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(secondaryVideoState2).isEqualTo(Renderer.STATE_STARTED);
    assertThat(secondaryVideoState3).isEqualTo(Renderer.STATE_DISABLED);
  }

  @Test
  public void prepare_withPeriodBetweenPlayingAndPrewarmingPeriods_playerSuccessfullyPrewarms()
      throws Exception {
    Clock fakeClock = new FakeClock(/* isAutoAdvancing= */ true);
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .setRenderersFactory(
                new FakeRenderersFactorySupportingSecondaryVideoRenderer(fakeClock))
            .build();
    Renderer secondaryVideoRenderer = player.getSecondaryRenderer(/* index= */ 0);
    // Set a playlist that allows a new renderer to be enabled early.
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT),
            new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.AUDIO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT)));
    player.prepare();

    // Advance media periods until secondary renderer is being pre-warmed.
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_ENABLED);
    @Renderer.State int secondaryVideoState = secondaryVideoRenderer.getState();
    player.release();

    assertThat(secondaryVideoState).isEqualTo(Renderer.STATE_ENABLED);
  }

  @Test
  public void
      setPlayWhenReady_playFromPauseWithPrewarmingPrimaryRenderer_primaryRendererIsEnabledButNotStarted()
          throws Exception {
    Clock fakeClock = new FakeClock(/* isAutoAdvancing= */ true);
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .setRenderersFactory(
                new FakeRenderersFactorySupportingSecondaryVideoRenderer(fakeClock))
            .build();
    Renderer videoRenderer = player.getRenderer(/* index= */ 0);
    Renderer secondaryVideoRenderer = player.getSecondaryRenderer(/* index= */ 0);
    // Set a playlist that allows a new renderer to be enabled early.
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT)));
    player.prepare();

    // Play a bit until the primary renderer has been enabled, but not yet started.
    player.play();
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_STARTED);
    run(player)
        .untilBackgroundThreadCondition(() -> videoRenderer.getState() == Renderer.STATE_ENABLED);
    @Renderer.State int videoState1 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState1 = secondaryVideoRenderer.getState();
    player.pause();
    run(player).untilPendingCommandsAreFullyHandled();
    @Renderer.State int videoState2 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState2 = secondaryVideoRenderer.getState();
    player.play();
    run(player)
        .untilBackgroundThreadCondition(
            () -> secondaryVideoRenderer.getState() == Renderer.STATE_STARTED);
    @Renderer.State int videoState3 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState3 = secondaryVideoRenderer.getState();
    player.release();

    assertThat(videoState1).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(secondaryVideoState1).isEqualTo(Renderer.STATE_STARTED);
    assertThat(videoState2).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(secondaryVideoState2).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoState3).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(secondaryVideoState3).isEqualTo(Renderer.STATE_STARTED);
  }

  @Test
  public void
      setPlayWhenReady_playFromPauseWithPrewarmingNonTransitioningRenderer_rendererIsEnabledButNotStarted()
          throws Exception {
    Clock fakeClock = new FakeClock(/* isAutoAdvancing= */ true);
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .setRenderersFactory(
                new FakeRenderersFactorySupportingSecondaryVideoRenderer(fakeClock))
            .build();
    Renderer videoRenderer = player.getRenderer(/* index= */ 0);
    Renderer secondaryVideoRenderer = player.getSecondaryRenderer(/* index= */ 0);
    // Set a playlist that allows a new renderer to be enabled early.
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.AUDIO_FORMAT),
            new FakeMediaSource(
                new FakeTimeline(),
                ExoPlayerTestRunner.VIDEO_FORMAT,
                ExoPlayerTestRunner.AUDIO_FORMAT)));
    player.prepare();

    // Play a bit until the primary renderer has been enabled, but not yet started.
    run(player).untilState(Player.STATE_READY);
    player.play();
    run(player).untilPendingCommandsAreFullyHandled();
    @Renderer.State int videoState1 = videoRenderer.getState();
    @Renderer.State int secondaryVideoState1 = secondaryVideoRenderer.getState();
    player.pause();
    run(player).untilPendingCommandsAreFullyHandled();
    @Renderer.State int videoState2 = videoRenderer.getState();
    player.play();
    run(player).untilPendingCommandsAreFullyHandled();
    @Renderer.State int videoState3 = videoRenderer.getState();
    player.release();

    assertThat(videoState1).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(secondaryVideoState1).isEqualTo(Renderer.STATE_DISABLED);
    assertThat(videoState2).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoState3).isEqualTo(Renderer.STATE_ENABLED);
  }

  private static class FakeRenderersFactorySupportingSecondaryVideoRenderer
      implements RenderersFactory {
    protected final Clock clock;

    public FakeRenderersFactorySupportingSecondaryVideoRenderer(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Renderer[] createRenderers(
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        AudioRendererEventListener audioRendererEventListener,
        TextOutput textRendererOutput,
        MetadataOutput metadataRendererOutput) {
      HandlerWrapper clockAwareHandler =
          clock.createHandler(eventHandler.getLooper(), /* callback= */ null);
      return new Renderer[] {
        new FakeVideoRenderer(clockAwareHandler, videoRendererEventListener),
        new FakeAudioRenderer(clockAwareHandler, audioRendererEventListener)
      };
    }

    @Override
    public Renderer createSecondaryRenderer(
        Renderer renderer,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        AudioRendererEventListener audioRendererEventListener,
        TextOutput textRendererOutput,
        MetadataOutput metadataRendererOutput) {
      if (renderer instanceof FakeVideoRenderer) {
        return new FakeVideoRenderer(
            clock.createHandler(eventHandler.getLooper(), /* callback= */ null),
            videoRendererEventListener);
      }
      return null;
    }
  }
}
