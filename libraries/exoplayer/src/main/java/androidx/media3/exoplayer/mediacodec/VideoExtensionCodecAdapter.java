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
package androidx.media3.exoplayer.mediacodec;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.media.MediaFormat;
import android.view.Surface;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import org.jspecify.annotations.Nullable;

/**
 * A specialized {@link ExtensionCodecAdapter} for video decoders producing {@link
 * VideoDecoderOutputBuffer} instances.
 */
@RestrictTo(LIBRARY_GROUP)
public abstract class VideoExtensionCodecAdapter extends ExtensionCodecAdapter {

  private static final String TAG = "VideoExtCodecAdapter";

  @GuardedBy("lock")
  private @Nullable Surface surface;

  @GuardedBy("lock")
  private @Nullable VideoDecoderOutputBufferRenderer outputBufferRenderer;

  @GuardedBy("lock")
  private @C.VideoOutputMode int outputMode;

  /**
   * Creates a new instance.
   *
   * @param format The input {@link Format} for decoding.
   * @param decoder The underlying video {@link Decoder} wrapped by this adapter.
   * @param surface The initial output {@link Surface}, or {@code null} if none.
   */
  protected VideoExtensionCodecAdapter(
      Format format,
      Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
          decoder,
      @Nullable Surface surface) {
    super(format, decoder);
    this.surface = surface;
    this.outputMode = surface != null ? C.VIDEO_OUTPUT_MODE_SURFACE_YUV : C.VIDEO_OUTPUT_MODE_NONE;
  }

  /**
   * Returns the underlying {@link VideoExtensionCodecAdapter} for {@code adapter} (unwrapping any
   * {@link ForwardingMediaCodecAdapter} decorators), or {@code null} if {@code adapter} is not a
   * {@link VideoExtensionCodecAdapter}.
   */
  public static @Nullable VideoExtensionCodecAdapter maybeUnwrap(
      @Nullable MediaCodecAdapter adapter) {
    @Nullable ExtensionCodecAdapter extensionAdapter = ExtensionCodecAdapter.maybeUnwrap(adapter);
    return extensionAdapter instanceof VideoExtensionCodecAdapter
        ? (VideoExtensionCodecAdapter) extensionAdapter
        : null;
  }

  @Override
  public final MediaFormat getOutputFormat() {
    synchronized (lock) {
      MediaFormat mediaFormat = super.getOutputFormat();
      Format outputFormat = getCurrentOutputFormat();
      if (outputFormat.width != Format.NO_VALUE) {
        mediaFormat.setInteger(MediaFormat.KEY_WIDTH, outputFormat.width);
      }
      if (outputFormat.height != Format.NO_VALUE) {
        mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, outputFormat.height);
      }
      return mediaFormat;
    }
  }

  @Override
  public final void setOutputSurface(Surface surface) {
    setOutput(surface);
  }

  @Override
  public final void detachOutputSurface() {
    setOutput(null);
  }

  @Override
  @CallSuper
  public void release() {
    synchronized (lock) {
      this.surface = null;
      this.outputBufferRenderer = null;
      this.outputMode = C.VIDEO_OUTPUT_MODE_NONE;
    }
    super.release();
  }

  /**
   * Sets the output target.
   *
   * <p>Supported output types are {@link Surface} and {@link VideoDecoderOutputBufferRenderer}.
   * Passing {@code null} or any other object type clears the output target so {@link
   * #canRenderOutputBuffer()} returns {@code false}, while leaving the underlying decoder's output
   * mode unchanged (without invoking {@link #onOutputModeChanged(int)}) in anticipation that a
   * subsequent output will likely use the same mode.
   *
   * @param output The output target, or {@code null} to clear the output.
   */
  @CallSuper
  public void setOutput(@Nullable Object output) {
    int newOutputMode;
    synchronized (lock) {
      if (output instanceof Surface) {
        this.surface = (Surface) output;
        this.outputBufferRenderer = null;
        this.outputMode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
      } else if (output instanceof VideoDecoderOutputBufferRenderer) {
        this.surface = null;
        this.outputBufferRenderer = (VideoDecoderOutputBufferRenderer) output;
        this.outputMode = C.VIDEO_OUTPUT_MODE_YUV;
      } else {
        this.surface = null;
        this.outputBufferRenderer = null;
        this.outputMode = C.VIDEO_OUTPUT_MODE_NONE;
      }
      newOutputMode = this.outputMode;
    }
    if (newOutputMode != C.VIDEO_OUTPUT_MODE_NONE) {
      onOutputModeChanged(newOutputMode);
    }
  }

  /**
   * Returns whether the currently dequeued output buffer can be rendered to the current output
   * target.
   */
  public final boolean canRenderOutputBuffer() {
    synchronized (lock) {
      DecoderOutputBuffer outputBuffer = getDequeuedOutputBuffer();
      return outputMode != C.VIDEO_OUTPUT_MODE_NONE
          && outputBuffer instanceof VideoDecoderOutputBuffer
          && ((VideoDecoderOutputBuffer) outputBuffer).mode == outputMode;
    }
  }

  @Override
  protected final boolean maybeUpdateOutputFormat(DecoderOutputBuffer outputBuffer) {
    synchronized (lock) {
      Format previousFormat = getCurrentOutputFormat();
      boolean formatChanged = super.maybeUpdateOutputFormat(outputBuffer);
      if (!(outputBuffer instanceof VideoDecoderOutputBuffer)) {
        return formatChanged;
      }
      VideoDecoderOutputBuffer videoBuffer = (VideoDecoderOutputBuffer) outputBuffer;
      Format baseFormat = getCurrentOutputFormat();
      int width =
          videoBuffer.width > 0
              ? videoBuffer.width
              : (baseFormat.width > 0 ? baseFormat.width : previousFormat.width);
      int height =
          videoBuffer.height > 0
              ? videoBuffer.height
              : (baseFormat.height > 0 ? baseFormat.height : previousFormat.height);
      Format updatedFormat =
          baseFormat.width != width || baseFormat.height != height
              ? baseFormat.buildUpon().setWidth(width).setHeight(height).build()
              : baseFormat;
      videoBuffer.format = updatedFormat;
      setCurrentOutputFormat(updatedFormat);
      return formatChanged
          || updatedFormat.width != previousFormat.width
          || updatedFormat.height != previousFormat.height;
    }
  }

  /**
   * Called when a non-null supported output target is set on {@link #setOutput(Object)}.
   *
   * <p>This hook is never called with {@link C#VIDEO_OUTPUT_MODE_NONE}; clearing the output target
   * leaves the underlying decoder's output mode unchanged.
   *
   * @param outputMode The new output mode (never {@link C#VIDEO_OUTPUT_MODE_NONE}).
   */
  protected void onOutputModeChanged(@C.VideoOutputMode int outputMode) {}

  /**
   * Renders the specified video output buffer to the surface.
   *
   * <p>The implementation of this method takes ownership of the output buffer and is responsible
   * for calling {@link VideoDecoderOutputBuffer#release()} either immediately or in the future
   * (even if an exception is thrown).
   *
   * @param outputBuffer The buffer to render.
   * @param surface The destination surface.
   * @throws DecoderException If rendering fails.
   */
  protected abstract void renderOutputBufferToSurface(
      VideoDecoderOutputBuffer outputBuffer, Surface surface) throws DecoderException;

  @Override
  /* package */ final void renderOutputBuffer(DecoderOutputBuffer outputBuffer) {
    if (!(outputBuffer instanceof VideoDecoderOutputBuffer)) {
      outputBuffer.release();
      return;
    }
    VideoDecoderOutputBuffer videoBuffer = (VideoDecoderOutputBuffer) outputBuffer;
    int currentOutputMode;
    @Nullable Surface currentSurface;
    @Nullable VideoDecoderOutputBufferRenderer currentOutputBufferRenderer;
    synchronized (lock) {
      currentOutputMode = outputMode;
      currentSurface = surface;
      currentOutputBufferRenderer = outputBufferRenderer;
    }
    if (currentOutputMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV
        && currentSurface != null
        && videoBuffer.mode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      try {
        renderOutputBufferToSurface(videoBuffer, currentSurface);
      } catch (DecoderException e) {
        Log.e(TAG, "Error rendering output buffer to surface", e);
        handleDecoderException(e);
      }
    } else if (currentOutputMode == C.VIDEO_OUTPUT_MODE_YUV
        && currentOutputBufferRenderer != null
        && videoBuffer.mode == C.VIDEO_OUTPUT_MODE_YUV) {
      currentOutputBufferRenderer.setOutputBuffer(videoBuffer);
    } else {
      // OutputMode NONE, output target cleared, or buffer mode does not match output mode.
      videoBuffer.release();
    }
  }
}
