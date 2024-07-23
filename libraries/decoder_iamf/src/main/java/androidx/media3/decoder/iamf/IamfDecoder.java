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
package androidx.media3.decoder.iamf;

import static android.support.annotation.VisibleForTesting.PACKAGE_PRIVATE;

import androidx.annotation.VisibleForTesting;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.util.List;

/** IAMF decoder. */
@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
public final class IamfDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, IamfDecoderException> {

  /**
   * Creates an IAMF decoder.
   *
   * @param initializationData ConfigOBUs data for the decoder.
   * @throws IamfDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public IamfDecoder(List<byte[]> initializationData) throws IamfDecoderException {
    super(new DecoderInputBuffer[0], new SimpleDecoderOutputBuffer[0]);
    int status = iamfConfigDecoder(initializationData.get(0));
    if (status != 0) {
      throw new IamfDecoderException("Failed to configure decoder with returned status: " + status);
    }
  }

  @Override
  public void release() {
    super.release();
    iamfClose();
  }

  public int getBinauralLayoutChannelCount() {
    return iamfLayoutBinauralChannelsCount();
  }

  @Override
  public String getName() {
    return "libiamf";
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected IamfDecoderException createUnexpectedDecodeException(Throwable error) {
    return new IamfDecoderException("Unexpected decode error", error);
  }

  @Override
  protected IamfDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    throw new UnsupportedOperationException();
  }

  private native int iamfLayoutBinauralChannelsCount();

  private native int iamfConfigDecoder(byte[] initializationData);

  private native void iamfClose();
}
