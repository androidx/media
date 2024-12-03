/*
 * Copyright 2024 The Android Open Source Project
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

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec.BufferInfo;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/**
 * Factory for creating instances of {@link Transformer} that can be used to extract frames.
 *
 * <p>This class is experimental and will be renamed or removed in a future release.
 */
/* package */ final class ExperimentalFrameExtractorFactory {

  private ExperimentalFrameExtractorFactory() {}

  /** A callback to be notified when a new image is available. */
  public interface Listener {

    // TODO: b/350498258 - Make this more user-friendly before making it a public API.
    /**
     * Called when a new {@link Image} is available. When this method returns, the {@link Image}
     * will be closed and can no longer be used.
     */
    void onImageAvailable(Image image);
  }

  /**
   * Builds a {@link Transformer} that runs as an analyzer.
   *
   * <p>No encoding or muxing is performed, therefore no data is written to any output files.
   *
   * @param context The {@link Context}.
   * @param listener The {@link Listener} to be used for generated images.
   * @return The fame extracting {@link Transformer}.
   */
  public static Transformer buildFrameExtractorTransformer(Context context, Listener listener) {
    return new Transformer.Builder(context)
        .experimentalSetTrimOptimizationEnabled(false)
        .setEncoderFactory(new ImageReaderEncoder.Factory(listener))
        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
        .setMuxerFactory(
            new NoWriteMuxer.Factory(
                /* audioMimeTypes= */ ImmutableList.of(MimeTypes.AUDIO_AAC),
                /* videoMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264)))
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .experimentalSetMaxFramesInEncoder(1) // Work around ImageReader frame dropping.
        .build();
  }

  /** A {@linkplain Codec encoder} implementation that outputs frames via {@link ImageReader}. */
  private static final class ImageReaderEncoder implements Codec {
    public static final class Factory implements Codec.EncoderFactory {

      private final Listener listener;

      public Factory(Listener listener) {
        this.listener = listener;
      }

      @Override
      public Codec createForAudioEncoding(Format format) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Codec createForVideoEncoding(Format format) {
        return new ImageReaderEncoder(format, listener);
      }
    }

    private static final String TAG = "ImageReaderEncoder";
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0);

    private final Format configurationFormat;
    private final ImageReader imageReader;
    private final Queue<Long> processedImageTimestampsNs;
    private final BufferInfo outputBufferInfo;

    private boolean hasOutputBuffer;
    private boolean inputStreamEnded;

    public ImageReaderEncoder(Format format, Listener listener) {
      this.configurationFormat = format;
      imageReader =
          ImageReader.newInstance(
              format.width, format.height, PixelFormat.RGBA_8888, /* maxImages= */ 1);

      processedImageTimestampsNs = new ConcurrentLinkedQueue<>();

      imageReader.setOnImageAvailableListener(
          reader -> {
            try (Image image = reader.acquireNextImage()) {
              processedImageTimestampsNs.add(image.getTimestamp());
              listener.onImageAvailable(image);
            }
          },
          Util.createHandlerForCurrentOrMainLooper());

      outputBufferInfo = new BufferInfo();
    }

    @Override
    public String getName() {
      return TAG;
    }

    @Override
    public Format getConfigurationFormat() {
      return configurationFormat;
    }

    @Override
    public Surface getInputSurface() {
      return imageReader.getSurface();
    }

    @Override
    @EnsuresNonNullIf(expression = "#1.data", result = true)
    public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void signalEndOfInputStream() {
      inputStreamEnded = true;
    }

    @Override
    public Format getOutputFormat() {
      return configurationFormat;
    }

    @Override
    @Nullable
    public ByteBuffer getOutputBuffer() {
      return maybeGenerateOutputBuffer() ? EMPTY_BUFFER : null;
    }

    @Override
    @Nullable
    public BufferInfo getOutputBufferInfo() {
      return maybeGenerateOutputBuffer() ? outputBufferInfo : null;
    }

    @Override
    public boolean isEnded() {
      return inputStreamEnded && processedImageTimestampsNs.isEmpty();
    }

    @Override
    public void releaseOutputBuffer(boolean render) {
      releaseOutputBuffer();
    }

    @Override
    public void releaseOutputBuffer(long renderPresentationTimeUs) {
      releaseOutputBuffer();
    }

    private void releaseOutputBuffer() {
      hasOutputBuffer = false;
    }

    @Override
    public void release() {}

    private boolean maybeGenerateOutputBuffer() {
      if (hasOutputBuffer) {
        return true;
      }
      Long timeNs = processedImageTimestampsNs.poll();
      if (timeNs == null) {
        return false;
      }

      hasOutputBuffer = true;
      outputBufferInfo.presentationTimeUs = timeNs / 1000;
      return true;
    }
  }
}
