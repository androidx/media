/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.PacketConsumer;
import androidx.media3.effect.PacketConsumer.Packet;
import androidx.media3.effect.PacketConsumerCaller;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.transformer.SequenceRenderersFactory.CompositionRendererListener;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;

// TODO: b/449956936 - This is a placeholder implementation, revisit the threading logic to make it
//  more robust.
/** Computes the release time for each {@linkplain List<HardwareBufferFrame> packet}. */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
/* package */ class CompositionVideoPacketReleaseControl implements CompositionRendererListener {

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final PacketConsumerCaller<List<HardwareBufferFrame>> downstreamConsumer;
  private final ConcurrentLinkedDeque<ImmutableList<HardwareBufferFrame>> packetQueue;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;
  private boolean isEnded;

  /**
   * Creates a new {@link CompositionVideoPacketReleaseControl}.
   *
   * @param downstreamConsumer Receives the {@linkplain List<HardwareBufferFrame> packet}, with each
   *     {@link HardwareBufferFrame} having the same {@linkplain HardwareBufferFrame#releaseTimeNs}
   *     release time}.
   */
  public CompositionVideoPacketReleaseControl(
      VideoFrameReleaseControl videoFrameReleaseControl,
      PacketConsumer<List<HardwareBufferFrame>> downstreamConsumer,
      ExecutorService glExecutorService,
      Consumer<Exception> exceptionConsumer) {
    videoFrameReleaseControl.setRequiresOutputSurface(false);
    this.videoFrameReleaseControl = videoFrameReleaseControl;
    this.downstreamConsumer =
        PacketConsumerCaller.create(downstreamConsumer, glExecutorService, exceptionConsumer);
    this.downstreamConsumer.run();
    packetQueue = new ConcurrentLinkedDeque<>();
    videoFrameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
  }

  /**
   * Queues a {@linkplain List<HardwareBufferFrame> packet}.
   *
   * <p>Once called, the caller must not modify the {@link HardwareBufferFrame}s in the packet.
   *
   * <p>Called on the GL thread.
   *
   * @param packet The {@link List<HardwareBufferFrame>} to queue.
   */
  public void queue(List<HardwareBufferFrame> packet) {
    checkArgument(!packet.isEmpty());
    if (!packet.get(0).equals(HardwareBufferFrame.END_OF_STREAM_FRAME)) {
      // The VideoFrameReleaseControl cannot currently handle a packet being queued in the past,
      // manually release all frames to handle this discontinuity.
      // TODO: b/449956936 - There is still a race condition in this check that could result in an
      //  extra dropped frame on a seek backwards, update VideoFrameReleaseControl to handle this
      //  case, or handle queueFrame and onRender on a single internal thread to fix this.
      @Nullable ImmutableList<HardwareBufferFrame> nextRenderedFrames = packetQueue.peek();
      if (nextRenderedFrames != null
          && packet.get(0).presentationTimeUs < nextRenderedFrames.get(0).presentationTimeUs) {
        reset();
      }
    }
    packetQueue.add(ImmutableList.copyOf(packet));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Computes the release action and release time of queued {@linkplain List<HardwareBufferFrame>
   * packets}, forwards them {@linkplain #downstreamConsumer downstream} if applicable or drops
   * them. Continues until a packet should be held until a later {@code positionUs}.
   *
   * <p>Called on the playback thread.
   */
  @Override
  public void onRender(
      long compositionTimePositionUs,
      long elapsedRealtimeUs,
      long compositionTimeOutputStreamStartPositionUs)
      throws ExoPlaybackException {
    // Remove packet from the packet queue to ensure frames are not simultaneously released by
    // queueFrame and forwarded downstream.
    @Nullable ImmutableList<HardwareBufferFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      checkState(!packet.isEmpty());
      if (packet.get(0).equals(HardwareBufferFrame.END_OF_STREAM_FRAME)) {
        if (packetQueue.peek() == null) {
          isEnded = true;
          // TODO: b/449956776 - Propagate EOS signal.
          return;
        }
        // Ignore EOS frames if there are more frames to be rendered.
        continue;
      }
      long presentationTimeUs = checkNotNull(packet).get(0).presentationTimeUs;
      @VideoFrameReleaseControl.FrameReleaseAction
      int frameReleaseAction =
          videoFrameReleaseControl.getFrameReleaseAction(
              presentationTimeUs,
              compositionTimePositionUs,
              elapsedRealtimeUs,
              compositionTimeOutputStreamStartPositionUs,
              /* isDecodeOnlyFrame= */ false,
              /* isLastFrame= */ false,
              videoFrameReleaseInfo);
      if (!maybeQueuePacketDownstream(frameReleaseAction, packet)) {
        packetQueue.addFirst(packet);
        return;
      }
      videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    }
  }

  @Override
  public boolean isEnded() {
    return isEnded;
  }

  /**
   * Called when rendering starts.
   *
   * <p>Called on the playback thread.
   */
  public void onStarted() {
    videoFrameReleaseControl.onStarted();
    isEnded = false;
  }

  /**
   * Called when rendering stops.
   *
   * <p>Called on the playback thread.
   */
  public void onStopped() {
    videoFrameReleaseControl.onStopped();
  }

  /**
   * {@linkplain HardwareBufferFrame#release Releases} all frames that have not been sent
   * downstream, and {@link VideoFrameReleaseControl#reset() resets} the release control.
   */
  public void reset() {
    @Nullable ImmutableList<HardwareBufferFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      releasePacket(packet);
    }
    videoFrameReleaseControl.reset();
    isEnded = false;
  }

  /**
   * Determines how the {@link HardwareBufferFrame} should be handled given the release action.
   *
   * @param frameReleaseAction The release action for this frame.
   * @param packet The {@link ImmutableList<HardwareBufferFrame>} to send downstream.
   * @return {@code true} if the {@link HardwareBufferFrame} should be removed from the internal
   *     {@link #packetQueue}.
   */
  private boolean maybeQueuePacketDownstream(
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction,
      ImmutableList<HardwareBufferFrame> packet) {
    switch (frameReleaseAction) {
      case VideoFrameReleaseControl.FRAME_RELEASE_SKIP:
      case VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER:
      case VideoFrameReleaseControl.FRAME_RELEASE_IGNORE:
        return false;
      case VideoFrameReleaseControl.FRAME_RELEASE_DROP:
        releasePacket(packet);
        return true;
      case VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY:
        return setReleaseTimeAndQueueDownstream(
            packet, /* releaseTimeNs= */ SystemClock.DEFAULT.nanoTime());
      case VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED:
        return setReleaseTimeAndQueueDownstream(
            packet, /* releaseTimeNs= */ videoFrameReleaseInfo.getReleaseTimeNs());
      default:
        throw new IllegalStateException(String.valueOf(frameReleaseAction));
    }
  }

  private void releasePacket(ImmutableList<HardwareBufferFrame> packet) {
    for (int i = 0; i < packet.size(); i++) {
      packet.get(i).release(/* releaseFence= */ null);
    }
  }

  /**
   * Updates the release time of all {@link HardwareBufferFrame}s and forwards them to a downstream
   * consumer of {@link GlTextureFrame}.
   *
   * <p>The downstream consumer is responsible for releasing the {@link GlTextureFrame} packet.
   *
   * @param packet The list of {@link HardwareBufferFrame} to send downstream.
   * @param releaseTimeNs The time the packet should be rendered on screen.
   * @return Whether the frame was queued downstream.
   */
  private boolean setReleaseTimeAndQueueDownstream(
      ImmutableList<HardwareBufferFrame> packet, long releaseTimeNs) {
    ImmutableList.Builder<HardwareBufferFrame> framesWithReleaseTimeBuilder =
        ImmutableList.builder();
    for (int i = 0; i < packet.size(); i++) {
      framesWithReleaseTimeBuilder.add(updateReleaseTime(packet.get(i), releaseTimeNs));
    }
    return downstreamConsumer.tryQueuePacket(Packet.of(framesWithReleaseTimeBuilder.build()));
  }

  private static HardwareBufferFrame updateReleaseTime(
      HardwareBufferFrame frame, long releaseTimeNs) {
    return frame.buildUpon().setReleaseTimeNs(releaseTimeNs).build();
  }
}
