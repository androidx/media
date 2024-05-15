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

import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888Image;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.transformer.mh.performance.PlaybackTestUtil.createTimestampOverlay;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
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
  private static final String MP4_ASSET_URI_STRING = "asset:///media/mp4/sample.mp4";
  private static final Format MP4_ASSET_FORMAT =
      new Format.Builder()
          .setSampleMimeType(VIDEO_H264)
          .setWidth(1080)
          .setHeight(720)
          .setFrameRate(29.97f)
          .setCodecs("avc1.64001F")
          .build();
  private static final Size MP4_ASSET_VIDEO_SIZE =
      new Size(MP4_ASSET_FORMAT.width, MP4_ASSET_FORMAT.height);
  private static final long TEST_TIMEOUT_MS = 10_000;

  @Rule public final TestName testName = new TestName();

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private @MonotonicNonNull CompositionPlayer player;
  private @MonotonicNonNull ImageReader outputImageReader;
  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() {
    instrumentation.runOnMainSync(
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
            MP4_ASSET_VIDEO_SIZE.getWidth(),
            MP4_ASSET_VIDEO_SIZE.getHeight(),
            PixelFormat.RGBA_8888,
            /* maxImages= */ 1);

    instrumentation.runOnMainSync(
        () -> {
          player = new CompositionPlayer.Builder(instrumentation.getContext()).build();
          outputImageReader.setOnImageAvailableListener(
              imageReader -> {
                try (Image image = imageReader.acquireLatestImage()) {
                  renderedFirstFrameBitmap.set(createArgb8888BitmapFromRgba8888Image(image));
                }
                hasRenderedFirstFrameCondition.open();
              },
              Util.createHandlerForCurrentOrMainLooper());

          player.setVideoSurface(outputImageReader.getSurface(), MP4_ASSET_VIDEO_SIZE);
          player.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence(
                          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET_URI_STRING))
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
}
