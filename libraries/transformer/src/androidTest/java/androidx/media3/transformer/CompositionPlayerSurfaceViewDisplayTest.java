/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.PlayerFence.futureWhen;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests verifying that {@link CompositionPlayer} frames actually reach the display
 * when output to a {@link SurfaceView}.
 *
 * <p>These tests deliberately avoid {@link android.view.PixelCopy}. {@code PixelCopy} on a {@link
 * SurfaceView} reads the last buffer <em>queued by the producer</em>, so it still succeeds when
 * {@code BLASTBufferQueue} rejects every buffer (for example because the {@link
 * android.view.SurfaceHolder} was never resized to the video size) and the user only sees a black
 * screen. Screen content is instead captured through {@link
 * android.app.UiAutomation#takeScreenshot()}, which goes through real SurfaceFlinger composition.
 */
@RunWith(AndroidJUnit4.class)
public final class CompositionPlayerSurfaceViewDisplayTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 30_000 : 10_000;
  private static final long DISPLAY_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;
  private static final long DISPLAY_POLL_INTERVAL_MS = 100;

  @Rule
  public final ActivityScenarioRule<SurfaceTestActivity> activityRule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  @Rule public final TestName testName = new TestName();

  @Rule
  public final GlFrameProcessorTestRule glFrameProcessorTestRule = new GlFrameProcessorTestRule();

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = ApplicationProvider.getApplicationContext();

  private @MonotonicNonNull CompositionPlayer compositionPlayer;
  private @MonotonicNonNull SurfaceView surfaceView;
  private String testId;

  @Before
  public void setUp() {
    testId = testName.getMethodName();
    activityRule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    instrumentation.runOnMainSync(() -> {
      if (compositionPlayer != null) {
        compositionPlayer.release();
      }
    });
    activityRule.getScenario().close();
  }

  @Test
  public void prepare_withFrameProcessorAndSurfaceView_displaysFrameOnScreen() throws Exception {
    SettableFuture<Void> firstFrameRenderedFuture = SettableFuture.create();
    instrumentation.runOnMainSync(() -> {
      compositionPlayer = glFrameProcessorTestRule.createCompositionPlayerBuilder(context).build();
      compositionPlayer.setVideoSurfaceView(surfaceView);
      firstFrameRenderedFuture.setFuture(futureWhen(compositionPlayer).rendersFirstFrame());
      compositionPlayer.setComposition(createSingleItemComposition());
      compositionPlayer.prepare();
    });
    awaitOrAssumeWindowFocused(firstFrameRenderedFuture);

    assertSurfaceViewDisplaysContent();
  }

  @Test
  public void experimentalRedrawLastFrame_surfaceRecreated_displaysFrame() throws Exception {
    SettableFuture<Void> firstFrameRenderedFuture = SettableFuture.create();
    ConditionVariable redrawnFrameRendered = new ConditionVariable();
    instrumentation.runOnMainSync(() -> {
      compositionPlayer = glFrameProcessorTestRule.createCompositionPlayerBuilder(context).build();
      compositionPlayer.setVideoSurfaceView(surfaceView);
      firstFrameRenderedFuture.setFuture(futureWhen(compositionPlayer).rendersFirstFrame());
      compositionPlayer.setComposition(createSingleItemComposition());
      compositionPlayer.prepare();
    });
    awaitOrAssumeWindowFocused(firstFrameRenderedFuture);
    // Recreate the SurfaceView while paused so no new playback frames arrive to repopulate it.
    recreateSurfaceView(
        surfaceView,
        MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat.width,
        MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat.height);
    instrumentation.runOnMainSync(() ->
        compositionPlayer.setVideoFrameMetadataListener(
            (presentationTimeUs, releaseTimeNs, format, mediaFormat) ->
                redrawnFrameRendered.open()));

    instrumentation.runOnMainSync(() -> compositionPlayer.experimentalRedrawLastFrame());

    assertThat(redrawnFrameRendered.block(TEST_TIMEOUT_MS)).isTrue();
    assertSurfaceViewDisplaysContent();
  }

  private static Composition createSingleItemComposition() {
    return new Composition.Builder(
            EditedMediaItemSequence.withVideoFrom(
                ImmutableList.of(
                    new EditedMediaItem.Builder(
                            MediaItem.fromUri(
                                MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                        .setDurationUs(
                            MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoDurationUs)
                        .build())))
        .build();
  }

  private static void recreateSurfaceView(
      SurfaceView surfaceView, int expectedWidth, int expectedHeight) throws InterruptedException {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    ConditionVariable surfaceDestroyed = new ConditionVariable();
    ConditionVariable surfaceRecreated = new ConditionVariable();
    SurfaceHolder.Callback callback =
        new SurfaceHolder.Callback() {
          @Override
          public void surfaceCreated(SurfaceHolder holder) {}

          @Override
          public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (width == expectedWidth && height == expectedHeight) {
              surfaceRecreated.open();
            }
          }

          @Override
          public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceDestroyed.open();
          }
        };
    try {
      instrumentation.runOnMainSync(() -> {
        surfaceView.getHolder().addCallback(callback);
        surfaceView.setVisibility(View.GONE);
      });
      checkState(
          surfaceDestroyed.block(TEST_TIMEOUT_MS), "Timed out waiting for surfaceDestroyed.");
      surfaceRecreated.close();
      instrumentation.runOnMainSync(() -> surfaceView.setVisibility(View.VISIBLE));
      checkState(surfaceRecreated.block(TEST_TIMEOUT_MS), "Timed out waiting for surfaceChanged.");
    } finally {
      instrumentation.runOnMainSync(() -> surfaceView.getHolder().removeCallback(callback));
    }
  }

  /**
   * Waits for {@code future} to complete within {@link #TEST_TIMEOUT_MS}.
   *
   * <p>If waiting times out because {@link #surfaceView} lost window focus (for example, due to a
   * system dialog on the test device), skips the test via a JUnit assumption.
   */
  private void awaitOrAssumeWindowFocused(ListenableFuture<?> future)
      throws ExecutionException, InterruptedException, TimeoutException {
    try {
      future.get(TEST_TIMEOUT_MS, MILLISECONDS);
    } catch (TimeoutException | CancellationException e) {
      // PlayerFence surfaces its internal timeout as CancellationException.
      assumeSurfaceViewHasWindowFocus();
      throw e;
    }
  }

  private void assertSurfaceViewDisplaysContent() throws InterruptedException {
    long timeoutMs = DISPLAY_TIMEOUT_MS;
    Bitmap lastCapture = null;
    while (timeoutMs > 0) {
      lastCapture = captureSurfaceViewFromScreen();
      int bottomY = lastCapture.getHeight() - 1;
      // Check that the SurfaceView contents changed from a uniform background.
      if (lastCapture.getPixel(0, bottomY)
          != lastCapture.getPixel(lastCapture.getWidth() / 2, bottomY)) {
        return;
      }
      Thread.sleep(DISPLAY_POLL_INTERVAL_MS);
      timeoutMs -= DISPLAY_POLL_INTERVAL_MS;
    }
    assumeSurfaceViewHasWindowFocus();
    maybeSaveTestBitmap(testId, "failure_lastCapture", lastCapture, /* path= */ null);
    throw new AssertionError(
        "Timed out after " + DISPLAY_TIMEOUT_MS + " ms waiting for SurfaceView content.");
  }

  private void assumeSurfaceViewHasWindowFocus() {
    AtomicBoolean hasWindowFocus = new AtomicBoolean();
    instrumentation.runOnMainSync(() -> hasWindowFocus.set(surfaceView.hasWindowFocus()));
    assumeTrue("SurfaceView lost window focus", hasWindowFocus.get());
  }

  private Bitmap captureSurfaceViewFromScreen() {
    Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
    Rect bounds = getSurfaceViewScreenBounds(screenshot.getWidth(), screenshot.getHeight());
    return Bitmap.createBitmap(
        screenshot, bounds.left, bounds.top, bounds.width(), bounds.height());
  }

  private Rect getSurfaceViewScreenBounds(int screenshotWidth, int screenshotHeight) {
    Rect bounds = new Rect();
    instrumentation.runOnMainSync(() -> {
      int[] locationOnScreen = new int[2];
      surfaceView.getLocationOnScreen(locationOnScreen);
      bounds.set(
          locationOnScreen[0],
          locationOnScreen[1],
          locationOnScreen[0] + surfaceView.getWidth(),
          locationOnScreen[1] + surfaceView.getHeight());
    });
    if (!bounds.intersect(0, 0, screenshotWidth, screenshotHeight)) {
      bounds.setEmpty();
    }
    return bounds;
  }
}
