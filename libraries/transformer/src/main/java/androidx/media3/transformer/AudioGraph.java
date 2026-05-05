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
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_AUDIO_GRAPH;
import static androidx.media3.effect.DebugTraceUtil.EVENT_BLOCK_INPUT;
import static androidx.media3.effect.DebugTraceUtil.EVENT_FLUSH;
import static androidx.media3.effect.DebugTraceUtil.EVENT_OUTPUT_ENDED;
import static androidx.media3.effect.DebugTraceUtil.EVENT_OUTPUT_FORMAT;
import static androidx.media3.effect.DebugTraceUtil.EVENT_PRODUCED_OUTPUT;
import static androidx.media3.effect.DebugTraceUtil.EVENT_REGISTER_NEW_INPUT_STREAM;
import static androidx.media3.effect.DebugTraceUtil.EVENT_RESET;
import static androidx.media3.effect.DebugTraceUtil.EVENT_UNBLOCK_INPUT;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Integer.toHexString;

import androidx.annotation.IntRange;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.effect.DebugTraceUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/** Processes raw audio samples. */
/* package */ final class AudioGraph {

  private final List<InputInfo> inputInfos;
  private final AudioMixer mixer;
  private final AudioProcessingPipeline audioProcessingPipeline;

  private AudioFormat mixerAudioFormat;
  private boolean isMixerConfigured;
  private boolean isMixerReady;
  private long pendingStartTimeUs;
  private ByteBuffer mixerOutput;
  private boolean isEndLogged;

  /**
   * Number of {@link InputInfo} instances whose underlying {@link AudioGraphInput} have ended, but
   * have not been released and are still in {@link #inputInfos}.
   */
  private int finishedInputs;

  /**
   * Creates an instance.
   *
   * @param mixerFactory The {@linkplain AudioMixer.Factory factory} used to {@linkplain
   *     AudioMixer.Factory#create() create} the underlying {@link AudioMixer}.
   * @param effects The composition-level audio effects that are applied after mixing.
   */
  public AudioGraph(AudioMixer.Factory mixerFactory, ImmutableList<AudioProcessor> effects) {
    inputInfos = new ArrayList<>();
    mixer = mixerFactory.create();
    mixerAudioFormat = AudioFormat.NOT_SET;
    mixerOutput = EMPTY_BUFFER;
    audioProcessingPipeline = new AudioProcessingPipeline(effects);
  }

  /** Returns whether an {@link AudioFormat} is valid as an input format. */
  public static boolean isInputAudioFormatValid(AudioFormat format) {
    if (format.encoding == Format.NO_VALUE) {
      return false;
    }
    if (format.sampleRate == Format.NO_VALUE) {
      return false;
    }
    if (format.channelCount == Format.NO_VALUE) {
      return false;
    }
    return true;
  }

  /**
   * Returns a new {@link AudioGraphInput} instance.
   *
   * <p>Must be called before {@linkplain #getOutput() accessing output}.
   */
  public AudioGraphInput registerInput(EditedMediaItem editedMediaItem, Format format)
      throws ExportException {
    checkArgument(format.pcmEncoding != Format.NO_VALUE);
    AudioGraphInput audioGraphInput;
    try {
      audioGraphInput = new AudioGraphInput(mixerAudioFormat, editedMediaItem, format);
      if (Objects.equals(mixerAudioFormat, AudioFormat.NOT_SET)) {
        this.mixerAudioFormat = audioGraphInput.getOutputAudioFormat();
        audioProcessingPipeline.configure(mixerAudioFormat);
        audioProcessingPipeline.flush(
            new StreamMetadata.Builder().setPositionOffsetUs(pendingStartTimeUs).build());
        DebugTraceUtil.logEvent(
            COMPONENT_AUDIO_GRAPH,
            EVENT_OUTPUT_FORMAT,
            C.TIME_UNSET,
            /* extraFormat= */ "%s",
            getOutputAudioFormat());
      }
    } catch (UnhandledAudioFormatException e) {
      throw ExportException.createForAudioProcessing(
          e, "Error while registering input " + inputInfos.size());
    }
    inputInfos.add(new InputInfo(audioGraphInput));
    DebugTraceUtil.logEvent(
        COMPONENT_AUDIO_GRAPH,
        EVENT_REGISTER_NEW_INPUT_STREAM,
        C.TIME_UNSET,
        /* extraFormat= */ "[%s]: %s",
        toHexString(audioGraphInput.hashCode()),
        format);
    return audioGraphInput;
  }

  /**
   * Returns the {@link AudioFormat} of the {@linkplain #getOutput() output}, or {@link
   * AudioFormat#NOT_SET} if no inputs were {@linkplain #registerInput(EditedMediaItem, Format)
   * registered} previously.
   */
  public AudioFormat getOutputAudioFormat() {
    return audioProcessingPipeline.getOutputAudioFormat();
  }

  /**
   * Returns a {@link ByteBuffer} containing output data between the position and limit.
   *
   * <p>The same buffer is returned until it has been fully consumed ({@code position == limit}),
   * unless the graph was {@linkplain #flush flushed}.
   */
  public ByteBuffer getOutput() throws ExportException {
    if (!ensureMixerReady()) {
      return EMPTY_BUFFER;
    }
    if (!mixer.isEnded()) {
      feedMixer();
    }
    if (!mixerOutput.hasRemaining()) {
      mixerOutput = mixer.getOutput();
    }

    ByteBuffer output = mixerOutput;

    if (audioProcessingPipeline.isOperational()) {
      feedProcessingPipelineFromMixer();
      output = audioProcessingPipeline.getOutput();
    }
    DebugTraceUtil.logEvent(
        COMPONENT_AUDIO_GRAPH,
        EVENT_PRODUCED_OUTPUT,
        C.TIME_UNSET,
        "pos:%s,remaining:%s",
        output.position(),
        output.remaining());
    maybeLogGraphEnded(isEnded());
    return output;
  }

  /** Instructs the {@code AudioGraph} to not queue any input buffer. */
  public void blockInput() {
    for (InputInfo info : getActiveInputs()) {
      info.audioGraphInput.blockInput();
    }
    DebugTraceUtil.logEvent(
        COMPONENT_AUDIO_GRAPH, EVENT_BLOCK_INPUT, /* presentationTimeUs= */ C.TIME_UNSET);
  }

  /** Unblocks incoming data if {@linkplain #blockInput() blocked}. */
  public void unblockInput() {
    for (InputInfo info : getActiveInputs()) {
      info.audioGraphInput.unblockInput();
    }
    DebugTraceUtil.logEvent(
        COMPONENT_AUDIO_GRAPH, EVENT_UNBLOCK_INPUT, /* presentationTimeUs= */ C.TIME_UNSET);
  }

  /**
   * Clears any pending data and prepares the {@link AudioGraph} to start receiving input from a new
   * position.
   *
   * @param positionOffsetUs The new position from which the graph will start receiving audio
   *     streams.
   */
  public void flush(@IntRange(from = 0) long positionOffsetUs) {
    this.pendingStartTimeUs = positionOffsetUs;

    for (InputInfo info : inputInfos) {
      // Remove all mixer IDs even if input is released, as we are resetting mixer below.
      info.mixerSourceId = C.INDEX_UNSET;
      if (info.audioGraphInput.isReleased()) {
        continue;
      }
      // AudioGraph does not know the exact position offset of each AudioGraphInput. Each holder of
      // AudioGraphInput must call AudioGraphInput#flush() or AudioGraphInput#onMediaItemChanged()
      // once the renderer resolves the seek position, which might be slightly different than
      // positionOffsetUs.
      info.audioGraphInput.flush(/* positionOffsetUs= */ 0);
    }
    mixer.reset();
    isMixerConfigured = false;
    isMixerReady = false;
    mixerOutput = EMPTY_BUFFER;
    audioProcessingPipeline.flush(
        new StreamMetadata.Builder().setPositionOffsetUs(pendingStartTimeUs).build());
    finishedInputs = 0;
    DebugTraceUtil.logEvent(COMPONENT_AUDIO_GRAPH, EVENT_FLUSH, positionOffsetUs);
  }

  /**
   * Resets the graph, un-registering inputs and releasing any underlying resources.
   *
   * <p>Call {@link #registerInput(EditedMediaItem, Format)} to prepare the audio graph again.
   */
  public void reset() {
    for (InputInfo info : getActiveInputs()) {
      info.audioGraphInput.release();
    }
    inputInfos.clear();
    mixer.reset();
    audioProcessingPipeline.reset();
    finishedInputs = 0;
    mixerOutput = EMPTY_BUFFER;
    mixerAudioFormat = AudioFormat.NOT_SET;
    isEndLogged = false;
    DebugTraceUtil.logEvent(COMPONENT_AUDIO_GRAPH, EVENT_RESET, C.TIME_UNSET);
  }

  /** Returns whether the input has ended and all queued data has been output. */
  public boolean isEnded() {
    boolean isEnded = isMixerEnded();
    if (audioProcessingPipeline.isOperational()) {
      isEnded = audioProcessingPipeline.isEnded();
    }
    maybeLogGraphEnded(isEnded);
    return isEnded;
  }

  private void maybeLogGraphEnded(boolean isEnded) {
    if (isEnded && !isEndLogged) {
      DebugTraceUtil.logEvent(COMPONENT_AUDIO_GRAPH, EVENT_OUTPUT_ENDED, C.TIME_UNSET);
      isEndLogged = true;
    }
  }

  /**
   * Returns an {@link Iterable} containing {@linkplain AudioGraphInput#isReleased() unreleased}
   * inputs.
   */
  private Iterable<InputInfo> getActiveInputs() {
    return Iterables.filter(inputInfos, info -> !info.audioGraphInput.isReleased());
  }

  private boolean ensureMixerReady() throws ExportException {
    if (isMixerReady) {
      return true;
    }
    if (!isMixerConfigured) {
      try {
        mixer.configure(mixerAudioFormat, /* bufferSizeMs= */ C.LENGTH_UNSET, pendingStartTimeUs);
      } catch (UnhandledAudioFormatException e) {
        throw ExportException.createForAudioProcessing(e, "Error while configuring mixer");
      }
      isMixerConfigured = true;
    }
    isMixerReady = true;
    ListIterator<InputInfo> iter = inputInfos.listIterator();
    while (iter.hasNext()) {
      InputInfo inputInfo = iter.next();
      if (inputInfo.mixerSourceId != C.INDEX_UNSET) {
        continue; // The source has already been added.
      }
      AudioGraphInput audioGraphInput = inputInfo.audioGraphInput;
      // If input has been released before being added as a mixer source, then we can remove it
      // from the input list directly. Otherwise, #removeEndedAndReleasedInputs() will eventually
      // unregister the source and clean the input up.
      if (audioGraphInput.isReleased()) {
        iter.remove();
        continue;
      }
      try {
        // Force processing input.
        audioGraphInput.getOutput();
        long sourceStartTimeUs = audioGraphInput.getStartTimeUs();
        if (sourceStartTimeUs == C.TIME_UNSET) {
          isMixerReady = false;
          continue;
        } else if (sourceStartTimeUs == C.TIME_END_OF_SOURCE) {
          continue;
        }
        inputInfo.mixerSourceId =
            mixer.addSource(audioGraphInput.getOutputAudioFormat(), sourceStartTimeUs);
      } catch (UnhandledAudioFormatException e) {
        throw ExportException.createForAudioProcessing(
            e, "Unhandled format while adding source " + inputInfo.mixerSourceId);
      }
    }
    return isMixerReady;
  }

  private void feedMixer() throws ExportException {
    removeEndedAndReleasedInputs();
    for (InputInfo info : inputInfos) {
      feedMixerFromInput(info);
    }
  }

  private void removeEndedAndReleasedInputs() {
    ListIterator<InputInfo> iter = inputInfos.listIterator();
    while (iter.hasNext()) {
      if (maybeUnregisterAndRemoveInput(iter.next())) {
        iter.remove();
      }
    }
  }

  /**
   * Checks whether the {@link InputInfo} is {@linkplain AudioGraphInput#isEnded() ended} or has
   * been {@linkplain AudioGraphInput#isReleased() released} to unregister it from the {@link
   * #mixer} and clear it from {@link #inputInfos}.
   *
   * @return Whether the underlying {@link AudioGraphInput} is released and should be removed from
   *     {@link #inputInfos}.
   */
  private boolean maybeUnregisterAndRemoveInput(InputInfo inputInfo) {
    int sourceId = inputInfo.mixerSourceId;
    // Remove the input directly if not registered in the mixer.
    if (!mixer.hasSource(sourceId)) {
      return inputInfo.audioGraphInput.isReleased();
    }

    // If input is still registered in mixer, remove source first and then remove input from list.
    AudioGraphInput input = inputInfo.audioGraphInput;
    if (input.isEnded()) {
      mixer.removeSource(sourceId);
      inputInfo.mixerSourceId = C.INDEX_UNSET;
      if (input.isReleased()) {
        return true;
      }
      // Only keep track of finished inputs that have not been released.
      finishedInputs++;
    }
    return false;
  }

  /**
   * Feeds the {@link #mixer} from a specific {@link InputInfo}.
   *
   * <p>This method assumes the {@link InputInfo} is registered in the mixer.
   */
  private void feedMixerFromInput(InputInfo inputInfo) throws ExportException {
    int sourceId = inputInfo.mixerSourceId;
    AudioGraphInput input = inputInfo.audioGraphInput;

    try {
      mixer.queueInput(sourceId, input.getOutput());
    } catch (UnhandledAudioFormatException e) {
      throw ExportException.createForAudioProcessing(
          e, "AudioGraphInput (sourceId=" + sourceId + ") reconfiguration");
    }
  }

  private void feedProcessingPipelineFromMixer() {
    if (isMixerEnded()) {
      audioProcessingPipeline.queueEndOfStream();
      return;
    }
    audioProcessingPipeline.queueInput(mixerOutput);
  }

  private boolean isMixerEnded() {
    return !mixerOutput.hasRemaining() && finishedInputs >= inputInfos.size() && mixer.isEnded();
  }

  private static final class InputInfo {
    public final AudioGraphInput audioGraphInput;
    public int mixerSourceId;

    public InputInfo(AudioGraphInput audioGraphInput) {
      this.audioGraphInput = audioGraphInput;
      mixerSourceId = C.INDEX_UNSET;
    }
  }
}
