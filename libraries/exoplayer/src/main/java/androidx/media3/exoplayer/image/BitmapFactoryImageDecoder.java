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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BitmapUtil;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.exoplayer.RendererCapabilities;
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

    @Nullable private final BitmapDecoder bitmapDecoder;

    /** Creates an instance. */
    public Factory() {
      this.bitmapDecoder = null;
    }

    /**
     * @deprecated Use {@link ExternallyLoadedImageDecoder} to control how images are decoded.
     */
    @Deprecated
    public Factory(BitmapDecoder bitmapDecoder) {
      this.bitmapDecoder = bitmapDecoder;
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
      return new BitmapFactoryImageDecoder(bitmapDecoder);
    }
  }

  @Nullable private final BitmapDecoder bitmapDecoder;

  private BitmapFactoryImageDecoder(@Nullable BitmapDecoder bitmapDecoder) {
    super(new DecoderInputBuffer[1], new ImageOutputBuffer[1]);
    this.bitmapDecoder = bitmapDecoder;
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
        outputBuffer.bitmap =
            BitmapUtil.decode(
                inputData.array(),
                inputData.remaining(),
                /* options= */ null,
                /* maximumOutputDimension= */ C.LENGTH_UNSET);
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
