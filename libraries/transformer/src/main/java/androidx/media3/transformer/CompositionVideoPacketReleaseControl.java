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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_FRAME_DISCONTINUITY_NUMBER;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.FixedFrameRateEstimator;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.transformer.SequenceRenderersFactory.CompositionRendererListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO: b/449956936 - This is a placeholder implementation, revisit the threading logic to make it
//  more robust.
/** Computes the release time for each {@linkplain List<HardwareBufferFrame> packet}. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
/* package */ class CompositionVideoPacketReleaseControl
    implements CompositionRendererListener, AutoCloseable {

  private static final String TAG = "CompositionReleaseCtrl";

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final FrameProcessor downstreamFrameProcessor;
  // Accessed on the playback thread only.
  private final ArrayDeque<ImmutableList<HardwareBufferFrame>> packetQueue;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;
  private final FixedFrameRateEstimator frameRateEstimator;
  private volatile boolean isEnded;
  private final Listener listener;
  // Accessed on the playback thread only.
  private int currentStreamDiscontinuityNumber;
  private final Object lock;

  @GuardedBy("lock")
  private final Map<Frame, HardwareBufferFrame> inFlightFrames;

  @Nullable private ImmutableList<HardwareBufferFrame> lastQueuedPacket;

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
    packetQueue = new ArrayDeque<>();
    videoFrameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
    lock = new Object();
    inFlightFrames = new HashMap<>();
    // Allow the first frame to be rendered before playback starts.
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
  }

  /**
   * Queues a {@linkplain List<HardwareBufferFrame> packet}.
   *
   * <p>Once called, the caller must not modify the {@link HardwareBufferFrame}s in the packet.
   *
   * <p>Called on the playback thread.
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
    currentStreamDiscontinuityNumber++;
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

  /** Called when a frame has been fully processed by the downstream {@link FrameProcessor}. */
  public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
    @Nullable HardwareBufferFrame hardwareBufferFrame;
    synchronized (lock) {
      hardwareBufferFrame = inFlightFrames.remove(frame);
    }
    if (hardwareBufferFrame != null) {
      hardwareBufferFrame.release(releaseFence);
    } else {
      if (releaseFence != null) {
        releaseFence.close();
      }
      Log.d(TAG, "onFrameProcessed: Frame not found: " + frame);
    }
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
    releaseRetainedFrames();
    videoFrameReleaseControl.reset();
    isEnded = false;
  }

  /**
   * Redraws the last released frame packet immediately, bypassing playback clock scheduling. Has no
   * effect if no frame has been queued downstream yet.
   *
   * <p>This method must be called on the playback thread.
   */
  public void redraw() {
    // TODO: b/517020679 - Add androidTest after integrating FrameProcessor.
    if (lastQueuedPacket == null) {
      return;
    }
    boolean unused =
        setReleaseTimeAndQueueDownstream(
            lastQueuedPacket, /* releaseTimeNs= */ SystemClock.DEFAULT.nanoTime());
  }

  @Override
  public void close() {
    releaseRetainedFrames();
  }

  /** Releases any resources held by this release control. */
  private void releaseRetainedFrames() {
    ImmutableList<HardwareBufferFrame> framesToRelease;
    synchronized (lock) {
      // Copy frames to release them without holding the lock.
      framesToRelease = ImmutableList.copyOf(inFlightFrames.values());
      inFlightFrames.clear();
    }
    releasePacket(framesToRelease);
    releasePacket(lastQueuedPacket);
    lastQueuedPacket = null;
  }

  private void updateLastQueuedPacket(ImmutableList<HardwareBufferFrame> newlyQueuedPacket) {
    ImmutableList<HardwareBufferFrame> lastQueuedPacket = this.lastQueuedPacket;
    // The newlyQueuedPacket is retained so that it's kept alive for replays.
    this.lastQueuedPacket = retainFrames(newlyQueuedPacket);
    // When replaying, the newly queued packet is the same as the last queued packet. When queueing
    // the same packet again to the downstream, inflightFrames adds mappings that point to the same
    // frames again. We thus need to skip releasing those frames, or the refCount of the frame would
    // drop to zero and the frame resource is released.
    if (newlyQueuedPacket != lastQueuedPacket) {
      releasePacket(lastQueuedPacket);
    }
  }

  private ImmutableList<HardwareBufferFrame> retainFrames(
      ImmutableList<HardwareBufferFrame> newlyQueuedPacket) {
    ImmutableList.Builder<HardwareBufferFrame> retainedFrames = new ImmutableList.Builder<>();
    for (int i = 0; i < newlyQueuedPacket.size(); i++) {
      HardwareBufferFrame hardwareBufferFrame = newlyQueuedPacket.get(i);
      retainedFrames.add(hardwareBufferFrame.retain());
    }
    return retainedFrames.build();
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
    Map<Frame, HardwareBufferFrame> pendingInFlightFrames = new HashMap<>();
    for (int i = 0; i < packet.size(); i++) {
      HardwareBufferFrame effectFrame = packet.get(i);
      ImmutableMap.Builder<String, Object> metadataBuilder =
          ImmutableMap.<String, Object>builder()
              .put(Frame.KEY_PRESENTATION_TIME_US, effectFrame.presentationTimeUs)
              .put(Frame.KEY_DISPLAY_TIME_NS, releaseTimeNs);
      if (effectFrame.getMetadata() instanceof CompositionFrameMetadata) {
        CompositionFrameMetadata frameMetadata =
            (CompositionFrameMetadata) effectFrame.getMetadata();
        metadataBuilder.put(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA, frameMetadata);
      }
      metadataBuilder.put(KEY_FRAME_DISCONTINUITY_NUMBER, currentStreamDiscontinuityNumber);
      DefaultHardwareBufferFrame commonFrame =
          new DefaultHardwareBufferFrame.Builder(checkNotNull(effectFrame.hardwareBuffer))
              .setFormat(effectFrame.format)
              .setContentTimeUs(effectFrame.sequencePresentationTimeUs)
              .setMetadata(metadataBuilder.buildOrThrow())
              .setInternalImage(effectFrame.internalFrame)
              .build();

      asyncFrameListBuilder.add(new AsyncFrame(commonFrame, effectFrame.acquireFence));
      pendingInFlightFrames.put(commonFrame, effectFrame);
    }

    boolean queued = downstreamFrameProcessor.queue(asyncFrameListBuilder.build());
    if (queued) {
      synchronized (lock) {
        inFlightFrames.putAll(pendingInFlightFrames);
      }
      updateLastQueuedPacket(packet);
    }
    return queued;
  }

  private static void releasePacket(@Nullable ImmutableList<HardwareBufferFrame> packet) {
    if (packet == null) {
      return;
    }
    for (int i = 0; i < packet.size(); i++) {
      packet.get(i).release(/* releaseFence= */ null);
    }
  }
}
