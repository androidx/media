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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeFrameWriter;
import androidx.media3.test.utils.FakeHardwareBufferNativeHelpers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Robolectric unit tests for {@link EnhancementSessionDecorator}. */
@RunWith(AndroidJUnit4.class)
public final class EnhancementSessionDecoratorTest {

  private static final int WIDTH = 128;
  private static final int HEIGHT = 128;

  private FakeFrameProcessor.Factory fakeBaseProcessorFactory;
  private FakeFrameWriter fakeDownstreamOutput;
  private FakeFrameProcessor.Listener testListener;
  private EnhancementSessionTestUtil.FakeEnhancementSession fakeSession;
  private EnhancementSessionTestUtil.FakeClient fakeClient;
  private FrameProcessor decorator;

  @Before
  public void setUp() {
    fakeBaseProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
    fakeDownstreamOutput = new FakeFrameWriter();
    testListener = new FakeFrameProcessor.Listener();
    fakeSession = new EnhancementSessionTestUtil.FakeEnhancementSession();
    fakeClient = new EnhancementSessionTestUtil.FakeClient(fakeSession);
    fakeClient.autoTriggerSessionCallback = false;

    decorator =
        new EnhancementSessionDecorator.Builder(getApplicationContext(), fakeBaseProcessorFactory)
            .setTonemappingEnabled(true)
            .setLooper(Looper.getMainLooper())
            .setSessionManagerClient(fakeClient)
            .setHardwareBufferNativeHelpers(new FakeHardwareBufferNativeHelpers())
            .build()
            .create(fakeDownstreamOutput, directExecutor(), testListener);
  }

  @After
  public void tearDown() {
    if (decorator != null) {
      decorator.close();
    }
  }

  @Test
  public void queue_emptyPacket_forwardsDirectlyToBaseProcessor() {
    ImmutableList<AsyncFrame> frames = ImmutableList.of();

    boolean result = decorator.queue(frames);

    assertThat(result).isTrue();
    assertThat(fakeBaseProcessorFactory.createdProcessor.queuedFramesCount).isEqualTo(1);
  }

  @Test
  public void configure_validFormat_triggersSessionInitialization() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();

    fakeBaseProcessorFactory.createdProcessor.output.configure(
        format, Frame.USAGE_GPU_COLOR_OUTPUT);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(fakeClient.createSessionCalled.get()).isTrue();
    assertThat(fakeClient.lastOptions).isNotNull();
    assertThat(fakeClient.lastOptions.getWidth()).isEqualTo(WIDTH);
    assertThat(fakeClient.lastOptions.getHeight()).isEqualTo(HEIGHT);
    assertThat(fakeClient.lastOptions.isTonemappingEnabled()).isTrue();
    assertThat(fakeClient.lastOptions.isDeblurAndDenoiseVideoEnabled()).isFalse();
    assertThat(fakeClient.lastOptions.isUpscaleVideoEnabled()).isFalse();
  }

  @Test
  public void close_beforeSessionCreated_releasesSessionWhenCreated() {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    fakeBaseProcessorFactory.createdProcessor.output.configure(
        format, Frame.USAGE_GPU_COLOR_OUTPUT);
    shadowOf(Looper.getMainLooper()).idle();

    decorator.close();
    fakeClient.savedSessionCallback.onSessionCreated(fakeSession);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(fakeSession.isReleased.get()).isTrue();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void configure_whenSessionManagerReportsError_notifiesListener() {
    fakeClient.deviceSupportedTask =
        Tasks.forException(new VideoFrameProcessingException("Session initialization failed"));
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();

    fakeBaseProcessorFactory.createdProcessor.output.configure(
        format, Frame.USAGE_GPU_COLOR_OUTPUT);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Session initialization failed");
  }

  @Test
  public void signalEndOfStream_whenCalled_forwardsToBaseProcessor() {
    decorator.signalEndOfStream();

    assertThat(fakeBaseProcessorFactory.createdProcessor.eosSignaled.get()).isTrue();
  }

  @Test
  public void close_whenCalled_shutsDownBaseProcessor() {
    decorator.close();

    assertThat(fakeBaseProcessorFactory.createdProcessor.closed.get()).isTrue();
  }
}
