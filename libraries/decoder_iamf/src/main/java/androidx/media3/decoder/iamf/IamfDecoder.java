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
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import javax.annotation.Nullable;

/** IAMF decoder. */
@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
public final class IamfDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, IamfDecoderException> {
  // TODO(ktrajkovski): Fetch channel count from the device instead of hardcoding.
  /* package */ static final int DEFAULT_CHANNEL_COUNT = 2;
  /* package */ static final int DEFAULT_OUTPUT_SAMPLE_RATE = 48000;
  /* package */ static final @C.PcmEncoding int DEFAULT_PCM_ENCODING = C.ENCODING_PCM_16BIT;

  private final byte[] initializationData;

  /**
   * Creates an IAMF decoder.
   *
   * @param initializationData ConfigOBUs data for the decoder.
   * @throws IamfDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public IamfDecoder(List<byte[]> initializationData) throws IamfDecoderException {
    super(new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[1]);
    if (initializationData.size() != 1) {
      throw new IamfDecoderException("Initialization data must contain a single element.");
    }
    this.initializationData = initializationData.get(0);
    int status =
        iamfConfigDecoder(
            this.initializationData,
            Util.getByteDepth(DEFAULT_PCM_ENCODING) * C.BITS_PER_BYTE,
            DEFAULT_OUTPUT_SAMPLE_RATE,
            DEFAULT_CHANNEL_COUNT);
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
  @Nullable
  protected IamfDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      iamfClose();
      iamfConfigDecoder(
          this.initializationData,
          Util.getByteDepth(DEFAULT_PCM_ENCODING) * C.BITS_PER_BYTE,
          DEFAULT_OUTPUT_SAMPLE_RATE,
          DEFAULT_CHANNEL_COUNT); // reconfigure
    }
    int bufferSize =
        iamfGetMaxFrameSize() * DEFAULT_CHANNEL_COUNT * Util.getByteDepth(DEFAULT_PCM_ENCODING);
    outputBuffer.init(inputBuffer.timeUs, bufferSize);
    ByteBuffer outputData = Util.castNonNull(outputBuffer.data);
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int ret = iamfDecode(inputData, inputData.limit(), outputData);
    if (ret < 0) {
      return new IamfDecoderException("Failed to decode error= " + ret);
    }
    outputData.position(0);
    outputData.limit(ret * DEFAULT_CHANNEL_COUNT * Util.getByteDepth(DEFAULT_PCM_ENCODING));
    return null;
  }

  private native int iamfLayoutBinauralChannelsCount();

  private native int iamfConfigDecoder(
      byte[] initializationData, int bitDepth, int sampleRate, int channelCount);

  private native void iamfClose();

  private native int iamfDecode(ByteBuffer inputBuffer, int inputSize, ByteBuffer outputBuffer);

  /**
   * Returns the maximum expected number of PCM samples per channel in a compressed audio frame.
   * Used to initialize the output buffer.
   */
  private native int iamfGetMaxFrameSize();
}
