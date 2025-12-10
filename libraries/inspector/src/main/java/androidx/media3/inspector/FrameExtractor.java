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

package androidx.media3.inspector;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.media.MediaCodec;
import android.view.SurfaceView;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts decoded frames from {@link MediaItem}.
 *
 * <p>Frame extractor instances must be accessed from a single application thread.
 *
 * <p>This class may produce incorrect or washed out colors, or images that have too high contrast
 * for inputs not covered by <a
 * href="https://cs.android.com/android/_/android/platform/cts/+/aaa242e5c26466cf245fa85ff8a7750378de9d72:tests/media/src/android/mediav2/cts/DecodeGlAccuracyTest.java;drc=d0d5ff338f8b84adf9066358bac435b1be3bbe61;l=534">testDecodeGlAccuracyRGB
 * CTS test</a>. That is:
 *
 * <ul>
 *   <li>Inputs of BT.601 limited range are likely to produce accurate output with either
 *       {@linkplain MediaCodecSelector#PREFER_SOFTWARE software} or {@linkplain
 *       MediaCodecSelector#DEFAULT hardware} decoders across a wide range of devices.
 *   <li>Other inputs are likely to produce accurate output when using {@linkplain
 *       MediaCodecSelector#DEFAULT hardware} decoders on devices that are launched with API 33 or
 *       later.
 *   <li>HDR inputs will produce a {@link Bitmap} with {@link ColorSpace.Named#BT2020_HLG}. There
 *       are no guarantees that an HLG {@link Bitmap} displayed in {@link ImageView} and an HLG
 *       video displayed in {@link SurfaceView} will look the same.
 *   <li>Depending on the device and input video, color inaccuracies can be mitigated with an
 *       appropriate {@link RgbMatrix} effect.
 * </ul>
 */
@UnstableApi
public final class FrameExtractor implements AutoCloseable {

  static {
    MediaLibraryInfo.registerModule("media3.inspector");
  }

  /** A builder for {@link FrameExtractor} instances. */
  public static final class Builder {
    private final Context context;
    private final MediaItem mediaItem;
    private List<Effect> effects;
    private SeekParameters seekParameters;
    private MediaCodecSelector mediaCodecSelector;
    private boolean extractHdrFrames;
    @Nullable private GlObjectsProvider glObjectsProvider;
    @Nullable private MediaSource.Factory mediaSourceFactory;

    /**
     * Creates a new instance.
     *
     * @param context The {@link Context}.
     * @param mediaItem The {@link MediaItem} from which to extract frames.
     */
    public Builder(Context context, MediaItem mediaItem) {
      this.context = context;
      this.mediaItem = mediaItem;
      this.effects = ImmutableList.of();
      seekParameters = SeekParameters.DEFAULT;
      // TODO: b/350498258 - Consider a switch to MediaCodecSelector.DEFAULT. Some hardware
      // MediaCodec decoders crash when flushing (seeking) and setVideoEffects is used. See also
      // b/362904942.
      mediaCodecSelector = MediaCodecSelector.PREFER_SOFTWARE;
      extractHdrFrames = false;
    }

    /**
     * Sets the {@link Effect Effects} to apply to the extracted video frames.
     *
     * @param effects The {@link List} of {@linkplain Effect video effects}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEffects(List<Effect> effects) {
      this.effects = effects;
      return this;
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

    /**
     * Sets the {@link MediaSource.Factory} to be used to create the {@link MediaSource} for the
     * given {@link MediaItem}. If not set, a {@link DefaultMediaSourceFactory} will be used.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      this.mediaSourceFactory = checkNotNull(mediaSourceFactory);
      return this;
    }

    /** Builds a new {@link FrameExtractor} instance. */
    public FrameExtractor build() {
      return new FrameExtractor(this);
    }
  }

  /** Stores an extracted and decoded video frame. */
  public static final class Frame {

    /** The presentation timestamp of the extracted frame, in milliseconds. */
    public final long presentationTimeMs;

    /** The extracted frame contents. */
    public final Bitmap bitmap;

    /* package */ Frame(long presentationTimeMs, Bitmap bitmap) {
      this.presentationTimeMs = presentationTimeMs;
      this.bitmap = bitmap;
    }
  }

  private final Context context;
  private final MediaItem mediaItem;
  private final ImmutableList<Effect> effects;
  private final SeekParameters seekParameters;
  private final MediaCodecSelector mediaCodecSelector;
  private final boolean extractHdrFrames;
  @Nullable private final GlObjectsProvider glObjectsProvider;
  @Nullable private MediaSource.Factory mediaSourceFactory;
  private final AtomicBoolean released;

  private FrameExtractor(Builder builder) {
    this.context = builder.context;
    this.mediaItem = builder.mediaItem;
    this.effects = ImmutableList.copyOf(builder.effects);
    this.seekParameters = builder.seekParameters;
    this.mediaCodecSelector = builder.mediaCodecSelector;
    this.extractHdrFrames = builder.extractHdrFrames;
    this.glObjectsProvider = builder.glObjectsProvider;
    this.mediaSourceFactory = builder.mediaSourceFactory;
    released = new AtomicBoolean(false);
    FrameExtractorInternal.getInstance().addReference();
  }

  /**
   * Extracts a representative {@link Frame} for the specified video position.
   *
   * @param positionMs The time position in the {@link MediaItem} for which a frame is extracted, in
   *     milliseconds.
   * @return A {@link ListenableFuture} of the result.
   */
  public ListenableFuture<Frame> getFrame(long positionMs) {
    if (released.get()) {
      return Futures.immediateFailedFuture(
          new IllegalStateException("getFrame() called on a released FrameExtractor."));
    }
    FrameExtractorInternal.FrameExtractionRequest request =
        new FrameExtractorInternal.FrameExtractionRequest(
            this.context,
            this.mediaItem,
            this.effects,
            this.seekParameters,
            this.mediaCodecSelector,
            this.glObjectsProvider,
            this.mediaSourceFactory,
            this.extractHdrFrames,
            positionMs);

    return FrameExtractorInternal.getInstance().submitTask(request);
  }

  public ListenableFuture<Frame> getThumbnail() {
    if (released.get()) {
      return Futures.immediateFailedFuture(
          new IllegalStateException("getThumbnail() called on a released FrameExtractor."));
    }
    FrameExtractorInternal.FrameExtractionRequest request =
        new FrameExtractorInternal.FrameExtractionRequest(
            context,
            mediaItem,
            effects,
            SeekParameters.NEXT_SYNC,
            mediaCodecSelector,
            glObjectsProvider,
            mediaSourceFactory,
            extractHdrFrames,
            /* positionMs= */ C.TIME_UNSET);
    return FrameExtractorInternal.getInstance().submitTask(request);
  }

  /**
   * Releases the underlying resources. This method must be called when the frame extractor is no
   * longer required. The frame extractor must not be used after calling this method.
   */
  @Override
  public void close() {
    if (released.getAndSet(true)) {
      return;
    }
    FrameExtractorInternal.getInstance().releaseReference();
  }

  @VisibleForTesting
  /* package */ ListenableFuture<@NullableType DecoderCounters> getDecoderCounters() {
    return FrameExtractorInternal.getInstance().getDecoderCounters();
  }
}
