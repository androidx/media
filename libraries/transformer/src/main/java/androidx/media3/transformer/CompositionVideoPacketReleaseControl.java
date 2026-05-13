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

import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.FixedFrameRateEstimator;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.transformer.SequenceRenderersFactory.CompositionRendererListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

// TODO: b/449956936 - This is a placeholder implementation, revisit the threading logic to make it
//  more robust.
/** Computes the release time for each {@linkplain List<HardwareBufferFrame> packet}. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
/* package */ class CompositionVideoPacketReleaseControl implements CompositionRendererListener {

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final FrameProcessor downstreamFrameProcessor;
  private final ConcurrentLinkedDeque<ImmutableList<HardwareBufferFrame>> packetQueue;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;
  private final FixedFrameRateEstimator frameRateEstimator;
  private volatile boolean isEnded;
  private final Listener listener;

  /** Listener for {@link CompositionVideoPacketReleaseControl} events. */
  public interface Listener {
    /**
     * Called when a frame, or EOS has been sent to the downstream consumer, or a frame is dropped.
     */
    void onFrameProcessed();

    /** Called when an error occurs during packet processing. */
    void onError(Exception e);
  }

  /**
   * Creates a new {@link CompositionVideoPacketReleaseControl}.
   *
   * @param videoFrameReleaseControl Controls when frames are released.
   * @param downstreamFrameProcessor Receives the {@linkplain List<HardwareBufferFrame> packet},
   *     with each {@link HardwareBufferFrame} having the same {@linkplain
   *     HardwareBufferFrame#releaseTimeNs} release time}.
   * @param listener The listener for {@link CompositionVideoPacketReleaseControl} events.
   */
  public CompositionVideoPacketReleaseControl(
      VideoFrameReleaseControl videoFrameReleaseControl,
      FrameProcessor downstreamFrameProcessor,
      Listener listener) {
    videoFrameReleaseControl.setRequiresOutputSurface(false);
    this.videoFrameReleaseControl = videoFrameReleaseControl;
    this.frameRateEstimator =
        new FixedFrameRateEstimator(
            frameRate -> videoFrameReleaseControl.setSurfaceMediaFrameRate(frameRate));
    this.listener = listener;
    this.downstreamFrameProcessor = downstreamFrameProcessor;
    packetQueue = new ConcurrentLinkedDeque<>();
    videoFrameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
    // Allow the first frame to be rendered before playback starts.
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
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
    packetQueue.add(ImmutableList.copyOf(packet));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Computes the release action and release time of queued {@linkplain List<HardwareBufferFrame>
   * packets}, forwards them {@linkplain #downstreamFrameProcessor downstream} if applicable or
   * drops them. Continues until a packet should be held until a later {@code positionUs}.
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
          downstreamFrameProcessor.signalEndOfStream();
          listener.onFrameProcessed();
          return;
        }
        // Ignore EOS frames if there are more frames to be rendered.
        continue;
      }
      long presentationTimeUs = checkNotNull(packet).get(0).sequencePresentationTimeUs;
      frameRateEstimator.onNextFrame(presentationTimeUs * 1000);
      @VideoFrameReleaseControl.FrameReleaseAction
      int frameReleaseAction =
          videoFrameReleaseControl.getFrameReleaseAction(
              presentationTimeUs,
              compositionTimePositionUs,
              elapsedRealtimeUs,
              compositionTimeOutputStreamStartPositionUs,
              /* isDecodeOnlyFrame= */ false,
              /* isLastFrame= */ false,
              frameRateEstimator.getFrameDurationNs(),
              frameRateEstimator.getFrameIndex(),
              videoFrameReleaseInfo);
      if (!maybeQueuePacketDownstream(frameReleaseAction, packet)) {
        packetQueue.addFirst(packet);
        return;
      }
      listener.onFrameProcessed();
      videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    }
  }

  /**
   * {@linkplain HardwareBufferFrame#release Releases} all frames that have not been sent
   * downstream, and {@link VideoFrameReleaseControl#reset() resets} the release control, when the
   * primary sequence is flushed.
   *
   * <p>Called on the playback thread.
   */
  public void flush(int sequenceIndex) {
    // Only reset when the primary sequence is flushed.
    if (sequenceIndex == 0) {
      reset();
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
  private void reset() {
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
    ImmutableList.Builder<AsyncFrame> asyncFrameListBuilder = ImmutableList.builder();
    for (int i = 0; i < packet.size(); i++) {
      HardwareBufferFrame effectFrame = packet.get(i);
      ImmutableMap.Builder<String, Object> metadataBuilder =
          ImmutableMap.<String, Object>builder()
              .put(Frame.KEY_PRESENTATION_TIME_US, effectFrame.presentationTimeUs)
              .put(Frame.KEY_DISPLAY_TIME_NS, releaseTimeNs);
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

    try {
      return downstreamFrameProcessor.queue(
          asyncFrameListBuilder.build(),
          /* listenerExecutor= */ directExecutor(),
          /* wakeupListener= */ () -> {},
          /* completionListener= */ (frame, fence) -> {
            if (frame instanceof DefaultHardwareBufferFrame) {
              DefaultHardwareBufferFrame hbFrame = (DefaultHardwareBufferFrame) frame;
              HardwareBuffer hardwareBuffer = hbFrame.getHardwareBuffer();
              for (int i = 0; i < packet.size(); i++) {
                HardwareBufferFrame effectFrameToRelease = packet.get(i);
                if (effectFrameToRelease.hardwareBuffer == hardwareBuffer) {
                  effectFrameToRelease.release(fence);
                  break;
                }
              }
            }
          });
    } catch (VideoFrameProcessingException e) {
      listener.onError(e);
      return false;
    }
  }
}
