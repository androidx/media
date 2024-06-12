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

package androidx.media3.transformer.mh.performance;

import static androidx.media3.common.util.Util.usToMs;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.GlEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.InputTimestampRecordingShaderProgram;
import androidx.media3.transformer.PlayerTestListener;
import androidx.media3.transformer.SurfaceTestActivity;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link CompositionPlayer} {@linkplain CompositionPlayer#seekTo
 * seeking}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSeekTest {

  private static final long TEST_TIMEOUT_MS = 10_000;
  private static final String MP4_ASSET = "asset:///media/mp4/sample.mp4";
  private static final long MP4_ASSET_DURATION_US = 1_024_000L;
  private static final ImmutableList<Long> MP4_ASSET_TIMESTAMPS_US =
      ImmutableList.of(
          0L, 33366L, 66733L, 100100L, 133466L, 166833L, 200200L, 233566L, 266933L, 300300L,
          333666L, 367033L, 400400L, 433766L, 467133L, 500500L, 533866L, 567233L, 600600L, 633966L,
          667333L, 700700L, 734066L, 767433L, 800800L, 834166L, 867533L, 900900L, 934266L, 967633L);

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context applicationContext = instrumentation.getContext().getApplicationContext();

  private CompositionPlayer compositionPlayer;
  private SurfaceView surfaceView;

  @Before
  public void setupSurfaces() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void closeActivity() {
    rule.getScenario().close();
  }

  @After
  public void releasePlayer() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
  }

  @Test
  public void seekToZero_singleSequenceOfTwoVideos() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    EditedMediaItem video =
        createEditedMediaItem(
            /* videoEffects= */ ImmutableList.of(
                (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence(video, video)).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilPlayerEnded();
    listener.resetStatus();
    instrumentation.runOnMainSync(() -> compositionPlayer.seekTo(0));
    listener.waitUntilPlayerEnded();

    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(MP4_ASSET_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                Iterables.transform(
                    MP4_ASSET_TIMESTAMPS_US, timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToZero_duringPlayingFirstVideoInSingleSequenceOfTwoVideos() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int numberOfFramesBeforeSeeking = 15;

    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first 15 frames of the first video
            .addAll(
                Iterables.limit(
                    MP4_ASSET_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Seek to zero, plays the first video again
            .addAll(MP4_ASSET_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                Iterables.transform(
                    MP4_ASSET_TIMESTAMPS_US, timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playCompositionOfTwoVideosAndGetTimestamps(
            listener, numberOfFramesBeforeSeeking, /* seekTimeMs= */ 0);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToSecondMedia_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int numberOfFramesBeforeSeeking = 15;
    // 100ms into the second video, should skip the first three frames.
    long seekTimeMs = 1124;

    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first 15 frames of the first video
            .addAll(
                Iterables.limit(
                    MP4_ASSET_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first three frames of the second video
            .addAll(
                Iterables.transform(
                    Iterables.skip(MP4_ASSET_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playCompositionOfTwoVideosAndGetTimestamps(
            listener, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstMedia_duringPlayingSecondVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int numberOfFramesBeforeSeeking = 45;
    // 100ms into the first video, should skip the first three frames.
    long seekTimeMs = 100;

    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play first video
            .addAll(MP4_ASSET_TIMESTAMPS_US)
            // Play the first 15 frames of the seconds video
            .addAll(
                Iterables.transform(
                    Iterables.limit(MP4_ASSET_TIMESTAMPS_US, /* limitSize= */ 15),
                    timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            // Seek to the first, skipping the first three frames.
            .addAll(Iterables.skip(MP4_ASSET_TIMESTAMPS_US, /* numberToSkip= */ 3))
            // Plays the second video
            .addAll(
                Iterables.transform(
                    MP4_ASSET_TIMESTAMPS_US, timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playCompositionOfTwoVideosAndGetTimestamps(
            listener, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfFirstMedia_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int numberOfFramesBeforeSeeking = 15;
    // Seek to the duration of the first video.
    long seekTimeMs = usToMs(MP4_ASSET_DURATION_US);

    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 15 frames of the first video
            .addAll(Iterables.limit(MP4_ASSET_TIMESTAMPS_US, /* limitSize= */ 15))
            // Plays the second video
            .addAll(
                Iterables.transform(
                    MP4_ASSET_TIMESTAMPS_US, timestampUs -> (MP4_ASSET_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playCompositionOfTwoVideosAndGetTimestamps(
            listener, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfSecondVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    int numberOfFramesBeforeSeeking = 15;
    // Seek to after the composition ends.
    long seekTimeMs = 10_000_000L;

    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 15 frames of the first video
            .addAll(Iterables.limit(MP4_ASSET_TIMESTAMPS_US, /* limitSize= */ 15))
            // Seeking to/beyond the end plays the last frame.
            .add(MP4_ASSET_DURATION_US + getLast(MP4_ASSET_TIMESTAMPS_US))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playCompositionOfTwoVideosAndGetTimestamps(
            listener, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  /**
   * Plays the {@link #MP4_ASSET} for {@code videoLoopCount} times, seeks after {@code
   * numberOfFramesBeforeSeeking} frames to {@code seekTimeMs}, and returns the timestamps of the
   * processed frames, in microsecond.
   */
  private ImmutableList<Long> playCompositionOfTwoVideosAndGetTimestamps(
      PlayerTestListener listener, int numberOfFramesBeforeSeeking, long seekTimeMs)
      throws Exception {
    ResettableCountDownLatch framesReceivedLatch =
        new ResettableCountDownLatch(numberOfFramesBeforeSeeking);
    AtomicBoolean shaderProgramShouldBlockInput = new AtomicBoolean();

    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram() {

          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
            framesReceivedLatch.countDown();
            if (framesReceivedLatch.getCount() == 0) {
              shaderProgramShouldBlockInput.set(true);
            }
          }

          @Override
          public void releaseOutputFrame(GlTextureInfo outputTexture) {
            // The input listener capacity is reported in the super method, block input by skip
            // reporting input capacity.
            if (shaderProgramShouldBlockInput.get()) {
              return;
            }
            super.releaseOutputFrame(outputTexture);
          }

          @Override
          public void flush() {
            super.flush();
            shaderProgramShouldBlockInput.set(false);
            framesReceivedLatch.reset(Integer.MAX_VALUE);
          }
        };

    ImmutableList<EditedMediaItem> editedMediaItems =
        ImmutableList.of(
            createEditedMediaItem(
                ImmutableList.of(
                    (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)),
            createEditedMediaItem(
                ImmutableList.of(
                    (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence(editedMediaItems)).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    // Wait until the number of frames are received, block further input on the shader program.
    framesReceivedLatch.await();
    instrumentation.runOnMainSync(() -> compositionPlayer.seekTo(seekTimeMs));
    listener.waitUntilPlayerEnded();
    return inputTimestampRecordingShaderProgram.getInputTimestampsUs();
  }

  private static EditedMediaItem createEditedMediaItem(List<Effect> videoEffects) {
    return new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET))
        .setDurationUs(1_024_000)
        .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
        .build();
  }

  private static final class ResettableCountDownLatch {
    private CountDownLatch latch;

    public ResettableCountDownLatch(int count) {
      latch = new CountDownLatch(count);
    }

    public void await() throws InterruptedException {
      latch.await();
    }

    public void countDown() {
      latch.countDown();
    }

    public long getCount() {
      return latch.getCount();
    }

    public void reset(int count) {
      latch = new CountDownLatch(count);
    }
  }
}
