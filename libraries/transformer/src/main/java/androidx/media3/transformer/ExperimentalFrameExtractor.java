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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.media.MediaCodec;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.inspector.FrameExtractor;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * @deprecated Use {@link androidx.media3.inspector.FrameExtractor} instead.
 */
@Deprecated
@UnstableApi
public final class ExperimentalFrameExtractor {

  /** Configuration for the frame extractor. */
  public static final class Configuration {

    /** A builder for {@link Configuration} instances. */
    public static final class Builder {
      private SeekParameters seekParameters;
      private MediaCodecSelector mediaCodecSelector;
      private boolean extractHdrFrames;
      @Nullable private GlObjectsProvider glObjectsProvider;

      /** Creates a new instance with default values. */
      public Builder() {
        seekParameters = SeekParameters.DEFAULT;
        // TODO: b/350498258 - Consider a switch to MediaCodecSelector.DEFAULT. Some hardware
        // MediaCodec decoders crash when flushing (seeking) and setVideoEffects is used. See also
        // b/362904942.
        mediaCodecSelector = MediaCodecSelector.PREFER_SOFTWARE;
        extractHdrFrames = false;
      }

      /**
       * Sets the parameters that control how seek operations are performed. Defaults to {@link
       * SeekParameters#DEFAULT}.
       *
       * @param seekParameters The {@link SeekParameters}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setSeekParameters(SeekParameters seekParameters) {
        this.seekParameters = seekParameters;
        return this;
      }

      /**
       * Sets the {@linkplain MediaCodecSelector selector} of {@link MediaCodec} instances. Defaults
       * to {@link MediaCodecSelector#PREFER_SOFTWARE}.
       *
       * @param mediaCodecSelector The {@link MediaCodecSelector}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setMediaCodecSelector(MediaCodecSelector mediaCodecSelector) {
        this.mediaCodecSelector = mediaCodecSelector;
        return this;
      }

      /**
       * Sets whether HDR {@link Frame#bitmap} should be extracted from HDR videos.
       *
       * <p>When set to {@code false}, extracted HDR frames will be tone-mapped to {@link
       * ColorSpace.Named#BT709}.
       *
       * <p>When set to {@code true}, extracted HDR frames will have a high bit depth {@link
       * Bitmap.Config} and {@link ColorSpace.Named#BT2020_HLG}. Extracting HDR frames is only
       * supported on API 34+.
       *
       * <p>This flag has no effect when the input is SDR.
       *
       * <p>Defaults to {@code false}.
       *
       * @param extractHdrFrames Whether HDR frames should be returned.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      @RequiresApi(34)
      public Builder setExtractHdrFrames(boolean extractHdrFrames) {
        this.extractHdrFrames = extractHdrFrames;
        return this;
      }

      /**
       * Sets the {@link GlObjectsProvider} to be used by the effect processing pipeline.
       *
       * <p>By default, a {@link DefaultGlObjectsProvider} is used.
       *
       * @param glObjectsProvider The {@link GlObjectsProvider}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setGlObjectsProvider(GlObjectsProvider glObjectsProvider) {
        this.glObjectsProvider = glObjectsProvider;
        return this;
      }

      /** Builds a new {@link Configuration} instance. */
      public Configuration build() {
        return new Configuration(
            seekParameters, mediaCodecSelector, extractHdrFrames, glObjectsProvider);
      }
    }

    /** The {@link SeekParameters}. */
    public final SeekParameters seekParameters;

    /** The {@link MediaCodecSelector}. */
    public final MediaCodecSelector mediaCodecSelector;

    /** Whether extracting HDR frames is requested. */
    public final boolean extractHdrFrames;

    /** The {@link GlObjectsProvider}. */
    @Nullable public final GlObjectsProvider glObjectsProvider;

    private Configuration(
        SeekParameters seekParameters,
        MediaCodecSelector mediaCodecSelector,
        boolean extractHdrFrames,
        @Nullable GlObjectsProvider glObjectsProvider) {
      this.seekParameters = seekParameters;
      this.mediaCodecSelector = mediaCodecSelector;
      this.extractHdrFrames = extractHdrFrames;
      this.glObjectsProvider = glObjectsProvider;
    }
  }

  /** Stores an extracted and decoded video frame. */
  public static final class Frame {

    /** The presentation timestamp of the extracted frame, in milliseconds. */
    public final long presentationTimeMs;

    /** The extracted frame contents. */
    public final Bitmap bitmap;

    private Frame(long presentationTimeMs, Bitmap bitmap) {
      this.presentationTimeMs = presentationTimeMs;
      this.bitmap = bitmap;
    }
  }

  private final Context context;
  private final Configuration configuration;
  private @MonotonicNonNull FrameExtractor inspectorFrameExtractor;
  private @MonotonicNonNull MediaItem mediaItem;
  private @MonotonicNonNull List<Effect> effects;
  private ListenableFuture<?> lastFrameFuture;

  /**
   * Creates an instance.
   *
   * @param context {@link Context}.
   * @param configuration The {@link Configuration} for this frame extractor.
   */
  public ExperimentalFrameExtractor(Context context, Configuration configuration) {
    this.context = context;
    this.configuration = configuration;
    this.lastFrameFuture = Futures.immediateVoidFuture();
  }

  /**
   * Sets a new {@link MediaItem}.
   *
   * <p>Changing between SDR and HDR {@link MediaItem}s is not supported when {@link
   * Configuration#extractHdrFrames} is true.
   *
   * @param mediaItem The {@link MediaItem} from which frames will be extracted.
   * @param effects The {@link List} of {@linkplain Effect video effects} to apply to the extracted
   *     video frames.
   */
  public void setMediaItem(MediaItem mediaItem, List<Effect> effects) {
    ListenableFuture<?> previousFrameFuture = lastFrameFuture;
    lastFrameFuture =
        Futures.whenAllComplete(previousFrameFuture)
            .call(
                () -> {
                  this.mediaItem = mediaItem;
                  this.effects = effects;
                  if (inspectorFrameExtractor != null) {
                    inspectorFrameExtractor.close();
                  }
                  inspectorFrameExtractor = createFrameExtractor(configuration.seekParameters);
                  return "ExperimentalFrameExtractor.setMediaItem";
                },
                directExecutor());
  }

  /**
   * Extracts a representative {@link Frame} for the specified video position using the {@link
   * SeekParameters} specified in the {@link Configuration}.
   *
   * @param positionMs The time position in the {@link MediaItem} for which a frame is extracted, in
   *     milliseconds.
   * @return A {@link ListenableFuture} of the result.
   */
  public ListenableFuture<Frame> getFrame(long positionMs) {
    ListenableFuture<Frame> frameFuture =
        Futures.transformAsync(
            lastFrameFuture,
            unused -> {
              checkState(inspectorFrameExtractor != null, "setMediaItem must be called first.");
              return convertFuture(inspectorFrameExtractor.getFrame(positionMs));
            },
            directExecutor());
    lastFrameFuture = frameFuture;
    return frameFuture;
  }

  /**
   * Extracts a representative {@link Frame} for the specified video position using the requested
   * {@link SeekParameters}.
   *
   * @param positionMs The time position in the {@link MediaItem} for which a frame is extracted, in
   *     milliseconds.
   * @param seekParameters The {@link SeekParameters}.
   * @return A {@link ListenableFuture} of the result.
   */
  public ListenableFuture<Frame> getFrame(long positionMs, SeekParameters seekParameters) {
    ListenableFuture<Frame> frameFuture =
        Futures.transformAsync(
            lastFrameFuture,
            unused -> {
              checkState(
                  mediaItem != null && effects != null, "setMediaItem must be called first.");
              FrameExtractor temporaryFrameExtractor = createFrameExtractor(seekParameters);
              ListenableFuture<FrameExtractor.Frame> future =
                  temporaryFrameExtractor.getFrame(positionMs);
              future.addListener(temporaryFrameExtractor::close, directExecutor());
              return convertFuture(future);
            },
            directExecutor());
    lastFrameFuture = frameFuture;
    return frameFuture;
  }

  private FrameExtractor createFrameExtractor(SeekParameters seekParameters) {
    FrameExtractor.Builder builder =
        new FrameExtractor.Builder(context, checkNotNull(mediaItem))
            .setEffects(checkNotNull(effects))
            .setSeekParameters(seekParameters)
            .setMediaCodecSelector(configuration.mediaCodecSelector);
    if (configuration.glObjectsProvider != null) {
      builder.setGlObjectsProvider(configuration.glObjectsProvider);
    }
    if (Build.VERSION.SDK_INT >= 34) {
      builder.setExtractHdrFrames(configuration.extractHdrFrames);
    }
    return builder.build();
  }

  private ListenableFuture<Frame> convertFuture(
      ListenableFuture<FrameExtractor.Frame> inspectorFuture) {
    return Futures.transform(
        inspectorFuture,
        inspectorFrame -> new Frame(inspectorFrame.presentationTimeMs, inspectorFrame.bitmap),
        directExecutor());
  }

  /**
   * Releases the underlying resources. This method must be called when the frame extractor is no
   * longer required. The frame extractor must not be used after calling this method.
   */
  public void release() {
    lastFrameFuture.addListener(
        () -> {
          if (inspectorFrameExtractor != null) {
            inspectorFrameExtractor.close();
          }
        },
        directExecutor());
  }
}
