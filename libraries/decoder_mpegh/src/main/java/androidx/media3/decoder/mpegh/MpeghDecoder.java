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
package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

/** MPEG-H decoder. */
@UnstableApi
public final class MpeghDecoder extends MpeghBaseDecoder {

  private static final int TARGET_LAYOUT_CICP = 2;

  private final MpeghDecoderJni decoder;
  private long outPtsUs;

  private final ByteBuffer tmpOutputBuffer;

  /**
   * Creates an MPEG-H decoder.
   *
   * @param format           The input {@link Format}.
   * @param numInputBuffers  The number of input buffers.
   * @param numOutputBuffers The number of output buffers.
   * @param uiHelper         A helper class to hold variables/commands which are obtained in the {@link
   *                         MpeghAudioRenderer} and are needed to perform the UI handling.
   * @throws MpeghDecoderException If an exception occurs when initializing the decoder.
   */
  public MpeghDecoder(
      Format format, int numInputBuffers, int numOutputBuffers, MpeghUiCommandHelper uiHelper)
      throws MpeghDecoderException {
    super(format, numInputBuffers, numOutputBuffers, uiHelper);

    outChannels = 2;
    outSampleRate = 48000;
    outSampleMimeType = MimeTypes.AUDIO_RAW;
    outPcmEncoding = C.ENCODING_PCM_16BIT;

    // Allocate memory for the temporary output of the native MPEG-H decoder.
    tmpOutputBuffer =
        ByteBuffer.allocateDirect(
            3072 * 24 * 6
                * 2); // MAX_FRAME_LENGTH * MAX_NUM_CHANNELS * MAX_NUM_FRAMES * BYTES_PER_SAMPLE

    byte[] configData = new byte[0];
    if (!format.initializationData.isEmpty()
        && MimeTypes.AUDIO_MPEGH_MHA1.equals(format.sampleMimeType)) {
      configData = format.initializationData.get(0);
    }

    // Initialize the native MPEG-H decoder.
    decoder = new MpeghDecoderJni();
    decoder.init(TARGET_LAYOUT_CICP, configData, configData.length);
  }

  @Override
  public String getName() {
    return "libmpegh";
  }

  @Override
  @Nullable
  protected MpeghDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {

    if (reset) {
      try {
        decoder.flush();
      } catch (MpeghDecoderException e) {
        return e;
      }
    }

    MpeghDecoderException exception = super.decode(inputBuffer, outputBuffer, reset);
    if (exception != null) {
      return exception;
    }

    // Get the data from the input buffer.
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();

    int numBytes = 0;
    tmpOutputBuffer.clear();

    long inputPtsUs = inputBuffer.timeUs;

    // Process/decode the incoming data.
    try {
      decoder.process(inputData, inputSize, inputPtsUs);
    } catch (MpeghDecoderException e) {
      return e;
    }

    // Get as many decoded samples as possible.
    int outputSize;
    int cnt = 0;
    do {
      try {
        outputSize = decoder.getSamples(tmpOutputBuffer, numBytes);
      } catch (MpeghDecoderException e) {
        return e;
      }
      // To concatenate possible additional audio frames, increase the write position.
      numBytes += outputSize;

      if (cnt == 0 && outputSize > 0) {
        // Only use the first frame for info about PTS, number of channels and sample rate.
        outPtsUs = decoder.getPts();
        outChannels = decoder.getNumChannels();
        outSampleRate = decoder.getSamplerate();
      }

      cnt++;
    } while (outputSize > 0);

    int outputSizeTotal = numBytes;
    tmpOutputBuffer.limit(outputSizeTotal);

    if (outputSizeTotal > 0) {
      // There is output data available

      // initialize the output buffer
      outputBuffer.clear();
      outputBuffer.init(outPtsUs, outputSizeTotal);

      // copy temporary output to output buffer
      outputBuffer.data.asShortBuffer().put(tmpOutputBuffer.asShortBuffer());

      outputBuffer.data.rewind();
    } else {
      // if no output data is available signalize that only decoding/processing was possible
      outputBuffer.shouldBeSkipped = true;
    }
    return null;
  }

  @Override
  public void release() {
    super.release();
    decoder.destroy();
  }
}
