/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.effect.HardwareBufferFrame.END_OF_STREAM_FRAME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameTimingEvaluator;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor.EosEvent;
import androidx.media3.test.utils.FakeFrameProcessor.Event;
import androidx.media3.test.utils.FakeFrameProcessor.FramesEvent;
import androidx.media3.transformer.CompositionVideoPacketReleaseControl.Listener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CompositionVideoPacketReleaseControl}. */
@RunWith(AndroidJUnit4.class)
public class CompositionVideoPacketReleaseControlTest {

  private CompositionVideoPacketReleaseControl compositionVideoPacketReleaseControl;
  private VideoFrameReleaseControl videoFrameReleaseControl;
  private FakeFrameTimingEvaluator fakeFrameTimingEvaluator;
  private FakeClock fakeClock;
  private FakeFrameProcessor frameProcessor;
  private Set<Long> releasedFrameTimestamps;
  private HardwareBuffer placeholderBuffer;
  // The first packet is required to be sent to the CompositionVideoPacketReleaseControl to
  // initialize the VideoFrameReleaseControl so subsequent behaviour can be tested.
  private ImmutableList<HardwareBufferFrame> firstPacket;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    releasedFrameTimestamps = new HashSet<>();
    placeholderBuffer =
        HardwareBuffer.create(
            /* width= */ 16,
            /* height= */ 16,
            /* format= */ HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            /* usage= */ 0);
    firstPacket = createPacket(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    fakeFrameTimingEvaluator = new FakeFrameTimingEvaluator();
    fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, fakeFrameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setClock(fakeClock);
    frameProcessor = new FakeFrameProcessor.Factory().create(new NoOpFrameWriter());
    compositionVideoPacketReleaseControl =
        new CompositionVideoPacketReleaseControl(
            videoFrameReleaseControl,
            frameProcessor,
            new Listener() {
              @Override
              public void onFrameProcessed() {}

              @Override
              public void onError(Exception e) {
                throw new IllegalStateException(e);
              }
            });
  }

  @After
  public void tearDown() {
    if (placeholderBuffer != null) {
      placeholderBuffer.close();
    }
  }

  @Test
  public void onRender_releaseActionDrop_releasesFrame() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 49_999, /* sequencePresentationTimeUs= */ 49_999);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(packet);
    // Render called at position 100ms, so the frame is >50ms late.
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));
    assertThat(releasedFrameTimestamps).containsExactly(packet.get(0).presentationTimeUs);
  }

  @Test
  public void onRender_releaseActionTryAgainLater_keepsFrame() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 200_000, /* sequencePresentationTimeUs= */ 200_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onRender_releaseActionImmediate_forwardsFrameDownstream()
      throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(
        /* ignoreReleaseTime= */ true, toFramesEvent(firstPacket), toFramesEvent(packet));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onRender_releaseActionSchedule_forwardsFrameDownstreamWithReleaseTime()
      throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 120_000, /* sequencePresentationTimeUs= */ 120_000);
    ImmutableList<HardwareBufferFrame> expectedPacket =
        updatePacketWithReleaseTime(packet, /* releaseTimeNs= */ 120_000_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    // Update the release time of the first packet to match, to verify that scheduled release time
    // is correct.
    FramesEvent firstEvent = (FramesEvent) frameProcessor.getQueuedEvents().get(0);
    DefaultHardwareBufferFrame firstFrame =
        (DefaultHardwareBufferFrame) firstEvent.frames.get(0).frame;
    long releaseTimeNs = (Long) firstFrame.getMetadata().get(Frame.KEY_DISPLAY_TIME_NS);
    ImmutableList<HardwareBufferFrame> expectedFirstFrame =
        updatePacketWithReleaseTime(firstPacket, releaseTimeNs);
    assertOutputEvents(
        /* ignoreReleaseTime= */ false,
        toFramesEvent(expectedFirstFrame),
        toFramesEvent(expectedPacket));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onRender_forwardsFirstFrameImmediately() throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(packet));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queue_backwardSeekWithoutFlush_doesNotReleaseHeldFrames() {
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);

    // Held frames are not released automatically on an implicit backwards seek.
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queueWhenStopped_afterFlush_forwardsFirstFrameAfterFlushImmediately()
      throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 200_000, /* sequencePresentationTimeUs= */ 200_000);
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.onStopped();

    // The first frame is always released immediately, so queue and release it now.
    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    // The next frame after the flush should also be released immediately.
    compositionVideoPacketReleaseControl.flush(/* sequenceIndex= */ 0);
    compositionVideoPacketReleaseControl.queue(packet2);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(
        /* ignoreReleaseTime= */ true, toFramesEvent(packet1), toFramesEvent(packet2));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queueWhenStopped_withoutFlush_doesNotForwardFirstFrame() throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 200_000, /* sequencePresentationTimeUs= */ 200_000);
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.onStopped();

    // The first frame is always released immediately, so queue and release it now.
    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    compositionVideoPacketReleaseControl.queue(packet2);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(packet1));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queue_lowerPresentationTimeButHigherSequenceTime_doesNotFlush() {
    // Simulate a frame from the end of the first media item.
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(
            /* presentationTimeUs= */ 2_000_000, /* sequencePresentationTimeUs= */ 2_000_000);
    // Simulate a frame from the start of the second media item.
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 2_033_333);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);

    // Because the sequence presentation time increased, the packet queue should not
    // interpret this as a backward seek, so it shouldn't flush/release the first packet.
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queue_lowerPresentationTimeButHigherSequenceTime_forwardsFrameDownstream()
      throws ExoPlaybackException {
    // Simulate a frame from the end of the first media item.
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    // Simulate a frame from the start of the second media item.
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 220_000);
    ImmutableList<HardwareBufferFrame> expectedPacket1 =
        updatePacketWithReleaseTime(packet1, /* releaseTimeNs= */ 120_000_000);
    ImmutableList<HardwareBufferFrame> expectedPacket2 =
        updatePacketWithReleaseTime(packet2, /* releaseTimeNs= */ 220_000_000);
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));
    fakeClock.advanceTime(/* timeDiffMs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(
        /* ignoreReleaseTime= */ true, toFramesEvent(firstPacket), toFramesEvent(expectedPacket1));
    fakeClock.advanceTime(/* timeDiffMs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet2);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 220_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputEvents(
        /* ignoreReleaseTime= */ true,
        toFramesEvent(firstPacket),
        toFramesEvent(expectedPacket1),
        toFramesEvent(expectedPacket2));
  }

  @Test
  public void flushPrimarySequence_releasesHeldFramesAndResetsReleaseControl()
      throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));
    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();

    compositionVideoPacketReleaseControl.flush(/* sequenceIndex= */ 0);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isFalse();
    assertThat(releasedFrameTimestamps)
        .containsExactly(packet1.get(0).presentationTimeUs, packet2.get(0).presentationTimeUs);
  }

  @Test
  public void flushSecondarySequence_isIgnored() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet1 =
        createPacket(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    ImmutableList<HardwareBufferFrame> packet2 =
        createPacket(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));
    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();

    compositionVideoPacketReleaseControl.flush(/* sequenceIndex= */ 1);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void isEnded_initially_returnsFalse() {
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
  }

  @Test
  public void queue_eosPacket_doesNotIsEndedWhenOnRenderNotCalled() {
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
  }

  @Test
  public void onRender_eosPacket_setsIsEndedTrue() throws Exception {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputEvents(/* ignoreReleaseTime= */ true, new EosEvent());
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onRender_eosPacketAfterFrames_setsIsEndedTrue() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputEvents(
        /* ignoreReleaseTime= */ true,
        toFramesEvent(firstPacket),
        toFramesEvent(packet),
        new EosEvent());
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onRender_eosPacketBeforeFrames_doesNotSetIsEndedTrue() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputEvents(/* ignoreReleaseTime= */ true, toFramesEvent(firstPacket));

    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
    assertOutputEvents(
        /* ignoreReleaseTime= */ true, toFramesEvent(firstPacket), toFramesEvent(packet));
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onStarted_afterEos_doesNotFlushIsEndedToFalse() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.onStarted();

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputEvents(/* ignoreReleaseTime= */ true, new EosEvent());
  }

  @Test
  public void flushPrimarySequence_afterEos_resetsIsEndedToFalse() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.flush(/* sequenceIndex= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
    assertOutputEvents(/* ignoreReleaseTime= */ true, new EosEvent());
  }

  @Test
  public void resetSecondarySequence_afterEos_doesNotFlushIsEndedToFalse()
      throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.flush(/* sequenceIndex= */ 1);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputEvents(/* ignoreReleaseTime= */ true, new EosEvent());
  }

  @Test
  public void onRender_emptyQueueWhenEnded_isEndedRemainsTrue() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 10_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime() + 10_000),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputEvents(/* ignoreReleaseTime= */ true, new EosEvent());
  }

  @Test
  public void onFrameProcessed_completionListenerCalled_releasesMatchingFrame() throws Exception {
    compositionVideoPacketReleaseControl.onStarted();
    ImmutableList<HardwareBufferFrame> packet =
        createPacket(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(frameProcessor.lastFrames).hasSize(1);
    AsyncFrame asyncFrame = frameProcessor.lastFrames.get(0);
    assertThat(releasedFrameTimestamps).isEmpty();

    checkNotNull(frameProcessor.lastCompletionListener)
        .onFrameProcessed(asyncFrame.frame, /* onCompleteFence= */ null);

    assertThat(releasedFrameTimestamps).containsExactly(100_000L);
  }

  private ImmutableList<HardwareBufferFrame> createPacket(
      long presentationTimeUs, long sequencePresentationTimeUs) {
    HardwareBufferFrame hardwareBufferFrame =
        new HardwareBufferFrame.Builder(
                checkNotNull(placeholderBuffer),
                directExecutor(),
                (releaseFence) -> releasedFrameTimestamps.add(presentationTimeUs))
            .setPresentationTimeUs(presentationTimeUs)
            .setSequencePresentationTimeUs(sequencePresentationTimeUs)
            .build();
    return ImmutableList.of(hardwareBufferFrame);
  }

  private static FramesEvent toFramesEvent(List<HardwareBufferFrame> packet) {
    ImmutableList.Builder<AsyncFrame> asyncFrameListBuilder = ImmutableList.builder();
    for (HardwareBufferFrame effectFrame : packet) {
      ImmutableMap.Builder<String, Object> metadataBuilder =
          ImmutableMap.<String, Object>builder()
              .put(Frame.KEY_PRESENTATION_TIME_US, effectFrame.presentationTimeUs)
              .put(Frame.KEY_DISPLAY_TIME_NS, effectFrame.releaseTimeNs);
      if (effectFrame.getMetadata() instanceof CompositionFrameMetadata) {
        metadataBuilder.put(
            CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA, effectFrame.getMetadata());
      }

      DefaultHardwareBufferFrame commonFrame =
          new DefaultHardwareBufferFrame.Builder(checkNotNull(effectFrame.hardwareBuffer))
              .setFormat(effectFrame.format)
              .setContentTimeUs(effectFrame.sequencePresentationTimeUs)
              .setMetadata(metadataBuilder.buildOrThrow())
              .setInternalImage(effectFrame.internalFrame)
              .build();
      asyncFrameListBuilder.add(new AsyncFrame(commonFrame, effectFrame.acquireFence));
    }
    return new FramesEvent(asyncFrameListBuilder.build());
  }

  /** Verifies that the {@link #frameProcessor} received the expected {@link Event}. */
  private void assertOutputEvents(boolean ignoreReleaseTime, Event... expectedEvents) {
    ImmutableList<Event> queuedEvents = frameProcessor.getQueuedEvents();
    assertThat(queuedEvents).hasSize(expectedEvents.length);
    for (int i = 0; i < expectedEvents.length; i++) {
      Event receivedEvent = queuedEvents.get(i);
      Event expectedEvent = expectedEvents[i];
      assertThat(receivedEvent).isInstanceOf(expectedEvent.getClass());
      if (expectedEvent instanceof FramesEvent) {
        assertThat(receivedEvent).isInstanceOf(FramesEvent.class);
        List<AsyncFrame> receivedFrames = ((FramesEvent) receivedEvent).frames;
        List<AsyncFrame> expectedFrames = ((FramesEvent) expectedEvent).frames;
        assertThat(receivedFrames).hasSize(expectedFrames.size());
        for (int j = 0; j < receivedFrames.size(); j++) {
          AsyncFrame receivedAsyncFrame = receivedFrames.get(j);
          assertThat(receivedAsyncFrame.frame).isInstanceOf(DefaultHardwareBufferFrame.class);
          DefaultHardwareBufferFrame receivedFrame =
              (DefaultHardwareBufferFrame) receivedAsyncFrame.frame;

          AsyncFrame expectedAsyncFrame = expectedFrames.get(j);
          assertThat(expectedAsyncFrame.frame).isInstanceOf(DefaultHardwareBufferFrame.class);
          DefaultHardwareBufferFrame expectedFrame =
              (DefaultHardwareBufferFrame) expectedAsyncFrame.frame;

          assertFramesEqual(receivedFrame, expectedFrame, ignoreReleaseTime);
        }
      }
    }
  }

  /**
   * Verifies that the fields on the received and expected {@link DefaultHardwareBufferFrame} are
   * equal.
   */
  private static void assertFramesEqual(
      DefaultHardwareBufferFrame received,
      DefaultHardwareBufferFrame expected,
      boolean ignoreReleaseTime) {
    assertThat(received.getContentTimeUs()).isEqualTo(expected.getContentTimeUs());
    assertThat(received.getHardwareBuffer()).isEqualTo(expected.getHardwareBuffer());
    assertThat(received.getFormat()).isEqualTo(expected.getFormat());
    assertThat(received.getInternalImage()).isEqualTo(expected.getInternalImage());

    Long receivedPresentationTimeUs =
        (Long) received.getMetadata().get(Frame.KEY_PRESENTATION_TIME_US);
    Long expectedPresentationTimeUs =
        (Long) expected.getMetadata().get(Frame.KEY_PRESENTATION_TIME_US);
    assertThat(receivedPresentationTimeUs).isEqualTo(expectedPresentationTimeUs);

    if (!ignoreReleaseTime) {
      Long receivedReleaseTimeNs = (Long) received.getMetadata().get(Frame.KEY_DISPLAY_TIME_NS);
      Long expectedReleaseTimeNs = (Long) expected.getMetadata().get(Frame.KEY_DISPLAY_TIME_NS);
      assertThat(receivedReleaseTimeNs).isEqualTo(expectedReleaseTimeNs);
    }
  }

  private static ImmutableList<HardwareBufferFrame> updatePacketWithReleaseTime(
      ImmutableList<HardwareBufferFrame> packet, long releaseTimeNs) {
    ImmutableList.Builder<HardwareBufferFrame> updatedPacketBuilder = new ImmutableList.Builder<>();
    for (HardwareBufferFrame frame : packet) {
      updatedPacketBuilder.add(frame.buildUpon().setReleaseTimeNs(releaseTimeNs).build());
    }
    return updatedPacketBuilder.build();
  }

  /** Empty {@link FrameWriter} implementation the returns no frames and always succeeds. */
  private static final class NoOpFrameWriter implements FrameWriter {
    @Override
    public Info getInfo() {
      return (format, usage) -> true;
    }

    @Override
    public void configure(Format format, @Frame.Usage long usage) {}

    @Override
    @Nullable
    public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
      return null;
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {}

    @Override
    public void signalEndOfStream() {}

    @Override
    public void close() {}
  }

  /**
   * A fake {@link FrameTimingEvaluator} for testing, that by default does not skip or force an
   * early release of frames.
   *
   * <p>Drops frames if they are more than 50ms late.
   */
  private static final class FakeFrameTimingEvaluator implements FrameTimingEvaluator {

    private final long lateThresholdUs;

    private FakeFrameTimingEvaluator() {
      this.lateThresholdUs = -50_000L;
    }

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return false;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return earlyUs < lateThresholdUs;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped) {
      return false;
    }
  }
}
