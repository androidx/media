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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED;
import static androidx.media3.transformer.DefaultCodec.DEFAULT_PCM_ENCODING;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.checkerframework.dataflow.qual.Pure;

/** Pipeline to process, re-encode and mux raw audio samples. */
/* package */ final class AudioSamplePipeline extends SamplePipeline {

  private static final int MAX_INPUT_BUFFER_COUNT = 10;
  private static final int DEFAULT_ENCODER_BITRATE = 128 * 1024;

  private final SilentAudioGenerator silentAudioGenerator;
  private final Queue<DecoderInputBuffer> availableInputBuffers;
  private final Queue<DecoderInputBuffer> pendingInputBuffers;
  private final AudioProcessingPipeline audioProcessingPipeline;
  private final Codec encoder;
  private final AudioFormat encoderInputAudioFormat;
  private final DecoderInputBuffer encoderInputBuffer;
  private final DecoderInputBuffer encoderOutputBuffer;

  private long nextEncoderInputBufferTimeUs;
  private long encoderBufferDurationRemainder;

  private volatile boolean queueEndOfStreamAfterSilence;

  // TODO(b/260618558): Move silent audio generation upstream of this component.
  public AudioSamplePipeline(
      Format firstAssetLoaderInputFormat,
      Format firstPipelineInputFormat,
      long streamOffsetUs,
      TransformationRequest transformationRequest,
      boolean flattenForSlowMotion,
      ImmutableList<AudioProcessor> audioProcessors,
      Codec.EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      FallbackListener fallbackListener)
      throws ExportException {
    super(firstAssetLoaderInputFormat, /* streamStartPositionUs= */ streamOffsetUs, muxerWrapper);

    silentAudioGenerator = new SilentAudioGenerator(firstPipelineInputFormat);
    availableInputBuffers = new ConcurrentLinkedDeque<>();
    ByteBuffer emptyBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    for (int i = 0; i < MAX_INPUT_BUFFER_COUNT; i++) {
      DecoderInputBuffer inputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DIRECT);
      inputBuffer.data = emptyBuffer;
      availableInputBuffers.add(inputBuffer);
    }
    pendingInputBuffers = new ConcurrentLinkedDeque<>();

    encoderInputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);
    encoderOutputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);

    if (flattenForSlowMotion && firstAssetLoaderInputFormat.metadata != null) {
      audioProcessors =
          new ImmutableList.Builder<AudioProcessor>()
              .add(
                  new SpeedChangingAudioProcessor(
                      new SegmentSpeedProvider(firstAssetLoaderInputFormat.metadata)))
              .addAll(audioProcessors)
              .build();
    }

    audioProcessingPipeline = new AudioProcessingPipeline(audioProcessors);
    // TODO(b/267301878): Once decoder format propagated, remove setting default PCM encoding.
    AudioFormat pipelineInputAudioFormat =
        new AudioFormat(
            firstPipelineInputFormat.sampleRate,
            firstPipelineInputFormat.channelCount,
            firstPipelineInputFormat.pcmEncoding != Format.NO_VALUE
                ? firstPipelineInputFormat.pcmEncoding
                : DEFAULT_PCM_ENCODING);

    try {
      encoderInputAudioFormat = audioProcessingPipeline.configure(pipelineInputAudioFormat);
    } catch (AudioProcessor.UnhandledAudioFormatException unhandledAudioFormatException) {
      throw ExportException.createForAudioProcessing(
          unhandledAudioFormatException, pipelineInputAudioFormat);
    }

    audioProcessingPipeline.flush();

    Format requestedEncoderFormat =
        new Format.Builder()
            .setSampleMimeType(
                transformationRequest.audioMimeType != null
                    ? transformationRequest.audioMimeType
                    : checkNotNull(firstAssetLoaderInputFormat.sampleMimeType))
            .setSampleRate(encoderInputAudioFormat.sampleRate)
            .setChannelCount(encoderInputAudioFormat.channelCount)
            .setPcmEncoding(encoderInputAudioFormat.encoding)
            .setAverageBitrate(DEFAULT_ENCODER_BITRATE)
            .build();

    encoder =
        encoderFactory.createForAudioEncoding(
            requestedEncoderFormat
                .buildUpon()
                .setSampleMimeType(
                    findSupportedMimeTypeForEncoderAndMuxer(
                        requestedEncoderFormat,
                        muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)))
                .build());

    fallbackListener.onTransformationRequestFinalized(
        createFallbackTransformationRequest(
            transformationRequest,
            requestedEncoderFormat,
            /* actualFormat= */ encoder.getConfigurationFormat()));

    // Use the same stream offset as the input stream for encoder input buffers.
    nextEncoderInputBufferTimeUs = streamOffsetUs;
  }

  @Override
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem,
      long durationUs,
      @Nullable Format trackFormat,
      boolean isLast) {
    if (trackFormat == null) {
      silentAudioGenerator.addSilence(durationUs);
      if (isLast) {
        queueEndOfStreamAfterSilence = true;
      }
    }
  }

  @Override
  @Nullable
  public DecoderInputBuffer getInputBuffer() {
    if (shouldGenerateSilence()) {
      return null;
    }
    return availableInputBuffers.peek();
  }

  @Override
  public boolean queueInputBuffer() {
    DecoderInputBuffer inputBuffer = availableInputBuffers.remove();
    pendingInputBuffers.add(inputBuffer);
    return true;
  }

  @Override
  public void release() {
    audioProcessingPipeline.reset();
    encoder.release();
  }

  @Override
  protected boolean processDataUpToMuxer() throws ExportException {
    if (!audioProcessingPipeline.isOperational()) {
      return feedEncoderFromInput();
    }

    return feedEncoderFromProcessingPipeline() || feedProcessingPipelineFromInput();
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

  /**
   * Attempts to pass input data to the encoder.
   *
   * @return Whether it may be possible to feed more data immediately by calling this method again.
   */
  private boolean feedEncoderFromInput() throws ExportException {
    if (!encoder.maybeDequeueInputBuffer(encoderInputBuffer)) {
      return false;
    }

    if (shouldGenerateSilence()) {
      feedEncoder(silentAudioGenerator.getBuffer());
      return true;
    }

    if (pendingInputBuffers.isEmpty()) {
      // Only read volatile variable queueEndOfStreamAfterSilence if there is a chance that end of
      // stream should be queued.
      if (!silentAudioGenerator.hasRemaining() && queueEndOfStreamAfterSilence) {
        queueEndOfStreamToEncoder();
      }
      return false;
    }

    DecoderInputBuffer pendingInputBuffer = pendingInputBuffers.element();
    if (pendingInputBuffer.isEndOfStream()) {
      queueEndOfStreamToEncoder();
      removePendingInputBuffer();
      return false;
    }

    ByteBuffer inputData = checkNotNull(pendingInputBuffer.data);
    feedEncoder(inputData);
    if (!inputData.hasRemaining()) {
      removePendingInputBuffer();
    }
    return true;
  }

  /**
   * Attempts to feed audio processor output data to the encoder.
   *
   * @return Whether it may be possible to feed more data immediately by calling this method again.
   */
  private boolean feedEncoderFromProcessingPipeline() throws ExportException {
    if (!encoder.maybeDequeueInputBuffer(encoderInputBuffer)) {
      return false;
    }

    ByteBuffer processingPipelineOutputBuffer = audioProcessingPipeline.getOutput();

    if (!processingPipelineOutputBuffer.hasRemaining()) {
      if (audioProcessingPipeline.isEnded()) {
        queueEndOfStreamToEncoder();
      }
      return false;
    }

    feedEncoder(processingPipelineOutputBuffer);
    return true;
  }

  /**
   * Attempts to feed input data to the {@link AudioProcessingPipeline}.
   *
   * @return Whether it may be possible to feed more data immediately by calling this method again.
   */
  private boolean feedProcessingPipelineFromInput() {
    if (shouldGenerateSilence()) {
      ByteBuffer inputData = silentAudioGenerator.getBuffer();
      audioProcessingPipeline.queueInput(inputData);
      return !inputData.hasRemaining();
    }

    if (pendingInputBuffers.isEmpty()) {
      // Only read volatile variable queueEndOfStreamAfterSilence if there is a chance that end of
      // stream should be queued.
      if (!silentAudioGenerator.hasRemaining() && queueEndOfStreamAfterSilence) {
        audioProcessingPipeline.queueEndOfStream();
      }
      return false;
    }

    DecoderInputBuffer pendingInputBuffer = pendingInputBuffers.element();
    if (pendingInputBuffer.isEndOfStream()) {
      audioProcessingPipeline.queueEndOfStream();
      removePendingInputBuffer();
      return false;
    }

    ByteBuffer inputData = checkNotNull(pendingInputBuffer.data);
    audioProcessingPipeline.queueInput(inputData);
    if (inputData.hasRemaining()) {
      return false;
    }

    removePendingInputBuffer();
    return true;
  }

  private void removePendingInputBuffer() {
    DecoderInputBuffer inputBuffer = pendingInputBuffers.remove();
    inputBuffer.clear();
    inputBuffer.timeUs = 0;
    availableInputBuffers.add(inputBuffer);
  }

  /**
   * Feeds as much data as possible between the current position and limit of the specified {@link
   * ByteBuffer} to the encoder, and advances its position by the number of bytes fed.
   */
  private void feedEncoder(ByteBuffer inputBuffer) throws ExportException {
    ByteBuffer encoderInputBufferData = checkNotNull(encoderInputBuffer.data);
    int bufferLimit = inputBuffer.limit();
    inputBuffer.limit(min(bufferLimit, inputBuffer.position() + encoderInputBufferData.capacity()));
    encoderInputBufferData.put(inputBuffer);
    encoderInputBuffer.timeUs = nextEncoderInputBufferTimeUs;
    computeNextEncoderInputBufferTimeUs(
        /* bytesWritten= */ encoderInputBufferData.position(), encoderInputAudioFormat);
    encoderInputBuffer.setFlags(0);
    encoderInputBuffer.flip();
    inputBuffer.limit(bufferLimit);
    encoder.queueInputBuffer(encoderInputBuffer);
  }

  private void queueEndOfStreamToEncoder() throws ExportException {
    checkState(checkNotNull(encoderInputBuffer.data).position() == 0);
    encoderInputBuffer.timeUs = nextEncoderInputBufferTimeUs;
    encoderInputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    encoderInputBuffer.flip();
    // Queuing EOS should only occur with an empty buffer.
    encoder.queueInputBuffer(encoderInputBuffer);
  }

  @Pure
  private static TransformationRequest createFallbackTransformationRequest(
      TransformationRequest transformationRequest, Format requestedFormat, Format actualFormat) {
    // TODO(b/259570024): Consider including bitrate and other audio characteristics in the revised
    //  fallback design.
    if (Util.areEqual(requestedFormat.sampleMimeType, actualFormat.sampleMimeType)) {
      return transformationRequest;
    }
    return transformationRequest.buildUpon().setAudioMimeType(actualFormat.sampleMimeType).build();
  }

  private void computeNextEncoderInputBufferTimeUs(long bytesWritten, AudioFormat audioFormat) {
    // The calculation below accounts for remainders and rounding. Without that it corresponds to
    // the following:
    // bufferDurationUs = numberOfFramesInBuffer * sampleDurationUs
    //     where numberOfFramesInBuffer = bytesWritten / bytesPerFrame
    //     and   sampleDurationUs       = C.MICROS_PER_SECOND / sampleRate
    long numerator = bytesWritten * C.MICROS_PER_SECOND + encoderBufferDurationRemainder;
    long denominator = (long) audioFormat.bytesPerFrame * audioFormat.sampleRate;
    long bufferDurationUs = numerator / denominator;
    encoderBufferDurationRemainder = numerator - bufferDurationUs * denominator;
    if (encoderBufferDurationRemainder > 0) { // Ceil division result.
      bufferDurationUs += 1;
      encoderBufferDurationRemainder -= denominator;
    }
    nextEncoderInputBufferTimeUs += bufferDurationUs;
  }

  private boolean shouldGenerateSilence() {
    return silentAudioGenerator.hasRemaining() && pendingInputBuffers.isEmpty();
  }
}
