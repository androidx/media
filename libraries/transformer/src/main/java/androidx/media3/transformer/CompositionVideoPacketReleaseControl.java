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
import static androidx.media3.transformer.TransformerUtil.END_OF_STREAM_ASYNC_FRAME;
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
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.video.FixedFrameRateEstimator;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.transformer.SequenceRenderersFactory.CompositionRendererListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// TODO: b/449956936 - This is a placeholder implementation, revisit the threading logic to make it
//  more robust.
/** Computes the release time for each {@linkplain List<AsyncFrame> packet}. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
/* package */ class CompositionVideoPacketReleaseControl
    implements CompositionRendererListener, AutoCloseable {

  private static final String TAG = "CompositionReleaseCtrl";

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final FrameProcessor downstreamFrameProcessor;
  // Accessed on the playback thread only.
  private final ArrayDeque<ImmutableList<AsyncFrame>> packetQueue;
  private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;
  private final FixedFrameRateEstimator frameRateEstimator;
  private volatile boolean isEnded;
  private final Listener listener;
  // Accessed on the playback thread only.
  private int currentStreamDiscontinuityNumber;
  private final Object lock;

  @GuardedBy("lock")
  private final Set<Frame> inFlightFrames;

  @Nullable private ImmutableList<AsyncFrame> lastQueuedPacket;

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
   * @param downstreamFrameProcessor Receives the {@linkplain List<AsyncFrame> packet}, with each
   *     {@link Frame} having the same release time.
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
    inFlightFrames = new HashSet<>();
    // Allow the first frame to be rendered before playback starts.
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
  }

  /**
   * Queues a {@linkplain List<AsyncFrame> packet}.
   *
   * <p>Once called, the caller must not modify the {@linkplain AsyncFrame async frames} in the
   * packet.
   *
   * <p>Called on the playback thread.
   *
   * @param packet The {@link List<AsyncFrame>} to queue.
   */
  public void queue(List<AsyncFrame> packet) {
    checkArgument(!packet.isEmpty());
    packetQueue.add(ImmutableList.copyOf(packet));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Computes the release action and release time of queued {@linkplain List<AsyncFrame>
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
    @Nullable ImmutableList<AsyncFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      checkState(!packet.isEmpty());
      if (packet.get(0) == END_OF_STREAM_ASYNC_FRAME) {
        if (packetQueue.peek() == null) {
          isEnded = true;
          downstreamFrameProcessor.signalEndOfStream();
          listener.onFrameProcessed();
          return;
        }
        // Ignore EOS frames if there are more frames to be rendered.
        continue;
      }
      long presentationTimeUs = checkNotNull(packet).get(0).frame.getContentTimeUs();
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
    }
  }

  /**
   * Releases all frames that have not been sent downstream, and {@link
   * VideoFrameReleaseControl#reset() resets} the release control, when the primary sequence is
   * flushed.
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
    boolean removed;
    synchronized (lock) {
      removed = inFlightFrames.remove(frame);
    }
    if (removed) {
      TransformerUtil.releaseIfNeeded(frame, releaseFence);
    } else {
      if (releaseFence != null) {
        releaseFence.close();
      }
      Log.d(TAG, "onFrameProcessed: Frame not found: " + frame);
    }
  }

  /**
   * Releases all frames that have not been sent downstream, and {@link
   * VideoFrameReleaseControl#reset() resets} the release control.
   *
   * <p>This method does not release the frames in {@link #inFlightFrames} which have been sent
   * downstream. The {@link #downstreamFrameProcessor} is responsible for releasing in flight
   * frames.
   */
  private void reset() {
    @Nullable ImmutableList<AsyncFrame> packet;
    while ((packet = packetQueue.poll()) != null) {
      releasePacket(packet);
    }
    releasePacket(lastQueuedPacket);
    lastQueuedPacket = null;
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
    List<Frame> framesToRelease;
    synchronized (lock) {
      // Copy frames to release them without holding the lock.
      framesToRelease = new ArrayList<>(inFlightFrames);
      inFlightFrames.clear();
    }
    TransformerUtil.releaseIfNeeded(framesToRelease);
    releasePacket(lastQueuedPacket);
    lastQueuedPacket = null;
  }

  private void updateLastQueuedPacket(ImmutableList<AsyncFrame> newlyQueuedPacket) {
    ImmutableList<AsyncFrame> lastQueuedPacket = this.lastQueuedPacket;
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

  private ImmutableList<AsyncFrame> retainFrames(ImmutableList<AsyncFrame> newlyQueuedPacket) {
    ImmutableList.Builder<AsyncFrame> retainedFrames = new ImmutableList.Builder<>();
    for (int i = 0; i < newlyQueuedPacket.size(); i++) {
      AsyncFrame asyncFrame = newlyQueuedPacket.get(i);
      checkState(asyncFrame.frame instanceof DefaultHardwareBufferFrame);
      DefaultHardwareBufferFrame retainedFrame =
          ((DefaultHardwareBufferFrame) asyncFrame.frame)
              .buildUpon()
              .shouldIncrementReferenceCount()
              .build();
      retainedFrames.add(new AsyncFrame(retainedFrame, asyncFrame.acquireFence));
    }
    return retainedFrames.build();
  }

  /**
   * Determines how the {@link AsyncFrame} should be handled given the release action.
   *
   * @param frameReleaseAction The release action for this frame.
   * @param packet The {@link ImmutableList<AsyncFrame>} to send downstream.
   * @return {@code true} if the {@link AsyncFrame} should be removed from the internal {@link
   *     #packetQueue}.
   */
  private boolean maybeQueuePacketDownstream(
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction,
      ImmutableList<AsyncFrame> packet) {
    switch (frameReleaseAction) {
      case VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER:
      case VideoFrameReleaseControl.FRAME_RELEASE_IGNORE:
        return false;
      case VideoFrameReleaseControl.FRAME_RELEASE_SKIP:
      case VideoFrameReleaseControl.FRAME_RELEASE_DROP:
        releasePacket(packet);
        return true;
      case VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY:
        boolean queuedImmediately =
            setReleaseTimeAndQueueDownstream(
                packet, /* releaseTimeNs= */ SystemClock.DEFAULT.nanoTime());
        if (queuedImmediately) {
          videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
        }
        return queuedImmediately;
      case VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED:
        boolean queuedScheduled =
            setReleaseTimeAndQueueDownstream(
                packet, /* releaseTimeNs= */ videoFrameReleaseInfo.getReleaseTimeNs());
        if (queuedScheduled) {
          videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
        }
        return queuedScheduled;
      default:
        throw new IllegalStateException(String.valueOf(frameReleaseAction));
    }
  }

  /**
   * Updates the release time of all {@link AsyncFrame} instances and forwards them to a downstream
   * consumer.
   *
   * <p>The downstream consumer is responsible for releasing the packet.
   *
   * @param packet The list of {@link AsyncFrame} to send downstream.
   * @param releaseTimeNs The time the packet should be rendered on screen.
   * @return Whether the frame was queued downstream.
   */
  private boolean setReleaseTimeAndQueueDownstream(
      ImmutableList<AsyncFrame> packet, long releaseTimeNs) {
    ImmutableList.Builder<AsyncFrame> downstreamAsyncFrames = ImmutableList.builder();

    for (int i = 0; i < packet.size(); i++) {
      AsyncFrame asyncFrame = packet.get(i);
      Frame inputFrame = asyncFrame.frame;

      ImmutableMap.Builder<String, Object> metadataBuilder =
          ImmutableMap.<String, Object>builder()
              .putAll(inputFrame.getMetadata())
              .put(Frame.KEY_DISPLAY_TIME_NS, releaseTimeNs)
              .put(KEY_FRAME_DISCONTINUITY_NUMBER, currentStreamDiscontinuityNumber);

      checkState(inputFrame instanceof DefaultHardwareBufferFrame);
      Frame downstreamFrame =
          ((DefaultHardwareBufferFrame) inputFrame)
              .buildUpon()
              .shouldIncrementReferenceCount()
              .setMetadata(metadataBuilder.buildKeepingLast())
              .build();

      downstreamAsyncFrames.add(new AsyncFrame(downstreamFrame, asyncFrame.acquireFence));
    }

    ImmutableList<AsyncFrame> asyncFrameList = downstreamAsyncFrames.build();

    boolean queued = downstreamFrameProcessor.queue(asyncFrameList);
    if (queued) {
      synchronized (lock) {
        for (int i = 0; i < asyncFrameList.size(); i++) {
          inFlightFrames.add(asyncFrameList.get(i).frame);
        }
      }
      updateLastQueuedPacket(packet);
      releasePacket(packet);
    } else {
      releasePacket(asyncFrameList);
    }
    return queued;
  }

  private static void releasePacket(@Nullable List<AsyncFrame> packet) {
    if (packet == null) {
      return;
    }
    for (int i = 0; i < packet.size(); i++) {
      TransformerUtil.releaseIfNeeded(packet.get(i).frame, /* releaseFence= */ null);
    }
  }
}
