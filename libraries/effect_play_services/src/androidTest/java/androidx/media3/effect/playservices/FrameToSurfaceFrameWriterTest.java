/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect.playservices;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Hermetic unit tests for {@link FrameToSurfaceFrameWriter}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class FrameToSurfaceFrameWriterTest {

  private static final int WIDTH = 16;
  private static final int HEIGHT = 16;

  private ImageReader imageReader;
  private TestListener testListener;
  private FrameToSurfaceFrameWriter frameWriter;

  @Before
  public void setUp() {
    imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 4);
    testListener = new TestListener();
    frameWriter = new FrameToSurfaceFrameWriter(Runnable::run, testListener);
    frameWriter.setOutputSurface(imageReader.getSurface(), WIDTH, HEIGHT);
  }

  @After
  public void tearDown() {
    frameWriter.close();
    imageReader.close();
  }

  @Test
  public void configure_forwardsFormatToListener() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    assertThat(testListener.configuredFormat.get()).isEqualTo(format);
  }

  @Test
  public void configure_calledMultipleTimes_throwsIllegalStateException() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    assertThrows(
        IllegalStateException.class,
        () -> frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT));
  }

  @Test
  public void dequeueInputFrame_whenAtCapacity_returnsNullUntilReleased() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    AsyncFrame firstFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});
    assertThat(firstFrame).isNotNull();
    frameWriter.queueInputFrame(firstFrame.frame, /* writeCompleteFence= */ null);

    // At capacity (1 in-flight frame), next dequeue returns null.
    assertThat(frameWriter.dequeueInputFrame(Runnable::run, () -> {})).isNull();

    try (Image outputImage = imageReader.acquireLatestImage()) {
      assertThat(outputImage).isNotNull();
    }
    frameWriter.onFrameReleased();

    AsyncFrame secondFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});
    assertThat(secondFrame).isNotNull();
  }

  @Test
  public void dequeueInputFrame_whenSurfaceNotConfigured_returnsNull() {
    try (FrameToSurfaceFrameWriter unsetFrameWriter =
        new FrameToSurfaceFrameWriter(Runnable::run, testListener)) {
      AsyncFrame frame = unsetFrameWriter.dequeueInputFrame(Runnable::run, () -> {});

      assertThat(frame).isNull();
    }
  }

  @Test
  public void dequeueInputFrame_whenAvailable_returnsAsyncFrameWrappingHardwareBuffer() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    AsyncFrame asyncFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});

    assertThat(asyncFrame).isNotNull();
    assertThat(asyncFrame.frame).isInstanceOf(DefaultHardwareBufferFrame.class);
    assertThat(asyncFrame.frame.getFormat()).isEqualTo(format);
  }

  @Test
  public void onFrameReleased_triggersRegisteredWakeupListener() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    AsyncFrame firstFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});
    assertThat(firstFrame).isNotNull();
    frameWriter.queueInputFrame(firstFrame.frame, /* writeCompleteFence= */ null);

    AtomicBoolean wakeupTriggered = new AtomicBoolean(false);
    assertThat(frameWriter.dequeueInputFrame(Runnable::run, () -> wakeupTriggered.set(true)))
        .isNull();

    try (Image outputImage = imageReader.acquireLatestImage()) {
      assertThat(outputImage).isNotNull();
    }
    frameWriter.onFrameReleased();

    assertThat(wakeupTriggered.get()).isTrue();
  }

  @Test
  public void queueInputFrame_queuesImageWithPresentationTimestamp() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    AsyncFrame asyncFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});
    assertThat(asyncFrame).isNotNull();
    DefaultHardwareBufferFrame dequeuedFrame = (DefaultHardwareBufferFrame) asyncFrame.frame;
    DefaultHardwareBufferFrame frameWithTimestamp =
        dequeuedFrame.buildUpon().setContentTimeUs(123_456L).build();
    frameWriter.queueInputFrame(frameWithTimestamp, /* writeCompleteFence= */ null);

    try (Image outputImage = imageReader.acquireLatestImage()) {
      assertThat(outputImage).isNotNull();
      assertThat(outputImage.getTimestamp()).isEqualTo(123_456_000L);
      assertThat(testListener.lastQueuedPtsUs.get()).isEqualTo(123_456L);
    }
  }

  @Test
  public void queueInputFrame_whenClosed_reportsErrorToListener() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    frameWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    AsyncFrame asyncFrame = frameWriter.dequeueInputFrame(Runnable::run, () -> {});
    assertThat(asyncFrame).isNotNull();
    frameWriter.close();

    frameWriter.queueInputFrame(asyncFrame.frame, /* writeCompleteFence= */ null);

    assertThat(testListener.lastError.get()).isInstanceOf(VideoFrameProcessingException.class);
  }

  @Test
  public void queueInputFrame_withInvalidFrameType_throwsIllegalArgumentException() {
    Frame invalidFrame =
        new Frame() {
          @Override
          public Format getFormat() {
            return new Format.Builder().build();
          }

          @Override
          public ImmutableMap<String, Object> getMetadata() {
            return ImmutableMap.of();
          }

          @Override
          public long getContentTimeUs() {
            return 0;
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () -> frameWriter.queueInputFrame(invalidFrame, /* writeCompleteFence= */ null));
  }

  @Test
  public void signalEndOfStream_notifiesListener() {
    frameWriter.signalEndOfStream();

    assertThat(testListener.endOfStreamSignaled.get()).isTrue();
  }

  private static class TestListener implements FrameToSurfaceFrameWriter.Listener {
    final AtomicReference<Format> configuredFormat = new AtomicReference<>();
    final AtomicLong lastQueuedPtsUs = new AtomicLong(C.TIME_UNSET);
    final AtomicBoolean endOfStreamSignaled = new AtomicBoolean(false);
    final AtomicReference<Exception> lastError = new AtomicReference<>();

    @Override
    public void onFormatConfigured(Format format) {
      configuredFormat.set(format);
    }

    @Override
    public void onFrameQueued(long presentationTimeUs) {
      lastQueuedPtsUs.set(presentationTimeUs);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      lastError.set(exception);
    }

    @Override
    public void onEndOfStream() {
      endOfStreamSignaled.set(true);
    }
  }
}
