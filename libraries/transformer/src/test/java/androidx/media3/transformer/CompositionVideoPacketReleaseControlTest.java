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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameTimingEvaluator;
import androidx.media3.test.utils.FakeClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
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
  private ArrayList<List<GlTextureFrame>> outputPackets;
  private Set<GlTextureInfo> releasedTextures;
  // The first packet is required to be sent to the CompositionVideoPacketReleaseControl to
  // initialize the VideoFrameReleaseControl so subsequent behaviour can be tested.
  private ImmutableList<GlTextureFrame> firstPacket;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    releasedTextures = new HashSet<>();
    outputPackets = new ArrayList<>();
    firstPacket = createPacket(/* presentationTimeUs= */ 0);
    fakeFrameTimingEvaluator = new FakeFrameTimingEvaluator();
    fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context, fakeFrameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setClock(fakeClock);
    videoFrameReleaseControl.setOutputSurface(new Surface(new SurfaceTexture(1)));
    videoFrameReleaseControl.onStarted();
    compositionVideoPacketReleaseControl =
        new CompositionVideoPacketReleaseControl(
            videoFrameReleaseControl, /* downstreamConsumer= */ outputPackets::add);
  }

  @Test
  public void onRender_releaseActionDrop_releasesFrame() throws ExoPlaybackException {
    ImmutableList<GlTextureFrame> packet = createPacket(/* presentationTimeUs= */ 50_000);
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
    assertThat(releasedTextures).containsExactly(packet.get(0).glTextureInfo);
  }

  @Test
  public void onRender_releaseActionTryAgainLater_keepsFrame() throws ExoPlaybackException {
    ImmutableList<GlTextureFrame> packet = createPacket(/* presentationTimeUs= */ 200_000);
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
    assertThat(releasedTextures).isEmpty();
  }

  @Test
  public void onRender_releaseActionImmediate_forwardsFrameDownstream()
      throws ExoPlaybackException {
    ImmutableList<GlTextureFrame> packet = createPacket(/* presentationTimeUs= */ 100_000);
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
    assertThat(releasedTextures).isEmpty();
  }

  @Test
  public void onRender_releaseActionSchedule_forwardsFrameDownstreamWithReleaseTime()
      throws ExoPlaybackException {
    ImmutableList<GlTextureFrame> packet = createPacket(/* presentationTimeUs= */ 120_000);
    ImmutableList<GlTextureFrame> expectedPacket =
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
    ImmutableList<GlTextureFrame> expectedFirstFrame =
        updatePacketWithReleaseTime(firstPacket, outputPackets.get(0).get(0).releaseTimeNs);
    assertOutputPackets(/* ignoreReleaseTime= */ false, expectedFirstFrame, expectedPacket);
    assertThat(releasedTextures).isEmpty();
  }

  @Test
  public void queue_backwardSeek_flushesAndReleasesHeldFrames() {
    ImmutableList<GlTextureFrame> packet1 = createPacket(/* presentationTimeUs= */ 200);
    ImmutableList<GlTextureFrame> packet2 = createPacket(/* presentationTimeUs= */ 100);

    compositionVideoPacketReleaseControl.queue(packet1);
    compositionVideoPacketReleaseControl.queue(packet2);

    assertThat(releasedTextures).containsExactly(packet1.get(0).glTextureInfo);
  }

  @Test
  public void reset_releasesHeldFramesAndResetsReleaseControl() throws ExoPlaybackException {
    ImmutableList<GlTextureFrame> packet1 = createPacket(/* presentationTimeUs= */ 100);
    ImmutableList<GlTextureFrame> packet2 = createPacket(/* presentationTimeUs= */ 200);
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
    assertThat(releasedTextures)
        .containsExactly(packet1.get(0).glTextureInfo, packet2.get(0).glTextureInfo);
  }

  private ImmutableList<GlTextureFrame> createPacket(
      /* presentationTimeUs= */ long presentationTimeUs) {
    GlTextureInfo glTextureInfo = new GlTextureInfo((int) presentationTimeUs, 1, 1, 100, 100);
    GlTextureFrame glFrame =
        new GlTextureFrame.Builder(glTextureInfo, directExecutor(), releasedTextures::add)
            .setPresentationTimeUs(presentationTimeUs)
            .build();
    return ImmutableList.of(glFrame);
  }

  @SafeVarargs
  private final void assertOutputPackets(
      boolean ignoreReleaseTime, List<GlTextureFrame>... expectedPackets) {
    assertThat(outputPackets).hasSize(expectedPackets.length);
    for (int i = 0; i < expectedPackets.length; i++) {
      List<GlTextureFrame> receivedFrames = outputPackets.get(i);
      List<GlTextureFrame> expectedFrames = expectedPackets[i];
      assertThat(receivedFrames).hasSize(expectedFrames.size());
      for (int j = 0; j < receivedFrames.size(); j++) {
        GlTextureFrame receivedFrame = receivedFrames.get(j);
        GlTextureFrame expectedFrame = expectedFrames.get(j);
        assertThat(receivedFrame.presentationTimeUs).isEqualTo(expectedFrame.presentationTimeUs);
        if (!ignoreReleaseTime) {
          assertThat(receivedFrame.releaseTimeNs).isEqualTo(expectedFrame.releaseTimeNs);
        }
        assertThat(receivedFrame.glTextureInfo).isEqualTo(expectedFrame.glTextureInfo);
      }
    }
  }

  private static ImmutableList<GlTextureFrame> updatePacketWithReleaseTime(
      ImmutableList<GlTextureFrame> packet, long releaseTimeNs) {
    ImmutableList.Builder<GlTextureFrame> updatedPacketBuilder = new ImmutableList.Builder<>();
    for (GlTextureFrame frame : packet) {
      updatedPacketBuilder.add(
          new GlTextureFrame.Builder(
                  frame.glTextureInfo, frame.releaseTextureExecutor, frame.releaseTextureCallback)
              .setPresentationTimeUs(frame.presentationTimeUs)
              .setReleaseTimeNs(releaseTimeNs)
              .setMetadata(frame.getMetadata())
              .build());
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
