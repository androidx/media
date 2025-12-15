/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law-or-agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.GlTextureProducer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

/** A {@link GlTextureProducer.Listener} that queues frames to a {@link FrameAggregator}. */
/* package */ final class CompositionTextureListener implements GlTextureProducer.Listener {

  private static final int TIMEOUT_MS = 100;

  private final Composition composition;
  private final int sequenceIndex;
  private final FrameAggregator frameAggregator;
  private final ExecutorService glExecutor;

  /**
   * A blocking queue containing a sequence of presentation timestamps and the corresponding {@link
   * EditedMediaItem} index in the {@link EditedMediaItemSequence}.
   *
   * <p>New elements are added on the playback thread and removed on the GL thread. Adding an
   * element should never block, but removing an element may block for images, where the GL thread
   * may produce an image before the metadata is sent.
   */
  private final BlockingQueue<Pair<Long, Integer>> pendingFrameInformation;

  CompositionTextureListener(
      Composition composition,
      int sequenceIndex,
      FrameAggregator frameAggregator,
      ExecutorService glExecutor) {
    this.composition = composition;
    this.sequenceIndex = sequenceIndex;
    this.frameAggregator = frameAggregator;
    this.glExecutor = glExecutor;
    pendingFrameInformation = new LinkedBlockingQueue<>();
  }

  /**
   * {@inheritDoc}
   *
   * <p>A texture was produced on the GL thread. The next frame timestamp should be the one we
   * expect.
   */
  @Override
  public void onTextureRendered(
      GlTextureProducer textureProducer,
      GlTextureInfo outputTexture,
      long presentationTimeUs,
      long syncObject)
      throws VideoFrameProcessingException {
    Pair<Long, Integer> presentationTimeAndItemIndex;
    try {
      presentationTimeAndItemIndex =
          checkNotNull(pendingFrameInformation.poll(TIMEOUT_MS, MILLISECONDS));
      checkState(presentationTimeAndItemIndex.first == presentationTimeUs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VideoFrameProcessingException(e);
    } catch (NullPointerException | IllegalStateException e) {
      throw new VideoFrameProcessingException(e);
    }
    int itemIndex = presentationTimeAndItemIndex.second;
    GlTextureFrame textureFrame =
        new GlTextureFrame.Builder(
                outputTexture,
                directExecutor(),
                (u) -> textureProducer.releaseOutputTexture(presentationTimeUs))
            .setPresentationTimeUs(presentationTimeUs)
            .setMetadata(new CompositionFrameMetadata(composition, sequenceIndex, itemIndex))
            .setFenceSync(syncObject)
            .build();
    frameAggregator.queueFrame(textureFrame, sequenceIndex);
  }

  /**
   * {@inheritDoc}
   *
   * <p>A flush operation completed on the GL thread. Drain all frames that were queued to be output
   * before the flush began.
   */
  @Override
  public void flush() throws VideoFrameProcessingException {
    Pair<Long, Integer> presentationTimeAndItemIndex;
    do {
      try {
        presentationTimeAndItemIndex =
            checkNotNull(pendingFrameInformation.poll(TIMEOUT_MS, MILLISECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new VideoFrameProcessingException(e);
      }
    } while (presentationTimeAndItemIndex.first != C.TIME_UNSET);
    checkState(presentationTimeAndItemIndex.second == C.INDEX_UNSET);
    frameAggregator.flush(sequenceIndex);
  }

  /**
   * Notifies the texture listener that this {@linkplain #sequenceIndex sequence} has ended.
   *
   * <p>Called on the playback thread.
   */
  void onEnded() {
    glExecutor.execute(() -> frameAggregator.queueEndOfStream(sequenceIndex));
  }

  /**
   * Notifies the texture listener that a new frame will begin processing in the video graph.
   *
   * <p>Called on the playback thread.
   */
  void willOutputFrame(long presentationTimeUs, int indexOfItem) {
    pendingFrameInformation.add(Pair.create(presentationTimeUs, indexOfItem));
  }

  /**
   * Notifies the texture listener that a flush will begin in the video graph.
   *
   * <p>Called on the playback thread.
   */
  void willFlush() {
    pendingFrameInformation.add(Pair.create(C.TIME_UNSET, C.INDEX_UNSET));
  }
}
