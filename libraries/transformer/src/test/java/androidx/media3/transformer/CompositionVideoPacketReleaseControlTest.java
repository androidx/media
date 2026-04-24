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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameTimingEvaluator;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.RecordingPacketConsumer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
  private RecordingPacketConsumer<ImmutableList<HardwareBufferFrame>> outputConsumer;
  private Set<Long> releasedFrameTimestamps;
  // The first packet is required to be sent to the CompositionVideoPacketReleaseControl to
  // initialize the VideoFrameReleaseControl so subsequent behaviour can be tested.
  private ImmutableList<HardwareBufferFrame> firstPacket;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    releasedFrameTimestamps = new HashSet<>();
    firstPacket = createPacket(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    fakeFrameTimingEvaluator = new FakeFrameTimingEvaluator();
    fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, fakeFrameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setClock(fakeClock);
    outputConsumer = new RecordingPacketConsumer<>();
    compositionVideoPacketReleaseControl =
        new CompositionVideoPacketReleaseControl(
            videoFrameReleaseControl,
            outputConsumer,
            exception -> {
              throw new IllegalStateException(exception);
            });
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(packet);
    // Render called at position 100ms, so the frame is >50ms late.
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket, packet);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    // Update the release time of the first packet to match, to verify that scheduled release time
    // is correct.
    ImmutableList<HardwareBufferFrame> expectedFirstFrame =
        updatePacketWithReleaseTime(
            firstPacket, outputConsumer.getQueuedPayloads().get(0).get(0).releaseTimeNs);
    assertOutputPackets(/* ignoreReleaseTime= */ false, expectedFirstFrame, expectedPacket);
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

    assertOutputPackets(/* ignoreReleaseTime= */ true, packet);
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

    assertOutputPackets(/* ignoreReleaseTime= */ true, packet1, packet2);
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

    assertOutputPackets(/* ignoreReleaseTime= */ true, packet1);
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void queue_lowerPresentationTimeButHigherSequenceTime_doesNotReset() {
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket, expectedPacket1);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet2);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 220_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(
        /* ignoreReleaseTime= */ true, firstPacket, expectedPacket1, expectedPacket2);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket, packet);
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
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);

    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100_000,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket, packet);
    assertThat(releasedFrameTimestamps).isEmpty();
  }

  @Test
  public void onStarted_afterEos_doesNotResetIsEndedToFalse() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.onStarted();
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.onStarted();

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();
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
  }

  @Test
  public void flushSecondarySequence_afterEos_doesNotResetIsEndedToFalse()
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
  }

  private ImmutableList<HardwareBufferFrame> createPacket(
      long presentationTimeUs, long sequencePresentationTimeUs) {
    HardwareBufferFrame hardwareBufferFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releasedFrameTimestamps.add(presentationTimeUs))
            .setPresentationTimeUs(presentationTimeUs)
            .setSequencePresentationTimeUs(sequencePresentationTimeUs)
            .setInternalFrame(presentationTimeUs)
            .build();
    return ImmutableList.of(hardwareBufferFrame);
  }

  @SafeVarargs
  private final void assertOutputPackets(
      boolean ignoreReleaseTime, List<HardwareBufferFrame>... expectedPackets) {
    List<ImmutableList<HardwareBufferFrame>> outputPackets = outputConsumer.getQueuedPayloads();
    assertThat(outputPackets).hasSize(expectedPackets.length);
    for (int i = 0; i < expectedPackets.length; i++) {
      List<HardwareBufferFrame> receivedFrames = outputPackets.get(i);
      List<HardwareBufferFrame> expectedFrames = expectedPackets[i];
      assertThat(receivedFrames).hasSize(expectedFrames.size());
      for (int j = 0; j < receivedFrames.size(); j++) {
        HardwareBufferFrame receivedFrame = receivedFrames.get(j);
        HardwareBufferFrame expectedFrame = expectedFrames.get(j);
        assertThat(receivedFrame.presentationTimeUs).isEqualTo(expectedFrame.presentationTimeUs);
        if (!ignoreReleaseTime) {
          assertThat(receivedFrame.releaseTimeNs).isEqualTo(expectedFrame.releaseTimeNs);
        }
        assertThat(receivedFrame.internalFrame).isEqualTo(expectedFrame.internalFrame);
      }
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
        boolean treatDroppedBuffersAsSkipped)
        throws ExoPlaybackException {
      return false;
    }
  }
}
