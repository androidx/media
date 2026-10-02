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
package androidx.media3.common.video;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.Activity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.test.utils.FakeHardwareBufferNativeHelpers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link SurfaceHolderFrameWriter} attached to a real {@link
 * SurfaceView}.
 */
@RunWith(AndroidJUnit4.class)
public final class SurfaceHolderFrameWriterSurfaceViewAndroidTest {

  private static final long TEST_TIMEOUT_MS = 10_000L;
  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;
  private static final int SECOND_WIDTH = 320;
  private static final int SECOND_HEIGHT = 240;

  @Rule
  public final ActivityScenarioRule<Activity> activityRule =
      new ActivityScenarioRule<>(Activity.class);

  private BlockingQueue<Size> surfaceSizes;
  private SurfaceView surfaceView;
  private SurfaceHolderFrameWriter frameWriter;
  private FakeListener listener;

  @Before
  public void setUp() throws Exception {
    surfaceSizes = new LinkedBlockingQueue<>();
    activityRule
        .getScenario()
        .onActivity(
            activity -> {
              surfaceView = new SurfaceView(activity);
              surfaceView
                  .getHolder()
                  .addCallback(
                      new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(SurfaceHolder holder) {}

                        @Override
                        public void surfaceChanged(
                            SurfaceHolder holder, int format, int width, int height) {
                          surfaceSizes.add(new Size(width, height));
                        }

                        @Override
                        public void surfaceDestroyed(SurfaceHolder holder) {}
                      });
              activity.setContentView(surfaceView, new ViewGroup.LayoutParams(100, 100));
            });
    assertThat(surfaceSizes.poll(TEST_TIMEOUT_MS, MILLISECONDS)).isEqualTo(new Size(100, 100));
    listener = new FakeListener();
    frameWriter =
        SurfaceHolderFrameWriter.create(
            surfaceView.getHolder(),
            surfaceView::post,
            listener,
            directExecutor(),
            new FakeHardwareBufferNativeHelpers());
  }

  @After
  public void tearDown() {
    if (frameWriter != null) {
      frameWriter.close();
    }
    activityRule.getScenario().close();
    assertThat(listener.lastException).isNull();
  }

  @Test
  public void configure_withSurfaceViewHolder_resizesSurfaceToVideoSize() throws Exception {
    frameWriter.configure(createFormat(WIDTH, HEIGHT), /* usage= */ 0);

    assertResized(WIDTH, HEIGHT);
  }

  @Test
  public void configure_withNewFormatSize_resizesSurfaceAgain() throws Exception {
    frameWriter.configure(createFormat(WIDTH, HEIGHT), /* usage= */ 0);
    assertResized(WIDTH, HEIGHT);

    frameWriter.configure(createFormat(SECOND_WIDTH, SECOND_HEIGHT), /* usage= */ 0);

    assertResized(SECOND_WIDTH, SECOND_HEIGHT);
  }

  private static Format createFormat(int width, int height) {
    return new Format.Builder()
        .setWidth(width)
        .setHeight(height)
        .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
        .build();
  }

  private void assertResized(int width, int height) throws Exception {
    // SurfaceHolderFrameWriter first sets the surface size to 1x1 to force a surfaceChanged
    // callback before setting the target format size.
    Size size = surfaceSizes.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    if (new Size(1, 1).equals(size)) {
      size = surfaceSizes.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    }
    assertThat(size).isEqualTo(new Size(width, height));
  }

  private static final class FakeListener implements SurfaceHolderFrameWriter.Listener {
    @Nullable volatile VideoFrameProcessingException lastException;

    @Override
    public void onFrameAboutToBeRendered(
        long presentationTimeUs, long releaseTimeNs, Format format) {}

    @Override
    public void onError(VideoFrameProcessingException videoFrameProcessingException) {
      lastException = videoFrameProcessingException;
    }

    @Override
    public void onEnded() {}
  }
}
