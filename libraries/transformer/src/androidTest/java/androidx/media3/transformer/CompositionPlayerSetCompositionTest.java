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
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.util.Pair;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.AssetInfo;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for setting {@link Composition} on {@link CompositionPlayer}. */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSetCompositionTest {
  // TODO: b/412585856: Keep tests focused or make them parameterized.
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
  public void composition_changeNumberOfItemsInAComposition_playbackCompletes() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();
    AtomicInteger numberOfTimelineUpdates = new AtomicInteger();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (firstTimelineUpdated.compareAndSet(false, true)) {
                    compositionPlayer.setComposition(createSingleSequenceComposition(video, video));
                    compositionPlayer.play();
                  }
                  numberOfTimelineUpdates.incrementAndGet();
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(video));
          compositionPlayer.prepare();
        });

    listener.waitUntilPlayerEnded();
    // Played two compositions so should update the timeline twice.
    assertThat(numberOfTimelineUpdates.get()).isEqualTo(2);
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

  @Test
  public void
      setComposition_withClippingEndPositionAndRemovingAudioStartAtEndPosition_playbackCompletes()
          throws Exception {
    long trimEndPositionMs = 600;
    EditedMediaItem clippedEditedMediaItem =
        createEditedMediaItemWithClippingConfiguration(
                MP4_ASSET,
                new ClippingConfiguration.Builder().setEndPositionMs(trimEndPositionMs).build())
            .buildUpon()
            .setRemoveAudio(true)
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.setComposition(
              createSingleSequenceComposition(clippedEditedMediaItem),
              /* startPositionMs= */ trimEndPositionMs);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    playerTestListener.waitUntilPlayerEnded();
  }

  @Test
  public void setComposition_withSameComposition_playbackCompletes() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    Composition composition = createSingleSequenceComposition(ImmutableList.of(editedMediaItem));
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (firstTimelineUpdated.compareAndSet(false, true)) {
                    compositionPlayer.setComposition(composition);
                    compositionPlayer.play();
                  }
                }
              });
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
        });
    playerTestListener.waitUntilPlayerEnded();
  }

  @Test
  public void setComposition_withSameCompositionDifferentStartPosition_playbackCompletes()
      throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    CopyOnWriteArraySet<Object> audioProcessorFlushOffsets = new CopyOnWriteArraySet<>();
    PassthroughAudioProcessor passthroughAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            super.onFlush(streamMetadata);
            // Could be flushed multiple times at the same offset, for example when seeking or reset
            audioProcessorFlushOffsets.add(streamMetadata.positionOffsetUs);
          }
        };
    Composition composition =
        createSingleSequenceComposition(ImmutableList.of(editedMediaItem))
            .buildUpon()
            .setEffects(
                new Effects(ImmutableList.of(passthroughAudioProcessor), ImmutableList.of()))
            .build();
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (firstTimelineUpdated.compareAndSet(false, true)) {
                    compositionPlayer.setComposition(composition, /* startPositionMs= */ 500);
                    compositionPlayer.play();
                  }
                }
              });
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
        });
    playerTestListener.waitUntilPlayerEnded();
    assertThat(audioProcessorFlushOffsets).containsExactly(0L, 500_000L).inOrder();
  }

  @Test
  public void setComposition_twiceWithClippingConfigurationChange_playbackCompletes()
      throws Exception {
    EditedMediaItem fullMediaItem =
        createEditedMediaItemWithClippingConfiguration(MP4_ASSET, ClippingConfiguration.UNSET);
    EditedMediaItem clippedMediaItem =
        createEditedMediaItemWithClippingConfiguration(
            MP4_ASSET, new ClippingConfiguration.Builder().setStartPositionMs(1_000).build());
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();
    AtomicBoolean secondTimelineUpdated = new AtomicBoolean();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (!firstTimelineUpdated.get()) {
                    compositionPlayer.setComposition(
                        createSingleSequenceComposition(clippedMediaItem));
                    firstTimelineUpdated.set(true);
                  }
                  if (firstTimelineUpdated.get() && !secondTimelineUpdated.get()) {
                    compositionPlayer.setComposition(
                        createSingleSequenceComposition(clippedMediaItem));
                    secondTimelineUpdated.set(true);
                  }
                  if (firstTimelineUpdated.get() && secondTimelineUpdated.get()) {
                    compositionPlayer.play();
                  }
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(fullMediaItem));
          compositionPlayer.prepare();
        });

    playerTestListener.waitUntilPlayerEnded();
  }

  @Test
  public void setComposition_sameMediaItemAndChangedClipping_playbackCompletes() throws Exception {
    EditedMediaItem fullMediaItem =
        createEditedMediaItemWithClippingConfiguration(MP4_ASSET, ClippingConfiguration.UNSET);
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (!firstTimelineUpdated.get()) {
                    compositionPlayer.setComposition(
                        createSingleSequenceComposition(fullMediaItem));
                    firstTimelineUpdated.set(true);
                    compositionPlayer.play();
                  }
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(fullMediaItem));
          compositionPlayer.prepare();
        });

    playerTestListener.waitUntilPlayerEnded();
  }

  @Test
  public void setComposition_twiceAndSettingVideoFrameMetadataListenerAfter_playbackCompletes()
      throws Exception {
    EditedMediaItem fullMediaItem =
        createEditedMediaItemWithClippingConfiguration(MP4_ASSET, ClippingConfiguration.UNSET);
    AtomicBoolean firstTimelineUpdated = new AtomicBoolean();
    AtomicBoolean videoFrameMetadataListenerCalled = new AtomicBoolean();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onTimelineChanged(Timeline timeline, int reason) {
                  if (firstTimelineUpdated.compareAndSet(false, true)) {
                    compositionPlayer.setComposition(
                        createSingleSequenceComposition(fullMediaItem));
                    compositionPlayer.play();
                  }
                }
              });
          compositionPlayer.setComposition(createSingleSequenceComposition(fullMediaItem));
          compositionPlayer.setVideoFrameMetadataListener(
              (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
                videoFrameMetadataListenerCalled.set(true);
              });
          compositionPlayer.prepare();
        });

    playerTestListener.waitUntilPlayerEnded();
    assertThat(videoFrameMetadataListenerCalled.get()).isTrue();
  }

  @Test
  public void setComposition_withStartPosition_playbackStartsFromSetPosition() throws Exception {
    assertThat(
            getFirstVideoFrameTimestampUsWithStartPosition(
                /* startPositionUs= */ 500_000L, /* numberOfItemsInSequence= */ 1))
        .isEqualTo(500_500L);
  }

  @Test
  public void setComposition_withZeroStartPosition_playbackStartsFromZero() throws Exception {
    assertThat(
            getFirstVideoFrameTimestampUsWithStartPosition(
                /* startPositionUs= */ 0, /* numberOfItemsInSequence= */ 1))
        .isEqualTo(0);
  }

  @Test
  public void setComposition_withStartPositionPastVideoDuration_playbackStopsAtLastFrame()
      throws Exception {
    assertThat(
            getFirstVideoFrameTimestampUsWithStartPosition(
                /* startPositionUs= */ 100_000_000L, /* numberOfItemsInSequence= */ 1))
        .isEqualTo(967633L);
  }

  @Test
  public void
      setComposition_withStartPositionPastVideoDurationInMultiItemSequence_playbackStopsAtLastFrame()
          throws Exception {
    assertThat(
            getFirstVideoFrameTimestampUsWithStartPosition(
                /* startPositionUs= */ 100_000_000L, /* numberOfItemsInSequence= */ 5))
        .isEqualTo(5_063_633L);
  }

  @Test
  public void setComposition_withStartPositionInMultiItemSequence_playbackStartsFromSetPosition()
      throws Exception {
    assertThat(
            getFirstVideoFrameTimestampUsWithStartPosition(
                /* startPositionUs= */ 1_500_000L, /* numberOfItemsInSequence= */ 2))
        .isEqualTo(1_524_500);
  }

  @Test
  public void
      setComposition_withStartPositionSingleItemAudioSequence_reportsCorrectAudioProcessorPositionOffset()
          throws Exception {
    AtomicLong lastItemPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    AtomicLong lastCompositionPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor itemAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastItemPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    PassthroughAudioProcessor compositionAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastCompositionPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(itemAudioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();
    final Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(compositionAudioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer = new CompositionPlayer.Builder(context).build();
              compositionPlayer.addListener(playerTestListener);
              compositionPlayer.setComposition(composition, Util.usToMs(500_000L));
              compositionPlayer.prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastItemPositionOffsetUs.get()).isEqualTo(500_000);
    assertThat(lastCompositionPositionOffsetUs.get()).isEqualTo(500_000);
  }

  @Test
  public void
      setComposition_withStartPositionTwoItemsAudioSequence_reportsCorrectAudioProcessorPositionOffset()
          throws Exception {
    AtomicLong lastItemPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    AtomicLong lastCompositionPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor itemAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastItemPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    PassthroughAudioProcessor compositionAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastCompositionPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    EditedMediaItem item1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem item2 =
        item1
            .buildUpon()
            .setEffects(new Effects(ImmutableList.of(itemAudioProcessor), ImmutableList.of()))
            .build();
    final Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(item1, item2).build())
            .setEffects(
                new Effects(ImmutableList.of(compositionAudioProcessor), ImmutableList.of()))
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer = new CompositionPlayer.Builder(context).build();
              compositionPlayer.addListener(playerTestListener);
              compositionPlayer.setComposition(composition, Util.usToMs(1_500_000L));
              compositionPlayer.prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastItemPositionOffsetUs.get()).isEqualTo(500_000);
    assertThat(lastCompositionPositionOffsetUs.get()).isEqualTo(1_500_000);
  }

  @Test
  public void setComposition_withNewCompositionAudioProcessor_recreatesAudioPipeline()
      throws Exception {
    ConditionVariable secondCompositionSentDataToAudioPipeline = new ConditionVariable();
    PassthroughAudioProcessor secondCompositionAudioProcessor =
        new PassthroughAudioProcessor() {
          @Override
          public void queueInput(ByteBuffer inputBuffer) {
            super.queueInput(inputBuffer);
            secondCompositionSentDataToAudioPipeline.open();
          }
        };
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    Composition firstComposition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(Collections.nCopies(5, editedMediaItem))
                    .build())
            .build();
    Composition secondComposition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(Collections.nCopies(5, editedMediaItem))
                    .build())
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(secondCompositionAudioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer = new CompositionPlayer.Builder(context).build();
              compositionPlayer.addListener(playerTestListener);
              compositionPlayer.setComposition(firstComposition);
              compositionPlayer.prepare();
            });
    playerTestListener.waitUntilPlayerReady();
    assertThat(secondCompositionSentDataToAudioPipeline.isOpen()).isFalse();

    playerTestListener.resetStatus();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer.setComposition(secondComposition);
              compositionPlayer.play();
            });
    playerTestListener.waitUntilPlayerEnded();

    assertThat(secondCompositionSentDataToAudioPipeline.block(TEST_TIMEOUT_MS)).isTrue();
  }

  private long getFirstVideoFrameTimestampUsWithStartPosition(
      long startPositionUs, int numberOfItemsInSequence) throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    AtomicLong firstFrameTimestampUs = new AtomicLong(C.TIME_UNSET);

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.setVideoFrameMetadataListener(
              (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
                if (firstFrameTimestampUs.compareAndSet(C.TIME_UNSET, presentationTimeUs)) {
                  instrumentation.runOnMainSync(compositionPlayer::play);
                }
              });
          compositionPlayer.setComposition(
              createSingleSequenceComposition(
                  Collections.nCopies(numberOfItemsInSequence, editedMediaItem)),
              Util.usToMs(startPositionUs));
          compositionPlayer.prepare();
        });

    playerTestListener.waitUntilPlayerEnded();
    return firstFrameTimestampUs.get();
  }

  private static EditedMediaItem createEditedMediaItemWithSpeed(AssetInfo assetInfo, float speed) {
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
      List<EditedMediaItem> editedMediaItems) {
    return new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItems).build())
        .build();
  }

  private static EditedMediaItem createEditedMediaItemWithClippingConfiguration(
      AssetInfo assetInfo, ClippingConfiguration clippingConfiguration) {
    return new EditedMediaItem.Builder(
            new MediaItem.Builder()
                .setUri(assetInfo.uri)
                .setClippingConfiguration(clippingConfiguration)
                .build())
        .setDurationUs(assetInfo.videoDurationUs)
        .build();
  }

  private static Composition createSingleSequenceComposition(
      EditedMediaItem editedMediaItem, EditedMediaItem... moreEditedMediaItems) {
    return createSingleSequenceComposition(
        new ImmutableList.Builder<EditedMediaItem>()
            .add(editedMediaItem)
            .add(moreEditedMediaItems)
            .build());
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

  private static class PassthroughAudioProcessor extends BaseAudioProcessor {
    @Override
    public void queueInput(ByteBuffer inputBuffer) {
      if (!inputBuffer.hasRemaining()) {
        return;
      }
      ByteBuffer buffer = this.replaceOutputBuffer(inputBuffer.remaining());
      buffer.put(inputBuffer).flip();
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
      return inputAudioFormat;
    }
  }
}
