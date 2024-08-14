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

import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888Image;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET;
import static androidx.media3.transformer.mh.performance.PlaybackTestUtil.createTimestampOverlay;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.view.SurfaceView;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Playback tests for {@link CompositionPlayer} */
@RunWith(AndroidJUnit4.class)
public class CompositionPlaybackTest {

  private static final String TEST_DIRECTORY = "test-generated-goldens/ExoPlayerPlaybackTest";
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

  @Rule public final TestName testName = new TestName();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context context = getInstrumentation().getContext().getApplicationContext();
  private final PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);

  private @MonotonicNonNull CompositionPlayer player;
  private @MonotonicNonNull ImageReader outputImageReader;

  private String testId;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() {
    rule.getScenario().close();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (player != null) {
                player.release();
              }
              if (outputImageReader != null) {
                outputImageReader.close();
              }
            });
  }

  @Test
  public void compositionPlayerPreviewTest_ensuresFirstFrameRenderedCorrectly() throws Exception {
    AtomicReference<Bitmap> renderedFirstFrameBitmap = new AtomicReference<>();
    ConditionVariable hasRenderedFirstFrameCondition = new ConditionVariable();
    outputImageReader =
        ImageReader.newInstance(
            MP4_ASSET.videoFormat.width,
            MP4_ASSET.videoFormat.height,
            PixelFormat.RGBA_8888,
            /* maxImages= */ 1);

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player = new CompositionPlayer.Builder(context).build();
              outputImageReader.setOnImageAvailableListener(
                  imageReader -> {
                    try (Image image = imageReader.acquireLatestImage()) {
                      renderedFirstFrameBitmap.set(createArgb8888BitmapFromRgba8888Image(image));
                    }
                    hasRenderedFirstFrameCondition.open();
                  },
                  Util.createHandlerForCurrentOrMainLooper());

              player.setVideoSurface(
                  outputImageReader.getSurface(),
                  new Size(MP4_ASSET.videoFormat.width, MP4_ASSET.videoFormat.height));
              player.setComposition(
                  new Composition.Builder(
                          new EditedMediaItemSequence(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setEffects(
                                      new Effects(
                                          /* audioProcessors= */ ImmutableList.of(),
                                          /* videoEffects= */ ImmutableList.of(
                                              createTimestampOverlay())))
                                  .setDurationUs(1_024_000L)
                                  .build()))
                      .build());
              player.prepare();
            });

    if (!hasRenderedFirstFrameCondition.block(TEST_TIMEOUT_MS)) {
      throw new TimeoutException(
          Util.formatInvariant("First frame not rendered in %d ms.", TEST_TIMEOUT_MS));
    }

    assertWithMessage("First frame is not rendered.")
        .that(renderedFirstFrameBitmap.get())
        .isNotNull();
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(
            /* expected= */ readBitmap(TEST_DIRECTORY + "/first_frame.png"),
            /* actual= */ renderedFirstFrameBitmap.get(),
            testId);
    assertThat(averagePixelAbsoluteDifference).isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    // TODO: b/315800590 - Verify onFirstFrameRendered is invoked only once.
  }

  @Test
  public void playback_sequenceOfVideos_effectsReceiveCorrectTimestamps() throws Exception {
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
                new EditedMediaItemSequence(ImmutableList.of(editedMediaItem, editedMediaItem)))
            .build();
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(VIDEO_TIMESTAMPS_US)
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void playback_sequenceOfImages_effectsReceiveCorrectTimestamps() throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Effect videoEffect = (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram;
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(IMAGE_MEDIA_ITEM)
            .setDurationUs(IMAGE_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(ImmutableList.of(editedMediaItem, editedMediaItem)))
            .build();
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(IMAGE_TIMESTAMPS_US)
            .addAll(
                Iterables.transform(
                    IMAGE_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void playback_sequenceOfVideoAndImage_effectsReceiveCorrectTimestamps() throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Effect videoEffect = (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram;
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM)
            .setDurationUs(VIDEO_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    EditedMediaItem imageEditedMediaItem =
        new EditedMediaItem.Builder(IMAGE_MEDIA_ITEM)
            .setDurationUs(IMAGE_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    ImmutableList.of(videoEditedMediaItem, imageEditedMediaItem)))
            .build();
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(VIDEO_TIMESTAMPS_US)
            .addAll(
                Iterables.transform(
                    IMAGE_TIMESTAMPS_US, timestampUs -> (VIDEO_DURATION_US + timestampUs)))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void playback_sequenceOfImageAndVideo_effectsReceiveCorrectTimestamps() throws Exception {
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Effect videoEffect = (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram;
    EditedMediaItem imageEditedMediaItem =
        new EditedMediaItem.Builder(IMAGE_MEDIA_ITEM)
            .setDurationUs(IMAGE_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(VIDEO_MEDIA_ITEM)
            .setDurationUs(VIDEO_DURATION_US)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(videoEffect)))
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    ImmutableList.of(imageEditedMediaItem, videoEditedMediaItem)))
            .build();
    ImmutableList<Long> expectedTimestampsUs =
        new ImmutableList.Builder<Long>()
            .addAll(IMAGE_TIMESTAMPS_US)
            .addAll(
                Iterables.transform(
                    VIDEO_TIMESTAMPS_US, timestampUs -> (IMAGE_DURATION_US + timestampUs)))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void playback_sequenceOfThreeVideosWithRemovingFirstAndLastAudio_succeeds()
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
    EditedMediaItem videoEditedMediaItemRemoveAudio =
        videoEditedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    videoEditedMediaItemRemoveAudio,
                    videoEditedMediaItem,
                    videoEditedMediaItemRemoveAudio))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
  }

  @Test
  public void playback_sequenceOfThreeVideosWithRemovingMiddleAudio_succeeds() throws Exception {
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
    EditedMediaItem videoEditedMediaItemRemoveAudio =
        videoEditedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence(
                    videoEditedMediaItem, videoEditedMediaItemRemoveAudio, videoEditedMediaItem))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(expectedTimestampsUs);
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
                new EditedMediaItemSequence(
                    videoEditedMediaItem, videoEditedMediaItemRemoveVideo, videoEditedMediaItem))
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

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs()).isEmpty();
  }
}
