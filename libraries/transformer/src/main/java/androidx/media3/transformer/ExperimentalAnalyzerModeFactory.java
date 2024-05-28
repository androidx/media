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

import static androidx.media3.common.util.Assertions.checkState;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.media3.muxer.Muxer;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/**
 * Factory for creating instances of {@link Transformer} that can be used to analyze media.
 *
 * <p>When using {@link Transformer} to analyze decoded data, users should provide their analysis
 * effects through the {@link EditedMediaItem#effects}.
 *
 * <p>This class is experimental and will be renamed or removed in a future release.
 */
@UnstableApi
public final class ExperimentalAnalyzerModeFactory {

  private ExperimentalAnalyzerModeFactory() {}

  /**
   * Builds a {@link Transformer} that runs as an analyzer.
   *
   * <p>No encoding or muxing is performed, therefore no data is written to any output files.
   *
   * @param context The {@link Context}.
   * @return The analyzer {@link Transformer}.
   */
  public static Transformer buildAnalyzer(Context context) {
    return buildAnalyzer(context, new Transformer.Builder(context).build());
  }

  /**
   * Builds a {@link Transformer} that runs as an analyzer.
   *
   * <p>No encoding or muxing is performed, therefore no data is written to any output files.
   *
   * @param context The {@link Context}.
   * @param transformer The {@link Transformer} to be built upon.
   * @return The analyzer {@link Transformer}.
   */
  public static Transformer buildAnalyzer(Context context, Transformer transformer) {
    return transformer
        .buildUpon()
        .experimentalSetTrimOptimizationEnabled(false)
        .setEncoderFactory(new DroppingEncoder.Factory(context))
        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
        .setMuxerFactory(
            new NoWriteMuxer.Factory(
                /* audioMimeTypes= */ ImmutableList.of(MimeTypes.AUDIO_AAC),
                /* videoMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264)))
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .build();
  }

  /** A {@linkplain Codec encoder} implementation that drops input and produces no output. */
  private static final class DroppingEncoder implements Codec {
    public static final class Factory implements Codec.EncoderFactory {
      private final Context context;

      public Factory(Context context) {
        this.context = context;
      }

      @Override
      public Codec createForAudioEncoding(Format format) {
        return new DroppingEncoder(context, format);
      }

      @Override
      public Codec createForVideoEncoding(Format format) {
        return new DroppingEncoder(context, format);
      }
    }

    private static final String TAG = "DroppingEncoder";
    private static final int INTERNAL_BUFFER_SIZE = 8196;

    private final Context context;
    private final Format configurationFormat;
    private final ByteBuffer buffer;

    private boolean inputStreamEnded;

    public DroppingEncoder(Context context, Format format) {
      this.context = context;
      this.configurationFormat = format;
      buffer = ByteBuffer.allocateDirect(INTERNAL_BUFFER_SIZE).order(ByteOrder.nativeOrder());
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
      return PlaceholderSurface.newInstance(context, /* secure= */ false);
    }

    @Override
    @EnsuresNonNullIf(expression = "#1.data", result = true)
    public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) {
      if (inputStreamEnded) {
        return false;
      }
      inputBuffer.data = buffer;
      return true;
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
      checkState(
          !inputStreamEnded, "Input buffer can not be queued after the input stream has ended.");
      if (inputBuffer.isEndOfStream()) {
        inputStreamEnded = true;
      }
      inputBuffer.clear();
      inputBuffer.data = null;
    }

    @Override
    public void signalEndOfInputStream() {
      inputStreamEnded = true;
    }

    @Override
    @Nullable
    public Format getOutputFormat() {
      return configurationFormat;
    }

    @Override
    @Nullable
    public ByteBuffer getOutputBuffer() {
      return null;
    }

    @Override
    @Nullable
    public BufferInfo getOutputBufferInfo() {
      return null;
    }

    @Override
    public boolean isEnded() {
      return inputStreamEnded;
    }

    @Override
    public void releaseOutputBuffer(boolean render) {}

    @Override
    public void releaseOutputBuffer(long renderPresentationTimeUs) {}

    @Override
    public void release() {}
  }

  /** A {@link Muxer} implementation that does nothing. */
  private static final class NoWriteMuxer implements Muxer {
    public static final class Factory implements Muxer.Factory {

      private final ImmutableList<String> audioMimeTypes;
      private final ImmutableList<String> videoMimeTypes;

      /**
       * Creates an instance.
       *
       * @param audioMimeTypes The audio {@linkplain MimeTypes mime types} to return in {@link
       *     #getSupportedSampleMimeTypes(int)}.
       * @param videoMimeTypes The video {@linkplain MimeTypes mime types} to return in {@link
       *     #getSupportedSampleMimeTypes(int)}.
       */
      public Factory(ImmutableList<String> audioMimeTypes, ImmutableList<String> videoMimeTypes) {
        this.audioMimeTypes = audioMimeTypes;
        this.videoMimeTypes = videoMimeTypes;
      }

      @Override
      public Muxer create(String path) {
        return new NoWriteMuxer();
      }

      @Override
      public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
        if (trackType == C.TRACK_TYPE_AUDIO) {
          return audioMimeTypes;
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
          return videoMimeTypes;
        }
        return ImmutableList.of();
      }
    }

    @Override
    public TrackToken addTrack(Format format) {
      return new TrackToken() {};
    }

    @Override
    public void writeSampleData(
        TrackToken trackToken, ByteBuffer data, MediaCodec.BufferInfo bufferInfo) {}

    @Override
    public void addMetadataEntry(Metadata.Entry metadataEntry) {}

    @Override
    public void close() {}
  }
}
