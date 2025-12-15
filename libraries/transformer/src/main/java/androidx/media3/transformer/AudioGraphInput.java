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

import static androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT;
import static androidx.media3.transformer.AudioGraph.isInputAudioFormatValid;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.decoder.DecoderInputBuffer;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processes a single sequential stream of PCM audio samples.
 *
 * <p>Supports changes to the input {@link Format} and {@link Effects} on {@linkplain
 * OnMediaItemChangedListener#onMediaItemChanged item boundaries}.
 *
 * <p>Class has thread-safe support for input and processing happening on different threads. In that
 * case, one is the upstream SampleConsumer "input" thread, and the other is the main internal
 * "processing" thread.
 */
/* package */ final class AudioGraphInput implements GraphInput {

  private static final int MAX_INPUT_BUFFER_COUNT = 10;
  private final AudioFormat outputAudioFormat;

  private final Queue<DecoderInputBuffer> availableInputBuffers;
  private final Queue<DecoderInputBuffer> pendingInputBuffers;
  private final Queue<MediaItemChange> pendingMediaItemChanges;

  /**
   * Position offset in microseconds relative to the overall {@link AudioGraph} output.
   *
   * <p>This is different from {@link MediaItemChange#positionOffsetUs}, where the position is
   * relative to the input stream.
   */
  private final AtomicLong startTimeUs;

  private final SilenceAppendingAudioProcessor silenceAppendingAudioProcessor;

  private AudioFormat lastInputFormat;

  /**
   * Pipeline containing {@link AudioProcessor} instances to apply immediately before {@link
   * #userPipeline}.
   *
   * <p>The output of this pipeline corresponds to the semantic input of the {@link AudioGraphInput}
   * and the stream should be congruent with values passed in {@link #onMediaItemChanged}.
   *
   * <p>The pre-processing pipeline is meant for modifying the input audio stream with effects like
   * speed changing or format conversion before reaching user-provided {@link AudioProcessor}
   * instance.
   */
  private AudioProcessingPipeline preProcessingPipeline;

  /**
   * Pipeline containing {@linkplain EditedMediaItem#effects user-provided} {@link AudioProcessor}
   * instances, and format/duration normalizing processors.
   *
   * <p>The end of the pipeline might contain format converting processors to normalize the stream's
   * format to a requested output format. The beginning of the pipeline might contain a {@link
   * SilenceAppendingAudioProcessor} to normalize the input stream's duration if requested.
   */
  private AudioProcessingPipeline userPipeline;

  private boolean processedFirstMediaItemChange;
  private boolean receivedEndOfStreamFromInput;
  private boolean inputBlocked;
  private long currentItemExpectedInputDurationUs;
  private boolean isCurrentItemLast;

  /**
   * Creates an instance.
   *
   * @param requestedOutputAudioFormat The requested {@linkplain AudioFormat properties} of the
   *     output audio. {@linkplain Format#NO_VALUE Unset} fields are ignored.
   * @param editedMediaItem The initial {@link EditedMediaItem}.
   * @param inputFormat The initial {@link Format} of audio input data.
   */
  public AudioGraphInput(
      AudioFormat requestedOutputAudioFormat, EditedMediaItem editedMediaItem, Format inputFormat)
      throws UnhandledAudioFormatException {
    AudioFormat inputAudioFormat = new AudioFormat(inputFormat);
    checkArgument(isInputAudioFormatValid(inputAudioFormat), /* errorMessage= */ inputAudioFormat);

    // TODO: b/323148735 - Use improved buffer assignment logic.
    availableInputBuffers = new ConcurrentLinkedQueue<>();
    ByteBuffer emptyBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    for (int i = 0; i < MAX_INPUT_BUFFER_COUNT; i++) {
      DecoderInputBuffer inputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DIRECT);
      inputBuffer.data = emptyBuffer;
      availableInputBuffers.add(inputBuffer);
    }
    pendingInputBuffers = new ConcurrentLinkedQueue<>();
    pendingMediaItemChanges = new ConcurrentLinkedQueue<>();
    silenceAppendingAudioProcessor = new SilenceAppendingAudioProcessor();
    preProcessingPipeline =
        new AudioProcessingPipeline(editedMediaItem.preProcessingAudioProcessors);
    AudioFormat preProcessingAudioFormat = preProcessingPipeline.configure(inputAudioFormat);
    preProcessingPipeline.flush(StreamMetadata.DEFAULT);
    userPipeline =
        configureProcessing(
            editedMediaItem,
            inputFormat.metadata,
            preProcessingAudioFormat,
            requestedOutputAudioFormat,
            silenceAppendingAudioProcessor);
    lastInputFormat = preProcessingAudioFormat;
    // APP configuration not active until flush called. getOutputAudioFormat based on active config.
    userPipeline.flush(StreamMetadata.DEFAULT);
    outputAudioFormat = userPipeline.getOutputAudioFormat();
    checkArgument(
        outputAudioFormat.encoding == C.ENCODING_PCM_16BIT, /* errorMessage= */ outputAudioFormat);
    startTimeUs = new AtomicLong(C.TIME_UNSET);
    currentItemExpectedInputDurationUs = C.TIME_UNSET;
  }

  /** Returns the {@link AudioFormat} of {@linkplain #getOutput() output buffers}. */
  public AudioFormat getOutputAudioFormat() {
    return outputAudioFormat;
  }

  /**
   * Returns a {@link ByteBuffer} of output, in the {@linkplain #getOutputAudioFormat() output audio
   * format}.
   *
   * <p>Should only be called by the processing thread.
   *
   * @throws UnhandledAudioFormatException If the configuration of underlying components fails as a
   *     result of upstream changes.
   */
  public ByteBuffer getOutput() throws UnhandledAudioFormatException {
    ByteBuffer outputBuffer = getOutputInternal();

    if (outputBuffer.hasRemaining()) {
      return outputBuffer;
    }

    if (!hasDataToOutput() && !pendingMediaItemChanges.isEmpty()) {
      configureForPendingMediaItemChange();
    }

    return EMPTY_BUFFER;
  }

  /**
   * {@inheritDoc}
   *
   * <p>When durationUs is {@link C#TIME_UNSET}, silence generation is disabled.
   *
   * <p>Should only be called by the input thread.
   */
  @Override
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem,
      long durationUs,
      @Nullable Format decodedFormat,
      boolean isLast,
      @IntRange(from = 0) long positionOffsetUs) {
    checkArgument(positionOffsetUs >= 0);

    if (decodedFormat == null) {
      checkState(
          durationUs != C.TIME_UNSET,
          "Could not generate silent audio because duration is unknown.");
    } else {
      checkState(MimeTypes.isAudio(decodedFormat.sampleMimeType));
      AudioFormat audioFormat = new AudioFormat(decodedFormat);
      checkState(isInputAudioFormatValid(audioFormat), /* errorMessage= */ audioFormat);
    }
    pendingMediaItemChanges.add(
        new MediaItemChange(editedMediaItem, durationUs, decodedFormat, isLast, positionOffsetUs));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Should only be called by the input thread.
   */
  @Override
  @Nullable
  public DecoderInputBuffer getInputBuffer() {
    if (inputBlocked || !pendingMediaItemChanges.isEmpty()) {
      return null;
    }
    return availableInputBuffers.peek();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Should only be called by the input thread.
   */
  @Override
  public boolean queueInputBuffer() {
    if (inputBlocked) {
      return false;
    }
    checkState(pendingMediaItemChanges.isEmpty());
    DecoderInputBuffer inputBuffer = availableInputBuffers.remove();
    pendingInputBuffers.add(inputBuffer);
    startTimeUs.compareAndSet(
        /* expectedValue= */ C.TIME_UNSET, /* newValue= */ inputBuffer.timeUs);
    return true;
  }

  /**
   * Returns the stream start time in microseconds relative to the {@link AudioGraph} output
   * position, or {@link C#TIME_UNSET} if unknown.
   */
  public long getStartTimeUs() {
    return startTimeUs.get();
  }

  /**
   * Instructs the {@code AudioGraphInput} to not queue any input buffer.
   *
   * <p>Should only be called if the input thread and processing thread are the same.
   */
  public void blockInput() {
    inputBlocked = true;
  }

  /**
   * Unblocks incoming data if {@linkplain #blockInput() blocked}.
   *
   * <p>Should only be called if the input thread and processing thread are the same.
   */
  public void unblockInput() {
    inputBlocked = false;
  }

  /**
   * Clears any pending data and prepares the {@link AudioGraphInput} to start receiving buffers
   * from a new position.
   *
   * <p><b>Note:</b> The new position is relative to the input stream and not to the overall {@link
   * AudioGraph} output position.
   *
   * <p>If an {@linkplain #getInputBuffer() input buffer} has been retrieved without being queued,
   * it shouldn't be used after calling this method.
   *
   * <p>Should only be called if the input thread and processing thread are the same.
   *
   * @param positionOffsetUs The new position in microseconds from which this component will start
   *     receiving input buffers after the flush.
   */
  public void flush(@IntRange(from = 0) long positionOffsetUs) {
    checkArgument(positionOffsetUs >= 0);
    pendingMediaItemChanges.clear();
    processedFirstMediaItemChange = true;
    if (!availableInputBuffers.isEmpty()) {
      // Clear first available buffer in case the caller wrote data in the input buffer without
      // queueing it.
      clearAndAddToAvailableBuffers(availableInputBuffers.remove());
    }

    while (!pendingInputBuffers.isEmpty()) {
      clearAndAddToAvailableBuffers(pendingInputBuffers.remove());
    }
    checkState(availableInputBuffers.size() == MAX_INPUT_BUFFER_COUNT);
    // positionOffsetUs and the output of preProcessingPipeline should be congruent, so we don't
    // allow preProcessingPipeline to modify the position offset.
    preProcessingPipeline.flush(new StreamMetadata(positionOffsetUs));
    userPipeline.flush(new StreamMetadata(positionOffsetUs));
    receivedEndOfStreamFromInput = false;
    startTimeUs.set(C.TIME_UNSET);
    currentItemExpectedInputDurationUs = C.TIME_UNSET;
    isCurrentItemLast = false;
  }

  /**
   * Releases any underlying resources.
   *
   * <p>Should only be called by the processing thread.
   */
  public void release() {
    preProcessingPipeline.reset();
    userPipeline.reset();
    lastInputFormat = AudioFormat.NOT_SET;
  }

  /**
   * Returns whether the input has ended and all queued data has been output.
   *
   * <p>Should only be called on the processing thread.
   */
  public boolean isEnded() {
    if (hasDataToOutput()) {
      return false;
    }
    if (!pendingMediaItemChanges.isEmpty()) {
      return false;
    }
    if (currentItemExpectedInputDurationUs != C.TIME_UNSET) {
      // When exporting a sequence of items, we rely on currentItemExpectedInputDurationUs and
      // receivedEndOfStreamFromInput to determine silence padding.
      // Use isCurrentItemLast to correctly propagate end of stream once for the entire sequence.
      return isCurrentItemLast;
    }
    // For a looping sequence, currentItemExpectedInputDurationUs is unset, and
    // there isn't a last item -- end of stream is passed through directly.
    return receivedEndOfStreamFromInput;
  }

  private ByteBuffer getOutputInternal() {
    if (!processedFirstMediaItemChange) {
      return EMPTY_BUFFER;
    }

    feedPreProcessingPipeline();
    if (!userPipeline.isOperational()) {
      return getOutputFromPreProcessingPipeline();
    }

    feedUserPipeline();
    return userPipeline.getOutput();
  }

  /**
   * Feeds input samples into {@link #userPipeline} until the pipeline stops accepting new input.
   *
   * <p>This method {@linkplain AudioProcessingPipeline#queueEndOfStream() queues end of stream} to
   * the pipeline at the end of a sequence or before an {@link EditedMediaItem} change.
   */
  private void feedUserPipeline() {
    feedPipeline(
        userPipeline, this::getOutputFromPreProcessingPipeline, this::isPreProcessingPipelineEnded);
  }

  private ByteBuffer getOutputFromPreProcessingPipeline() {
    if (preProcessingPipeline.isOperational()) {
      return preProcessingPipeline.getOutput();
    }
    return getQueuedInput();
  }

  private boolean isPreProcessingPipelineEnded() {
    if (preProcessingPipeline.isOperational()) {
      return preProcessingPipeline.isEnded();
    }
    return hasMediaItemInputEnded();
  }

  private void feedPreProcessingPipeline() {
    if (!preProcessingPipeline.isOperational()) {
      return;
    }
    feedPipeline(preProcessingPipeline, this::getQueuedInput, this::hasMediaItemInputEnded);
  }

  private boolean hasMediaItemInputEnded() {
    return receivedEndOfStreamFromInput || !pendingMediaItemChanges.isEmpty();
  }

  /**
   * Returns the next buffer to process or an empty buffer if no more buffers are available to
   * process.
   *
   * <p>If {@link #pendingInputBuffers} is empty, or the next {@link DecoderInputBuffer} is
   * signalling {@linkplain DecoderInputBuffer#isEndOfStream() end of stream}, this method returns
   * an empty buffer.
   *
   * <p>This method releases any {@link DecoderInputBuffer} that has been processed and makes it
   * available to {@link #getInputBuffer()}.
   */
  private ByteBuffer getQueuedInput() {
    @Nullable DecoderInputBuffer currentInputBuffer;
    while ((currentInputBuffer = pendingInputBuffers.peek()) != null) {
      receivedEndOfStreamFromInput = currentInputBuffer.isEndOfStream();

      if (receivedEndOfStreamFromInput) {
        clearAndAddToAvailableBuffers(pendingInputBuffers.remove());
        return EMPTY_BUFFER;
      }

      ByteBuffer currentInputBufferData = checkNotNull(currentInputBuffer.data);
      if (currentInputBufferData.hasRemaining()) {
        return currentInputBufferData;
      }
      clearAndAddToAvailableBuffers(pendingInputBuffers.remove());
    }
    return EMPTY_BUFFER;
  }

  private boolean hasDataToOutput() {
    if (!processedFirstMediaItemChange) {
      return false;
    }

    if (!pendingInputBuffers.isEmpty()) {
      return true;
    }

    return (userPipeline.isOperational() && !userPipeline.isEnded())
        || (preProcessingPipeline.isOperational() && !preProcessingPipeline.isEnded());
  }

  private void clearAndAddToAvailableBuffers(DecoderInputBuffer inputBuffer) {
    inputBuffer.clear();
    inputBuffer.timeUs = 0;
    availableInputBuffers.add(inputBuffer);
  }

  /**
   * Configures the graph based on the pending {@linkplain
   * OnMediaItemChangedListener#onMediaItemChanged media item change}.
   *
   * <p>Before configuration, all {@linkplain #hasDataToOutput() pending data} must be consumed
   * through {@link #getOutput()}.
   */
  private void configureForPendingMediaItemChange() throws UnhandledAudioFormatException {
    MediaItemChange pendingChange = checkNotNull(pendingMediaItemChanges.poll());

    isCurrentItemLast = pendingChange.isLast;
    AudioFormat pendingAudioFormat;
    Metadata metadata = null;
    boolean onlyGenerateSilence = false;
    if (pendingChange.format != null) {
      currentItemExpectedInputDurationUs = pendingChange.durationUs;
      pendingAudioFormat = new AudioFormat(pendingChange.format);
      metadata = pendingChange.format.metadata;
    } else { // Generating silence
      // No audio track. Generate silence based on video track duration after applying effects.
      if (pendingChange.editedMediaItem.effects.audioProcessors.isEmpty()) {
        // No audio track and no effects.
        // Generate silence based on video track duration after applying effects.
        currentItemExpectedInputDurationUs =
            pendingChange.editedMediaItem.getDurationAfterEffectsApplied(pendingChange.durationUs);
      } else {
        // No audio track, but effects are present.
        // Generate audio track based on video duration, and apply effects.
        currentItemExpectedInputDurationUs = pendingChange.durationUs;
      }
      pendingAudioFormat = lastInputFormat;
      startTimeUs.compareAndSet(/* expectedValue= */ C.TIME_UNSET, /* newValue= */ 0);
      onlyGenerateSilence = true;
    }

    silenceAppendingAudioProcessor.setExpectedDurationUs(currentItemExpectedInputDurationUs);

    if (processedFirstMediaItemChange) {
      // APP is configured in constructor for first media item.
      preProcessingPipeline =
          new AudioProcessingPipeline(pendingChange.editedMediaItem.preProcessingAudioProcessors);
      AudioFormat postAudioFormat = preProcessingPipeline.configure(pendingAudioFormat);

      userPipeline =
          configureProcessing(
              pendingChange.editedMediaItem,
              metadata,
              postAudioFormat,
              /* requiredOutputAudioFormat= */ outputAudioFormat,
              silenceAppendingAudioProcessor);
      lastInputFormat = postAudioFormat;
    }

    // positionOffsetUs and the output of preProcessingPipeline should be congruent, so we don't
    // allow preProcessingPipeline to modify the position offset.
    preProcessingPipeline.flush(new StreamMetadata(pendingChange.positionOffsetUs));
    userPipeline.flush(new StreamMetadata(pendingChange.positionOffsetUs));
    receivedEndOfStreamFromInput = false;
    processedFirstMediaItemChange = true;
    if (onlyGenerateSilence) {
      preProcessingPipeline.reset();
      userPipeline.queueEndOfStream();
    }
  }

  /**
   * Feeds an {@link AudioProcessingPipeline} with buffers from {@code inputSupplier} until no more
   * input can be processed.
   *
   * <p>If {@code inputSupplier} returns and empty buffer and {@code shouldQueueEndOfStream} returns
   * true, this method {@linkplain AudioProcessingPipeline#queueEndOfStream() signals end of stream}
   * to the provided pipeline.
   */
  private static void feedPipeline(
      AudioProcessingPipeline pipeline,
      Supplier<ByteBuffer> inputSupplier,
      Supplier<Boolean> shouldQueueEndOfStream) {
    ByteBuffer byteBuffer;
    do {
      byteBuffer = inputSupplier.get();
      if (!byteBuffer.hasRemaining()) {
        if (shouldQueueEndOfStream.get()) {
          pipeline.queueEndOfStream();
        }
        return;
      }

      pipeline.queueInput(byteBuffer);
    } while (!byteBuffer.hasRemaining());
  }

  /**
   * Returns a new configured {@link AudioProcessingPipeline}.
   *
   * <p>Additional {@link AudioProcessor} instances may be added to the returned pipeline that:
   *
   * <ul>
   *   <li>Handle {@linkplain EditedMediaItem#flattenForSlowMotion slow motion flattening}.
   *   <li>Modify the audio stream to match the {@code requiredOutputAudioFormat}.
   * </ul>
   */
  private static AudioProcessingPipeline configureProcessing(
      EditedMediaItem editedMediaItem,
      @Nullable Metadata metadata,
      AudioFormat inputAudioFormat,
      AudioFormat requiredOutputAudioFormat,
      SilenceAppendingAudioProcessor silenceAppendingAudioProcessor)
      throws UnhandledAudioFormatException {
    ImmutableList.Builder<AudioProcessor> audioProcessors = new ImmutableList.Builder<>();
    audioProcessors.add(silenceAppendingAudioProcessor);

    // TODO: b/467992561 - Move SEF SpeedChangingAudioProcessor into pre-processing pipeline.
    if (editedMediaItem.flattenForSlowMotion && metadata != null) {
      audioProcessors.add(new SpeedChangingAudioProcessor(new SegmentSpeedProvider(metadata)));
    }
    audioProcessors.addAll(editedMediaItem.effects.audioProcessors);

    if (requiredOutputAudioFormat.sampleRate != Format.NO_VALUE) {
      SonicAudioProcessor sampleRateChanger = new SonicAudioProcessor();
      sampleRateChanger.setOutputSampleRateHz(requiredOutputAudioFormat.sampleRate);
      audioProcessors.add(sampleRateChanger);
    }

    // TODO: b/262706549 - Handle channel mixing with AudioMixer.
    // ChannelMixingMatrix.create only has defaults for mono/stereo input/output.
    if (requiredOutputAudioFormat.channelCount == 1
        || requiredOutputAudioFormat.channelCount == 2) {
      ChannelMixingAudioProcessor channelCountChanger = new ChannelMixingAudioProcessor();
      channelCountChanger.putChannelMixingMatrix(
          ChannelMixingMatrix.createForConstantGain(
              /* inputChannelCount= */ 1, requiredOutputAudioFormat.channelCount));
      channelCountChanger.putChannelMixingMatrix(
          ChannelMixingMatrix.createForConstantGain(
              /* inputChannelCount= */ 2, requiredOutputAudioFormat.channelCount));
      audioProcessors.add(channelCountChanger);
    }

    AudioProcessingPipeline audioProcessingPipeline =
        new AudioProcessingPipeline(audioProcessors.build());
    AudioFormat outputAudioFormat = audioProcessingPipeline.configure(inputAudioFormat);
    if ((requiredOutputAudioFormat.sampleRate != Format.NO_VALUE
            && requiredOutputAudioFormat.sampleRate != outputAudioFormat.sampleRate)
        || (requiredOutputAudioFormat.channelCount != Format.NO_VALUE
            && requiredOutputAudioFormat.channelCount != outputAudioFormat.channelCount)
        || (requiredOutputAudioFormat.encoding != Format.NO_VALUE
            && requiredOutputAudioFormat.encoding != outputAudioFormat.encoding)) {
      throw new UnhandledAudioFormatException(
          "Audio can not be modified to match downstream format", inputAudioFormat);
    }

    return audioProcessingPipeline;
  }

  private static final class MediaItemChange {
    public final EditedMediaItem editedMediaItem;
    public final long durationUs;
    @Nullable public final Format format;
    public final boolean isLast;
    public final long positionOffsetUs;

    public MediaItemChange(
        EditedMediaItem editedMediaItem,
        long durationUs,
        @Nullable Format format,
        boolean isLast,
        @IntRange(from = 0) long positionOffsetUs) {
      this.editedMediaItem = editedMediaItem;
      this.durationUs = durationUs;
      this.format = format;
      this.isLast = isLast;
      this.positionOffsetUs = positionOffsetUs;
    }
  }
}
