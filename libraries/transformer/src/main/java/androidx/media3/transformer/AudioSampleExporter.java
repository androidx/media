/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import android.media.metrics.LogSessionId;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.DebugTraceUtil;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.checkerframework.dataflow.qual.Pure;

/** Processes, encodes and muxes raw audio samples. */
/* package */ final class AudioSampleExporter extends SampleExporter {

  private final Codec encoder;
  private final AudioFormat encoderInputAudioFormat;
  private final DecoderInputBuffer nextEncoderInputBuffer;
  private final DecoderInputBuffer encoderOutputBuffer;
  private final AudioGraph audioGraph;

  private final AudioGraphInput firstInput;
  private final Format firstInputFormat;

  private boolean returnedFirstInput;
  private long encoderTotalInputBytes;
  @Nullable private DecoderInputBuffer partiallyFilledEncoderInputBuffer;

  public AudioSampleExporter(
      Format firstAssetLoaderTrackFormat,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      EditedMediaItem firstEditedMediaItem,
      ImmutableList<AudioProcessor> compositionAudioProcessors,
      AudioMixer.Factory mixerFactory,
      Codec.EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      FallbackListener fallbackListener,
      @Nullable LogSessionId logSessionId)
      throws ExportException {
    super(firstAssetLoaderTrackFormat, muxerWrapper);
    SonicAudioProcessor outputResampler = new SonicAudioProcessor();
    audioGraph =
        new AudioGraph(
            mixerFactory,
            new ImmutableList.Builder<AudioProcessor>()
                .addAll(compositionAudioProcessors)
                .add(outputResampler)
                .build());
    this.firstInputFormat = firstInputFormat;
    AudioGraphInput currentFirstInput =
        audioGraph.registerInput(firstEditedMediaItem, firstInputFormat);
    AudioFormat currentEncoderInputAudioFormat = audioGraph.getOutputAudioFormat();
    checkState(!currentEncoderInputAudioFormat.equals(AudioFormat.NOT_SET));

    Format requestedEncoderFormat =
        new Format.Builder()
            .setSampleMimeType(
                transformationRequest.audioMimeType != null
                    ? transformationRequest.audioMimeType
                    : checkNotNull(firstAssetLoaderTrackFormat.sampleMimeType))
            .setSampleRate(currentEncoderInputAudioFormat.sampleRate)
            .setChannelCount(currentEncoderInputAudioFormat.channelCount)
            .setPcmEncoding(currentEncoderInputAudioFormat.encoding)
            .setCodecs(firstInputFormat.codecs)
            .build();

    // TODO: b/324426022 - Move logic for supported mime types to DefaultEncoderFactory.
    encoder =
        encoderFactory.createForAudioEncoding(
            requestedEncoderFormat
                .buildUpon()
                .setSampleMimeType(
                    findSupportedMimeTypeForEncoderAndMuxer(
                        requestedEncoderFormat,
                        muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)))
                .build(),
            logSessionId);

    AudioFormat actualEncoderAudioFormat = new AudioFormat(encoder.getInputFormat());
    // This occurs when the encoder does not support the requested format. In this case, the audio
    // graph output needs to be resampled to a sample rate matching the encoder input to avoid
    // distorted audio.
    if (actualEncoderAudioFormat.sampleRate != currentEncoderInputAudioFormat.sampleRate) {
      audioGraph.reset();
      outputResampler.setOutputSampleRateHz(actualEncoderAudioFormat.sampleRate);
      currentFirstInput = audioGraph.registerInput(firstEditedMediaItem, firstInputFormat);
      currentEncoderInputAudioFormat = audioGraph.getOutputAudioFormat();
    }
    this.firstInput = currentFirstInput;
    this.encoderInputAudioFormat = currentEncoderInputAudioFormat;

    nextEncoderInputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);
    encoderOutputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);

    fallbackListener.onTransformationRequestFinalized(
        createFallbackTransformationRequest(
            transformationRequest,
            requestedEncoderFormat,
            /* actualFormat= */ encoder.getConfigurationFormat()));
  }

  @Override
  public AudioGraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex)
      throws ExportException {
    if (!returnedFirstInput) {
      // First input initialized in constructor because output AudioFormat is needed.
      returnedFirstInput = true;
      checkState(format.equals(this.firstInputFormat));
      return firstInput;
    }
    return audioGraph.registerInput(editedMediaItem, format);
  }

  @Override
  public void release() {
    audioGraph.reset();
    encoder.release();
  }

  @Override
  protected boolean processDataUpToMuxer() throws ExportException {
    // Check if encoder is ready.
    if (partiallyFilledEncoderInputBuffer == null) {
      if (!encoder.maybeDequeueInputBuffer(nextEncoderInputBuffer)) {
        return false;
      }
    }
    if (audioGraph.isEnded()) {
      // Feed any remaining data from partiallyFilledEncoderInputBuffer.
      if (partiallyFilledEncoderInputBuffer != null) {
        maybeFeedEncoder();
      }
      DebugTraceUtil.logEvent(
          DebugTraceUtil.COMPONENT_AUDIO_GRAPH,
          DebugTraceUtil.EVENT_OUTPUT_ENDED,
          C.TIME_END_OF_SOURCE);
      queueEndOfStreamToEncoder();
      return false;
    }

    return maybeFeedEncoder();
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    return encoder.getOutputFormat();
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    encoderOutputBuffer.data = encoder.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    encoderOutputBuffer.timeUs = checkNotNull(encoder.getOutputBufferInfo()).presentationTimeUs;
    encoderOutputBuffer.setFlags(C.BUFFER_FLAG_KEY_FRAME);
    return encoderOutputBuffer;
  }

  @Override
  protected void releaseMuxerInputBuffer() throws ExportException {
    encoder.releaseOutputBuffer(/* render= */ false);
  }

  @Override
  protected boolean isMuxerInputEnded() {
    return encoder.isEnded();
  }

  /** Tries to feed the encoder, if there is enough data available. */
  private boolean maybeFeedEncoder() throws ExportException {
    DecoderInputBuffer encoderInputBuffer =
        partiallyFilledEncoderInputBuffer == null
            ? nextEncoderInputBuffer
            : partiallyFilledEncoderInputBuffer;
    ByteBuffer encoderInputBufferData = checkNotNull(encoderInputBuffer.data);
    // Keep retrieving as much data from the AudioGraph but do not block if data is not yet
    // available.
    while (!audioGraph.isEnded()
        && audioGraph.getOutput().hasRemaining()
        && encoderInputBufferData.remaining() > 0) {
      ByteBuffer audioGraphBuffer = audioGraph.getOutput();
      int audioDataSize = audioGraphBuffer.remaining();
      int bytesToRead = min(audioDataSize, encoderInputBufferData.remaining());
      int audioGraphBufferLimit = audioGraphBuffer.limit();
      audioGraphBuffer.limit(audioGraphBuffer.position() + bytesToRead);
      encoderInputBufferData.put(audioGraphBuffer);
      audioGraphBuffer.limit(audioGraphBufferLimit);
    }
    // Queue input buffer only when input buffer is full or there is no more data.
    if (encoderInputBufferData.remaining() == 0 || audioGraph.isEnded()) {
      encoderInputBuffer.timeUs = getOutputAudioDurationUs();
      encoderTotalInputBytes += encoderInputBufferData.position();
      encoderInputBuffer.setFlags(0);
      encoderInputBuffer.flip();
      encoder.queueInputBuffer(encoderInputBuffer);
      partiallyFilledEncoderInputBuffer = null;
      return true;
    } else {
      partiallyFilledEncoderInputBuffer = encoderInputBuffer;
      return false;
    }
  }

  private void queueEndOfStreamToEncoder() throws ExportException {
    checkState(
        partiallyFilledEncoderInputBuffer == null
            && checkNotNull(nextEncoderInputBuffer.data).position() == 0);
    nextEncoderInputBuffer.timeUs = getOutputAudioDurationUs();
    nextEncoderInputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    nextEncoderInputBuffer.flip();
    // Queuing EOS should only occur with an empty buffer.
    encoder.queueInputBuffer(nextEncoderInputBuffer);
  }

  @Pure
  private static TransformationRequest createFallbackTransformationRequest(
      TransformationRequest transformationRequest, Format requestedFormat, Format actualFormat) {
    // TODO: b/255953153 - Consider including bitrate and other audio characteristics in the revised
    //  fallback.
    if (Objects.equals(requestedFormat.sampleMimeType, actualFormat.sampleMimeType)) {
      return transformationRequest;
    }
    return transformationRequest.buildUpon().setAudioMimeType(actualFormat.sampleMimeType).build();
  }

  private long getOutputAudioDurationUs() {
    long totalFramesWritten = encoderTotalInputBytes / encoderInputAudioFormat.bytesPerFrame;
    return (totalFramesWritten * C.MICROS_PER_SECOND) / encoderInputAudioFormat.sampleRate;
  }
}
