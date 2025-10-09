/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.util.Util.getByteDepth;
import static androidx.media3.common.util.Util.getInt24;
import static androidx.media3.common.util.Util.isEncodingLinearPcm;
import static androidx.media3.common.util.Util.putInt24;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An {@link AudioProcessor} that applies a mapping from input channels onto specified output
 * channels. This can be used to reorder, duplicate or discard channels.
 */
@UnstableApi
public final class ChannelMappingAudioProcessor extends BaseAudioProcessor {

  @Nullable private int[] pendingOutputChannels;
  @Nullable private int[] outputChannels;

  /**
   * Resets the channel mapping. After calling this method, call {@link #configure(AudioFormat)} to
   * start using the new channel map.
   *
   * <p>See {@link AudioSink#configure(Format, int, int[])}.
   *
   * @param outputChannels The mapping from input to output channel indices, or {@code null} to
   *     leave the input unchanged.
   */
  public void setChannelMap(@Nullable int[] outputChannels) {
    pendingOutputChannels = outputChannels;
  }

  @Override
  public AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    @Nullable int[] outputChannels = pendingOutputChannels;
    if (outputChannels == null) {
      return AudioFormat.NOT_SET;
    }

    if (!isEncodingLinearPcm(inputAudioFormat.encoding)) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }

    boolean active = inputAudioFormat.channelCount != outputChannels.length;
    for (int i = 0; i < outputChannels.length; i++) {
      int channelIndex = outputChannels[i];
      if (channelIndex >= inputAudioFormat.channelCount) {
        throw new UnhandledAudioFormatException(
            "Channel map ("
                + Arrays.toString(outputChannels)
                + ") trying to access non-existent input channel.",
            inputAudioFormat);
      }
      active |= (channelIndex != i);
    }
    return active
        ? new AudioFormat(
            inputAudioFormat.sampleRate, outputChannels.length, inputAudioFormat.encoding)
        : AudioFormat.NOT_SET;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    int[] outputChannels = checkNotNull(this.outputChannels);
    int position = inputBuffer.position();
    int limit = inputBuffer.limit();
    int frameCount = (limit - position) / inputAudioFormat.bytesPerFrame;
    int outputSize = frameCount * outputAudioFormat.bytesPerFrame;
    ByteBuffer buffer = replaceOutputBuffer(outputSize);
    while (position < limit) {
      for (int channelIndex : outputChannels) {
        int inputIndex = position + getByteDepth(inputAudioFormat.encoding) * channelIndex;
        switch (inputAudioFormat.encoding) {
          case C.ENCODING_PCM_8BIT:
            buffer.put(inputBuffer.get(inputIndex));
            break;
          case C.ENCODING_PCM_16BIT:
          case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
            buffer.putShort(inputBuffer.getShort(inputIndex));
            break;
          case C.ENCODING_PCM_24BIT:
          case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
            putInt24(buffer, getInt24(inputBuffer, inputIndex));
            break;
          case C.ENCODING_PCM_32BIT:
          case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
            buffer.putInt(inputBuffer.getInt(inputIndex));
            break;
          case C.ENCODING_PCM_FLOAT:
            buffer.putFloat(inputBuffer.getFloat(inputIndex));
            break;
          case C.ENCODING_PCM_DOUBLE:
            buffer.putDouble(inputBuffer.getDouble(inputIndex));
            break;
          default:
            throw new IllegalStateException("Unexpected encoding: " + inputAudioFormat.encoding);
        }
      }
      position += inputAudioFormat.bytesPerFrame;
    }
    inputBuffer.position(limit);
    buffer.flip();
  }

  @Override
  protected void onFlush() {
    outputChannels = pendingOutputChannels;
  }

  @Override
  protected void onReset() {
    outputChannels = null;
    pendingOutputChannels = null;
  }
}
