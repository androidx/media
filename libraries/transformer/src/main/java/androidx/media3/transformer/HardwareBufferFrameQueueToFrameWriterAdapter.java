/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Adapts a {@link HardwareBufferFrameQueue} to the {@link FrameWriter} interface. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
/* package */ final class HardwareBufferFrameQueueToFrameWriterAdapter implements FrameWriter {

  private final HardwareBufferFrameQueue queue;
  private final Map<HardwareBuffer, HardwareBufferFrame> frameMap;
  @Nullable private Format format;
  @Frame.Usage long usage;

  public HardwareBufferFrameQueueToFrameWriterAdapter(HardwareBufferFrameQueue queue) {
    this.queue = queue;
    this.frameMap = new ConcurrentHashMap<>();
  }

  @Override
  public Info getInfo() {
    return (format, usage) -> true; // Assume supported for simplicity in adapter
  }

  @Override
  public void configure(Format format, @Frame.Usage long usage) {
    this.format = format;
    this.usage = usage;
  }

  @Nullable
  @Override
  public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    Format localFormat = checkNotNull(format, "configure() must be called first");
    HardwareBufferFrameQueue.FrameFormat.Builder frameFormatBuilder =
        new HardwareBufferFrameQueue.FrameFormat.Builder()
            .setWidth(localFormat.width)
            .setHeight(localFormat.height)
            .setUsageFlags(usage)
            .setRotationDegrees(localFormat.rotationDegrees);
    if (localFormat.colorInfo != null) {
      frameFormatBuilder.setColorInfo(localFormat.colorInfo);
    }
    HardwareBufferFrameQueue.FrameFormat frameFormat = frameFormatBuilder.build();

    Runnable adaptedWakeupListener = () -> wakeupExecutor.execute(wakeupListener);

    HardwareBufferFrame effectFrame = queue.dequeue(frameFormat, adaptedWakeupListener);

    if (effectFrame == null) {
      return null;
    }

    if (effectFrame.hardwareBuffer != null) {
      frameMap.put(effectFrame.hardwareBuffer, effectFrame);
    }

    HardwareBuffer hardwareBuffer = checkNotNull(effectFrame.hardwareBuffer);
    DefaultHardwareBufferFrame commonFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(localFormat)
            .setContentTimeUs(effectFrame.presentationTimeUs)
            .setInternalImage(effectFrame.internalFrame)
            .build();

    return new AsyncFrame(commonFrame, effectFrame.acquireFence);
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    checkState(frame instanceof DefaultHardwareBufferFrame, "Expected DefaultHardwareBufferFrame");
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) frame;
    HardwareBuffer hardwareBuffer = hardwareBufferFrame.getHardwareBuffer();

    HardwareBufferFrame effectFrame =
        checkNotNull(frameMap.remove(hardwareBuffer), "Matching effect Frame not found");

    HardwareBufferFrame.Builder frameBuilder =
        effectFrame.buildUpon().setAcquireFence(writeCompleteFence).setFormat(frame.getFormat());

    Object metadata =
        frame.getMetadata().get(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA);
    if (metadata instanceof CompositionFrameMetadata) {
      frameBuilder.setMetadata((CompositionFrameMetadata) metadata);
    }

    Long presentationTimeUs =
        (Long) hardwareBufferFrame.getMetadata().get(Frame.KEY_PRESENTATION_TIME_US);
    if (presentationTimeUs != null) {
      frameBuilder.setPresentationTimeUs(presentationTimeUs);
    } else {
      frameBuilder.setPresentationTimeUs(hardwareBufferFrame.getContentTimeUs());
    }

    frameBuilder.setSequencePresentationTimeUs(hardwareBufferFrame.getContentTimeUs());
    // When encoding, releaseTime and contentTime are the same.
    frameBuilder.setReleaseTimeNs(hardwareBufferFrame.getContentTimeUs() * 1000);

    queue.queue(frameBuilder.build());
  }

  @Override
  public void signalEndOfStream() {
    queue.signalEndOfStream();
  }

  @Override
  public void close() {
    queue.release();
    frameMap.clear();
  }
}
