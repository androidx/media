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

import static androidx.media3.common.video.Frame.KEY_PRESENTATION_TIME_US;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adapts a {@link FrameWriter} to the {@link HardwareBufferFrameQueue} interface. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class FrameWriterToHardwareBufferFrameQueueAdapter
    implements HardwareBufferFrameQueue {

  private final FrameWriter writer;
  private final Map<HardwareBuffer, AsyncFrame> frameMap;
  @Nullable private Format configuredFormat;

  public FrameWriterToHardwareBufferFrameQueueAdapter(FrameWriter writer) {
    this.writer = writer;
    this.frameMap = new ConcurrentHashMap<>();
  }

  @Nullable
  @Override
  public HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener) {
    Format commonFormat =
        new Format.Builder()
            .setWidth(format.width)
            .setHeight(format.height)
            .setRotationDegrees(format.rotationDegrees)
            .setColorInfo(format.colorInfo)
            .build();

    if (!commonFormat.equals(configuredFormat)) {
      writer.configure(commonFormat, format.usageFlags);
      configuredFormat = commonFormat;
    }

    AsyncFrame asyncFrame = writer.dequeueInputFrame(directExecutor(), wakeupListener);

    if (asyncFrame == null) {
      return null;
    }

    checkState(
        asyncFrame.frame instanceof DefaultHardwareBufferFrame,
        "Expected DefaultHardwareBufferFrame");
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) asyncFrame.frame;
    HardwareBuffer hardwareBuffer = hardwareBufferFrame.getHardwareBuffer();

    frameMap.put(hardwareBuffer, asyncFrame);

    long presentationTimeUs =
        (long)
            checkNotNull(
                hardwareBufferFrame
                    .getMetadata()
                    .getOrDefault(KEY_PRESENTATION_TIME_US, C.TIME_UNSET));

    return new HardwareBufferFrame.Builder(
            hardwareBuffer,
            directExecutor(),
            releaseFence -> {
              // Frames returned by dequeue should be queued back.
            })
        .setPresentationTimeUs(presentationTimeUs)
        .setAcquireFence(asyncFrame.acquireFence)
        .setFormat(hardwareBufferFrame.getFormat())
        .setInternalFrame(hardwareBufferFrame.getInternalImage())
        .build();
  }

  @Override
  public void queue(HardwareBufferFrame frame) {
    HardwareBuffer hardwareBuffer = frame.hardwareBuffer;
    AsyncFrame asyncFrame =
        checkNotNull(frameMap.remove(hardwareBuffer), "Matching AsyncFrame not found");

    ImmutableMap.Builder<String, Object> metadataBuilder = ImmutableMap.builder();
    metadataBuilder.putAll(asyncFrame.frame.getMetadata());
    if (frame.getMetadata() instanceof CompositionFrameMetadata) {
      metadataBuilder.put(
          CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA, frame.getMetadata());
    }
    DefaultHardwareBufferFrame newFrame =
        ((DefaultHardwareBufferFrame.Builder)
                ((DefaultHardwareBufferFrame) asyncFrame.frame).buildUpon())
            .setFormat(frame.format)
            .setContentTimeUs(frame.sequencePresentationTimeUs)
            .setMetadata(metadataBuilder.buildOrThrow())
            .build();

    writer.queueInputFrame(newFrame, frame.acquireFence);
  }

  @Override
  public void signalEndOfStream() {
    writer.signalEndOfStream();
  }

  @Override
  public void release() {
    writer.close();
    frameMap.clear();
  }
}
