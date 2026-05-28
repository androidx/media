package androidx.media3.decoder.mpegh;

import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_CONFIG;
import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import java.nio.ByteBuffer;
import java.util.Set;

public class MpeghBaseDecoder extends
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, MpeghDecoderException> {

  private static final String TAG = "MpeghBaseDecoder";

  /**
   * The default input buffer size.
   */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 2048 * 6;

  /**
   * The maximum padding required for MPEG-H UI manager data added to the input buffer. This is
   * based on the size of a single MPEG-H AU.
   */
  private static final int UI_MANAGER_PADDING_SIZE = 2048;

  private final MpeghUiCommandHelper uiHelper;

  private @Nullable MpeghUiManagerJni uiManager;

  protected int outChannels;
  protected int outSampleRate;
  protected String outSampleMimeType;
  protected @C.Encoding int outPcmEncoding;

  /**
   * Creates an MPEG-H base decoder.
   *
   * @param format           The input {@link Format}.
   * @param numInputBuffers  The number of input buffers.
   * @param numOutputBuffers The number of output buffers.
   * @param uiHelper         A helper class to hold variables/commands which are obtained in the {@link
   *                         MpeghAudioRenderer} and are needed to perform the UI handling.
   * @throws MpeghDecoderException If an exception occurs when initializing the decoder.
   */
  public MpeghBaseDecoder(
      Format format, int numInputBuffers, int numOutputBuffers, MpeghUiCommandHelper uiHelper)
      throws MpeghDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!MpeghLibrary.isAvailable()) {
      throw new MpeghDecoderException("Failed to load decoder native libraries.");
    }

    outChannels = 2;
    outSampleRate = 48000;
    outPcmEncoding = C.ENCODING_INVALID;
    outSampleMimeType = MimeTypes.AUDIO_MPEGH_MHM1;
    String sampleMimeType = format.sampleMimeType;
    if (sampleMimeType != null) {
      outSampleMimeType = sampleMimeType;
    }

    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    // Add padding for MPEG-H UI manager data so we don't need to reallocate at runtime.
    setInitialInputBufferSize(initialInputBufferSize + UI_MANAGER_PADDING_SIZE);

    this.uiHelper = uiHelper;
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
  protected MpeghDecoderException createUnexpectedDecodeException(Throwable error) {
    return new MpeghDecoderException("Unexpected decode error", error);
  }

  @Nullable
  @Override
  protected MpeghDecoderException decode(DecoderInputBuffer inputBuffer,
      SimpleDecoderOutputBuffer outputBuffer, boolean reset) {

    // lazy initialization of UI manager
    if (uiManager == null
        && MimeTypes.AUDIO_MPEGH_MHM1.equals(checkNotNull(inputBuffer.format).sampleMimeType)) {

      ByteBuffer persistenceBuffer = uiHelper.getPersistenceStorage();

      int persistenceBufferSize = 0;
      if (persistenceBuffer != null) {
        persistenceBufferSize = persistenceBuffer.capacity();
      }

      uiManager = new MpeghUiManagerJni();
      try {
        uiManager.init(persistenceBuffer, persistenceBufferSize);
      } catch (MpeghDecoderException e) {
        return e;
      }

      // apply MPEG-H system settings
      for (String command : uiHelper.getCommands(/* includeSystemSettings= */ true)) {
        uiManager.command(command);
      }
    }

    // Get the data from the input buffer.
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();

    if (uiManager != null) {
      // Allow the UI manager to access the whole buffer (including padding).
      inputData.limit(inputData.capacity());

      boolean feedSuccess = uiManager.feed(inputData, inputSize);
      if (feedSuccess) {
        for (String command : uiHelper.getCommands(/* includeSystemSettings= */ false)) {
          uiManager.command(command);
        }

        // process the access unit with the UI manager
        boolean forceUiUpdate = uiHelper.getForceUiUpdate();
        inputSize = uiManager.update(inputData, inputSize, forceUiUpdate);
        inputData.limit(inputSize);
        uiHelper.setForceUiUpdate(false);

        boolean newOsdAvailable = uiManager.newOsdAvailable();
        if (newOsdAvailable) {
          String osdXml = uiManager.getOsd();

          @Nullable Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
          @Nullable
          AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
          if (subscribedKeys != null && dispatcher != null) {
            if (subscribedKeys.contains(CODEC_PARAM_MPEGH_UI_CONFIG)) {
              // reset CodecParameter with KEY_MPEGH_UI_CONFIG to null as it is possible that the
              // last config needs to be resent, because only 'real' changes are propagated
              // further on by audioCodecParametersChanged
              dispatcher.audioCodecParametersChanged(
                  new CodecParameters.Builder()
                      .setString(CODEC_PARAM_MPEGH_UI_CONFIG, null)
                      .build());
              // actually send the current MPEG-H UI config
              dispatcher.audioCodecParametersChanged(
                  new CodecParameters.Builder()
                      .setString(CODEC_PARAM_MPEGH_UI_CONFIG, osdXml)
                      .build());
            }
          }
        }
      }
    }
    return null;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public void release() {
    super.release();

    if (uiManager != null) {
      ByteBuffer persistenceBuffer = uiHelper.getPersistenceStorage();
      if (persistenceBuffer != null) {
        persistenceBuffer.rewind();
        int unused = uiManager.destroy(persistenceBuffer, persistenceBuffer.capacity());
        @Nullable Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
        @Nullable
        AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
        if (subscribedKeys != null && dispatcher != null) {
          if (subscribedKeys.contains(CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER)) {
            dispatcher.audioCodecParametersChanged(
                new CodecParameters.Builder()
                    .setByteBuffer(CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER, persistenceBuffer)
                    .build());
          }
        }
      }
      uiManager = null;
    }
  }

  /**
   * Returns the channel count of output audio.
   */
  protected int getChannelCount() {
    return outChannels;
  }

  /**
   * Returns the sample rate of output audio.
   */
  protected int getSampleRate() {
    return outSampleRate;
  }

  /**
   * Returns the sample mime type of output audio.
   */
  protected String getSampleMimeType() {
    return outSampleMimeType;
  }

  /**
   * Returns the PCM encoding of output audio.
   */
  protected @C.PcmEncoding int getPcmEncoding() {
    return outPcmEncoding;
  }
}
