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

import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_CONFIG;
import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import java.nio.ByteBuffer;
import java.util.Set;

/** MPEG-H decoder. */
@UnstableApi
public final class MpeghDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, MpeghDecoderException> {

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 2048 * 6;

  /**
   * The maximum padding required for MPEG-H UI manager data added to the input buffer. This is
   * based on the size of a single MPEG-H AU.
   */
  private static final int UI_MANAGER_PADDING_SIZE = 2048;

  private static final int TARGET_LAYOUT_CICP = 2;

  private final ByteBuffer tmpOutputBuffer;

  private MpeghDecoderJni decoder;
  private long outPtsUs;
  private int outChannels;
  private int outSampleRate;

  private final MpeghUiCommandHelper uiHelper;
  private @Nullable MpeghUiManagerJni uiManager;

  /**
   * Creates an MPEG-H decoder.
   *
   * @param format The input {@link Format}.
   * @param numInputBuffers The number of input buffers.
   * @param numOutputBuffers The number of output buffers.
   * @param uiHelper A helper class to hold variables/commands which are obtained in the {@link
   *     MpeghAudioRenderer} and are needed to perform the UI handling
   * @throws MpeghDecoderException If an exception occurs when initializing the decoder.
   */
  public MpeghDecoder(
      Format format, int numInputBuffers, int numOutputBuffers, MpeghUiCommandHelper uiHelper)
      throws MpeghDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!MpeghLibrary.isAvailable()) {
      throw new MpeghDecoderException("Failed to load decoder native libraries.");
    }

    byte[] configData = new byte[0];
    if (!format.initializationData.isEmpty()
        && MimeTypes.AUDIO_MPEGH_MHA1.equals(format.sampleMimeType)) {
      configData = format.initializationData.get(0);
    }

    // Initialize the native MPEG-H decoder.
    decoder = new MpeghDecoderJni();
    decoder.init(TARGET_LAYOUT_CICP, configData, configData.length);

    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    // Add padding for MPEG-H UI manager data so we don't need to reallocate at runtime.
    setInitialInputBufferSize(initialInputBufferSize + UI_MANAGER_PADDING_SIZE);

    // Allocate memory for the temporary output of the native MPEG-H decoder.
    tmpOutputBuffer =
        ByteBuffer.allocateDirect(
            3072 * 24 * 6
                * 2); // MAX_FRAME_LENGTH * MAX_NUM_CHANNELS * MAX_NUM_FRAMES * BYTES_PER_SAMPLE

    this.uiHelper = uiHelper;
  }

  @Override
  public String getName() {
    return "libmpegh";
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
      for (String command : uiHelper.getCommands(true)) {
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
        for (String command : uiHelper.getCommands(false)) {
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

          Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
          AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
          if (subscribedKeys != null && dispatcher != null) {
            if (subscribedKeys.contains(CODEC_PARAM_MPEGH_UI_CONFIG)) {
              // reset CodecParameter with KEY_MPEGH_UI_CONFIG to null as it is possible that the
              // last config needs to be resend, because only 'real' changes are propagated
              // further on by audioCodecParametersChanged
              CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
              codecParametersBuilder.setString(CODEC_PARAM_MPEGH_UI_CONFIG, null);
              dispatcher.audioCodecParametersChanged(codecParametersBuilder.build());
              // actually send the current MPEG-H UI config
              codecParametersBuilder = new CodecParameters.Builder();
              codecParametersBuilder.setString(CODEC_PARAM_MPEGH_UI_CONFIG, osdXml);
              dispatcher.audioCodecParametersChanged(codecParametersBuilder.build());
            }
          }
        }
      }
    }

    long inputPtsUs = inputBuffer.timeUs;

    // Process/decode the incoming data.
    try {
      decoder.process(inputData, inputSize, inputPtsUs);
    } catch (MpeghDecoderException e) {
      return e;
    }

    // Get as many decoded samples as possible.
    int outputSize;
    int numBytes = 0;
    int cnt = 0;
    tmpOutputBuffer.clear();
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

    if (uiManager != null) {
      ByteBuffer persistenceBuffer = uiHelper.getPersistenceStorage();
      if (persistenceBuffer != null) {
        persistenceBuffer.rewind();
        int unused = uiManager.destroy(persistenceBuffer, persistenceBuffer.capacity());
        Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
        AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
        if (subscribedKeys != null && dispatcher != null) {
          if (subscribedKeys.contains(CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER)) {
            CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
            codecParametersBuilder.setByteBuffer(
                CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER, persistenceBuffer);
            dispatcher.audioCodecParametersChanged(codecParametersBuilder.build());
          }
        }
      }
      uiManager = null;
    }

    if (decoder != null) {
      decoder.destroy();
      decoder = null;
    }
  }

  /** Returns the channel count of output audio. */
  public int getChannelCount() {
    return outChannels;
  }

  /** Returns the sample rate of output audio. */
  public int getSampleRate() {
    return outSampleRate;
  }
}
