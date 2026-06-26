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

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.transformer.AndroidTestUtil.ForceEncodeEncoderFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android tests for {@link GlEncoderFrameWriter}. Tests hardware surfaces and real MediaCodec
 * instances, which are unsupported on Robolectric.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public final class GlEncoderFrameWriterAndroidTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;
  private static final Format TEST_ENCODING_FORMAT =
      new Format.Builder()
          .setWidth(1280)
          .setHeight(720)
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
          .build();
  private static final int CAPACITY = 4;

  private GlEncoderFrameWriter glEncoderFrameWriter;
  private AtomicReference<Codec> createdEncoder;
  private AtomicBoolean endOfStreamSignaled;
  private AtomicReference<VideoFrameProcessingException> errorException;
  private ConditionVariable eosCondition;
  private ConditionVariable errorCondition;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    ForceEncodeEncoderFactory encoderFactory =
        new ForceEncodeEncoderFactory(
            new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build());

    createdEncoder = new AtomicReference<>();
    endOfStreamSignaled = new AtomicBoolean();
    errorException = new AtomicReference<>();
    eosCondition = new ConditionVariable();
    errorCondition = new ConditionVariable();

    GlEncoderFrameWriter.Listener listener =
        new GlEncoderFrameWriter.Listener() {
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
            eosCondition.open();
          }

          @Override
          public void onError(VideoFrameProcessingException e) {
            errorException.set(e);
            errorCondition.open();
          }
        };

    glEncoderFrameWriter =
        new GlEncoderFrameWriter(
            context,
            encoderFactory,
            listener,
            /* listenerExecutor= */ directExecutor(),
            new DefaultGlObjectsProvider(),
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            HardwareBufferJni.INSTANCE,
            /* logSessionId= */ null);
  }

  @After
  public void tearDown() {
    if (glEncoderFrameWriter != null) {
      glEncoderFrameWriter.close();
    }
  }

  @Test
  public void configure_withImpossibleFormat_invokesErrorListener() throws Exception {
    Format impossibleFormat =
        new Format.Builder()
            .setWidth(1280)
            .setHeight(720)
            .setSampleMimeType("video/invalid")
            .build();

    glEncoderFrameWriter.configure(impossibleFormat, Frame.USAGE_VIDEO_ENCODE);

    if (errorException.get() == null) {
      errorCondition.block(TEST_TIMEOUT_MS);
    }

    assertThat(createdEncoder.get()).isNull();
    assertThat(errorException.get()).isNotNull();
  }

  @Test
  public void dequeueInputFrame_returnsValidAsyncFrame() {
    glEncoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    AsyncFrame asyncFrame =
        glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});

    assertThat(errorException.get()).isNull();
    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrame.frame;
    assertThat(frame.getHardwareBuffer()).isNotNull();
    assertThat(frame.getFormat()).isEqualTo(createdEncoder.get().getConfigurationFormat());
  }

  @Test
  public void dequeueInputFrame_atCapacity_returnsNullWithoutBlocking() {
    glEncoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    for (int i = 0; i < CAPACITY; i++) {
      AsyncFrame frame =
          glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});
      assertThat(frame).isNotNull();
    }

    // The capacity is exhausted. This should instantly return null instead of blocking.
    AsyncFrame nullFrame =
        glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});

    assertThat(nullFrame).isNull();
    assertThat(errorException.get()).isNull();
  }

  @Test
  public void queueInputFrame_withSdrColorInfo_completesWithoutError() throws Exception {
    Format srgbFormat =
        TEST_ENCODING_FORMAT.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();
    glEncoderFrameWriter.configure(srgbFormat, Frame.USAGE_VIDEO_ENCODE);

    AsyncFrame frame =
        glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});
    assertThat(frame).isNotNull();

    glEncoderFrameWriter.queueInputFrame(frame.frame, frame.acquireFence);
    glEncoderFrameWriter.signalEndOfStream();

    assertThat(eosCondition.block(TEST_TIMEOUT_MS)).isTrue();
    assertThat(errorException.get()).isNull();
  }

  @Test
  public void queueInputFrame_freesCapacityAndCallsWakeupListener() throws Exception {
    glEncoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);
    ConditionVariable wakeupCondition = new ConditionVariable();
    List<AsyncFrame> dequeuedFrames = new ArrayList<>();
    for (int i = 0; i < CAPACITY; i++) {
      dequeuedFrames.add(
          glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {}));
    }
    AsyncFrame nullFrame =
        glEncoderFrameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(), wakeupCondition::open);
    assertThat(nullFrame).isNull();

    AsyncFrame frameToQueue = dequeuedFrames.get(0);
    glEncoderFrameWriter.queueInputFrame(frameToQueue.frame, frameToQueue.acquireFence);
    glEncoderFrameWriter.signalEndOfStream();

    assertThat(wakeupCondition.block(TEST_TIMEOUT_MS)).isTrue();

    AsyncFrame freedFrame =
        glEncoderFrameWriter.dequeueInputFrame(/* wakeupExecutor= */ directExecutor(), () -> {});
    assertThat(freedFrame).isNotNull();
  }

  @Test
  public void signalEndOfStream_notifiesEncoderAndListener() throws InterruptedException {
    glEncoderFrameWriter.configure(TEST_ENCODING_FORMAT, Frame.USAGE_VIDEO_ENCODE);

    glEncoderFrameWriter.signalEndOfStream();

    // Verify the listener received the callback.
    assertThat(eosCondition.block(TEST_TIMEOUT_MS)).isTrue();
    assertThat(endOfStreamSignaled.get()).isTrue();
    assertThat(errorException.get()).isNull();
  }
}
