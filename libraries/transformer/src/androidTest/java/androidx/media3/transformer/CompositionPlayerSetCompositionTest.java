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

package androidx.media3.transformer;

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.util.Pair;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Player.State;
import androidx.media3.common.Timeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.effect.GlEffect;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for setting {@link Composition} on {@link CompositionPlayer}. */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSetCompositionTest {
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  private @MonotonicNonNull CompositionPlayer compositionPlayer;

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = getInstrumentation();
  private final Context context = instrumentation.getContext().getApplicationContext();

  private PlayerTestListener playerTestListener;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    rule.getScenario().close();
    if (compositionPlayer != null) {
      instrumentation.runOnMainSync(compositionPlayer::release);
    }
  }

  @Test
  public void composition_changeComposition() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    InputTimestampRecordingShaderProgram timestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .setEffects(
                new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(
                        (GlEffect) (context, useHdr) -> timestampRecordingShaderProgram)))
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence.Builder(video).build()).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilFirstFrameRendered();
    instrumentation.runOnMainSync(
        () ->
            compositionPlayer.setComposition(
                new Composition.Builder(new EditedMediaItemSequence.Builder(video, video).build())
                    .build()));

    listener.waitUntilPlayerEnded();
    // Played two compositions so should render two frames of timestamp zero.
    assertThat(
            Iterables.filter(
                timestampRecordingShaderProgram.getInputTimestampsUs(),
                timestamp -> timestamp == 0))
        .hasSize(2);
  }

  @Test
  public void setComposition_withChangedRemoveAudio_playbackCompletes() throws Exception {
    EditedMediaItem mediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    EditedMediaItem mediaItemRemoveAudio = mediaItem.buildUpon().setRemoveAudio(true).build();
    AtomicBoolean changedComposition = new AtomicBoolean();
    ConditionVariable playerEnded = new ConditionVariable();
    CopyOnWriteArrayList<Integer> playerStates = new CopyOnWriteArrayList<>();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(@State int playbackState) {
                  playerStates.add(playbackState);
                  if (playbackState == Player.STATE_READY) {
                    if (!changedComposition.get()) {
                      compositionPlayer.setComposition(
                          createSingleSequenceComposition(
                              mediaItemRemoveAudio, mediaItemRemoveAudio));
                      compositionPlayer.play();
                      changedComposition.set(true);
                    }
                  } else if (playbackState == Player.STATE_ENDED) {
                    playerEnded.open();
                  }
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(mediaItem, mediaItem));
          compositionPlayer.prepare();
        });

    // Wait until the final state is added to playerStates.
    playerEnded.block(TEST_TIMEOUT_MS);
    // waitUntilPlayerEnded should return immediate and will throw any player error.
    playerTestListener.waitUntilPlayerEnded();
    // Asserts that changing removeAudio does not cause the player to get back to buffering state,
    // because the player should not be re-prepared.
    assertThat(playerStates)
        .containsExactly(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)
        .inOrder();
  }

  @Test
  public void setComposition_withChangedSpeed_playbackCompletes() throws Exception {
    EditedMediaItem fastMediaItem = createEditedMediaItemWithSpeed(MP4_ASSET, 3.f);
    EditedMediaItem slowMediaItem = createEditedMediaItemWithSpeed(MP4_ASSET, 1 / 3.f);

    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();
    CopyOnWriteArrayList<Long> playerDurations = new CopyOnWriteArrayList<>();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  playerDurations.add(
                      timeline.getWindow(/* windowIndex= */ 0, new Timeline.Window()).durationUs);
                  if (!firstTimelineUpdated.get()) {
                    compositionPlayer.setComposition(
                        createSingleSequenceComposition(slowMediaItem));
                    compositionPlayer.play();
                    firstTimelineUpdated.set(true);
                  }
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(fastMediaItem));
          compositionPlayer.prepare();
        });

    playerTestListener.waitUntilPlayerEnded();
    // 1024ms scaled by 3 and 1/3.
    assertThat(playerDurations).containsExactly(341333L, 3071999L).inOrder();
  }

  private static EditedMediaItem createEditedMediaItemWithSpeed(
      AndroidTestUtil.AssetInfo assetInfo, float speed) {
    Pair<AudioProcessor, Effect> speedChangingEffect =
        Effects.createExperimentalSpeedChangingEffect(new SimpleSpeedProvider(speed));
    return new EditedMediaItem.Builder(MediaItem.fromUri(assetInfo.uri))
        .setDurationUs(assetInfo.videoDurationUs)
        .setEffects(
            new Effects(
                /* audioProcessors= */ ImmutableList.of(speedChangingEffect.first),
                /* videoEffects= */ ImmutableList.of(speedChangingEffect.second)))
        .build();
  }

  private static Composition createSingleSequenceComposition(
      EditedMediaItem editedMediaItem, EditedMediaItem... moreEditedMediaItems) {
    return new Composition.Builder(
            new EditedMediaItemSequence.Builder(
                    new ImmutableList.Builder<EditedMediaItem>()
                        .add(editedMediaItem)
                        .add(moreEditedMediaItems)
                        .build())
                .build())
        .build();
  }

  private static final class SimpleSpeedProvider implements SpeedProvider {

    private final float speed;

    public SimpleSpeedProvider(float speed) {
      this.speed = speed;
    }

    @Override
    public float getSpeed(long timeUs) {
      return speed;
    }

    @Override
    public long getNextSpeedChangeTimeUs(long timeUs) {
      // Adjust speed for all timestamps.
      return C.TIME_UNSET;
    }
  }
}
