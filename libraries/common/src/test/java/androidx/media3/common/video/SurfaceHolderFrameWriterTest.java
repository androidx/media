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
import static org.junit.Assert.assertThrows;

import android.os.Handler;
import android.os.HandlerThread;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.SurfaceHolderFrameWriter.Listener;
import androidx.media3.test.utils.FakeHardwareBufferNativeHelpers;
import androidx.media3.test.utils.ImageReaderSurfaceHolder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link HardwareBufferPool}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 28)
public final class SurfaceHolderFrameWriterTest {

  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

  private ImageReaderSurfaceHolder surfaceHolder;
  private SurfaceHolderFrameWriter frameWriter;
  private AtomicBoolean onEnded;
  private Executor executor;
  private HandlerThread callbackThread;

  @Before
  public void setUp() {
    callbackThread = new HandlerThread("SurfaceHolderCallbackThread");
    callbackThread.start();
    Handler callbackHandler = new Handler(callbackThread.getLooper());
    surfaceHolder = new ImageReaderSurfaceHolder(callbackHandler);
    FakeHardwareBufferNativeHelpers hardwareBufferNativeHelpers =
        new FakeHardwareBufferNativeHelpers();
    onEnded = new AtomicBoolean();
    executor = directExecutor();
    Listener listener =
        new Listener() {
          @Override
          public void onFrameAboutToBeRendered(
              long presentationTimeUs, long releaseTimeNs, Format format) {}

          @Override
          public void onError(VideoFrameProcessingException videoFrameProcessingException) {}

          @Override
          public void onEnded() {
            onEnded.set(true);
          }
        };
    frameWriter =
        SurfaceHolderFrameWriter.create(
            surfaceHolder, executor, listener, executor, hardwareBufferNativeHelpers);
  }

  @After
  public void tearDown() {
    if (surfaceHolder != null) {
      surfaceHolder.close();
    }
    if (frameWriter != null) {
      frameWriter.close();
    }
    if (callbackThread != null) {
      callbackThread.quitSafely();
    }
  }

  @Test
  public void dequeueInputFrame_withoutConfigure_throwsIllegalStateException() {
    assertThrows(
        IllegalStateException.class, () -> frameWriter.dequeueInputFrame(executor, () -> {}));
  }

  @Test
  public void configure_invalidHeight_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(0)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    assertThrows(
        IllegalArgumentException.class, () -> frameWriter.configure(format, /* usage= */ 0));
  }

  @Test
  public void configure_invalidWidth_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(0)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    assertThrows(
        IllegalArgumentException.class, () -> frameWriter.configure(format, /* usage= */ 0));
  }

  @Test
  public void configure_invalidColorInfo_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).setColorInfo(null).build();

    assertThrows(
        IllegalArgumentException.class, () -> frameWriter.configure(format, /* usage= */ 0));
  }

  @Test
  public void queueInputFrame_withInvalidFrameType_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Frame invalidFrame =
        new Frame() {
          @Override
          public Format getFormat() {
            return format;
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
    frameWriter.configure(format, /* usage= */ 0);

    assertThrows(
        IllegalArgumentException.class,
        () -> frameWriter.queueInputFrame(invalidFrame, /* writeCompleteFence= */ null));
  }

  @Test
  public void signalEndOfStream_notifiesListener() {
    frameWriter.signalEndOfStream();

    assertThat(onEnded.get()).isTrue();
  }
}
