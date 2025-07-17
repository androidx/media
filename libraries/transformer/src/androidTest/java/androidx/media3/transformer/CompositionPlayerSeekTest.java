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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.skip;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link CompositionPlayer} {@linkplain CompositionPlayer#seekTo(long)
 * seeking}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSeekTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  private static final long VIDEO_DURATION_US = MP4_ASSET.videoDurationUs;
  private static final MediaItemConfig VIDEO_MEDIA_ITEM =
      new MediaItemConfig(MediaItem.fromUri(MP4_ASSET.uri), VIDEO_DURATION_US);
  private static final ImmutableList<Long> VIDEO_TIMESTAMPS_US = MP4_ASSET.videoTimestampsUs;
  private static final long IMAGE_DURATION_US = 200_000;
  private static final MediaItemConfig IMAGE_MEDIA_ITEM =
      new MediaItemConfig(
          new MediaItem.Builder()
              .setUri(PNG_ASSET.uri)
              .setImageDurationMs(usToMs(IMAGE_DURATION_US))
              .build(),
          IMAGE_DURATION_US);
  // 200 ms at 30 fps (default frame rate)
  private static final ImmutableList<Long> IMAGE_TIMESTAMPS_US =
      ImmutableList.of(0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L);
  private static final long VIDEO_GRAPH_END_TIMEOUT_MS = 1_000;

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context applicationContext =
      getInstrumentation().getContext().getApplicationContext();
  private final AtomicReference<CompositionPlayer> player = new AtomicReference<>();

  private PlayerTestListener playerTestListener;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    CompositionPlayer p = player.getAndSet(null);
    if (p != null) {
      getInstrumentation().runOnMainSync(() -> p.release());
    }

    rule.getScenario().close();
  }

  @Test
  public void seekToZero_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), /* seekTimeMs= */ 0))
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstVideo_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Skips the first three video frames
    long seekTimeMs = 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the first video skipping the first three frames
            .addAll(skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3))
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToStartOfSecondVideo_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Seeks to the end of the first video
    long seekTimeMs = usToMs(VIDEO_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToSecondVideo_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Skips the first three image frames of the second image.
    long seekTimeMs = usToMs(VIDEO_DURATION_US) + 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the second video skipping the first three frames
            .addAll(
                transform(
                    skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToEndOfSecondVideo_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Seeks to the end of the second video
    long seekTimeMs = usToMs(2 * VIDEO_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the last frame of the second video
            .add(1991633L)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToAfterEndOfSecondVideo_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    long seekTimeMs = usToMs(3 * VIDEO_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the last frame of the second video
            .add(1991633L)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToZero_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), /* seekTimeMs= */ 0))
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstImage_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    // Skips the first three image frames.
    long seekTimeMs = 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the first image skipping the first three frames
            .addAll(skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3))
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToStartOfSecondImage_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    // Seeks to the start of the second image
    long seekTimeMs = usToMs(IMAGE_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToSecondImage_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    // Skips the first three image frames of the second image.
    long seekTimeMs = usToMs(IMAGE_DURATION_US) + 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the second image skipping the first three framees
            .addAll(
                transform(
                    skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToEndOfSecondImage_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    // Seeks to the end of the second image
    long seekTimeMs = usToMs(2 * IMAGE_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the last image frame, which is one microsecond smaller than the total duration.
            .add(399_999L)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToAfterEndOfSecondImage_afterPlayingSingleSequenceOfTwoImages() throws Exception {
    long seekTimeMs = usToMs(3 * IMAGE_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the second image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the last image frame, which is one microsecond smaller than the total duration.
            .add(399_999L)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToZero_afterPlayingSingleSequenceOfVideoAndImage() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, IMAGE_MEDIA_ITEM), /* seekTimeMs= */ 0))
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToVideo_afterPlayingSingleSequenceOfVideoAndImage() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Skips three video frames
    long seekTimeMs = 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the video skipping the first three frames
            .addAll(skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3))
            // Plays the image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToImage_afterPlayingSingleSequenceOfVideoAndImage() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Skips video frames and three image frames
    long seekTimeMs = usToMs(VIDEO_DURATION_US) + 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the image
            .addAll(
                transform(IMAGE_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Plays the image
            .addAll(
                transform(
                    skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(VIDEO_MEDIA_ITEM, IMAGE_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToZero_afterPlayingSingleSequenceOfImageAndVideo() throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM), /* seekTimeMs= */ 0))
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToImage_afterPlayingSingleSequenceOfImageAndVideo() throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assumeFalse("Skipped on emulator due to surface dropping frames", isRunningOnEmulator());
    // Skips three image frames
    long seekTimeMs = 100;
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Seek skipping 3 image frames
            .addAll(skip(IMAGE_TIMESTAMPS_US, 3))
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToVideo_afterPlayingSingleSequenceOfImageAndVideo() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    // Skips to the first video frame.
    long seekTimeMs = usToMs(IMAGE_DURATION_US);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the image
            .addAll(IMAGE_TIMESTAMPS_US)
            // Plays the video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            // Plays the video again
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    assertThat(
            playSequenceUntilEndedAndSeekAndGetTimestampsUs(
                ImmutableList.of(IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM), seekTimeMs))
        .isEqualTo(sequenceTimestampsUs);
  }

  @Test
  public void seekToZero_duringPlayingFirstVideoInSingleSequenceOfTwoVideos() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first 15 frames of the first video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Seek to zero, plays the first video again
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, numberOfFramesBeforeSeeking, /* seekTimeMs= */ 0);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToSecondVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    // 100ms into the second video, should skip the first 3 frames.
    long seekTimeMs = 1124;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first 15 frames of the first video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames of the second video
            .addAll(
                transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstVideo_duringPlayingSecondVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 45;
    // 100ms into the first video, should skip the first 3 frames.
    long seekTimeMs = 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Play the first 15 frames of the seconds video
            .addAll(
                transform(
                    Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ 15),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Seek to the first, skipping the first 3 frames.
            .addAll(Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3))
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfFirstVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    // Seek to the duration of the first video.
    long seekTimeMs = usToMs(VIDEO_DURATION_US);
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 15 frames of the first video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Plays the second video
            .addAll(
                transform(VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfSecondVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    // Seek to after the composition ends.
    long seekTimeMs = 10_000_000L;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 15 frames of the first video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Seeking to/beyond the end plays the last frame.
            .add(VIDEO_DURATION_US + getLast(VIDEO_TIMESTAMPS_US))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstImage_duringPlayingFirstImageInSequenceOfTwoImages() throws Exception {
    ImmutableList<MediaItemConfig> mediaItems = ImmutableList.of(IMAGE_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 2;
    // Should skip the first 3 frames.
    long seekTimeMs = 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 2 frames
            .addAll(
                Iterables.limit(IMAGE_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames
            .addAll(Iterables.skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToSecondImage_duringPlayingFirstImageInSequenceOfTwoImages() throws Exception {
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 2;
    // Should skip the first 3 frames of the second image.
    long seekTimeMs = Util.usToMs(IMAGE_DURATION_US) + 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 2 frames of the first image
            .addAll(
                Iterables.limit(IMAGE_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames of the second image
            .addAll(
                transform(
                    Iterables.skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToVideo_atTransitionBetweenImages_completes() throws Exception {
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 2;
    long seekTimeMs = 2 * Util.usToMs(IMAGE_DURATION_US) + 200;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 2 frames of the first image
            .addAll(
                Iterables.limit(IMAGE_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            .addAll(
                transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 6),
                    timestampUs -> (2 * IMAGE_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToImage_duringPlayingFirstImageInSequenceOfVideoAndImage() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, IMAGE_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    // Should skip the first 3 frames of the image.
    long seekTimeMs = Util.usToMs(VIDEO_DURATION_US) + 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 15 frames of the video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames of the image
            .addAll(
                transform(
                    Iterables.skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToVideo_duringPlayingFirstImageInSequenceOfImageAndVideo() throws Exception {
    assumeFalse(
        "Skipped due to failing audio decoder on API 31 emulator",
        isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 3;
    // Should skip the first 3 frames of the video.
    long seekTimeMs = Util.usToMs(IMAGE_DURATION_US) + 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play the first 3 frames of the image
            .addAll(
                Iterables.limit(IMAGE_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames of the video
            .addAll(
                transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(mediaItems, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void
      seekToSecondVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideosWithPrewarmingDisabled()
          throws Exception {
    assumeFalse("Skipped due to failing audio decoder", isRunningOnEmulator() && SDK_INT == 31);
    ImmutableList<MediaItemConfig> mediaItems =
        ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    int numberOfFramesBeforeSeeking = 15;
    // 100ms into the second video, should skip the first 3 frames.
    long seekTimeMs = 1124;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first 15 frames of the first video
            .addAll(
                Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ numberOfFramesBeforeSeeking))
            // Skipping the first 3 frames of the second video
            .addAll(
                transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems,
            numberOfFramesBeforeSeeking,
            seekTimeMs,
            /* videoPrewarmingEnabled= */ false);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToMidClip_withSingleAudioClipSequence_reportsCorrectAudioProcessorPositionOffset()
      throws PlaybackException, TimeoutException {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(ImmutableList.of(fakeProcessor), ImmutableList.of()))
            .build();
    final Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(item).build()).build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new CompositionPlayer.Builder(applicationContext).build());
              player.get().addListener(playerTestListener);
              player.get().setComposition(composition);
              player.get().prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> player.get().seekTo(/* positionMs= */ 500));
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastPositionOffsetUs.get()).isEqualTo(/* positionOffsetUs */ 500_000);
  }

  @Test
  public void seekToMidClip_withCompositionAudioProcessor_reportsCorrectPositionOffset()
      throws PlaybackException, TimeoutException {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();
    final Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(item).build())
            .setEffects(new Effects(ImmutableList.of(fakeProcessor), ImmutableList.of()))
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new CompositionPlayer.Builder(applicationContext).build());
              player.get().addListener(playerTestListener);
              player.get().setComposition(composition);
              player.get().prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> player.get().seekTo(/* positionMs= */ 300));
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastPositionOffsetUs.get()).isEqualTo(/* positionOffsetUs */ 300_000);
  }

  @Test
  public void
      seekToSecondClip_withMultipleAudioClipSequence_reportsMediaItemRelativePositionOffset()
          throws PlaybackException, TimeoutException {
    AtomicLong lastPositionOffsetUs = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor fakeProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUs.set(streamMetadata.positionOffsetUs);
          }
        };
    EditedMediaItem firstItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .build();

    EditedMediaItem secondItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(ImmutableList.of(fakeProcessor), ImmutableList.of()))
            .build();
    final Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(firstItem, secondItem).build())
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new CompositionPlayer.Builder(applicationContext).build());
              player.get().addListener(playerTestListener);
              player.get().setComposition(composition);
              player.get().prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> player.get().seekTo(/* positionMs= */ 1200));
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastPositionOffsetUs.get()).isEqualTo(/* positionOffsetUs */ 200_000);
  }

  @Test
  public void seek_withMultipleAudioSequences_reportsExpectedPositionToEachSequence()
      throws PlaybackException, TimeoutException {
    AtomicLong lastPositionOffsetUsFirstSequence = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor firstSequenceProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUsFirstSequence.set(streamMetadata.positionOffsetUs);
          }
        };

    AtomicLong lastPositionOffsetUsSecondSequence = new AtomicLong(C.TIME_UNSET);
    PassthroughAudioProcessor secondSequenceProcessor =
        new PassthroughAudioProcessor() {
          @Override
          protected void onFlush(StreamMetadata streamMetadata) {
            lastPositionOffsetUsSecondSequence.set(streamMetadata.positionOffsetUs);
          }
        };

    EditedMediaItem firstSequenceItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setEffects(new Effects(ImmutableList.of(firstSequenceProcessor), ImmutableList.of()))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem secondSequenceItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(AndroidTestUtil.WAV_ASSET.uri))
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(ImmutableList.of(secondSequenceProcessor), ImmutableList.of()))
            .build();

    final Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(firstSequenceItem).build(),
                new EditedMediaItemSequence.Builder()
                    .addGap(/* durationUs= */ 300_000)
                    .addItem(secondSequenceItem)
                    .experimentalSetForceAudioTrack(true)
                    .build())
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new CompositionPlayer.Builder(applicationContext).build());
              player.get().addListener(playerTestListener);
              player.get().setComposition(composition);
              player.get().prepare();
            });
    playerTestListener.waitUntilPlayerReady();

    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> player.get().seekTo(/* positionMs= */ 400));
    playerTestListener.waitUntilPlayerReady();

    assertThat(lastPositionOffsetUsFirstSequence.get()).isEqualTo(/* positionOffsetUs */ 400_000);
    assertThat(lastPositionOffsetUsSecondSequence.get()).isEqualTo(/* positionOffsetUs */ 100_000);
  }

  /**
   * Plays the first {@code numberOfFramesBeforeSeeking} frames of the provided sequence, seeks to
   * {@code seekTimeMs}, resumes playback until it ends, and returns the timestamps of the processed
   * frames, in microsecond.
   */
  private ImmutableList<Long> playSequenceAndGetTimestampsUs(
      List<MediaItemConfig> mediaItems,
      int numberOfFramesBeforeSeeking,
      long seekTimeMs,
      boolean videoPrewarmingEnabled)
      throws Exception {
    CountDownLatch waitUntilNumberOfFramesOrError = new CountDownLatch(1);
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        createInputTimestampRecordingShaderProgram(
            numberOfFramesBeforeSeeking, waitUntilNumberOfFramesOrError);
    CountDownLatch videoGraphEnded = new CountDownLatch(1);
    AtomicReference<@NullableType PlaybackException> playbackException = new AtomicReference<>();

    List<EditedMediaItem> editedMediaItems = new ArrayList<>();
    for (int i = 0; i < mediaItems.size(); i++) {
      editedMediaItems.add(
          createEditedMediaItem(
              mediaItems.get(i),
              /* videoEffect= */ (GlEffect)
                  (context, useHdr) -> inputTimestampRecordingShaderProgram));
    }
    AtomicReference<CompositionPlayer> compositionPlayer = new AtomicReference<>();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer.set(
                  new CompositionPlayer.Builder(applicationContext)
                      .setVideoGraphFactory(new ListenerCapturingVideoGraphFactory(videoGraphEnded))
                      .setVideoPrewarmingEnabled(videoPrewarmingEnabled)
                      .build());
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              compositionPlayer.get().setVideoSurfaceView(surfaceView);
              compositionPlayer.get().addListener(playerTestListener);
              compositionPlayer
                  .get()
                  .addListener(
                      new Player.Listener() {
                        @Override
                        public void onPlayerError(PlaybackException error) {
                          playbackException.set(error);
                          waitUntilNumberOfFramesOrError.countDown();
                        }
                      });
              compositionPlayer
                  .get()
                  .setComposition(
                      new Composition.Builder(
                              new EditedMediaItemSequence.Builder(editedMediaItems).build())
                          .build());
              compositionPlayer.get().prepare();
              compositionPlayer.get().play();
            });

    // Wait until the number of frames are received, block further input on the shader program.
    assertWithMessage("Timeout reached while waiting for frames.")
        .that(waitUntilNumberOfFramesOrError.await(TEST_TIMEOUT_MS, MILLISECONDS))
        .isTrue();
    if (playbackException.get() != null) {
      throw playbackException.get();
    }
    getInstrumentation().runOnMainSync(() -> compositionPlayer.get().seekTo(seekTimeMs));
    playerTestListener.waitUntilPlayerEnded();

    assertThat(videoGraphEnded.await(VIDEO_GRAPH_END_TIMEOUT_MS, MILLISECONDS)).isTrue();

    getInstrumentation().runOnMainSync(() -> compositionPlayer.get().release());
    if (playbackException.get() != null) {
      throw playbackException.get();
    }
    return inputTimestampRecordingShaderProgram.getInputTimestampsUs();
  }

  /**
   * Plays the first {@code numberOfFramesBeforeSeeking} frames of the provided sequence, seeks to
   * {@code seekTimeMs}, resumes playback until it ends, and returns the timestamps of the processed
   * frames, in microsecond.
   */
  private ImmutableList<Long> playSequenceAndGetTimestampsUs(
      List<MediaItemConfig> mediaItems, int numberOfFramesBeforeSeeking, long seekTimeMs)
      throws Exception {
    return playSequenceAndGetTimestampsUs(
        mediaItems, numberOfFramesBeforeSeeking, seekTimeMs, /* videoPrewarmingEnabled= */ true);
  }

  /**
   * Plays the {@linkplain MediaItemConfig media items} until playback ends, seeks to {@code
   * seekTimeMs}, resumes playback until it ends, and returns the timestamps of the processed
   * frames, in microsecond.
   */
  private ImmutableList<Long> playSequenceUntilEndedAndSeekAndGetTimestampsUs(
      List<MediaItemConfig> mediaItems, long seekTimeMs)
      throws PlaybackException, TimeoutException {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    CountDownLatch videoGraphEnded = new CountDownLatch(1);
    AtomicReference<@NullableType PlaybackException> playbackException = new AtomicReference<>();

    List<EditedMediaItem> editedMediaItems = new ArrayList<>();
    for (int i = 0; i < mediaItems.size(); i++) {
      editedMediaItems.add(
          createEditedMediaItem(
              mediaItems.get(i),
              /* videoEffect= */ (GlEffect)
                  (context, useHdr) -> inputTimestampRecordingShaderProgram));
    }

    AtomicReference<CompositionPlayer> compositionPlayer = new AtomicReference<>();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer.set(
                  new CompositionPlayer.Builder(applicationContext)
                      .setVideoGraphFactory(new ListenerCapturingVideoGraphFactory(videoGraphEnded))
                      .build());
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              compositionPlayer.get().setVideoSurfaceView(surfaceView);
              compositionPlayer.get().addListener(playerTestListener);
              compositionPlayer
                  .get()
                  .addListener(
                      new Player.Listener() {
                        @Override
                        public void onPlayerError(PlaybackException error) {
                          playbackException.set(error);
                        }
                      });
              compositionPlayer
                  .get()
                  .setComposition(
                      new Composition.Builder(
                              new EditedMediaItemSequence.Builder(editedMediaItems).build())
                          .build());
              compositionPlayer.get().prepare();
              compositionPlayer.get().play();
            });
    playerTestListener.waitUntilPlayerEnded();
    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> compositionPlayer.get().seekTo(seekTimeMs));
    playerTestListener.waitUntilPlayerEnded();

    getInstrumentation().runOnMainSync(() -> compositionPlayer.get().release());
    if (playbackException.get() != null) {
      throw playbackException.get();
    }
    return inputTimestampRecordingShaderProgram.getInputTimestampsUs();
  }

  /**
   * Creates an {@link InputTimestampRecordingShaderProgram} that signals the provided {@link
   * CountDownLatch} and blocks input after receiving the number of frames specified.
   *
   * <p>Input is unblocked when the shader program is flushed.
   */
  private static InputTimestampRecordingShaderProgram createInputTimestampRecordingShaderProgram(
      int numberOfFramesBeforeSeeking, CountDownLatch waitUntilNumberOfFrames) {
    return new InputTimestampRecordingShaderProgram() {
      private int framesQueued;
      private boolean seekCompleted;

      @Override
      public void queueInputFrame(
          GlObjectsProvider glObjectsProvider,
          GlTextureInfo inputTexture,
          long presentationTimeUs) {
        super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
        framesQueued += 1;
        if (areEnoughFramesQueued()) {
          waitUntilNumberOfFrames.countDown();
        }
      }

      @Override
      public void releaseOutputFrame(GlTextureInfo outputTexture) {
        // The input listener capacity is reported in the super method, block input by skip
        // reporting input capacity.
        if (!seekCompleted && areEnoughFramesQueued()) {
          return;
        }
        super.releaseOutputFrame(outputTexture);
      }

      @Override
      public void flush() {
        super.flush();
        if (areEnoughFramesQueued()) {
          // The flush is caused by the seek operation. We do this check because the shader
          // program can be flushed for other reasons, for example at the transition between 2
          // renderers.
          seekCompleted = true;
        }
      }

      private boolean areEnoughFramesQueued() {
        return framesQueued >= numberOfFramesBeforeSeeking;
      }
    };
  }

  /**
   * Returns a list of {@linkplain EditedMediaItem EditedMediaItems}.
   *
   * @param mediaItemConfig The {@link MediaItemConfig}.
   * @param videoEffect The {@link Effect} to apply to each {@link EditedMediaItem}.
   * @return A list of {@linkplain EditedMediaItem EditedMediaItems}.
   */
  private static EditedMediaItem createEditedMediaItem(
      MediaItemConfig mediaItemConfig, Effect videoEffect) {
    return new EditedMediaItem.Builder(mediaItemConfig.mediaItem)
        .setDurationUs(mediaItemConfig.durationUs)
        .setEffects(
            new Effects(/* audioProcessors= */ ImmutableList.of(), ImmutableList.of(videoEffect)))
        .build();
  }

  private static final class ListenerCapturingVideoGraphFactory implements VideoGraph.Factory {

    private final VideoGraph.Factory singleInputVideoGraphFactory;
    private final CountDownLatch videoGraphEnded;

    public ListenerCapturingVideoGraphFactory(CountDownLatch videoGraphEnded) {
      singleInputVideoGraphFactory = new SingleInputVideoGraph.Factory();
      this.videoGraphEnded = videoGraphEnded;
    }

    @Override
    public VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      return singleInputVideoGraphFactory.create(
          context,
          outputColorInfo,
          debugViewProvider,
          new VideoGraph.Listener() {

            @Override
            public void onOutputSizeChanged(int width, int height) {
              listener.onOutputSizeChanged(width, height);
            }

            @Override
            public void onOutputFrameRateChanged(float frameRate) {
              listener.onOutputFrameRateChanged(frameRate);
            }

            @Override
            public void onOutputFrameAvailableForRendering(
                long framePresentationTimeUs, boolean isRedrawnFrame) {
              listener.onOutputFrameAvailableForRendering(framePresentationTimeUs, isRedrawnFrame);
            }

            @Override
            public void onEnded(long finalFramePresentationTimeUs) {
              videoGraphEnded.countDown();
              listener.onEnded(finalFramePresentationTimeUs);
            }

            @Override
            public void onError(VideoFrameProcessingException exception) {
              listener.onError(exception);
            }
          },
          listenerExecutor,
          initialTimestampOffsetUs,
          renderFramesAutomatically);
    }

    @Override
    public boolean supportsMultipleInputs() {
      return singleInputVideoGraphFactory.supportsMultipleInputs();
    }
  }

  private static final class MediaItemConfig {

    public final MediaItem mediaItem;
    public final long durationUs;

    public MediaItemConfig(MediaItem mediaItem, long durationUs) {
      this.mediaItem = mediaItem;
      this.durationUs = durationUs;
    }
  }

  /**
   * {@link BaseAudioProcessor} implementation that accepts all input audio formats and outputs a
   * copy of any received input buffer.
   */
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
