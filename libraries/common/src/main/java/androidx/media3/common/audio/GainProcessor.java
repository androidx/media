/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.common.util.Util.durationUsToSampleCount;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import androidx.annotation.IntRange;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Applies {@linkplain GainProvider gain automation} over an audio stream. */
@UnstableApi
public final class GainProcessor extends BaseAudioProcessor {

  /** Interface that provides sample-level gain automation to be applied on an audio stream. */
  public interface GainProvider {
    /**
     * Returns a gain factor between [0f; 1f] to apply at the given sample position relative to
     * {@code sampleRate}.
     *
     * <p>Returned values must not change for the same pair of parameter values within the lifetime
     * of the instance.
     */
    float getGainFactorAtSamplePosition(
        @IntRange(from = 0) long samplePosition, @IntRange(from = 1) int sampleRate);

    /**
     * Returns the exclusive upper limit of the range starting at {@code samplePosition} where the
     * gain value is 1f (unity), or {@link C#TIME_UNSET} if {@code samplePosition} does not
     * correspond to a gain of 1f.
     *
     * <p>If the range continues until the end of the stream, this method returns {@link
     * C#TIME_END_OF_SOURCE}.
     *
     * <p>Returned values must not change for the same pair of parameter values within the lifetime
     * of the instance.
     *
     * @param samplePosition Inclusive starting position of the unity range.
     * @param sampleRate Sample rate in Hertz related to {@code samplePosition}.
     */
    long isUnityUntil(@IntRange(from = 0) long samplePosition, @IntRange(from = 1) int sampleRate);
  }

  private final GainProvider gainProvider;
  private long readFrames;

  public GainProcessor(GainProvider gainProvider) {
    this.gainProvider = checkNotNull(gainProvider);
  }

  @CanIgnoreReturnValue
  @Override
  public AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    int encoding = inputAudioFormat.encoding;
    if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
      throw new UnhandledAudioFormatException(
          "Invalid PCM encoding. Expected 16 bit PCM or float PCM.", inputAudioFormat);
    }
    return inputAudioFormat;
  }

  @Override
  public boolean isActive() {
    return super.isActive()
        && !Objects.equals(inputAudioFormat, AudioFormat.NOT_SET)
        && gainProvider.isUnityUntil(/* samplePosition= */ 0, inputAudioFormat.sampleRate)
            != C.TIME_END_OF_SOURCE;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    checkState(
        !Objects.equals(inputAudioFormat, AudioFormat.NOT_SET),
        "Audio processor must be configured and flushed before calling queueInput().");

    if (!inputBuffer.hasRemaining()) {
      return;
    }

    checkArgument(
        inputBuffer.remaining() % inputAudioFormat.bytesPerFrame == 0,
        "Queued an incomplete frame.");

    ByteBuffer buffer = replaceOutputBuffer(inputBuffer.remaining());

    // Each iteration handles one frame.
    while (inputBuffer.hasRemaining()) {
      float gain =
          gainProvider.getGainFactorAtSamplePosition(readFrames, inputAudioFormat.sampleRate);
      if (gain == 1f) {
        int oldLimit = inputBuffer.limit();

        long regionEnd = gainProvider.isUnityUntil(readFrames, inputAudioFormat.sampleRate);
        checkState(regionEnd != C.TIME_UNSET, "Expected a valid end boundary for unity region.");

        // Only set limit if unity does not last until EoS.
        if (regionEnd != C.TIME_END_OF_SOURCE) {
          long limitOffsetBytes = (regionEnd - readFrames) * inputAudioFormat.bytesPerFrame;
          inputBuffer.limit(min(oldLimit, (int) limitOffsetBytes + inputBuffer.position()));
        }

        readFrames += inputBuffer.remaining() / inputAudioFormat.bytesPerFrame;
        buffer.put(inputBuffer);
        inputBuffer.limit(oldLimit);
      } else {
        for (int i = 0; i < inputAudioFormat.channelCount; i++) {
          switch (inputAudioFormat.encoding) {
            case C.ENCODING_PCM_16BIT:
              buffer.putShort((short) (inputBuffer.getShort() * gain));
              break;
            case C.ENCODING_PCM_FLOAT:
              buffer.putFloat(inputBuffer.getFloat() * gain);
              break;
            default:
              throw new IllegalStateException(
                  "Unexpected PCM encoding: " + inputAudioFormat.encoding);
          }
        }
        readFrames++;
      }
    }
    buffer.flip();
  }

  @Override
  public void onFlush(StreamMetadata streamMetadata) {
    readFrames =
        durationUsToSampleCount(streamMetadata.positionOffsetUs, inputAudioFormat.sampleRate);
  }

  @Override
  public void onReset() {
    readFrames = 0;
  }
}
