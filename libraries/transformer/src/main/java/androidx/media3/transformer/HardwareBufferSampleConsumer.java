/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.transformer.TransformerUtil.END_OF_STREAM_ASYNC_FRAME;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.transformer.SequenceFrameReader.Listener;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A {@link GraphInput} that wraps {@link SequenceFrameReader}.
 *
 * <p>Offsets {@link Frame#KEY_DISPLAY_TIME_NS} based on preceding media item durations to ensure a
 * continuous timeline within the sequence, starting from an initial timestamp offset.
 */
/* package */ final class HardwareBufferSampleConsumer implements GraphInput {

  private final SequenceFrameReader sequenceFrameReader;
  private final SequenceFrameReader.InputSurface inputSurface;
  private final Composition composition;
  private final int sequenceIndex;

  private @Nullable Format currentFormat;
  // Start at -1 so the initial onMediaItemChanged increments the current index to 0.
  private int currentMediaItemIndex = C.INDEX_UNSET;
  private long currentTimeOffsetUs;
  private long nextTimeOffsetUs;
  private volatile @Nullable Runnable wakeupListener;

  /**
   * Creates a new instance.
   *
   * @param composition The {@link Composition} containing the sequences being exported.
   * @param sequenceIndex The index of the sequence this consumer belongs to.
   * @param initialTimestampOffsetUs The offset to add to the timestamps of all output frames, in
   *     microseconds. A negative value shifts the timestamps earlier.
   * @param playbackLooper The {@link Looper} for playback operations.
   * @param listenerHandler The {@link HandlerWrapper} for scheduling to dispatch {@link Listener}
   *     callbacks.
   * @param frameConsumer The {@link Consumer<AsyncFrame>} to which processed frames are output.
   * @param errorConsumer A consumer to accept {@link ExportException}s if errors occur.
   * @param hardwareBufferJniWrapper The {@link HardwareBufferJniWrapper} used to convert software
   *     bitmaps to hardware buffers.
   */
  public HardwareBufferSampleConsumer(
      Composition composition,
      int sequenceIndex,
      long initialTimestampOffsetUs,
      Looper playbackLooper,
      HandlerWrapper listenerHandler,
      Consumer<AsyncFrame> frameConsumer,
      Consumer<ExportException> errorConsumer,
      HardwareBufferJniWrapper hardwareBufferJniWrapper) {
    this(
        composition,
        sequenceIndex,
        initialTimestampOffsetUs,
        playbackLooper,
        listenerHandler,
        frameConsumer,
        errorConsumer,
        hardwareBufferJniWrapper,
        new DefaultImageReaderAdapter.Factory());
  }

  /**
   * Creates a new instance whose input surface uses an image reader from the given {@link
   * ImageReaderAdapter.Factory}.
   */
  @VisibleForTesting
  /* package */ HardwareBufferSampleConsumer(
      Composition composition,
      int sequenceIndex,
      long initialTimestampOffsetUs,
      Looper playbackLooper,
      HandlerWrapper listenerHandler,
      Consumer<AsyncFrame> frameConsumer,
      Consumer<ExportException> errorConsumer,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      ImageReaderAdapter.Factory imageReaderAdapterFactory) {
    this.composition = composition;
    this.sequenceIndex = sequenceIndex;
    currentTimeOffsetUs = initialTimestampOffsetUs;
    // Modify the release times of frames exiting the sequenceFrameReader to account for item
    // duration.
    Consumer<AsyncFrame> intermediateConsumer =
        asyncFrame -> {
          if (asyncFrame != END_OF_STREAM_ASYNC_FRAME) {
            // The frame's sequencePresentationTimeUs is already adjusted by the
            // SequenceFrameReader to be monotonic across the entire sequence.
            // Adjust the release times so they are valid for the encoder.
            long releaseTimeNs = asyncFrame.frame.getContentTimeUs() * 1000;
            Map<String, Object> updatedMetadata = new HashMap<>(asyncFrame.frame.getMetadata());
            updatedMetadata.put(Frame.KEY_DISPLAY_TIME_NS, releaseTimeNs);
            Frame frame =
                asyncFrame.frame instanceof ImagePlanesFrame
                    ? ((ImagePlanesFrame) asyncFrame.frame)
                        .buildUpon()
                        .setMetadata(updatedMetadata)
                        .build()
                    : ((DefaultHardwareBufferFrame) asyncFrame.frame)
                        .buildUpon()
                        .setMetadata(updatedMetadata)
                        .build();
            asyncFrame = new AsyncFrame(frame, asyncFrame.acquireFence);
          }
          frameConsumer.accept(asyncFrame);
        };
    this.sequenceFrameReader =
        new SequenceFrameReader.Builder()
            .setComposition(composition)
            .setSequenceIndex(sequenceIndex)
            .setFrameConsumer(intermediateConsumer)
            .setPlaybackLooper(playbackLooper)
            .setImageReaderAdapterFactory(imageReaderAdapterFactory)
            .setListener(
                e -> errorConsumer.accept(ExportException.createForUnexpected(e)), listenerHandler)
            .setHardwareBufferJniWrapper(hardwareBufferJniWrapper)
            .build();
    inputSurface = sequenceFrameReader.createInputSurface();
    sequenceFrameReader.addRendererWakeupListener(this::maybeWakeupRenderer);
  }

  @Override
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem,
      long durationUs,
      @Nullable Format decodedFormat,
      boolean isLast,
      long positionOffsetUs) {
    this.currentFormat = decodedFormat;
    // The editedMediaItems passed to this method are different instances to those in the
    // composition, so they cannot be directly compared. Assume that media items are processed in
    // order, so every onMediaItemChanged call increments the media item index.
    EditedMediaItemSequence sequence = composition.sequences.get(sequenceIndex);
    currentMediaItemIndex =
        EditedMediaItemSequence.getEditedMediaItemIndex(sequence, currentMediaItemIndex + 1);
    currentTimeOffsetUs += nextTimeOffsetUs;
    nextTimeOffsetUs = durationUs;
  }

  @Override
  public @InputResult int queueInputBitmap(
      Bitmap inputBitmap, TimestampIterator timestampIterator) {
    sequenceFrameReader.outputBitmap(
        inputBitmap, timestampIterator, currentTimeOffsetUs, currentMediaItemIndex);
    return INPUT_RESULT_SUCCESS;
  }

  @Override
  public Surface getInputSurface() {
    return inputSurface.getSurface();
  }

  @Override
  public boolean registerVideoFrame(long presentationTimeUs) {
    return registerVideoFrame(presentationTimeUs, /* additionalMetadata= */ ImmutableMap.of());
  }

  @Override
  public boolean registerVideoFrame(
      long presentationTimeUs, ImmutableMap<String, Object> additionalMetadata) {
    if (!inputSurface.canAcceptFrame()) {
      return false;
    }
    checkState(currentFormat != null);
    inputSurface.queueFrame(
        presentationTimeUs,
        currentTimeOffsetUs,
        currentMediaItemIndex,
        currentFormat,
        additionalMetadata);
    return true;
  }

  @Override
  public void signalEndOfVideoInput() {
    EditedMediaItemSequence sequence = composition.sequences.get(sequenceIndex);
    if (sequence.isLooping || currentMediaItemIndex == sequence.editedMediaItems.size() - 1) {
      sequenceFrameReader.queueEndOfStream();
    }
  }

  @Override
  public int getPendingVideoFrameCount() {
    // registerVideoFrame is always called before a sample is queued, that is the source of truth
    // for whether the underlying SequenceFrameReader can accept a frame, so it is fine to
    // always return 0 here.
    return 0;
  }

  @CanIgnoreReturnValue
  @Override
  public boolean setWakeupListener(@Nullable Runnable wakeupListener) {
    this.wakeupListener = wakeupListener;
    return true;
  }

  /** Releases resources associated with the underlying {@link SequenceFrameReader}. */
  public void release() {
    sequenceFrameReader.release();
  }

  private void maybeWakeupRenderer() {
    @Nullable Runnable listener = wakeupListener;
    if (listener != null) {
      listener.run();
    }
  }
}
