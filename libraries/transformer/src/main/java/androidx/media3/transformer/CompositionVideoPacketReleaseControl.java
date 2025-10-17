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

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.transformer.SequenceRenderersFactory.CompositionRendererListener;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

// TODO: b/430250432 - This is a placeholder implementation, revisit the threading logic to make it
//  more robust.
/** Computes the release time for each {@linkplain List<GlTextureFrame> packet}. */
/* package */ class CompositionVideoPacketReleaseControl implements CompositionRendererListener {

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final Consumer<List<GlTextureFrame>> downstreamConsumer;
  private final ConcurrentLinkedDeque<ImmutableList<GlTextureFrame>> packetQueue;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;

  /**
   * Creates a new {@link CompositionVideoPacketReleaseControl}.
   *
   * @param downstreamConsumer Receives the {@linkplain List<GlTextureFrame> packet}, with each
   *     {@link GlTextureFrame} having the same {@linkplain GlTextureFrame#getReleaseTimeNs()}
   *     release time}.
   */
  public CompositionVideoPacketReleaseControl(
      VideoFrameReleaseControl videoFrameReleaseControl,
      Consumer<List<GlTextureFrame>> downstreamConsumer) {
    this.videoFrameReleaseControl = videoFrameReleaseControl;
    this.downstreamConsumer = downstreamConsumer;
    packetQueue = new ConcurrentLinkedDeque<>();
    videoFrameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
  }

  /**
   * Queues a {@linkplain List<GlTextureFrame> packet}.
   *
   * <p>Once called, the caller must not modify the {@link GlTextureFrame}s in the packet.
   *
   * <p>Called on the GL thread.
   *
   * @param packet The {@link List<GlTextureFrame>} to queue.
   */
  public void queue(List<GlTextureFrame> packet) {
    checkArgument(!packet.isEmpty());
    // The VideoFrameReleaseControl cannot currently handle a packet being queued in the past,
    // manually release all frames to handle this discontinuity.
    // TODO: b/430250432 - There is still a race condition in this check that could result in an
    //  extra dropped frame on a seek backwards, update VideoFrameReleaseControl to handle this
    //  case, or handle queueFrame and onRender on a single internal thread to fix this.
    @Nullable ImmutableList<GlTextureFrame> nextRenderedFrames = packetQueue.peek();
    if (nextRenderedFrames != null
        && packet.get(0).presentationTimeUs < nextRenderedFrames.get(0).presentationTimeUs) {
      reset();
    }
    packetQueue.add(ImmutableList.copyOf(packet));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Computes the release action and release time of queued {@linkplain List<GlTextureFrame>
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
    @Nullable ImmutableList<GlTextureFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      checkState(!packet.isEmpty());
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

  /**
   * Called when rendering starts.
   *
   * <p>Called on the playback thread.
   */
  public void onStarted() {
    videoFrameReleaseControl.onStarted();
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
   * {@linkplain GlTextureFrame#release() Releases} all frames that have not been sent downstream,
   * and {@link VideoFrameReleaseControl#reset() resets} the release control.
   */
  public void reset() {
    @Nullable ImmutableList<GlTextureFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      releasePacket(packet);
    }
    videoFrameReleaseControl.reset();
  }

  // TODO: b/430250432 - Make this work without setting the output Surface.
  /**
   * Called when the display surface changed.
   *
   * <p>Called on the playback thread.
   */
  public void setOutputSurface(@Nullable Surface outputSurface) {
    videoFrameReleaseControl.setOutputSurface(outputSurface);
  }

  /**
   * Determines how the {@link GlTextureFrame} should be handled given the release action.
   *
   * @param frameReleaseAction The release action for this frame.
   * @param packet The {@link ImmutableList<GlTextureFrame>} to send downstream.
   * @return {@code true} if the {@link GlTextureFrame} should be removed from the internal {@link
   *     #packetQueue}.
   */
  private boolean maybeQueuePacketDownstream(
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction,
      ImmutableList<GlTextureFrame> packet) {
    switch (frameReleaseAction) {
      case VideoFrameReleaseControl.FRAME_RELEASE_SKIP:
      case VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER:
      case VideoFrameReleaseControl.FRAME_RELEASE_IGNORE:
        return false;
      case VideoFrameReleaseControl.FRAME_RELEASE_DROP:
        releasePacket(packet);
        return true;
      case VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY:
        setReleaseTimeAndQueueDownstream(
            packet, /* releaseTimeNs= */ SystemClock.DEFAULT.nanoTime());
        return true;
      case VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED:
        setReleaseTimeAndQueueDownstream(
            packet, /* releaseTimeNs= */ videoFrameReleaseInfo.getReleaseTimeNs());
        return true;
      default:
        throw new IllegalStateException(String.valueOf(frameReleaseAction));
    }
  }

  private void releasePacket(ImmutableList<GlTextureFrame> packet) {
    for (int i = 0; i < packet.size(); i++) {
      packet.get(i).release();
    }
  }

  /**
   * Updates the release time of all {@link GlTextureFrame}s and forwards them to {@link
   * #downstreamConsumer}.
   *
   * <p>The {@code downstreamConsumer} is responsible for releasing the {@linkplain
   * ImmutableList<GlTextureFrame> packet}.
   *
   * @param packet The {@link ImmutableList<GlTextureFrame>} to send downstream.
   * @param releaseTimeNs The time the {@link GlTextureFrame} should be rendered on screen.
   */
  private void setReleaseTimeAndQueueDownstream(
      ImmutableList<GlTextureFrame> packet, long releaseTimeNs) {
    ImmutableList.Builder<GlTextureFrame> framesWithReleaseTimeBuilder = ImmutableList.builder();
    for (int i = 0; i < packet.size(); i++) {
      framesWithReleaseTimeBuilder.add(updateReleaseTime(packet.get(i), releaseTimeNs));
    }
    downstreamConsumer.accept(framesWithReleaseTimeBuilder.build());
  }

  private static GlTextureFrame updateReleaseTime(GlTextureFrame frame, long releaseTimeNs) {
    // This method only modifies the frame metadata, the downstream consumer is still responsible
    // for releasing the frame.
    return new GlTextureFrame.Builder(
            frame.glTextureInfo, frame.releaseTextureExecutor, frame.releaseTextureCallback)
        .setPresentationTimeUs(frame.presentationTimeUs)
        .setReleaseTimeNs(releaseTimeNs)
        .setMetadata(frame.getMetadata())
        .build();
  }
}
