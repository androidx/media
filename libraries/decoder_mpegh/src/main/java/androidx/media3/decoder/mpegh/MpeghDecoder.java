package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

/**
 * Mpegh decoder.
 */
@UnstableApi
public final class MpeghDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, MpeghException> {

  private static final String TAG = "MpeghDecoder";

  private final static int TARGETLAYOUT_CICP = 2;

  private MpeghDecoderJni decoder;
  private long outPtsUs;
  private int outChannels;
  private int outSamplerate;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 2048 * 6;

  private final ByteBuffer tmpOutputBuffer;

  /**
   * Creates an Mpegh decoder.
   * @param numInputBuffers The number of input buffers.
   * @param numOutputBuffers The number of output buffers.
   * @param format Input format.
   * @throws MpeghException Thrown if an exception occurs when initializing the decoder.
   */
  public MpeghDecoder(
      int numInputBuffers, int numOutputBuffers, Format format) throws MpeghException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!MpeghLibrary.isAvailable()) {
      throw new MpeghException("Failed to load decoder native libraries.");
    }

    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;

    byte[] configData = new byte[0];
    if (!format.initializationData.isEmpty()
        && MimeTypes.AUDIO_MPEGH_MHA1.equalsIgnoreCase(format.sampleMimeType)) {
      configData = format.initializationData.get(0);
    }

    // initialize the native MPEG-H decoder
    decoder = new MpeghDecoderJni();
    decoder.init(TARGETLAYOUT_CICP, configData, configData.length);

    setInitialInputBufferSize(initialInputBufferSize);

    // allocate memory for the temporary output of the native MPEG-H decoder
    tmpOutputBuffer = ByteBuffer.allocateDirect(3072 * 24 * 6 * 2); // MAX_FRAME_LENGTH * MAX_NUM_CHANNELS * MAX_NUM_FRAMES * BYTES_PER_SAMPLE
  }

  @Override
  public String getName() {
    return TAG;
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
  protected MpeghException createUnexpectedDecodeException(Throwable error) {
    return new MpeghException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected MpeghException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      try {
        decoder.flush();
      } catch (MpeghException e) {
        return e;
      }
    }

    // check if end of stream is reached
    // in this case flushing and getting the remaining samples
    // of the native MPEG-H decoder would be necessary
    // but this state is never reached (?)
    boolean eos = inputBuffer.isEndOfStream();

    // get the data from the input buffer
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    long inputPts = inputBuffer.timeUs;

    // process/decode the incoming data
    try {
      decoder.process(inputData, inputSize, inputPts);
    } catch (MpeghException e) {
      e.printStackTrace();
    }

    // get as many decoded samples as possible
    int outputSize = 0;
    int numBytes = 0;
    int cnt = 0;
    tmpOutputBuffer.clear();
    do {
      try {
        outputSize = decoder.getSamples(tmpOutputBuffer, numBytes);
      } catch (MpeghException e) {
        return e;
      }
      // to concatenate possible additional audio frames, increase the write position
      numBytes += outputSize;

      if (cnt == 0 && outputSize > 0) {
        // only use the first frame for info about PTS, number of channels and sample rate
        outPtsUs = decoder.getPts();
        outChannels = decoder.getNumChannels();
        outSamplerate = decoder.getSamplerate();
      }

      cnt++;
    } while (outputSize > 0);

    int outputSizeTotal = numBytes;
    tmpOutputBuffer.limit(outputSizeTotal);

    if (outputSizeTotal > 0) {
      // there is output data available

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

    if (decoder != null) {
      decoder.destroy();
      decoder = null;
    }
  }

  /**
   * Returns the channel count of output audio.
   */
  public int getChannelCount() {
    return outChannels;
  }

  /**
   * Returns the sample rate of output audio.
   */
  public int getSampleRate() {
    return outSamplerate;
  }
}
