/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.media3.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessor.InputType;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import java.util.concurrent.Executor;

/** Represents a graph for processing raw video frames. */
@UnstableApi
public interface VideoGraph {

  /** A factory for {@link VideoGraph} instances. */
  interface Factory {
    /**
     * Creates a new {@link VideoGraph} instance.
     *
     * @param context A {@link Context}.
     * @param outputColorInfo The {@link ColorInfo} for the output frames.
     * @param debugViewProvider A {@link DebugViewProvider}.
     * @param listener A {@link Listener}.
     * @param listenerExecutor The {@link Executor} on which the {@code listener} is invoked.
     * @param initialTimestampOffsetUs The timestamp offset for the first frame, in microseconds.
     * @param renderFramesAutomatically If {@code true}, the instance will render output frames to
     *     the {@linkplain VideoGraph#setOutputSurfaceInfo(SurfaceInfo) output surface}
     *     automatically as the instance is done processing them. If {@code false}, the instance
     *     will block until {@code VideoGraph#renderOutputFrameWithMediaPresentationTime()} is
     *     called, to render the frame.
     * @return A new instance.
     */
    VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically);

    /**
     * Returns whether the {@linkplain #create created} {@link VideoGraph} supports multiple video
     * {@linkplain VideoGraph#registerInputStream inputs}.
     */
    boolean supportsMultipleInputs();
  }

  /** Listener for video frame processing events. */
  interface Listener {
    /**
     * Called when the output size changes.
     *
     * @param width The new output width in pixels.
     * @param height The new output width in pixels.
     */
    default void onOutputSizeChanged(int width, int height) {}

    /**
     * Called when the output frame rate changes.
     *
     * @param frameRate The output frame rate in frames per second, or {@link Format#NO_VALUE} if
     *     unknown.
     */
    default void onOutputFrameRateChanged(float frameRate) {}

    /**
     * Called when an output frame with the given {@code framePresentationTimeUs} becomes available
     * for rendering.
     *
     * @param framePresentationTimeUs The presentation time of the frame, in microseconds.
     * @param isRedrawnFrame Whether the frame is a frame that is {@linkplain #redraw redrawn},
     *     redrawn frames are rendered directly thus {@link #renderOutputFrame} must not be called
     *     on such frames.
     */
    default void onOutputFrameAvailableForRendering(
        long framePresentationTimeUs, boolean isRedrawnFrame) {}

    /**
     * Called after the {@link VideoGraph} has rendered its final output frame.
     *
     * @param finalFramePresentationTimeUs The timestamp of the last output frame, in microseconds.
     */
    default void onEnded(long finalFramePresentationTimeUs) {}

    /**
     * Called when an exception occurs during video frame processing.
     *
     * <p>If this is called, the calling {@link VideoGraph} must immediately be {@linkplain
     * #release() released}.
     */
    default void onError(VideoFrameProcessingException exception) {}
  }

  /**
   * Initialize the {@code VideoGraph}.
   *
   * <p>This method must be called before calling other methods.
   *
   * <p>If the method throws, the caller must call {@link #release}.
   */
  void initialize() throws VideoFrameProcessingException;

  /**
   * Registers a new input to the {@code VideoGraph}.
   *
   * <p>All inputs must be registered before rendering frames by calling {@link
   * #registerInputFrame}, {@link #queueInputBitmap} or {@link #queueInputTexture}.
   *
   * <p>If the method throws, the caller must call {@link #release}.
   *
   * @param inputIndex The index of the input which could be used to order the inputs. The index
   *     must start from 0.
   */
  void registerInput(@IntRange(from = 0) int inputIndex) throws VideoFrameProcessingException;

  /**
   * Sets the output surface and supporting information.
   *
   * <p>The new output {@link SurfaceInfo} is applied from the next output frame rendered onwards.
   * If the output {@link SurfaceInfo} is {@code null}, the {@code VideoGraph} will stop rendering
   * pending frames and resume rendering once a non-null {@link SurfaceInfo} is set.
   *
   * <p>If the dimensions given in {@link SurfaceInfo} do not match the {@linkplain
   * Listener#onOutputSizeChanged(int,int) output size after applying the final effect} the frames
   * are resized before rendering to the surface and letter/pillar-boxing is applied.
   *
   * <p>The caller is responsible for tracking the lifecycle of the {@link SurfaceInfo#surface}
   * including calling this method with a new surface if it is destroyed. When this method returns,
   * the previous output surface is no longer being used and can safely be released by the caller.
   */
  void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo);

  /**
   * Sets a listener that's called when the {@linkplain #getInputSurface input surface} is ready to
   * use at {@code inputIndex}.
   */
  void setOnInputSurfaceReadyListener(int inputIndex, Runnable listener);

  /** Returns the input {@link Surface} at {@code inputIndex}. */
  Surface getInputSurface(int inputIndex);

  /** Sets the {@link OnInputFrameProcessedListener} at {@code inputIndex}. */
  void setOnInputFrameProcessedListener(int inputIndex, OnInputFrameProcessedListener listener);

  /**
   * Sets the Composition-level video effects that are applied after the effects on single
   * {@linkplain #registerInputStream input stream}.
   *
   * <p>This method should be called before {@link #registerInputStream} to set the desired
   * composition level {@link Effect effects}.
   */
  void setCompositionEffects(List<Effect> compositionEffects);

  /**
   * Sets the {@link VideoCompositorSettings}.
   *
   * <p>This method should be called before {@link #registerInputStream} to set the desired
   * composition level {@link VideoCompositorSettings}.
   *
   * <p>Setting a custom {@link VideoCompositorSettings} where {@link
   * Factory#supportsMultipleInputs()} returns {@code false} throws an {@link
   * IllegalArgumentException}.
   */
  void setCompositorSettings(VideoCompositorSettings videoCompositorSettings);

  /**
   * Informs the graph that a new input stream will be queued to the graph input corresponding to
   * {@code inputIndex}.
   *
   * <p>After registering the first input stream, this method must only be called for the same index
   * after the last frame of the already-registered input stream has been {@linkplain
   * #registerInputFrame registered}, last bitmap {@linkplain #queueInputBitmap queued} or last
   * texture id {@linkplain #queueInputTexture queued}.
   *
   * <p>This method blocks the calling thread until the previous input stream corresponding to the
   * same {@code inputIndex} has been fully registered internally.
   *
   * @param inputIndex The index of the input for which a new input stream should be registered.
   *     This index must start from 0.
   * @param inputType The {@link InputType} of the new input stream.
   * @param format The {@link Format} of the new input stream. The {@link Format#colorInfo}, the
   *     {@link Format#width}, the {@link Format#height} and the {@link
   *     Format#pixelWidthHeightRatio} must be set.
   * @param effects The list of {@link Effect effects} to apply to the new input stream.
   * @param offsetToAddUs The offset that must be added to the frame presentation timestamps, in
   *     microseconds. This offset is not part of the input timestamps. It is added to the frame
   *     timestamps before processing, and is retained in the output timestamps.
   */
  void registerInputStream(
      int inputIndex,
      @InputType int inputType,
      Format format,
      List<Effect> effects,
      long offsetToAddUs);

  /**
   * Returns the number of pending input frames at {@code inputIndex} that has not been processed
   * yet.
   */
  int getPendingInputFrameCount(int inputIndex);

  /**
   * Registers a new input frame at {@code inputIndex}.
   *
   * @see VideoFrameProcessor#registerInputFrame()
   */
  boolean registerInputFrame(int inputIndex);

  /**
   * Queues the input {@link Bitmap} at {@code inputIndex}.
   *
   * @see VideoFrameProcessor#queueInputBitmap(Bitmap, TimestampIterator)
   */
  boolean queueInputBitmap(int inputIndex, Bitmap inputBitmap, TimestampIterator timestampIterator);

  /**
   * Queues the input texture at {@code inputIndex}.
   *
   * @see VideoFrameProcessor#queueInputTexture(int, long)
   */
  boolean queueInputTexture(int inputIndex, int textureId, long presentationTimeUs);

  /**
   * Renders the output frame from the {@code VideoGraph}.
   *
   * <p>This method must be called only for frames that have become {@linkplain
   * Listener#onOutputFrameAvailableForRendering available}, calling the method renders the frame
   * that becomes available the earliest but not yet rendered.
   *
   * @see VideoFrameProcessor#renderOutputFrame(long)
   */
  void renderOutputFrame(long renderTimeNs);

  /**
   * Updates an {@linkplain Listener#onOutputFrameAvailableForRendering available frame} with the
   * modified effects.
   */
  void redraw();

  /**
   * Returns whether the {@code VideoGraph} has produced a frame with zero presentation timestamp.
   */
  boolean hasProducedFrameWithTimestampZero();

  /**
   * Flushes the {@linkplain #registerInput inputs} of the {@code VideoGraph}.
   *
   * @see VideoFrameProcessor#flush()
   */
  void flush();

  /**
   * Informs that no further inputs should be accepted at {@code inputIndex}.
   *
   * @see VideoFrameProcessor#signalEndOfInput()
   */
  void signalEndOfInput(int inputIndex);

  /**
   * Releases the associated resources.
   *
   * <p>This {@code VideoGraph} instance must not be used after this method is called.
   */
  void release();
}
