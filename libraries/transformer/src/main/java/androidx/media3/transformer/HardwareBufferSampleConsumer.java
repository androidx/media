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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.transformer.HardwareBufferFrameReader.Listener;

/**
 * A {@link GraphInput} that wraps {@link HardwareBufferFrameReader}.
 *
 * <p>Offsets {@link HardwareBufferFrame#releaseTimeNs} based on preceding media item durations to
 * ensure a continuous timeline within the sequence.
 */
/* package */ final class HardwareBufferSampleConsumer implements GraphInput {

  private final HardwareBufferFrameReader hardwareBufferFrameReader;
  private final Composition composition;
  private final int sequenceIndex;

  @Nullable private Format currentFormat;
  // Start at -1 so the initial onMediaItemChanged increments the current index to 0.
  private int currentMediaItemIndex = C.INDEX_UNSET;
  private long currentReleaseTimeOffsetNs;
  private long nextReleaseTimeOffsetNs;

  /**
   * Creates a new instance.
   *
   * @param composition The {@link Composition} containing the sequences being exported.
   * @param sequenceIndex The index of the sequence this consumer belongs to.
   * @param playbackLooper The {@link Looper} for playback operations.
   * @param listenerHandler The {@link HandlerWrapper} for scheduling to dispatch {@link Listener}
   *     callbacks.
   * @param frameConsumer The {@link Consumer<HardwareBufferFrame>} to which processed frames are
   *     output.
   * @param errorConsumer A consumer to accept {@link ExportException}s if errors occur.
   */
  public HardwareBufferSampleConsumer(
      Composition composition,
      int sequenceIndex,
      Looper playbackLooper,
      HandlerWrapper listenerHandler,
      Consumer<HardwareBufferFrame> frameConsumer,
      Consumer<ExportException> errorConsumer) {
    this.composition = composition;
    this.sequenceIndex = sequenceIndex;
    // Modify the release times of frames exiting the hardwareBufferFrameReader to account for
    // item duration.
    Consumer<HardwareBufferFrame> intermediateConsumer =
        (hardwareBufferFrame) -> {
          if (hardwareBufferFrame != HardwareBufferFrame.END_OF_STREAM_FRAME) {
            // The presentation time of each frame is with respect to the individual media item.
            // Adjust the
            // release times so they are valid for the encoder.
            long releaseTimeNs =
                (hardwareBufferFrame.presentationTimeUs * 1000) + currentReleaseTimeOffsetNs;
            hardwareBufferFrame =
                hardwareBufferFrame.buildUpon().setReleaseTimeNs(releaseTimeNs).build();
          }
          frameConsumer.accept(hardwareBufferFrame);
        };
    this.hardwareBufferFrameReader =
        new HardwareBufferFrameReader(
            checkNotNull(composition),
            sequenceIndex,
            intermediateConsumer,
            playbackLooper,
            /* defaultSurfacePixelFormat= */ ImageFormat.PRIVATE,
            e -> errorConsumer.accept(ExportException.createForUnexpected(e)),
            listenerHandler);
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
    currentMediaItemIndex++;
    currentReleaseTimeOffsetNs += nextReleaseTimeOffsetNs;
    nextReleaseTimeOffsetNs = durationUs * 1000;
  }

  @Override
  public @InputResult int queueInputBitmap(
      Bitmap inputBitmap, TimestampIterator timestampIterator) {
    hardwareBufferFrameReader.outputBitmap(inputBitmap, timestampIterator, currentMediaItemIndex);
    return INPUT_RESULT_SUCCESS;
  }

  @Override
  public Surface getInputSurface() {
    return hardwareBufferFrameReader.getSurface();
  }

  @Override
  public boolean registerVideoFrame(long presentationTimeUs) {
    if (!hardwareBufferFrameReader.canAcceptFrameViaSurface()) {
      return false;
    }
    checkState(currentFormat != null);
    hardwareBufferFrameReader.queueFrameViaSurface(
        presentationTimeUs, currentMediaItemIndex, currentFormat);
    return true;
  }

  @Override
  public void signalEndOfVideoInput() {
    if (currentMediaItemIndex
        == composition.sequences.get(sequenceIndex).editedMediaItems.size() - 1) {
      hardwareBufferFrameReader.queueEndOfStream();
    }
  }

  @Override
  public int getPendingVideoFrameCount() {
    // registerVideoFrame is always called before a sample is queued, that is the source of truth
    // for whether the underlying HardwareBufferFrameReader can accept a frame, so it is fine to
    // always return 0 here.
    return 0;
  }

  /** Releases resources associated the underlying {@link HardwareBufferFrameReader}. */
  public void release() {
    hardwareBufferFrameReader.release();
  }
}
