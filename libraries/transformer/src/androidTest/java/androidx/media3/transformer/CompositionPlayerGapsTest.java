/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link CompositionPlayer} when the {@link EditedMediaItemSequence} has
 * gaps.
 */
@RunWith(AndroidJUnit4.class)
public final class CompositionPlayerGapsTest {
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;
  private static final long AUDIO_VIDEO_MEDIA_ITEM_DURATION_US = MP4_SIMPLE_ASSET.videoDurationUs;
  private static final long AUDIO_ONLY_MEDIA_ITEM_DURATION_US = 1_000_000;
  private static final long GAP_DURATION_US = 1_000_000;
  private static final long EXPECTED_GAP_WIDTH = 16;
  private static final long EXPECTED_GAP_HEIGHT = 16;
  private static final int SKIP_FRAMES_COUNT = 2;
  private static final EditedMediaItem AUDIO_VIDEO_MEDIA_ITEM =
      new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
          .setDurationUs(AUDIO_VIDEO_MEDIA_ITEM_DURATION_US)
          .build();
  private static final EditedMediaItem AUDIO_ONLY_MEDIA_ITEM =
      new EditedMediaItem.Builder(new MediaItem.Builder().setUri(WAV_ASSET.uri).build())
          .setDurationUs(AUDIO_ONLY_MEDIA_ITEM_DURATION_US)
          .build();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = ApplicationProvider.getApplicationContext();

  private @MonotonicNonNull CompositionPlayer compositionPlayer;
  private @MonotonicNonNull SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
    rule.getScenario().close();
  }

  @Test
  public void
      playback_withTwoMediaItemsAndGapAtStart_inAudioVideoSequence_rendersOneByOneBlackFramesForGap()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long gapStartCompositionUs = 0;
    long gapEndCompositionUs = GAP_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addGap(GAP_DURATION_US)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInGap = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInGapCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= gapStartCompositionUs
              && presentationTimeUs < gapEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInGapCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInGap.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInGap.get()).isFalse();
    assertThat(oneByOneFramesInGapCount.get()).isTrue();
  }

  @Test
  public void
      playback_withTwoMediaItemsAndGapInMiddle_inAudioVideoSequence_rendersOneByOneBlackFramesForGap()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long gapStartCompositionUs = AUDIO_VIDEO_MEDIA_ITEM_DURATION_US;
    long gapEndCompositionUs = gapStartCompositionUs + GAP_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addGap(GAP_DURATION_US)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInGap = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInGapCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= gapStartCompositionUs
              && presentationTimeUs < gapEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInGapCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInGap.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInGap.get()).isFalse();
    assertThat(oneByOneFramesInGapCount.get()).isTrue();
  }

  @Test
  public void
      playback_withTwoMediaItemsAndGapAtTheEnd_inAudioVideoSequence_rendersOneByOneBlackFramesForGap()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long gapStartCompositionUs = 2 * AUDIO_VIDEO_MEDIA_ITEM_DURATION_US;
    long gapEndCompositionUs = gapStartCompositionUs + GAP_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addGap(GAP_DURATION_US)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInGap = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInGapCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= gapStartCompositionUs
              && presentationTimeUs < gapEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInGapCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInGap.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInGap.get()).isFalse();
    assertThat(oneByOneFramesInGapCount.get()).isTrue();
  }

  @Test
  public void
      playback_withThreeMediaItemsAndFirstMediaItemHavingNoVideo_inAudioVideoSequence_rendersOneByOneBlackFramesForFirstMediaItem()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long audioOnlyItemStartCompositionUs = 0;
    long audioOnlyItemEndCompositionUs = AUDIO_ONLY_MEDIA_ITEM_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_ONLY_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInAudioOnlyItem = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInAudioOnlyItemCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= audioOnlyItemStartCompositionUs
              && presentationTimeUs < audioOnlyItemEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInAudioOnlyItemCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInAudioOnlyItem.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInAudioOnlyItem.get()).isFalse();
    assertThat(oneByOneFramesInAudioOnlyItemCount.get()).isTrue();
  }

  @Test
  public void
      playback_withThreeMediaItemsAndSecondMediaItemHavingNoVideo_inAudioVideoSequence_rendersOneByOneBlackFramesForSecondMediaItem()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long audioOnlyItemStartCompositionUs = AUDIO_VIDEO_MEDIA_ITEM_DURATION_US;
    long audioOnlyItemEndCompositionUs =
        audioOnlyItemStartCompositionUs + AUDIO_ONLY_MEDIA_ITEM_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_ONLY_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInAudioOnlyItem = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInAudioOnlyItemCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= audioOnlyItemStartCompositionUs
              && presentationTimeUs < audioOnlyItemEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInAudioOnlyItemCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInAudioOnlyItem.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInAudioOnlyItem.get()).isFalse();
    assertThat(oneByOneFramesInAudioOnlyItemCount.get()).isTrue();
  }

  @Test
  public void
      playback_withThreeMediaItemsAndLastMediaItemHavingNoVideo_inAudioVideoSequence_rendersOneByOneBlackFramesForLastMediaItem()
          throws Exception {
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    long audioOnlyItemStartCompositionUs = 2 * AUDIO_VIDEO_MEDIA_ITEM_DURATION_US;
    long audioOnlyItemEndCompositionUs =
        audioOnlyItemStartCompositionUs + AUDIO_ONLY_MEDIA_ITEM_DURATION_US;
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_ONLY_MEDIA_ITEM)
                    .build())
            .build();
    final AtomicBoolean incorrectFrameDimensionsInAudioOnlyItem = new AtomicBoolean(false);
    final AtomicBoolean oneByOneFramesInAudioOnlyItemCount = new AtomicBoolean(false);
    final AtomicInteger renderedGapFrames = new AtomicInteger(0);
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= audioOnlyItemStartCompositionUs
              && presentationTimeUs < audioOnlyItemEndCompositionUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInAudioOnlyItemCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInAudioOnlyItem.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(context).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(videoFrameMetadataListener);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(composition);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInAudioOnlyItem.get()).isFalse();
    assertThat(oneByOneFramesInAudioOnlyItemCount.get()).isTrue();
  }
}
