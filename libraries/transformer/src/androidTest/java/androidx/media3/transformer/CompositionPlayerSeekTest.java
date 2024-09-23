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

import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlEffect;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
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

  private static final MediaItem VIDEO_MEDIA_ITEM = MediaItem.fromUri(MP4_ASSET.uri);
  private static final long VIDEO_DURATION_US = MP4_ASSET.videoDurationUs;
  private static final ImmutableList<Long> VIDEO_TIMESTAMPS_US = MP4_ASSET.videoTimestampsUs;
  private static final MediaItem IMAGE_MEDIA_ITEM =
      new MediaItem.Builder().setUri(PNG_ASSET.uri).setImageDurationMs(200).build();
  private static final long IMAGE_DURATION_US = 200_000;
  // 200 ms at 30 fps (default frame rate)
  private static final ImmutableList<Long> IMAGE_TIMESTAMPS_US =
      ImmutableList.of(0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L);

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context applicationContext =
      getInstrumentation().getContext().getApplicationContext();
  private final PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);

  private CompositionPlayer compositionPlayer;
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
              if (compositionPlayer != null) {
                compositionPlayer.release();
              }
            });
  }

  @Test
  public void seekToZero_afterPlayingSingleSequenceOfTwoVideos() throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    EditedMediaItem video =
        createEditedMediaItem(
            VIDEO_MEDIA_ITEM,
            VIDEO_DURATION_US,
            /* videoEffect= */ (GlEffect)
                (context, useHdr) -> inputTimestampRecordingShaderProgram);
    ImmutableList<Long> sequenceTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Plays the first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Plays the second video
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();
    // Seeked after the first playback ends, so the timestamps are repeated twice.
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(sequenceTimestampsUs)
            .addAll(sequenceTimestampsUs)
            .build();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              compositionPlayer.setVideoSurfaceView(surfaceView);
              compositionPlayer.addListener(playerTestListener);
              compositionPlayer.setComposition(
                  new Composition.Builder(new EditedMediaItemSequence.Builder(video, video).build())
                      .build());
              compositionPlayer.prepare();
              compositionPlayer.play();
            });
    playerTestListener.waitUntilPlayerEnded();
    playerTestListener.resetStatus();
    getInstrumentation().runOnMainSync(() -> compositionPlayer.seekTo(0));
    playerTestListener.waitUntilPlayerEnded();

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToZero_duringPlayingFirstVideoInSingleSequenceOfTwoVideos() throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, VIDEO_DURATION_US);
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
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, /* seekTimeMs= */ 0);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToSecondMedia_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, VIDEO_DURATION_US);
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
                Iterables.transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToFirstMedia_duringPlayingSecondVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, VIDEO_DURATION_US);
    int numberOfFramesBeforeSeeking = 45;
    // 100ms into the first video, should skip the first 3 frames.
    long seekTimeMs = 100;
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            // Play first video
            .addAll(VIDEO_TIMESTAMPS_US)
            // Play the first 15 frames of the seconds video
            .addAll(
                Iterables.transform(
                    Iterables.limit(VIDEO_TIMESTAMPS_US, /* limitSize= */ 15),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            // Seek to the first, skipping the first 3 frames.
            .addAll(Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3))
            // Plays the second video
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfFirstMedia_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, VIDEO_DURATION_US);
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
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToEndOfSecondVideo_duringPlayingFirstVideoInSingleSequenceOfTwoVideos()
      throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, VIDEO_DURATION_US);
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
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToImage_fromSameImage() throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(IMAGE_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(IMAGE_DURATION_US);
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
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToImage_fromOtherImage() throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(IMAGE_MEDIA_ITEM, IMAGE_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(IMAGE_DURATION_US, IMAGE_DURATION_US);
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
                Iterables.transform(
                    Iterables.skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToImage_fromVideo() throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(VIDEO_MEDIA_ITEM, IMAGE_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(VIDEO_DURATION_US, IMAGE_DURATION_US);
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
                Iterables.transform(
                    Iterables.skip(IMAGE_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (VIDEO_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void seekToVideo_fromImage() throws Exception {
    ImmutableList<MediaItem> mediaItems = ImmutableList.of(IMAGE_MEDIA_ITEM, VIDEO_MEDIA_ITEM);
    ImmutableList<Long> durationsUs = ImmutableList.of(IMAGE_DURATION_US, VIDEO_DURATION_US);
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
                Iterables.transform(
                    Iterables.skip(VIDEO_TIMESTAMPS_US, /* numberToSkip= */ 3),
                    timestampUs -> (IMAGE_DURATION_US + timestampUs)))
            .build();

    ImmutableList<Long> actualTimestampsUs =
        playSequenceAndGetTimestampsUs(
            mediaItems, durationsUs, numberOfFramesBeforeSeeking, seekTimeMs);

    assertThat(actualTimestampsUs).isEqualTo(expectedTimestampsUs);
  }

  /**
   * Plays the first {@code numberOfFramesBeforeSeeking} frames of the provided sequence, seeks to
   * {@code seekTimeMs}, resumes playback until it ends, and returns the timestamps of the processed
   * frames, in microsecond.
   */
  private ImmutableList<Long> playSequenceAndGetTimestampsUs(
      List<MediaItem> mediaItems,
      List<Long> durationsUs,
      int numberOfFramesBeforeSeeking,
      long seekTimeMs)
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
            if (framesReceivedLatch.getCount() == 0) {
              // The flush is caused by the seek operation. We do this check because the shader
              // program can be flushed for other reasons, for example at the transition between 2
              // renderers.
              shaderProgramShouldBlockInput.set(false);
              framesReceivedLatch.reset(Integer.MAX_VALUE);
            }
          }
        };

    List<EditedMediaItem> editedMediaItems = new ArrayList<>();
    for (int i = 0; i < mediaItems.size(); i++) {
      editedMediaItems.add(
          createEditedMediaItem(
              mediaItems.get(i),
              durationsUs.get(i),
              /* videoEffect= */ (GlEffect)
                  (context, useHdr) -> inputTimestampRecordingShaderProgram));
    }

    getInstrumentation()
        .runOnMainSync(
            () -> {
              compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              compositionPlayer.setVideoSurfaceView(surfaceView);
              compositionPlayer.addListener(playerTestListener);
              compositionPlayer.setComposition(
                  new Composition.Builder(
                          new EditedMediaItemSequence.Builder(editedMediaItems).build())
                      .build());
              compositionPlayer.prepare();
              compositionPlayer.play();
            });

    // Wait until the number of frames are received, block further input on the shader program.
    framesReceivedLatch.await();
    getInstrumentation().runOnMainSync(() -> compositionPlayer.seekTo(seekTimeMs));
    playerTestListener.waitUntilPlayerEnded();
    return inputTimestampRecordingShaderProgram.getInputTimestampsUs();
  }

  private static EditedMediaItem createEditedMediaItem(
      MediaItem mediaItem, long durationUs, Effect videoEffect) {
    return new EditedMediaItem.Builder(mediaItem)
        .setDurationUs(durationUs)
        .setEffects(
            new Effects(/* audioProcessors= */ ImmutableList.of(), ImmutableList.of(videoEffect)))
        .build();
  }

  private static final class ResettableCountDownLatch {
    private CountDownLatch latch;

    public ResettableCountDownLatch(int count) {
      latch = new CountDownLatch(count);
    }

    public void await() throws InterruptedException, TimeoutException {
      if (!latch.await(TEST_TIMEOUT_MS, MILLISECONDS)) {
        throw new TimeoutException();
      }
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
