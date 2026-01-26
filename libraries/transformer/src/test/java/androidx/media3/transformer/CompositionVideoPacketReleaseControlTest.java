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
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

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
  private RecordingPacketConsumer<List<HardwareBufferFrame>> outputConsumer;
  private Set<Long> releasedFrameTimestamps;
  // The first packet is required to be sent to the CompositionVideoPacketReleaseControl to
  // initialize the VideoFrameReleaseControl so subsequent behaviour can be tested.
  private ImmutableList<HardwareBufferFrame> firstPacket;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    releasedFrameTimestamps = new HashSet<>();
    firstPacket = createPacket(/* presentationTimeUs= */ 0);
    fakeFrameTimingEvaluator = new FakeFrameTimingEvaluator();
    fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, fakeFrameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setClock(fakeClock);
    videoFrameReleaseControl.onStarted();
    outputConsumer = new RecordingPacketConsumer<>();
    compositionVideoPacketReleaseControl =
        new CompositionVideoPacketReleaseControl(
            videoFrameReleaseControl,
            outputConsumer,
            newDirectExecutorService(),
            exception -> {
              throw new IllegalStateException(exception);
            });
  }

  @Test
  public void onRender_releaseActionDrop_releasesFrame() throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 50_000);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
    fakeFrameTimingEvaluator.shouldDropFrame = true;

    compositionVideoPacketReleaseControl.queue(packet);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 100,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);

    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
    assertThat(releasedFrameTimestamps).containsExactly(packet.get(0).presentationTimeUs);
  }

  @Test
  public void onRender_releaseActionTryAgainLater_keepsFrame() throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 200_000);
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
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 100_000);
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
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 120_000);
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
  public void queue_backwardSeek_flushesAndReleasesHeldFrames() {
    ImmutableList<HardwareBufferFrame> packet1 = createPacket(/* presentationTimeUs= */ 200);
    ImmutableList<HardwareBufferFrame> packet2 = createPacket(/* presentationTimeUs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);

    assertThat(releasedFrameTimestamps).containsExactly(packet1.get(0).presentationTimeUs);
  }

  @Test
  public void reset_releasesHeldFramesAndResetsReleaseControl() throws ExoPlaybackException {
    ImmutableList<HardwareBufferFrame> packet1 = createPacket(/* presentationTimeUs= */ 100);
    ImmutableList<HardwareBufferFrame> packet2 = createPacket(/* presentationTimeUs= */ 200);
    compositionVideoPacketReleaseControl.queue(firstPacket);
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertOutputPackets(/* ignoreReleaseTime= */ true, firstPacket);
    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();

    compositionVideoPacketReleaseControl.reset();

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isFalse();
    assertThat(releasedFrameTimestamps)
        .containsExactly(packet1.get(0).presentationTimeUs, packet2.get(0).presentationTimeUs);
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
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 100_000);
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
    ImmutableList<HardwareBufferFrame> packet = createPacket(/* presentationTimeUs= */ 100_000);
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
  public void onStarted_afterEos_resetsIsEndedToFalse() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.onStarted();

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
  }

  @Test
  public void reset_afterEos_resetsIsEndedToFalse() throws ExoPlaybackException {
    compositionVideoPacketReleaseControl.queue(ImmutableList.of(END_OF_STREAM_FRAME));
    compositionVideoPacketReleaseControl.onRender(
        /* compositionTimePositionUs= */ 0,
        /* elapsedRealtimeUs= */ msToUs(fakeClock.elapsedRealtime()),
        /* compositionTimeOutputStreamStartPositionUs= */ 0);
    assertThat(compositionVideoPacketReleaseControl.isEnded()).isTrue();

    compositionVideoPacketReleaseControl.reset();

    assertThat(compositionVideoPacketReleaseControl.isEnded()).isFalse();
  }

  @Test
  public void onRender_emptyQueueWhenEnded_isEndedRemainsTrue() throws ExoPlaybackException {
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

  private ImmutableList<HardwareBufferFrame> createPacket(long presentationTimeUs) {
    HardwareBufferFrame hardwareBufferFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                () -> releasedFrameTimestamps.add(presentationTimeUs))
            .setPresentationTimeUs(presentationTimeUs)
            .setInternalFrame(presentationTimeUs)
            .build();
    return ImmutableList.of(hardwareBufferFrame);
  }

  @SafeVarargs
  private final void assertOutputPackets(
      boolean ignoreReleaseTime, List<HardwareBufferFrame>... expectedPackets) {
    List<List<HardwareBufferFrame>> outputPackets = outputConsumer.getQueuedPayloads();
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
   * A fake {@link FrameTimingEvaluator} for testing, that by default does not drop, skip or force
   * an early release of frames.
   */
  private static final class FakeFrameTimingEvaluator implements FrameTimingEvaluator {

    private boolean shouldDropFrame;

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return false;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return shouldDropFrame;
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
