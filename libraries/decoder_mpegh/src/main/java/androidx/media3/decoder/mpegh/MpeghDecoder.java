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
import java.util.Objects;
import java.util.Set;

/** MPEG-H decoder. */
@UnstableApi
public final class MpeghDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, MpeghDecoderException> {

  private static final String TAG = "MpeghDecoder";

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 2048 * 6;

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
   * @param helper A helper class to hold variables/commands which are obtained in the
   *     MpeghAudioRender and are needed to perform the UI handling
   * @throws MpeghDecoderException If an exception occurs when initializing the decoder.
   */
  public MpeghDecoder(
      Format format, int numInputBuffers, int numOutputBuffers, MpeghUiCommandHelper helper)
      throws MpeghDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!MpeghLibrary.isAvailable()) {
      throw new MpeghDecoderException("Failed to load decoder native libraries.");
    }

    byte[] configData = new byte[0];
    if (!format.initializationData.isEmpty()
        && Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_MPEGH_MHA1)) {
      configData = format.initializationData.get(0);
    }

    // Initialize the native MPEG-H decoder.
    decoder = new MpeghDecoderJni();
    decoder.init(TARGET_LAYOUT_CICP, configData, configData.length);

    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    setInitialInputBufferSize(initialInputBufferSize);

    // Allocate memory for the temporary output of the native MPEG-H decoder.
    tmpOutputBuffer =
        ByteBuffer.allocateDirect(
            3072 * 24 * 6
                * 2); // MAX_FRAME_LENGTH * MAX_NUM_CHANNELS * MAX_NUM_FRAMES * BYTES_PER_SAMPLE

    uiHelper = helper;
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
        && Objects.equals(inputBuffer.format.sampleMimeType, MimeTypes.AUDIO_MPEGH_MHM1)) {

      ByteBuffer persistence_buffer = uiHelper.getPersistenceStorage();

      int persistence_buffer_size = 0;
      if (persistence_buffer != null) {
        persistence_buffer_size = persistence_buffer.capacity();
      }

      uiManager = new MpeghUiManagerJni();
      try {
        uiManager.init(persistence_buffer, persistence_buffer_size);
      } catch (MpeghDecoderException e) {
        return e;
      }

      // apply MPEG-H system settings
      for (String command : uiHelper.getCommands(true)) {
        uiManager.command(command);
      }
    }

    if (uiManager != null) {
      // check if there is enough space to write additional data to the access unit in the UI
      // manager
      if (inputBuffer.data.remaining() + 1000 > inputBuffer.data.capacity()) {
        ByteBuffer tmp = ByteBuffer.allocateDirect(inputBuffer.data.capacity() + 1000);
        inputBuffer.data.rewind();
        int limit = inputBuffer.data.limit();
        tmp.put(inputBuffer.data);
        inputBuffer.data = tmp;
        inputBuffer.data.limit(limit);
        inputBuffer.data.rewind();
      }

      // Get the data from the input buffer.
      ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
      int inputSize = inputData.remaining();
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

        boolean newOSDavailable = uiManager.newOsdAvailable();
        if (newOSDavailable) {
          String osdxml = uiManager.getOsd();

          Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
          AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
          if (subscribedKeys != null && dispatcher != null) {
            if (subscribedKeys.contains("mpegh-ui-config")) {
              // reset CodecParameter with KEY_MPEGH_UI_CONFIG to null as it is possible that the
              // last config needs to be resend, because only 'real' changes are propagated
              // further on by audioCodecParametersChanged
              CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
              codecParametersBuilder.setString("mpegh-ui-config", null);
              dispatcher.audioCodecParametersChanged(codecParametersBuilder.build());
              // actually send the current MPEG-H UI config
              codecParametersBuilder = new CodecParameters.Builder();
              codecParametersBuilder.setString("mpegh-ui-config", osdxml);
              dispatcher.audioCodecParametersChanged(codecParametersBuilder.build());
            }
          }
        }
      }
    }

    // Get the data from the input buffer.
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    long inputPtsUs = inputBuffer.timeUs;

    // Process/decode the incoming data.
    try {
      decoder.process(inputData, inputSize, inputPtsUs);
    } catch (MpeghDecoderException e) {
      return e;
    }

    // Get as many decoded samples as possible.
    int outputSize = 0;
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
      ByteBuffer persistence_buffer = uiHelper.getPersistenceStorage();
      if (persistence_buffer != null) {
        persistence_buffer.rewind();
        int persistence_buffer_capacity = persistence_buffer.capacity();
        int size = uiManager.destroy(persistence_buffer, persistence_buffer_capacity);
        Set<String> subscribedKeys = uiHelper.getSubscribedCodecParameterKeys();
        AudioRendererEventListener.EventDispatcher dispatcher = uiHelper.getEventDispatcher();
        if (subscribedKeys != null && dispatcher != null) {
          if (subscribedKeys.contains("mpegh-ui-persistence-buffer")) {
            CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
            codecParametersBuilder.setByteBuffer("mpegh-ui-persistence-buffer", persistence_buffer);
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
