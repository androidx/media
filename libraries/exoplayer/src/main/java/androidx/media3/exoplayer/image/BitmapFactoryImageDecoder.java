/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.image;

import static androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BitmapUtil;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.exoplayer.RendererCapabilities;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An image decoder that uses {@link BitmapFactory} to decode images.
 *
 * <p>Only supports decoding one input buffer into one output buffer (i.e. one {@link Bitmap}
 * alongside one timestamp)).
 */
@UnstableApi
public final class BitmapFactoryImageDecoder
    extends SimpleDecoder<DecoderInputBuffer, ImageOutputBuffer, ImageDecoderException>
    implements ImageDecoder {

  /**
   * @deprecated Use {@link ExternallyLoadedImageDecoder} to control how images are decoded.
   */
  @Deprecated
  @VisibleForTesting
  public interface BitmapDecoder {

    /**
     * Decodes data into a {@link Bitmap}.
     *
     * @param data An array holding the data to be decoded, starting at position 0.
     * @param length The length of the input to be decoded.
     * @return The decoded {@link Bitmap}.
     * @throws ImageDecoderException If a decoding error occurs.
     */
    Bitmap decode(byte[] data, int length) throws ImageDecoderException;
  }

  /** A factory for {@link BitmapFactoryImageDecoder} instances. */
  public static final class Factory implements ImageDecoder.Factory {

    // TODO: Remove @Nullable from this field (and all related null checks) when the deprecated
    // zero-args constructor is removed.
    @Nullable private final Context context;
    @Nullable private final BitmapDecoder bitmapDecoder;
    private int maxOutputSize;

    /**
     * @deprecated Use {@link Factory#Factory(Context)} instead.
     */
    @Deprecated
    public Factory() {
      this(/* context= */ null, /* bitmapDecoder= */ null);
    }

    /** Creates an instance. */
    public Factory(Context context) {
      this(context, /* bitmapDecoder= */ null);
    }

    /**
     * @deprecated Use {@link ExternallyLoadedImageDecoder} to control how images are decoded.
     */
    @Deprecated
    public Factory(BitmapDecoder bitmapDecoder) {
      this(/* context= */ null, bitmapDecoder);
    }

    private Factory(@Nullable Context context, @Nullable BitmapDecoder bitmapDecoder) {
      this.context = context;
      this.bitmapDecoder = bitmapDecoder;
      this.maxOutputSize = C.LENGTH_UNSET;
    }

    /**
     * Sets the maximum size of {@link Bitmap} instances decoded by decoders produced by this
     * factory.
     *
     * <p>This overrides any maximum size derived from the display via {@link Context}. Passing
     * {@link C#LENGTH_UNSET} clears any max output size set.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setMaxOutputSize(int maxOutputSize) {
      checkArgument(maxOutputSize == C.LENGTH_UNSET || maxOutputSize > 0);
      this.maxOutputSize = maxOutputSize;
      return this;
    }

    @Override
    public @RendererCapabilities.Capabilities int supportsFormat(Format format) {
      if (format.sampleMimeType == null || !MimeTypes.isImage(format.sampleMimeType)) {
        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
      }
      return isBitmapFactorySupportedMimeType(format.sampleMimeType)
          ? RendererCapabilities.create(C.FORMAT_HANDLED)
          : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }

    @Override
    public BitmapFactoryImageDecoder createImageDecoder() {
      return new BitmapFactoryImageDecoder(context, bitmapDecoder, maxOutputSize);
    }
  }

  @Nullable private final Context context;
  @Nullable private final BitmapDecoder bitmapDecoder;
  private final int maxOutputSize;

  private BitmapFactoryImageDecoder(
      @Nullable Context context, @Nullable BitmapDecoder bitmapDecoder, int maxOutputSize) {
    super(new DecoderInputBuffer[1], new ImageOutputBuffer[1]);
    this.context = context;
    this.bitmapDecoder = bitmapDecoder;
    this.maxOutputSize = maxOutputSize;
  }

  @Override
  public String getName() {
    return "BitmapFactoryImageDecoder";
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_NORMAL);
  }

  @Override
  protected ImageOutputBuffer createOutputBuffer() {
    return new ImageOutputBuffer() {
      @Override
      public void release() {
        BitmapFactoryImageDecoder.this.releaseOutputBuffer(this);
      }
    };
  }

  @Override
  protected ImageDecoderException createUnexpectedDecodeException(Throwable error) {
    return new ImageDecoderException("Unexpected decode error", error);
  }

  @Nullable
  @Override
  protected ImageDecoderException decode(
      DecoderInputBuffer inputBuffer, ImageOutputBuffer outputBuffer, boolean reset) {
    ByteBuffer inputData = checkNotNull(inputBuffer.data);
    checkState(inputData.hasArray());
    checkArgument(inputData.arrayOffset() == 0);
    if (bitmapDecoder != null) {
      try {
        outputBuffer.bitmap = bitmapDecoder.decode(inputData.array(), inputData.remaining());
      } catch (ImageDecoderException e) {
        return e;
      }
    } else {
      try {
        int maxSize;
        if (this.maxOutputSize != C.LENGTH_UNSET) {
          maxSize = maxOutputSize;
        } else if (context != null) {
          Point currentDisplayModeSize = Util.getCurrentDisplayModeSize(context);
          int maxWidth = currentDisplayModeSize.x;
          int maxHeight = currentDisplayModeSize.y;
          if (inputBuffer.format != null) {
            if (inputBuffer.format.tileCountHorizontal != Format.NO_VALUE) {
              maxWidth *= inputBuffer.format.tileCountHorizontal;
            }
            if (inputBuffer.format.tileCountVertical != Format.NO_VALUE) {
              maxHeight *= inputBuffer.format.tileCountVertical;
            }
          }
          // BitmapUtil.decode can only downscale in powers of 2, so nearly doubling the max size
          // ensures that an image is never downscaled to be smaller than the max size.
          maxSize = max(maxWidth, maxHeight) * 2 - 1;
        } else {
          // If we can't get the display size, fallback to a sensible default.
          maxSize = GlUtil.MAX_BITMAP_DECODING_SIZE;
        }

        outputBuffer.bitmap =
            BitmapUtil.decode(
                inputData.array(),
                inputData.remaining(),
                /* options= */ null,
                /* maximumOutputDimension= */ maxSize);
      } catch (ParserException e) {
        return new ImageDecoderException("Could not decode image data with BitmapFactory.", e);
      } catch (IOException e) {
        return new ImageDecoderException(e);
      }
    }
    outputBuffer.timeUs = inputBuffer.timeUs;
    return null;
  }
}
