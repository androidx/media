/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.effect;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;

/**
 * A shader program that caches the input frames, and {@linkplain #replayFrame replays} the oldest
 * input frame when instructed.
 *
 * <p>This class doesn't strictly follow the {@link OutputListener} contract. If {@link
 * #replayFrame()} is called after calling {@link #signalEndOfCurrentInputStream()}, it will {@link
 * OutputListener#onOutputFrameAvailable produce further frames}.
 */
/* package */ final class ReplayableFrameCacheGlShaderProgram extends FrameCacheGlShaderProgram {
  private static final int CAPACITY = 2;
  private static final int REPLAY_FRAME_INDEX = 0;
  private static final int REGULAR_FRAME_INDEX = 1;

  // Use a manually managed array to be more efficient than List add/remove methods.
  private final TimedGlTextureInfo[] cachedFrames;
  private int cacheSize;

  public ReplayableFrameCacheGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    super(context, CAPACITY, useHdr);
    cachedFrames = new TimedGlTextureInfo[CAPACITY];
  }

  @Override
  public void queueInputFrame(
      GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
    checkState(cacheSize < CAPACITY);
    super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
    cachedFrames[cacheSize++] =
        new TimedGlTextureInfo(
            checkNotNull(outputTexturePool.getMostRecentlyUsedTexture()), presentationTimeUs);
  }

  @Override
  public void releaseOutputFrame(GlTextureInfo outputTexture) {
    // Do nothing here as this method will be called as soon as the output frame is queued into the
    // subsequent shader program. This class only releases output frame based on rendering event
    // from the FinalShaderProgramWrapper. See onFrameRendered().
  }

  @Override
  public void flush() {
    cacheSize = 0;
    super.flush();
  }

  /**
   * Signals a new input stream.
   *
   * <p>This method should be called before chaining shader programs.
   */
  public void onNewInputStream() {
    for (int i = 0; i < cacheSize; i++) {
      super.releaseOutputFrame(cachedFrames[i].glTextureInfo);
    }
    cacheSize = 0;
  }

  /** Returns whether there is no cached frame. */
  public boolean isEmpty() {
    return cacheSize == 0;
  }

  /**
   * Returns the presentation time of the frame that will be replayed, if {@link #replayFrame()} is
   * called.
   */
  public long getReplayFramePresentationTimeUs() {
    if (isEmpty()) {
      return C.TIME_UNSET;
    }
    return cachedFrames[REPLAY_FRAME_INDEX].presentationTimeUs;
  }

  /**
   * Replays the frame from cache, with the {@linkplain #getReplayFramePresentationTimeUs replay
   * timestamp}.
   */
  public void replayFrame() {
    if (isEmpty()) {
      return;
    }

    // Get the oldest frame that is queued.
    TimedGlTextureInfo oldestFrame = cachedFrames[REPLAY_FRAME_INDEX];
    getOutputListener()
        .onOutputFrameAvailable(oldestFrame.glTextureInfo, oldestFrame.presentationTimeUs);

    // Queue the subsequent frame also to keep the player's output frame queue full.
    if (cacheSize > 1) {
      TimedGlTextureInfo secondOldestFrame = cachedFrames[REGULAR_FRAME_INDEX];
      getOutputListener()
          .onOutputFrameAvailable(
              secondOldestFrame.glTextureInfo, secondOldestFrame.presentationTimeUs);
    }
  }

  /** Removes a frame from the cache when a frame of the {@code presentationTimeUs} is rendered. */
  public void onFrameRendered(long presentationTimeUs) {
    // Cache needs to be full when capacity is two, only release frame n when frame n+1 is released.
    if (cacheSize < CAPACITY
        || presentationTimeUs < cachedFrames[REGULAR_FRAME_INDEX].presentationTimeUs) {
      return;
    }

    // Evict the oldest frame.
    TimedGlTextureInfo cachedFrame = cachedFrames[REPLAY_FRAME_INDEX];
    cachedFrames[REPLAY_FRAME_INDEX] = cachedFrames[REGULAR_FRAME_INDEX];
    cacheSize--;

    // Release the texture, this also calls readyToAcceptInput.
    super.releaseOutputFrame(cachedFrame.glTextureInfo);
  }
}
