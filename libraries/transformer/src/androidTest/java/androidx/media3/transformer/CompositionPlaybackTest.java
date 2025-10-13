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

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_80KHZ_MONO_20_REPEATING_1_SAMPLES_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.NullableType;
import androidx.media3.effect.GlEffect;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Playback tests for {@link CompositionPlayer} */
@RunWith(AndroidJUnit4.class)
public class CompositionPlaybackTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;
  private static final MediaItem VIDEO_MEDIA_ITEM = MediaItem.fromUri(MP4_ASSET.uri);
  private static final long VIDEO_DURATION_US = MP4_ASSET.videoDurationUs;
  private static final ImmutableList<Long> VIDEO_TIMESTAMPS_US = MP4_ASSET.videoTimestampsUs;

  private final Context context = getInstrumentation().getContext().getApplicationContext();
  private final PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);

  private @MonotonicNonNull CompositionPlayer player;

  @After
  public void tearDown() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (player != null) {
                player.release();
              }
            });
  }

  @Test
  public void playback_sequenceOfThreeVideosRemovingMiddleVideo_noFrameIsRendered()
      throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();

    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM)
            .setDurationUs(VIDEO_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)))
            .build();
    EditedMediaItem videoEditedMediaItemRemoveVideo =
        videoEditedMediaItem.buildUpon().setRemoveVideo(true).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        videoEditedMediaItem,
                        videoEditedMediaItemRemoveVideo,
                        videoEditedMediaItem)))
            .build();

    runCompositionPlayer(composition);

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs()).isEmpty();
  }

  @Test
  public void playback_compositionWithSecondSequenceRemoveVideo_rendersVideoFromFirstSequence()
      throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();

    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM)
            .setDurationUs(VIDEO_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)))
            .build();
    EditedMediaItem videoEditedMediaItemRemoveVideo =
        videoEditedMediaItem.buildUpon().setRemoveVideo(true).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(videoEditedMediaItem)),
                EditedMediaItemSequence.withAudioFrom(
                    ImmutableList.of(videoEditedMediaItemRemoveVideo)))
            .build();

    runCompositionPlayer(composition);

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(VIDEO_TIMESTAMPS_US);
  }

  @Test
  public void playback_withRepeatModeSet_succeeds() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM).setDurationUs(VIDEO_DURATION_US).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(editedMediaItem)))
            .build();
    CountDownLatch repetitionEndedLatch = new CountDownLatch(2);
    AtomicReference<@NullableType PlaybackException> playbackException = new AtomicReference<>();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player = new CompositionPlayer.Builder(context).build();
              player.addListener(playerTestListener);
              player.addListener(
                  new Player.Listener() {
                    @Override
                    public void onPositionDiscontinuity(
                        Player.PositionInfo oldPosition,
                        Player.PositionInfo newPosition,
                        int reason) {
                      if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        repetitionEndedLatch.countDown();
                      }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                      playbackException.set(error);
                      while (repetitionEndedLatch.getCount() > 0) {
                        repetitionEndedLatch.countDown();
                      }
                    }
                  });
              player.setComposition(composition);
              player.setRepeatMode(REPEAT_MODE_ALL);
              player.prepare();
              player.play();
            });
    boolean latchTimedOut = !repetitionEndedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(playbackException.get()).isNull();
    assertThat(latchTimedOut).isFalse();
    getInstrumentation().runOnMainSync(() -> player.setRepeatMode(REPEAT_MODE_OFF));
    playerTestListener.waitUntilPlayerEnded();
  }

  @Test
  public void playback_sequenceOfVideosWithPrewarmingDisabled_effectsReceiveCorrectTimestamps()
      throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Effect videoEffect = (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram;
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM)
            .setDurationUs(VIDEO_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(editedMediaItem, editedMediaItem, editedMediaItem)))
            .build();
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(VIDEO_TIMESTAMPS_US)
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (2 * VIDEO_DURATION_US + timestampUs)))
            .build();

    runCompositionPlayer(composition, /* videoPrewarmingEnabled= */ false);

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void audioOnlySingleAsset_inAudioVideoSequence_doesNotOutputSilence()
      throws PlaybackException, TimeoutException {
    EditedMediaItem audioClip =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(WAV_80KHZ_MONO_20_REPEATING_1_SAMPLES_ASSET.uri))
            .setDurationUs(250L)
            .build();
    EditedMediaItemSequence sequence =
        EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(audioClip));
    AtomicInteger samplesProcessedCount = new AtomicInteger();
    TeeAudioProcessor teeAudioProcessor =
        new TeeAudioProcessor(
            new TeeAudioProcessor.AudioBufferSink() {
              @Override
              public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {}

              @Override
              public void handleBuffer(ByteBuffer buffer) {
                ShortBuffer samplesBuffer = buffer.asShortBuffer();
                while (samplesBuffer.hasRemaining()) {
                  // The input asset is made of 20 samples of value 1.
                  assertThat(samplesBuffer.get()).isEqualTo(1);
                  samplesProcessedCount.getAndIncrement();
                }
              }
            });
    Composition composition =
        new Composition.Builder(sequence)
            .setEffects(new Effects(ImmutableList.of(teeAudioProcessor), ImmutableList.of()))
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player = new CompositionPlayer.Builder(context).build();
              player.addListener(playerTestListener);
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    playerTestListener.waitUntilPlayerEnded();
    assertThat(samplesProcessedCount.get()).isEqualTo(20);
  }

  @Test
  public void playback_singleAssetAudioSequence_doesNotUnderrun()
      throws PlaybackException, TimeoutException {
    EditedMediaItem clip =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(clip)))
            .build();
    AtomicInteger underrunCount = new AtomicInteger();
    AudioSink.Listener sinkListener =
        new AudioSink.Listener() {
          @Override
          public void onPositionDiscontinuity() {}

          @Override
          public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
            underrunCount.addAndGet(1);
          }

          @Override
          public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {}
        };
    AudioSink sink = new DefaultAudioSink.Builder(context).build();
    sink.setListener(sinkListener);

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player = new CompositionPlayer.Builder(context).setAudioSink(sink).build();
              player.addListener(playerTestListener);
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    playerTestListener.waitUntilPlayerEnded();
    assertThat(underrunCount.get()).isEqualTo(0);
  }

  @Test
  public void playback_audioSequenceWithMiddleGap_doesNotCrash()
      throws PlaybackException, TimeoutException {
    EditedMediaItem clip =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addItem(clip)
            .addGap(500_000)
            .addItem(clip)
            .build();
    Composition composition = new Composition.Builder(sequence).build();

    runCompositionPlayer(composition);
  }

  @Test
  public void playback_audioSequenceWithStartGap_doesNotCrash()
      throws PlaybackException, TimeoutException {
    EditedMediaItem clip =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addGap(500_000)
            .addItem(clip)
            .addItem(clip)
            .build();
    Composition composition = new Composition.Builder(sequence).build();

    runCompositionPlayer(composition);
  }

  @Test
  public void playback_audioSequenceWithMiddleGapAndVideoSequence_doesNotCrash()
      throws PlaybackException, TimeoutException {
    EditedMediaItem audioClip =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addGap(500_000)
            .addItem(audioClip)
            .build();
    EditedMediaItem videoClip =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM).setDurationUs(VIDEO_DURATION_US).build();
    EditedMediaItemSequence videoSequence =
        EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(videoClip));
    Composition composition = new Composition.Builder(videoSequence, audioSequence).build();

    runCompositionPlayer(composition);
  }

  private void runCompositionPlayer(Composition composition)
      throws PlaybackException, TimeoutException {
    runCompositionPlayer(composition, /* videoPrewarmingEnabled= */ true);
  }

  private void runCompositionPlayer(Composition composition, boolean videoPrewarmingEnabled)
      throws PlaybackException, TimeoutException {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .setVideoPrewarmingEnabled(videoPrewarmingEnabled)
                      .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                      .build();
              player.addListener(playerTestListener);
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    playerTestListener.waitUntilPlayerEnded();
  }
}
