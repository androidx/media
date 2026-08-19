package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

public class MpeghPassThroughDecoder extends MpeghBaseDecoder {

  private static final String TAG = "MpeghPassThroughDecoder";
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
  public MpeghPassThroughDecoder(
      Format format, int numInputBuffers, int numOutputBuffers, MpeghUiCommandHelper uiHelper)
      throws MpeghDecoderException {
    super(format, numInputBuffers, numOutputBuffers, uiHelper);

    outChannels = 2;
    outSampleRate = 48000;
    outSampleMimeType = MimeTypes.AUDIO_MPEGH_MHM1;
    outPcmEncoding = C.ENCODING_INVALID;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  @Nullable
  protected MpeghDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {

    MpeghDecoderException exception = super.decode(inputBuffer, outputBuffer, reset);
    if (exception != null) {
      return exception;
    }

    // Get the data from the input buffer.
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    if (inputData.remaining() > 0) {
      // There is output data available

      // initialize the output buffer
      outputBuffer.clear();
      outputBuffer.init(inputBuffer.timeUs, inputData.remaining());

      // copy temporary output to output buffer
      outputBuffer.data.put(inputData);
      outputBuffer.data.rewind();
    } else {
      // if no output data is available signalize that only decoding/processing was possible
      outputBuffer.shouldBeSkipped = true;
    }
    return null;
  }
}
