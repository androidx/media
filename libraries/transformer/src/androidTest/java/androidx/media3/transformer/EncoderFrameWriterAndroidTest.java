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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.transformer.AndroidTestUtil.ForceEncodeEncoderFactory;
import androidx.media3.transformer.EncoderFrameWriter.Listener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android tests for {@link EncoderFrameWriter}. Tests hardware surfaces and real MediaCodec
 * instances, which are unsupported on Robolectric.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public class EncoderFrameWriterAndroidTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;
  private static final Format TEST_ENCODING_FORMAT =
      new Format.Builder()
          .setWidth(1280)
          .setHeight(720)
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
          .build();
  private static final int CAPACITY = 4;

  private EncoderFrameWriter encoderFrameWriter;
  private AtomicReference<Codec> createdEncoder;
  private AtomicBoolean endOfStreamSignaled;
  private AtomicReference<VideoFrameProcessingException> errorException;
  private HandlerThread handlerThread;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    ForceEncodeEncoderFactory encoderFactory =
        new ForceEncodeEncoderFactory(
            new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build());

    createdEncoder = new AtomicReference<>();
    endOfStreamSignaled = new AtomicBoolean();
    errorException = new AtomicReference<>();

    EncoderFrameWriter.Listener listener =
        new Listener() {
          @Override
          public Format onConfigure(Format requestedFormat) {
            return requestedFormat;
          }

          @Override
          public void onEncoderCreated(Codec encoder) {
            createdEncoder.set(encoder);
          }

          @Override
          public void onEndOfStream() {
            endOfStreamSignaled.set(true);
          }

          @Override
          public void onError(VideoFrameProcessingException e) {
            errorException.set(e);
          }
        };

    handlerThread = new HandlerThread("EncoderFrameWriterTest");
    handlerThread.start();
    Handler imageReleaseHandler = new Handler(handlerThread.getLooper());

    encoderFrameWriter =
        new EncoderFrameWriter(
            encoderFactory,
            listener,
            /* listenerExecutor= */ directExecutor(),
            imageReleaseHandler);
  }

  @After
  public void tearDown() {
    if (encoderFrameWriter != null) {
      encoderFrameWriter.close();
    }
    if (handlerThread != null) {
      handlerThread.quit();
    }
  }

  @Test
  public void configure_withImpossibleFormat_invokesErrorListener() {
    Format impossibleFormat =
        new Format.Builder()
            .setWidth(1)
            .setHeight(1)
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build();

    encoderFrameWriter.configure(impossibleFormat, Frame.USAGE_VIDEO_ENCODE);

    assertThat(createdEncoder.get()).isNull();
    assertThat(errorException.get()).isNotNull();
  }

  @Test
  public void dequeueInputFrame_beforeConfigure_throwIllegalStateException() {
    Executor wakeupExecutor = directExecutor();
    assertThrows(
        IllegalStateException.class,
        () -> encoderFrameWriter.dequeueInputFrame(wakeupExecutor, () -> {}));

    assertThrows(IllegalStateException.class, () -> encoderFrameWriter.signalEndOfStream());
  }

  @Test
  public void dequeueInputFrame_returnsValidAsyncFrame() {
    encoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    AsyncFrame asyncFrame =
        encoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});

    assertThat(errorException.get()).isNull();
    assertThat(asyncFrame).isNotNull();
    assertThat(asyncFrame.frame).isNotNull();
    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrame.frame;
    assertThat(frame.getHardwareBuffer()).isNotNull();
    assertThat(frame.getFormat()).isEqualTo(createdEncoder.get().getConfigurationFormat());
  }

  @Test
  public void dequeueInputFrame_atCapacity_returnsNullWithoutBlocking() {
    encoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    for (int i = 0; i < CAPACITY; i++) {
      AsyncFrame frame =
          encoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});
      assertThat(frame).isNotNull();
    }

    // The capacity is exhausted. This should instantly return null instead of blocking.
    AsyncFrame nullFrame =
        encoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});

    assertThat(nullFrame).isNull();
    assertThat(errorException.get()).isNull();
  }

  @Test
  public void queueInputFrame_freesCapacityAndCallsWakeupListener() throws Exception {
    encoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    // Exhaust the capacity.
    List<AsyncFrame> dequeuedFrames = new ArrayList<>();
    for (int i = 0; i < CAPACITY; i++) {
      dequeuedFrames.add(
          encoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {}));
    }

    // Try to dequeue one more, which registers the wakeup listener.
    ConditionVariable wakeupCondition = new ConditionVariable();
    AsyncFrame nullFrame =
        encoderFrameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(), wakeupCondition::open);
    assertThat(nullFrame).isNull();

    // Queue the first frame back to the encoder to be processed.
    AsyncFrame frameToQueue = dequeuedFrames.get(0);
    encoderFrameWriter.queueInputFrame(frameToQueue.frame, frameToQueue.acquireFence);

    // Signal EoS to force the encoder to flush and release the buffer.
    encoderFrameWriter.signalEndOfStream();

    // The encoder will process the frame and release the buffer asynchronously,
    // which should trigger the wakeupCondition.
    assertThat(wakeupCondition.block(TEST_TIMEOUT_MS)).isTrue();

    // Now that capacity is freed, we should be able to dequeue a frame again.
    AsyncFrame freedFrame =
        encoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});
    assertThat(freedFrame).isNotNull();
  }

  @Test
  public void signalEndOfStream_notifiesEncoderAndListener() {
    encoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    encoderFrameWriter.signalEndOfStream();

    // Verify the listener received the callback.
    assertThat(endOfStreamSignaled.get()).isTrue();
    assertThat(errorException.get()).isNull();
  }
}
