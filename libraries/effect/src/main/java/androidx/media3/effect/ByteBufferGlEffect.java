/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkArgument;

import android.content.Context;
import android.graphics.Rect;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

/**
 * A {@link GlEffect} implementation that runs an asynchronous {@link Processor} on video frame data
 * passed in as a {@link ByteBuffer}.
 *
 * <p>This effect can be used to apply CPU-based effects. Or the provided {@link ByteBuffer} can be
 * passed to other heterogeneous compute components that are available such as another GPU context,
 * FPGAs, or NPUs.
 */
@UnstableApi
/* package */ class ByteBufferGlEffect<T> implements GlEffect {

  private static final int DEFAULT_QUEUE_SIZE = 6;

  /**
   * A processor that takes in {@link ByteBuffer ByteBuffers} that represent input image data, and
   * produces results of type {@code <T>}.
   *
   * <p>All methods are called on the GL thread.
   *
   * @param <T> The result type of running the processor.
   */
  public interface Processor<T> {

    /**
     * Configures the instance and returns the dimensions of the image required by {@link
     * #processPixelBuffer}.
     *
     * <p>When the returned dimensions differ from {@code inputWidth} and {@code inputHeight}, the
     * image will be scaled based on {@link #getScaledRegion}.
     *
     * @param inputWidth The input width in pixels.
     * @param inputHeight The input height in pixels.
     * @return The size in pixels of the image data accepted by {@link #processPixelBuffer}.
     * @throws VideoFrameProcessingException On error.
     */
    Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException;

    /**
     * Selects a region of the input texture that will be scaled to fill the image given that is
     * given to {@link #processPixelBuffer}.
     *
     * <p>Called once per input frame.
     *
     * <p>The contents are scaled to fit the image dimensions returned by {@link #configure}.
     *
     * @param presentationTimeUs The presentation time in microseconds.
     * @return The rectangular region of the input image that will be scaled to fill the effect
     *     input image.
     */
    // TODO: b/b/361286064 - This method misuses android.graphics.Rect for OpenGL coordinates.
    //   Implement a custom GlUtils.Rect to correctly label lower left corner as (0, 0).
    Rect getScaledRegion(long presentationTimeUs);

    /**
     * Processing the image data in the {@code pixelBuffer}.
     *
     * <p>Accessing {@code pixelBuffer} after the returned future is {@linkplain Future#isDone()
     * done} or {@linkplain Future#isCancelled() cancelled} can lead to undefined behaviour.
     *
     * @param pixelBuffer The image data.
     * @param presentationTimeUs The presentation time in microseconds.
     * @return A {@link ListenableFuture} of the result.
     */
    // TODO: b/361286064 - Add helper functions for easier conversion to Bitmap.
    ListenableFuture<T> processPixelBuffer(ByteBuffer pixelBuffer, long presentationTimeUs);

    /**
     * Finishes processing the frame at {@code presentationTimeUs}. Use this method to perform
     * custom drawing on the output frame.
     *
     * <p>The {@linkplain GlTextureInfo outputFrame} contains the image data corresponding to the
     * frame at {@code presentationTimeUs} when this method is invoked.
     *
     * @param outputFrame The texture info of the frame.
     * @param presentationTimeUs The presentation timestamp of the frame, in microseconds.
     * @param result The result of the asynchronous computation in {@link #processPixelBuffer}.
     */
    void finishProcessingAndBlend(GlTextureInfo outputFrame, long presentationTimeUs, T result)
        throws VideoFrameProcessingException;

    /**
     * Releases all resources.
     *
     * @throws VideoFrameProcessingException If an error occurs while releasing resources.
     */
    void release() throws VideoFrameProcessingException;
  }

  private final Processor<T> processor;

  /**
   * Creates an instance.
   *
   * @param processor The effect to apply.
   */
  public ByteBufferGlEffect(Processor<T> processor) {
    this.processor = processor;
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    // TODO: b/361286064 - Implement HDR support.
    checkArgument(!useHdr, "HDR support not yet implemented.");
    return new QueuingGlShaderProgram<>(
        /* useHighPrecisionColorComponents= */ useHdr,
        /* queueSize= */ DEFAULT_QUEUE_SIZE,
        new ByteBufferConcurrentEffect<>(processor));
  }
}
